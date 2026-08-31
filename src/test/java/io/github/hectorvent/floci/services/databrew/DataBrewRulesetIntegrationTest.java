package io.github.hectorvent.floci.services.databrew;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies DataBrew restJson1 ruleset create/describe/update/delete and tags. */
@QuarkusTest
class DataBrewRulesetIntegrationTest {

    private static final String AUTH = auth("000000000501", "us-east-1");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRulesetOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/rulesets/missing-ruleset")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateDeleteAndTagsLifecycle() {
        String datasetName = "source-" + UUID.randomUUID().toString().substring(0, 8);
        String rulesetName = "quality-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Format": "CSV",
                          "Input": {
                            "S3InputDefinition": { "Bucket": "raw-data", "Key": "raw/data.csv" }
                          }
                        }
                        """.formatted(datasetName))
                .when()
                .post("/datasets")
                .then()
                .statusCode(200)
                .body("Name", equalTo(datasetName));

        String targetArn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/datasets/" + datasetName)
                .then()
                .statusCode(200)
                .extract()
                .path("ResourceArn");

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "Description": "basic data quality",
                          "TargetArn": "%s",
                          "Rules": [
                            {
                              "Name": "no-missing-ids",
                              "CheckExpression": "AGG(MISSING_VALUES_PERCENTAGE) == :val1",
                              "SubstitutionMap": { ":val1": "0" },
                              "ColumnSelectors": [{ "Name": "id" }]
                            }
                          ],
                          "Tags": { "Environment": "test" }
                        }
                        """.formatted(rulesetName, targetArn))
                .when()
                .post("/rulesets")
                .then()
                .statusCode(200)
                .body("Name", equalTo(rulesetName));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/rulesets/" + rulesetName)
                .then()
                .statusCode(200)
                .body("Name", equalTo(rulesetName))
                .body("Description", equalTo("basic data quality"))
                .body("TargetArn", equalTo(targetArn))
                .body("Rules", hasSize(1))
                .body("Rules[0].Name", equalTo("no-missing-ids"))
                .body("Rules[0].CheckExpression",
                        equalTo("AGG(MISSING_VALUES_PERCENTAGE) == :val1"))
                .body("Tags.Environment", equalTo("test"))
                .body("ResourceArn", notNullValue())
                .extract()
                .path("ResourceArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/rulesets")
                .then()
                .statusCode(200)
                .body("Rulesets.find { it.Name == '" + rulesetName + "' }.TargetArn", equalTo(targetArn))
                .body("Rulesets.find { it.Name == '" + rulesetName + "' }.RuleCount", equalTo(1));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Description": "basic data quality v2",
                          "Rules": [
                            {
                              "Name": "no-missing-ids",
                              "CheckExpression": "AGG(MISSING_VALUES_PERCENTAGE) == :val1",
                              "SubstitutionMap": { ":val1": "0" },
                              "ColumnSelectors": [{ "Name": "id" }]
                            },
                            {
                              "Name": "row-count",
                              "CheckExpression": "AGG(DUPLICATE_ROWS_COUNT) == :val1",
                              "SubstitutionMap": { ":val1": "0" }
                            }
                          ]
                        }
                        """)
                .when()
                .put("/rulesets/" + rulesetName)
                .then()
                .statusCode(200)
                .body("Name", equalTo(rulesetName));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/rulesets/" + rulesetName)
                .then()
                .statusCode(200)
                .body("Description", equalTo("basic data quality v2"))
                .body("Rules", hasSize(2))
                .body("Rules[1].Name", equalTo("row-count"));

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
                .get("/rulesets/" + rulesetName)
                .then()
                .statusCode(200)
                .body("Tags.Team", equalTo("platform"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "%s",
                          "TargetArn": "%s",
                          "Rules": [{ "Name": "x", "CheckExpression": "AGG(MISSING_VALUES_PERCENTAGE) == :val1" }]
                        }
                        """.formatted(rulesetName, targetArn))
                .when()
                .post("/rulesets")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/rulesets/" + rulesetName)
                .then()
                .statusCode(200)
                .body("Name", equalTo(rulesetName));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/rulesets/" + rulesetName)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(404);
    }

    @Test
    void createRulesetWithoutRulesFailsWithValidationException() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "no-rules",
                          "TargetArn": "arn:aws:databrew:us-east-1:000000000501:dataset/missing"
                        }
                        """)
                .when()
                .post("/rulesets")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/databrew/aws4_request";
    }
}
