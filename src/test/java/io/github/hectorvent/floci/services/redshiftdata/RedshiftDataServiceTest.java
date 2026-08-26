package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshiftdata.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDataServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftDataService service;

    @BeforeEach
    void setUp() {
        service = new RedshiftDataService();
    }

    @Test
    void describeMissingStatementThrowsResourceNotFound() {
        ObjectNode request = mapper.createObjectNode();
        request.put("Id", "d9b6c0c9-0747-4bf4-b142-e8883122f766");
        AwsException error = assertThrows(AwsException.class, () -> service.describe(request));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("d9b6c0c9-0747-4bf4-b142-e8883122f766", error.getExtendedData().get("ResourceId"));
    }

    @Test
    void listDatabasesUnknownWorkgroupThrowsValidation() {
        ObjectNode request = mapper.createObjectNode();
        request.put("Database", "dev");
        request.put("WorkgroupName", "alchemy-test-rsd-does-not-exist");
        AwsException error = assertThrows(AwsException.class, () -> service.listDatabases(request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(error.getMessage().contains("alchemy-test-rsd-does-not-exist"));
    }

    @Test
    void executeSelectLiteralFinishesWithLongValue() {
        ObjectNode request = mapper.createObjectNode();
        request.put("Sql", "SELECT 1 AS n");
        request.put("Database", "dev");
        request.put("WorkgroupName", "alchemy-test-rsd-wg");
        Statement statement = service.execute(request);
        assertEquals("FINISHED", statement.getStatus());
        assertEquals(List.of("n"), statement.getColumnNames());
        assertEquals(1L, statement.getRows().get(0).get(0));
        assertEquals("dev", service.listDatabases(request).get(0));
    }

    @Test
    void evaluateSelectLiteralAndCount() {
        RedshiftDataService.QueryResult literal = RedshiftDataService.evaluate("SELECT 7 AS n");
        assertEquals("n", literal.columns().get(0));
        assertEquals(7L, literal.rows().get(0).get(0));
        RedshiftDataService.QueryResult count = RedshiftDataService.evaluate(
                "SELECT count(*) FROM pg_catalog.pg_attribute a");
        assertEquals("count", count.columns().get(0));
    }

    @Test
    void executeCountQueryStaysRunningUntilCanceled() {
        ObjectNode request = mapper.createObjectNode();
        request.put("Sql",
                "SELECT count(*) FROM pg_catalog.pg_attribute a CROSS JOIN pg_catalog.pg_attribute b");
        request.put("Database", "dev");
        request.put("WorkgroupName", "alchemy-test-rsd-wg");
        Statement statement = service.execute(request);
        assertEquals("RUNNING", statement.getStatus());

        ObjectNode cancel = mapper.createObjectNode();
        cancel.put("Id", statement.getId());
        Statement aborted = service.cancel(cancel);
        assertEquals("ABORTED", aborted.getStatus());
    }
}
