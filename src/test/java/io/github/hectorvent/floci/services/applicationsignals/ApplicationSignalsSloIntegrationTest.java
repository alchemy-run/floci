package io.github.hectorvent.floci.services.applicationsignals;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Application Signals restJson1 SLO create/get/update/tag/delete. */
@QuarkusTest
class ApplicationSignalsSloIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String SLI = """
            "SliConfig": {
              "SliMetricConfig": {
                "MetricDataQueries": [
                  {
                    "Id": "m1",
                    "MetricStat": {
                      "Metric": {
                        "Namespace": "Alchemy/Test",
                        "MetricName": "AppSignalsSloLatency",
                        "Dimensions": [{"Name": "Fixture", "Value": "slo"}]
                      },
                      "Period": 60,
                      "Stat": "Average"
                    },
                    "ReturnData": true
                  }
                ]
              },
              "MetricThreshold": %s,
              "ComparisonOperator": "LessThanOrEqualTo"
            }
            """;
    private static final String GOAL = """
            "Goal": {
              "Interval": {"RollingInterval": {"DurationUnit": "DAY", "Duration": 7}},
              "AttainmentGoal": %s,
              "WarningThreshold": 50
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getServiceLevelObjectiveOnABogusIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000901", EAST))
                .when()
                .get("/slo/alchemy-nonexistent-slo-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("ServiceLevelObjective"));
    }

    @Test
    void sloCreateUpdateTagsReplaceAndDeleteLifecycle() {
        String authorization = auth("000000000902", EAST);
        String createBody = """
                {
                  "Name": "lifecycle-slo",
                  "Description": "alchemy application-signals test slo",
                  %s,
                  %s,
                  "Tags": [
                    {"Key": "fixture", "Value": "application-signals-slo"},
                    {"Key": "alchemy::id", "Value": "Slo"}
                  ]
                }
                """.formatted(SLI.formatted("2000"), GOAL.formatted("99"));

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(createBody)
                .when()
                .post("/slo")
                .then()
                .statusCode(200)
                .body("Slo.Name", equalTo("lifecycle-slo"))
                .body("Slo.Arn", notNullValue())
                .body("Slo.EvaluationType", equalTo("PeriodBased"))
                .body("Slo.Description", equalTo("alchemy application-signals test slo"))
                .body("Slo.Goal.AttainmentGoal", equalTo(99))
                .body("Slo.Sli.MetricThreshold", equalTo(2000f))
                .extract()
                .path("Slo.Arn");
        assertTrue(arn.contains(":slo/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/slo/lifecycle-slo")
                .then()
                .statusCode(200)
                .body("Slo.Arn", equalTo(arn))
                .body("Slo.Sli.MetricThreshold", equalTo(2000f));

        Map<String, String> tags = tagsByKey(given()
                .header("Authorization", authorization)
                .when()
                .get("/tags?ResourceArn=" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("Tags"));
        assertEquals("application-signals-slo", tags.get("fixture"));
        assertEquals("Slo", tags.get("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Description": "alchemy application-signals test slo (updated)",
                          %s,
                          %s
                        }
                        """.formatted(SLI.formatted("3000"), GOAL.formatted("99.5")))
                .when()
                .patch("/slo/lifecycle-slo")
                .then()
                .statusCode(200)
                .body("Slo.Arn", equalTo(arn))
                .body("Slo.Description", equalTo("alchemy application-signals test slo (updated)"))
                .body("Slo.Goal.AttainmentGoal", equalTo(99.5f))
                .body("Slo.Sli.MetricThreshold", equalTo(3000f));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceArn": "%s",
                          "Tags": [{"Key": "updated", "Value": "true"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/tag-resource")
                .then()
                .statusCode(200);

        Map<String, String> updatedTags = tagsByKey(given()
                .header("Authorization", authorization)
                .when()
                .get("/tags?ResourceArn=" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("Tags"));
        assertEquals("true", updatedTags.get("updated"));

        String replacedArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name": "alchemy-appsignals-slo-replaced",
                          "Description": "alchemy application-signals test slo (replaced)",
                          %s,
                          %s,
                          "Tags": [{"Key": "fixture", "Value": "application-signals-slo"}]
                        }
                        """.formatted(SLI.formatted("3000"), GOAL.formatted("99.5")))
                .when()
                .post("/slo")
                .then()
                .statusCode(200)
                .body("Slo.Name", equalTo("alchemy-appsignals-slo-replaced"))
                .extract()
                .path("Slo.Arn");
        assertNotEquals(arn, replacedArn);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/slo/lifecycle-slo")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/lifecycle-slo")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/slo/alchemy-appsignals-slo-replaced")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/slo/alchemy-appsignals-slo-replaced")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateCreateReturnsConflict() {
        String authorization = auth("000000000903", EAST);
        String body = """
                {
                  "Name": "duplicate-slo",
                  %s,
                  %s
                }
                """.formatted(SLI.formatted("1000"), GOAL.formatted("99"));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/slo")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/slo")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/application-signals/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tagsByKey(List<Map<String, Object>> tags) {
        return tags.stream().collect(
                java.util.stream.Collectors.toMap(
                        tag -> (String) tag.get("Key"),
                        tag -> (String) tag.get("Value"),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new));
    }
}
