package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CloudTrailLakeSqlTest {
    private static final String STORE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String ARN = "arn:aws:cloudtrail:us-east-1:000000000000:eventdatastore/" + STORE;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rewritesBareQuotedAndArnStoreReferencesButNotLiterals() {
        List<String> seen = new ArrayList<>();
        Function<String, String> resolver = id -> {
            seen.add(id);
            return "\"eds\"";
        };
        String sql = CloudTrailLakeSql.translate("SELECT eventID FROM " + STORE.toUpperCase()
                + " a JOIN `" + ARN + "` b ON a.eventID = b.eventID WHERE a.eventID <> '" + STORE + "'"
                + " AND a.eventName = \"" + STORE + "\"", List.of(), resolver);
        assertEquals("SELECT eventID FROM \"eds\" a JOIN \"eds\" b ON a.eventID = b.eventID WHERE a.eventID <> '"
                + STORE + "' AND a.eventName = \"eds\"", sql);
        assertEquals(List.of(STORE, STORE, STORE), seen);
    }

    @Test
    void bindsParametersAsEscapedLiteralsAndRenamesTrinoFunctions() {
        String sql = CloudTrailLakeSql.translate(
                "SELECT approx_distinct(eventName), '?' FROM " + STORE + " WHERE eventName = ? AND awsRegion = ?;",
                List.of("It's", "us-east-1"), id -> "t");
        assertEquals("SELECT approx_count_distinct(eventName), '?' FROM t WHERE eventName = 'It''s'"
                + " AND awsRegion = 'us-east-1'", sql);
    }

    @Test
    void rejectsNonSelectMultipleStatementsAndParameterMismatch() {
        assertError("InvalidQueryStatementException", () -> CloudTrailLakeSql.translate(
                "DROP TABLE " + STORE, List.of(), id -> "t"));
        assertError("InvalidQueryStatementException", () -> CloudTrailLakeSql.translate(
                "SELECT 1 FROM " + STORE + "; SELECT 2", List.of(), id -> "t"));
        assertError("InvalidQueryStatementException", () -> CloudTrailLakeSql.translate(
                "SELECT 'unterminated FROM " + STORE, List.of(), id -> "t"));
        assertError("InvalidParameterException", () -> CloudTrailLakeSql.translate(
                "SELECT * FROM " + STORE + " WHERE eventName = ?", List.of(), id -> "t"));
        assertError("InvalidParameterException", () -> CloudTrailLakeSql.translate(
                "SELECT * FROM " + STORE, List.of("extra"), id -> "t"));
    }

    @Test
    void mergesEventTablesIntoExistingWithClause() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("\"eds\"", "SELECT 1 AS eventID");
        assertEquals("WITH \"eds\" AS (SELECT 1 AS eventID) SELECT * FROM \"eds\"",
                CloudTrailLakeSql.withEventTables("SELECT * FROM \"eds\"", tables));
        assertEquals("WITH RECURSIVE \"eds\" AS (SELECT 1 AS eventID), r AS (SELECT 1) SELECT * FROM r",
                CloudTrailLakeSql.withEventTables("with recursive r AS (SELECT 1) SELECT * FROM r", tables));
    }

    @Test
    void eventRowsUseLakeSchemaShapes() {
        ObjectNode event = mapper.createObjectNode();
        event.put("eventID", "e-1").put("eventTime", "2026-09-24T10:11:12.345Z").put("eventName", "PutParameter")
                .put("readOnly", false).put("managementEvent", true);
        event.putObject("userIdentity").put("type", "Root").put("accountId", "000000000000")
                .put("arn", "arn:aws:iam::000000000000:root");
        event.putObject("requestParameters").put("name", "/p").put("overwrite", true)
                .putObject("nested").put("a", 1);
        event.putArray("resources").addObject().put("type", "AWS::S3::Bucket").put("ARN", "arn:aws:s3:::b");

        JsonNode row = CloudTrailLakeSql.eventRow(event, mapper);
        assertEquals("2026-09-24 10:11:12.345", row.path("eventTime").asText());
        assertFalse(row.path("readOnly").asBoolean());
        assertEquals("000000000000", row.path("userIdentity").path("accountid").asText());
        assertEquals("true", row.path("requestParameters").path("overwrite").asText());
        assertEquals("{\"a\":1}", row.path("requestParameters").path("nested").asText());
        assertEquals("arn:aws:s3:::b", row.path("resources").get(0).path("arn").asText());

        String table = CloudTrailLakeSql.eventTable(List.of(event.put("errorMessage", "it's")), mapper);
        assertTrue(table.startsWith("SELECT struct_extract(r, 'eventVersion') AS \"eventVersion\""));
        assertTrue(table.contains("from_json('[{"));
        assertTrue(table.contains("it''s"));
    }

    @Test
    void rendersNestedValuesLikeLake() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", "Root");
        identity.put("arn", null);
        assertEquals("{type=Root, arn=null}", CloudTrailLakeSql.render(identity));
        assertEquals("[a, 2]", CloudTrailLakeSql.render(List.of("a", 2)));
        assertEquals("42", CloudTrailLakeSql.render(42));
    }

    @Test
    void generatorProducesStatementsOnlyForRecognisedPrompts() {
        assertEquals(Optional.of("SELECT COUNT(*) AS eventCount FROM " + STORE
                        + " WHERE eventTime > now() - INTERVAL '1' DAY"),
                CloudTrailLakeQueryGenerator.generate("How many events were recorded in the last day?", STORE));
        assertEquals(Optional.of("SELECT eventName AS eventName, COUNT(*) AS eventCount FROM " + STORE
                        + " WHERE eventTime > now() - INTERVAL '14' DAY AND errorCode IS NOT NULL"
                        + " GROUP BY eventName ORDER BY eventCount DESC LIMIT 5"),
                CloudTrailLakeQueryGenerator.generate("Top 5 API calls that failed in the past 2 weeks", STORE));
        assertEquals(Optional.empty(), CloudTrailLakeQueryGenerator.generate("Compose a poem about autumn", STORE));
    }

    private static void assertError(String code, Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals(code, error.getErrorCode());
    }
}
