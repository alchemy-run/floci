package io.github.hectorvent.floci.services.timestreaminfluxdb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Timestream InfluxDB coverage used by Alchemy DbInstance.test.ts:
 * GetDbInstance on a hyphenated identifier is ValidationException; missing
 * well-formed ids are ResourceNotFoundException; create/get/list/tag/delete
 * round-trip with AVAILABLE status.
 */
@QuarkusTest
class TimestreamInfluxDbIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/timestream-influxdb/aws4_request";
    private static final String TARGET = "AmazonTimestreamInfluxDB.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDbInstance_malformedIdentifier_returnsValidationException() {
        influx("GetDbInstance", "{\"identifier\":\"alchemy-timestream-does-not-exist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("reason", equalTo("FIELD_VALIDATION_FAILED"));
    }

    @Test
    void getDbInstance_missing_returnsResourceNotFoundException() {
        influx("GetDbInstance", "{\"identifier\":\"abc123doesnotexist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("db-instance"));
    }

    @Test
    void createGetListTagDelete_roundTrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "influx" + suffix;

        String id = influx("CreateDbInstance", """
                {
                  "name": "%s",
                  "dbInstanceType": "db.influx.medium",
                  "allocatedStorage": 20,
                  "vpcSubnetIds": ["subnet-aaa", "subnet-bbb"],
                  "vpcSecurityGroupIds": ["sg-aaa"],
                  "password": "alchemy-super-secret-pw-1",
                  "tags": {"Environment": "test"}
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("name", equalTo(name))
                .body("status", equalTo("AVAILABLE"))
                .body("arn", notNullValue())
                .body("vpcSubnetIds", hasItem("subnet-aaa"))
                .extract().path("id");

        influx("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .then()
                .statusCode(200)
                .body("name", equalTo(name))
                .body("status", equalTo("AVAILABLE"))
                .body("dbInstanceType", equalTo("db.influx.medium"));

        influx("ListDbInstances", "{}")
                .then()
                .statusCode(200)
                .body("items.id", hasItem(id));

        String arn = influx("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .then()
                .extract().path("arn");

        influx("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        influx("UpdateDbInstance", """
                {"identifier":"%s","allocatedStorage":40,"port":8087}
                """.formatted(id))
                .then()
                .statusCode(200)
                .body("allocatedStorage", equalTo(40))
                .body("port", equalTo(8087))
                .body("status", equalTo("AVAILABLE"));

        influx("DeleteDbInstance", "{\"identifier\":\"" + id + "\"}")
                .then()
                .statusCode(200);

        influx("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static io.restassured.response.Response influx(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/");
    }
}
