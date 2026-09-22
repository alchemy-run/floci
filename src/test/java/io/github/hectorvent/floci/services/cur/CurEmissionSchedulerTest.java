package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bcmdataexports.BcmDataExportsService;
import io.github.hectorvent.floci.services.bcmdataexports.model.DataQuery;
import io.github.hectorvent.floci.services.bcmdataexports.model.DestinationConfiguration;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.services.cur.model.ReportDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurEmissionSchedulerTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final CurService cur = new CurService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "123456789012"));
    private final BcmDataExportsService bcm = mock(BcmDataExportsService.class);
    private final EmissionEngine engine = mock(EmissionEngine.class);
    private final CurEmissionScheduler scheduler = new CurEmissionScheduler(config, cur, bcm, engine);

    @Test
    void csvReportsPersistButDeliveryRecordsErrorWithoutCallingParquet() {
        when(config.services().cur().emitMode()).thenReturn("synchronous");
        for (String compression : List.of("GZIP", "ZIP")) {
            ReportDefinition report = new ReportDefinition();
            report.setReportName("csv-" + compression);
            report.setFormat("textORcsv");
            report.setCompression(compression);
            report.setTimeUnit("DAILY");
            report.setS3Bucket("billing-destination");
            report.setS3Region("us-east-1");
            cur.putReportDefinition(report, "us-east-1");

            AwsException error = assertThrows(AwsException.class,
                    () -> scheduler.emitForReportSync(report, "us-east-1"));
            assertEquals("InternalServerException", error.getErrorCode());
            assertTrue(error.getMessage().contains("not implemented"));
            assertEquals("ERROR", cur.getReportDefinitionOrSelf(report, "us-east-1").getReportStatus());
            assertEquals(compression, cur.getReportDefinitionOrSelf(report, "us-east-1").getCompression());
        }
        verifyNoInteractions(engine);
    }

    @Test
    void csvExportRecordsFailedExecutionWithoutCallingParquet() {
        Export export = export("TEXT_OR_CSV", "GZIP", "SELECT * FROM COST_AND_USAGE_REPORT");
        assertUnsupportedExport(export, "only for PARQUET");
    }

    @Test
    void parquetExportDoesNotPretendToExecuteProjection() {
        Export export = export("PARQUET", "PARQUET",
                "SELECT identity_line_item_id FROM COST_AND_USAGE_REPORT");
        assertUnsupportedExport(export, "query execution is not implemented");
    }

    @Test
    void disabledEmissionCreatesNoExecution() {
        when(config.services().bcmDataExports().emitMode()).thenReturn("off");
        assertNull(scheduler.emitForExportSync(
                export("TEXT_OR_CSV", "GZIP", "SELECT * FROM COST_AND_USAGE_REPORT"), "us-east-1", "USER"));
        verifyNoInteractions(engine, bcm);
    }

    private void assertUnsupportedExport(Export export, String message) {
        when(config.services().bcmDataExports().emitMode()).thenReturn("synchronous");
        ExportExecution execution = new ExportExecution();
        execution.setExecutionId("attempt");
        when(bcm.recordExecution("123456789012", export.getExportArn(), "USER")).thenReturn(execution);
        AwsException error = assertThrows(AwsException.class,
                () -> scheduler.emitForExportSync(export, "us-east-1", "USER"));
        assertEquals("InternalServerException", error.getErrorCode());
        assertTrue(error.getMessage().contains(message));
        verify(bcm).completeExecution("123456789012", execution, false, error.getMessage());
        verifyNoInteractions(engine);
    }

    private static Export export(String format, String compression, String query) {
        Export export = new Export();
        export.setName("runtime-export");
        export.setExportArn("arn:aws:bcm-data-exports:us-east-1:123456789012:export/runtime-export");
        export.setOwnerAccountId("123456789012");
        DataQuery dataQuery = new DataQuery();
        dataQuery.setQueryStatement(query);
        export.setDataQuery(dataQuery);
        DestinationConfiguration.S3OutputConfigurations output = new DestinationConfiguration.S3OutputConfigurations();
        output.setFormat(format);
        output.setCompression(compression);
        DestinationConfiguration.S3Destination destination = new DestinationConfiguration.S3Destination();
        destination.setS3Bucket("billing-destination");
        destination.setS3Region("us-east-1");
        destination.setS3OutputConfigurations(output);
        DestinationConfiguration destinations = new DestinationConfiguration();
        destinations.setS3Destination(destination);
        export.setDestinationConfigurations(destinations);
        return export;
    }
}
