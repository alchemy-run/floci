package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Verifies DataBrew restJson1 schedule create/describe/update/delete and tags. */
@QuarkusTest
class DataBrewScheduleIntegrationTest {

    private static final String AUTH = auth("000000000401", "us-east-1");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeScheduleOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/schedules/missing-schedule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateDeleteAndTagsLifecycle() {
        String name = "nightly-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "Name": "%s",
                  "CronExpression": "cron(0 3 * * ? *)",
                  "Tags": { "Environment": "test" }
                }
                """.formatted(name);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/schedules")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/schedules/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("CronExpression", equalTo("cron(0 3 * * ? *)"))
                .body("ResourceArn", notNullValue())
                .body("Tags.Environment", equalTo("test"))
                .extract()
                .path("ResourceArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/schedules")
                .then()
                .statusCode(200)
                .body("Schedules.find { it.Name == '" + name + "' }.CronExpression",
                        equalTo("cron(0 3 * * ? *)"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"CronExpression\":\"cron(30 4 * * ? *)\"}")
                .when()
                .put("/schedules/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/schedules/" + name)
                .then()
                .statusCode(200)
                .body("CronExpression", equalTo("cron(30 4 * * ? *)"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Tags\":{\"Team\":\"platform\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Team", equalTo("platform"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/tags/" + arn + "?tagKeys=Environment")
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.Environment", nullValue())
                .body("Tags.Team", equalTo("platform"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(body)
                .when()
                .post("/schedules")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/schedules/" + name)
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/schedules/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
