package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static io.github.hectorvent.floci.testing.DynamoDbRequests.decimalAt;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbExportIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TABLE_NAME = "ExportTestTable";
    private static final String BUCKET_NAME = "export-test-bucket";
    private static final String TABLE_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";

    private static boolean setupDone = false;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void ensureSetup() {
        if (setupDone) return;

        // Create S3 bucket
        given()
            .when().put("/" + BUCKET_NAME)
            .then().statusCode(anyOf(equalTo(200), equalTo(409)));

        // Create table
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "%s",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                    ]
                }
                """.formatted(TABLE_NAME))
            .when().post("/").then().statusCode(200);
        enablePointInTimeRecovery(TABLE_NAME);

        // Insert 3 items
        putItem("{\"pk\": {\"S\": \"user-1\"}, \"sk\": {\"S\": \"order-001\"}, \"total\": {\"N\": \"99\"}}");
        putItem("{\"pk\": {\"S\": \"user-2\"}, \"sk\": {\"S\": \"order-002\"}, \"total\": {\"N\": \"55\"}}");
        putItem("{\"pk\": {\"S\": \"user-3\"}, \"sk\": {\"S\": \"order-003\"}, \"total\": {\"N\": \"150\"}}");

        setupDone = true;
    }

    @Test
    void exportTableToPointInTime_returnsInProgress() {
        String exportArn = given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "S3Prefix": "exports",
                    "ExportFormat": "DYNAMODB_JSON"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportDescription.ExportArn", notNullValue())
            .body("ExportDescription.ExportStatus", equalTo("IN_PROGRESS"))
            .body("ExportDescription.TableArn", equalTo(TABLE_ARN))
            .body("ExportDescription.S3Bucket", equalTo(BUCKET_NAME))
            .body("ExportDescription.ExportFormat", equalTo("DYNAMODB_JSON"))
            .body("ExportDescription.ExportType", equalTo("FULL_EXPORT"))
            .extract().path("ExportDescription.ExportArn");

        assertNotNull(exportArn);
        assertTrue(exportArn.contains("/export/"), "ExportArn should contain /export/ segment");

        // Poll DescribeExport until COMPLETED (max 10s)
        String status = pollUntilCompleted(exportArn, 10_000);
        assertEquals("COMPLETED", status, "Export should complete within 10 seconds");
    }

    @Test
    void describeExport_returnsCompletedExportWithS3Manifest() throws Exception {
        String exportArn = startExport("exports-describe");
        String status = pollUntilCompleted(exportArn, 10_000);
        assertEquals("COMPLETED", status);

        // Verify ExportManifest is set
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"" + exportArn + "\"}")
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportDescription.ExportStatus", equalTo("COMPLETED"))
            .body("ExportDescription.ItemCount", equalTo(3))
            .body("ExportDescription.BilledSizeBytes", greaterThan(0))
            .body("ExportDescription.ExportManifest", notNullValue());
    }

    @Test
    void listExports_returnsCompletedExport() throws Exception {
        String exportArn = startExport("exports-list");
        pollUntilCompleted(exportArn, 10_000);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListExports")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableArn\": \"" + TABLE_ARN + "\"}")
            .when().post("/")
            .then()
            .statusCode(200)
            .body("ExportSummaries", hasSize(greaterThanOrEqualTo(1)))
            .body("ExportSummaries[0].ExportArn", notNullValue())
            .body("ExportSummaries[0].ExportStatus", notNullValue())
            .body("ExportSummaries[0].ExportType", equalTo("FULL_EXPORT"));
    }

    @Test
    void exportTableToPointInTime_unsupportedExportType_returnsValidationException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "ExportType": "INCREMENTAL_EXPORT"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void exportTableToPointInTime_ionFormat_returnsValidationException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "ExportFormat": "ION"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void exportTableToPointInTime_tableNotFound_returnsTableNotFoundException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "arn:aws:dynamodb:us-east-1:000000000000:table/NonExistentTable",
                    "S3Bucket": "%s"
                }
                """.formatted(BUCKET_NAME))
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("TableNotFoundException"))
            .body("message", equalTo(
                "Table not found: arn:aws:dynamodb:us-east-1:000000000000:table/NonExistentTable"));
    }

    @Test
    void describeExport_notFound_returnsExportNotFoundException() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"arn:aws:dynamodb:us-east-1:000000000000:table/T/export/doesnotexist\"}")
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", containsString("ExportNotFoundException"));
    }

    @Test
    void exportData_s3ObjectsExist_andNdjsonIsValid() throws Exception {
        String exportArn = startExport("exports-data");
        pollUntilCompleted(exportArn, 10_000);

        String manifestKey = given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"ExportArn\": \"" + exportArn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .extract().path("ExportDescription.ExportManifest");

        assertNotNull(manifestKey, "ExportManifest key should be set");

        // Extract bucket prefix from the manifest key
        // manifestKey is like: exports-data/AWSDynamoDB/<exportId>/manifest-summary.json
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String manifestFilesKey = "exports-data/AWSDynamoDB/" + exportId + "/manifest-files.json";

        // Download manifest-files.json and find the data key
        byte[] manifestFiles = given()
            .when().get("/" + BUCKET_NAME + "/" + manifestFilesKey)
            .then().statusCode(200)
            .extract().asByteArray();

        String dataKey = new String(manifestFiles, StandardCharsets.UTF_8).trim();
        assertFalse(dataKey.isEmpty(), "manifest-files.json should contain the data file key");
        assertTrue(dataKey.endsWith(".json.gz"), "Data file should be a .json.gz file");

        // Download and decompress the data file
        byte[] gzipData = given()
            .when().get("/" + BUCKET_NAME + "/" + dataKey)
            .then().statusCode(200)
            .extract().asByteArray();

        String ndjson = decompressGzip(gzipData);
        String[] lines = ndjson.split("\n");
        assertEquals(3, lines.length, "Should have 3 items in the export");

        for (String line : lines) {
            assertTrue(line.contains("\"Item\""), "Each line should have Item wrapper");
            assertTrue(line.contains("\"pk\""), "Each line should have pk attribute");
        }
    }

    @Test
    void exportTimeExportsTheItemsOfThatTime() throws Exception {
        String table = "ExportTimeItems";
        createHashTable(table);
        BigDecimal earliest = decimalAt(enablePointInTimeRecovery(table),
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");
        putHashItem(table, "k1", "v1");
        BigDecimal t1 = pause();
        putHashItem(table, "k1", "v2");
        putHashItem(table, "k2", "v1");

        exportAt(table, "export-time-earliest", earliest).statusCode(200);
        ValidatableResponse atT1 = exportAt(table, "export-time-t1", t1).statusCode(200);
        assertEquals(0, t1.compareTo(decimalAt(atT1, "/ExportDescription/ExportTime")));
        long before = System.currentTimeMillis();
        ValidatableResponse noTime = ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "export-time-now"}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)).statusCode(200);
        long after = System.currentTimeMillis();
        BigDecimal startTime = decimalAt(noTime, "/ExportDescription/StartTime");
        assertEquals(0, startTime.compareTo(decimalAt(noTime, "/ExportDescription/ExportTime")));
        long startMillis = startTime.movePointRight(3).longValueExact();
        assertTrue(before <= startMillis && startMillis <= after, before + " <= " + startTime + " <= " + after);

        String atT1Arn = atT1.extract().path("ExportDescription.ExportArn");
        assertEquals("COMPLETED", pollUntilCompleted(atT1Arn, 10_000));
        assertEquals(List.of("k1=v1"), exportedItems(atT1Arn, "export-time-t1"));
        String noTimeArn = noTime.extract().path("ExportDescription.ExportArn");
        assertEquals("COMPLETED", pollUntilCompleted(noTimeArn, 10_000));
        assertEquals(List.of("k1=v2", "k2=v1"), exportedItems(noTimeArn, "export-time-now"));
    }

    @Test
    void exportTableToPointInTimeChecksInTheAwsOrder() throws Exception {
        String table = "ExportTimeChecks";
        long beforeCreate = System.currentTimeMillis();
        createHashTable(table);
        long afterCreate = System.currentTimeMillis();
        BigDecimal future = BigDecimal.valueOf(System.currentTimeMillis() + 60_000, 3);

        expectError(exportAt("ExportTimeMissing", "export-checks", future),
                "TableNotFoundException", "Table not found: " + TABLE_ARN_PREFIX + "ExportTimeMissing");
        expectError(exportAt(table, "export-checks", future), "InvalidExportTimeException", "Export Time is invalid");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s"}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)), "PointInTimeRecoveryUnavailableException",
                "Point in time recovery is not enabled for table '" + table + "'");

        // EarliestRestorableDateTime is in whole seconds. Enabling in a later second puts it after the creation.
        while (System.currentTimeMillis() / 1000 <= afterCreate / 1000) {
            Thread.sleep(10);
        }
        BigDecimal earliest = decimalAt(enablePointInTimeRecovery(table),
                "/ContinuousBackupsDescription/PointInTimeRecoveryDescription/EarliestRestorableDateTime");
        expectError(exportAt(table, "export-checks", BigDecimal.valueOf(beforeCreate - 1000, 3)),
                "InvalidExportTimeException", "Export Time is invalid");
        expectError(exportAt(table, "export-checks", earliest.subtract(new BigDecimal("0.001"))),
                "InvalidExportTimeException", "Point in Time Recovery is not available for specified export time");
        expectError(exportAt(table, "export-checks", future), "InvalidExportTimeException", "Export Time is invalid");
        exportAt(table, "export-checks", earliest).statusCode(200);
    }

    @Test
    void exportTimeBeyondTheMillisecondRangeIsInvalid() {
        String table = "ExportTimeRange";
        createHashTable(table);
        enablePointInTimeRecovery(table);
        BigDecimal nowPlusTwoToThe64Millis = BigDecimal.valueOf(System.currentTimeMillis(), 3)
                .add(new BigDecimal(BigInteger.TWO.pow(64), 3));

        expectError(exportAt(table, "export-range", nowPlusTwoToThe64Millis),
                "InvalidExportTimeException", "Export Time is invalid");
        expectError(ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "ExportTime": 1e400}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME)),
                "InvalidExportTimeException", "Export Time is invalid");
    }

    // --- Helpers ---

    /** Returns a time between the writes before and after the call, in epoch seconds with milliseconds. */
    private static BigDecimal pause() {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.onSpinWait();
        }
        return BigDecimal.valueOf(now, 3);
    }

    private static void createHashTable(String table) {
        ddb("CreateTable", """
            {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
             "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
             "BillingMode": "PAY_PER_REQUEST"}
            """.formatted(table)).statusCode(200);
    }

    private static void putHashItem(String table, String pk, String value) {
        ddb("PutItem", """
            {"TableName": "%s", "Item": {"pk": {"S": "%s"}, "v": {"S": "%s"}}}
            """.formatted(table, pk, value)).statusCode(200);
    }

    private static ValidatableResponse exportAt(String table, String prefix, BigDecimal exportTime) {
        return ddb("ExportTableToPointInTime", """
            {"TableArn": "%s", "S3Bucket": "%s", "S3Prefix": "%s", "ExportTime": %s}
            """.formatted(TABLE_ARN_PREFIX + table, BUCKET_NAME, prefix, exportTime.toPlainString()));
    }

    private List<String> exportedItems(String exportArn, String prefix) throws Exception {
        String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
        String dataKey = given()
            .when().get("/" + BUCKET_NAME + "/" + prefix + "/AWSDynamoDB/" + exportId + "/manifest-files.json")
            .then().statusCode(200)
            .extract().asString().trim();
        byte[] gzipData = given()
            .when().get("/" + BUCKET_NAME + "/" + dataKey)
            .then().statusCode(200)
            .extract().asByteArray();
        List<String> items = new ArrayList<>();
        for (String line : decompressGzip(gzipData).split("\n")) {
            JsonPath item = JsonPath.from(line);
            items.add(item.getString("Item.pk.S") + "=" + item.getString("Item.v.S"));
        }
        return items.stream().sorted().toList();
    }

    private static void expectError(ValidatableResponse response, String type, String message) {
        response.statusCode(400)
            .body("__type", containsString(type))
            .body("message", equalTo(message));
    }

    private static ValidatableResponse enablePointInTimeRecovery(String table) {
        return ddb("UpdateContinuousBackups", """
            {"TableName": "%s", "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": true}}
            """.formatted(table)).statusCode(200);
    }

    private void putItem(String itemJson) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"" + TABLE_NAME + "\", \"Item\": " + itemJson + "}")
            .when().post("/").then().statusCode(200);
    }

    private String startExport(String prefix) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810.ExportTableToPointInTime")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableArn": "%s",
                    "S3Bucket": "%s",
                    "S3Prefix": "%s"
                }
                """.formatted(TABLE_ARN, BUCKET_NAME, prefix))
            .when().post("/")
            .then().statusCode(200)
            .extract().path("ExportDescription.ExportArn");
    }

    private String pollUntilCompleted(String exportArn, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = given()
                .header("X-Amz-Target", "DynamoDB_20120810.DescribeExport")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"ExportArn\": \"" + exportArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("ExportDescription.ExportStatus");

            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
        return "TIMEOUT";
    }

    private String decompressGzip(byte[] data) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
