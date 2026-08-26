package io.github.hectorvent.floci.services.timestream;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Timestream coverage used by Alchemy Bindings.test.ts:
 * DescribeEndpoints, database/table CRUD, WriteRecords, Query COUNT(*), PrepareQuery.
 */
@QuarkusTest
class TimestreamIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/timestream/aws4_request";
    private static final String TARGET = "Timestream_20181101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpoints_echoesHost() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeEndpoints")
                .header("Authorization", AUTH)
                .header("Host", "localhost:4566")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Endpoints[0].Address", equalTo("localhost:4566"))
                .body("Endpoints[0].CachePeriodInMinutes", equalTo(1440));
    }

    @Test
    void describeDatabase_missing_returnsResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"missing-db-name\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void writeQueryPrepare_roundTrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String database = "metrics-" + suffix;
        String table = "cpu-" + suffix;

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + database + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Database.DatabaseName", equalTo(database))
                .body("Database.Arn", notNullValue());

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"arn:aws:timestream:us-east-1:000000000000:database/" + database + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("env"))
                .body("Tags[0].Value", equalTo("test"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateTable")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + database + "\",\"TableName\":\"" + table + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Table.TableName", equalTo(table))
                .body("Table.TableStatus", equalTo("ACTIVE"))
                .body("Table.RetentionProperties.MemoryStoreRetentionPeriodInHours", equalTo(6));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "WriteRecords")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "DatabaseName": "%s",
                          "TableName": "%s",
                          "Records": [{
                            "Dimensions": [{"Name":"host","Value":"web-1"}],
                            "MeasureName": "cpu",
                            "MeasureValue": "42.0",
                            "MeasureValueType": "DOUBLE",
                            "Time": "%s",
                            "TimeUnit": "MILLISECONDS"
                          }]
                        }
                        """.formatted(database, table, System.currentTimeMillis()))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RecordsIngested.Total", greaterThanOrEqualTo(1));

        String sql = "SELECT COUNT(*) AS c FROM \\\"" + database + "\\\".\\\"" + table + "\\\"";
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "Query")
                .header("Authorization", AUTH)
                .body("{\"QueryString\":\"" + sql + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Rows[0].Data[0].ScalarValue", equalTo("1"))
                .body("ColumnInfo[0].Name", equalTo("c"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "PrepareQuery")
                .header("Authorization", AUTH)
                .body("{\"QueryString\":\"" + sql + "\",\"ValidateOnly\":true}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Columns[0].Name", equalTo("c"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteTable")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + database + "\",\"TableName\":\"" + table + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + database + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void createDatabase_duplicate_returnsConflictException() {
        String name = "dup-db-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + name + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateDatabase")
                .header("Authorization", AUTH)
                .body("{\"DatabaseName\":\"" + name + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }
}
