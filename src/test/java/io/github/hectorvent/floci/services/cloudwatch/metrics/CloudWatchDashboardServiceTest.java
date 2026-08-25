package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dashboard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchDashboardServiceTest {

    private CloudWatchDashboardService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchDashboardService(
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
    }

    @Test
    void putGetListAndDeleteDashboard() {
        String body = "{\"widgets\":[]}";
        Dashboard created = service.putDashboard("ops", body);
        assertEquals("ops", created.getDashboardName());
        assertEquals("arn:aws:cloudwatch::000000000000:dashboard/ops", created.getDashboardArn());
        assertEquals(body, created.getDashboardBody());
        assertEquals(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, created.getSize());

        Dashboard got = service.getDashboard("ops");
        assertEquals(body, got.getDashboardBody());

        List<Dashboard> listed = service.listDashboards(null);
        assertTrue(listed.stream().anyMatch(d -> "ops".equals(d.getDashboardName())));

        service.deleteDashboards(List.of("ops"));
        AwsException missing = assertThrows(AwsException.class, () -> service.getDashboard("ops"));
        assertEquals("DashboardNotFoundError", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());

        service.deleteDashboards(List.of("ops"));
    }

    @Test
    void getDashboardRequiresName() {
        AwsException error = assertThrows(AwsException.class, () -> service.getDashboard(""));
        assertEquals("MissingRequiredParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
