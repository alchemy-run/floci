package io.github.hectorvent.floci.services.timestream;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.SSLConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 1.0 coverage for Alchemy RecordsSink.test.ts: CommonAttributes writes,
 * COUNT(*) WHERE dimension filter, and RejectedRecordsException for timestamps
 * outside the memory-store retention window.
 */
@QuarkusTest
class TimestreamRecordsSinkIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/timestream/aws4_request";
    private static final String TARGET = "Timestream_20181101.";

    @Inject
    TimestreamDiscoveryTls discoveryTls;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        RestAssured.config = RestAssured.config
                .sslConfig(SSLConfig.sslConfig().relaxedHTTPSValidation());
    }

    @Test
    void describeEndpoints_returnsHttpsReachableAddress() {
        String address = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeEndpoints")
                .header("Authorization", AUTH)
                .header("Host", "localhost:4566")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoints[0].Address", notNullValue())
                .body("Endpoints[0].CachePeriodInMinutes", equalTo(1440))
                .extract().path("Endpoints[0].Address");

        int colon = address.lastIndexOf(':');
        assertTrue(colon > 0, "Address must include a port: " + address);
        String host = address.substring(0, colon);
        int port = Integer.parseInt(address.substring(colon + 1));
        if (discoveryTls.listenPort() > 0) {
            assertEquals(discoveryTls.listenPort(), port,
                    "HTTP-only gateway must advertise the discovery TLS port, got " + address);
        }

        given()
                .baseUri("https://" + host)
                .port(port)
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeEndpoints")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoints[0].Address", notNullValue());
    }

    @Test
    void describeEndpoints_flociDnsHost_rewritesPortOntoTlsSidecar() {
        String address = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeEndpoints")
                .header("Authorization", AUTH)
                .header("Host", "localhost.floci.io:4566")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().path("Endpoints[0].Address");

        assertTrue(address.startsWith("localhost.floci.io:"),
                "Lambda AWS_ENDPOINT_URL Host must be echoed so https:// rewrite lands on the sidecar: "
                        + address);
        if (discoveryTls.listenPort() > 0) {
            assertEquals("localhost.floci.io:" + discoveryTls.listenPort(), address);
        }
    }

    @Test
    void replacePort_swapsGatewayPortOnFlociAndDockerHosts() {
        assertEquals("localhost.floci.io:9999",
                TimestreamDiscoveryTls.replacePort("localhost.floci.io:4566", 9999));
        assertEquals("host.docker.internal:9999",
                TimestreamDiscoveryTls.replacePort("host.docker.internal:4566", 9999));
        assertEquals("127.0.0.1:9999",
                TimestreamDiscoveryTls.replacePort("127.0.0.1", 9999));
    }

    @Test
    void writeQuery_commonAttributesAndWhereHost_countsMatchingRows() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String database = "sink-db-" + suffix;
        String table = "sink-cpu-" + suffix;
        createDatabaseAndTable(database, table);

        long now = System.currentTimeMillis();
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "WriteRecords")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "CommonAttributes": {
                            "MeasureName": "cpu",
                            "MeasureValueType": "DOUBLE",
                            "TimeUnit": "MILLISECONDS"
                          },
                          "Records": [
                            {"Dimensions":[{"Name":"host","Value":"sink-bulk"}],"MeasureValue":"1","Time":"%d"},
                            {"Dimensions":[{"Name":"host","Value":"sink-bulk"}],"MeasureValue":"2","Time":"%d"},
                            {"Dimensions":[{"Name":"host","Value":"other"}],"MeasureValue":"3","Time":"%d"}
                          ]
                        }
                        """.formatted(database, table, now - 2000, now - 1000, now))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RecordsIngested.Total", equalTo(3));

        String sql = "SELECT COUNT(*) AS c FROM \\\"" + database + "\\\".\\\"" + table
                + "\\\" WHERE host = 'sink-bulk'";
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "Query")
                .header("Authorization", AUTH)
                .body("{\"QueryString\":\"" + sql + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Rows[0].Data[0].ScalarValue", equalTo("2"))
                .body("ColumnInfo[0].Name", equalTo("c"));
    }

    @Test
    void writeRecords_outOfRetention_returnsRejectedRecordsException() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String database = "rej-db-" + suffix;
        String table = "rej-cpu-" + suffix;
        createDatabaseAndTable(database, table);

        long now = System.currentTimeMillis();
        long tooOld = now - 24L * 60 * 60 * 1000;
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "WriteRecords")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "CommonAttributes": {
                            "MeasureName": "cpu",
                            "MeasureValueType": "DOUBLE",
                            "TimeUnit": "MILLISECONDS"
                          },
                          "Records": [
                            {"Dimensions":[{"Name":"host","Value":"sink-rejects"}],"MeasureValue":"1","Time":"%d"},
                            {"Dimensions":[{"Name":"host","Value":"sink-rejects"}],"MeasureValue":"2","Time":"%d"},
                            {"Dimensions":[{"Name":"host","Value":"sink-rejects"}],"MeasureValue":"3","Time":"%d"}
                          ]
                        }
                        """.formatted(database, table, now - 2000, tooOld, now - 1000))
        .when()
                .post("/")
        .then()
                .statusCode(419)
                .body("__type", equalTo("RejectedRecordsException"))
                .body("RejectedRecords", hasSize(1))
                .body("RejectedRecords[0].RecordIndex", equalTo(1));

        String sql = "SELECT COUNT(*) AS c FROM \\\"" + database + "\\\".\\\"" + table
                + "\\\" WHERE host = 'sink-rejects'";
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "Query")
                .header("Authorization", AUTH)
                .body("{\"QueryString\":\"" + sql + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Rows[0].Data[0].ScalarValue", equalTo("2"));
    }

    @Test
    void writeRecords_over100_returnsValidationException() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String database = "max-db-" + suffix;
        String table = "max-cpu-" + suffix;
        createDatabaseAndTable(database, table);

        StringBuilder records = new StringBuilder("[");
        long now = System.currentTimeMillis();
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                records.append(',');
            }
            records.append("{\"Dimensions\":[{\"Name\":\"host\",\"Value\":\"bulk\"}],")
                    .append("\"MeasureValue\":\"").append(i).append("\",\"Time\":\"")
                    .append(now - i * 1000L).append("\"}");
        }
        records.append(']');

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "WriteRecords")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "CommonAttributes": {
                            "MeasureName": "cpu",
                            "MeasureValueType": "DOUBLE",
                            "TimeUnit": "MILLISECONDS"
                          },
                          "Records": %s
                        }
                        """.formatted(database, table, records))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static void createDatabaseAndTable(String database, String table) {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + database + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateTable")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "RetentionProperties": {
                            "MemoryStoreRetentionPeriodInHours": 6,
                            "MagneticStoreRetentionPeriodInDays": 30
                          }
                        }
                        """.formatted(database, table))
        .when()
                .post("/")
                .then()
                .statusCode(200);
    }
}
