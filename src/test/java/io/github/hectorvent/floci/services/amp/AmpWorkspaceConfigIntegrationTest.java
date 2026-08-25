package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Workspace configuration, logging, query logging, policy, and anomaly detector. */
@QuarkusTest
class AmpWorkspaceConfigIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listWorkspacesIsAClaimedApsOperation() {
        given()
                .header("Authorization", auth("000000000211", EAST))
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaces", notNullValue());
    }

    @Test
    void workspaceConfigurationLoggingPolicyAndDetectorLifecycle() {
        String authorization = auth("000000000212", EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaces", notNullValue());

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"alias\":\"config-lifecycle\",\"tags\":{\"Owner\":\"floci\"}}")
                .when()
                .post("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaceId", startsWith("ws-"))
                .extract().response();
        String workspaceId = created.path("workspaceId");
        String workspaceArn = created.path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaces.workspaceId", hasItem(workspaceId))
                .body("workspaces.find { it.workspaceId == '" + workspaceId + "' }.tags.Owner",
                        equalTo("floci"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/configuration")
                .then()
                .statusCode(200)
                .body("workspaceConfiguration.retentionPeriodInDays", equalTo(150));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"retentionPeriodInDays\":30}")
                .when()
                .patch("/workspaces/" + workspaceId + "/configuration")
                .then()
                .statusCode(200)
                .body("status.statusCode", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/configuration")
                .then()
                .statusCode(200)
                .body("workspaceConfiguration.retentionPeriodInDays", equalTo(30));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"retentionPeriodInDays\":45}")
                .when()
                .patch("/workspaces/" + workspaceId + "/configuration")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/configuration")
                .then()
                .statusCode(200)
                .body("workspaceConfiguration.retentionPeriodInDays", equalTo(45));

        String logArn = "arn:aws:logs:us-east-1:000000000212:log-group:/aws/vendedlogs/prometheus/lifecycle:*";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"logGroupArn\":\"" + logArn + "\"}")
                .when()
                .post("/workspaces/" + workspaceId + "/logging")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/logging")
                .then()
                .statusCode(200)
                .body("loggingConfiguration.logGroupArn", equalTo(logArn));

        String queryArn = "arn:aws:logs:us-east-1:000000000212:log-group:/aws/vendedlogs/prometheus/lifecycle-queries:*";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "destinations":[{
                            "cloudWatchLogs":{"logGroupArn":"%s"},
                            "filters":{"qspThreshold":0}
                          }]
                        }
                        """.formatted(queryArn))
                .when()
                .post("/workspaces/" + workspaceId + "/logging/query")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "destinations":[{
                            "cloudWatchLogs":{"logGroupArn":"%s"},
                            "filters":{"qspThreshold":1000}
                          }]
                        }
                        """.formatted(queryArn))
                .when()
                .put("/workspaces/" + workspaceId + "/logging/query")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/logging/query")
                .then()
                .statusCode(200)
                .body("queryLoggingConfiguration.destinations", hasSize(1))
                .body("queryLoggingConfiguration.destinations[0].filters.qspThreshold", equalTo(1000));

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"aps:QueryMetrics\"],\"Resource\":\"*\"}]}";
        String firstRevision = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policyDocument\":" + jsonString(policy) + "}")
                .when()
                .put("/workspaces/" + workspaceId + "/policy")
                .then()
                .statusCode(200)
                .body("policyStatus", equalTo("ACTIVE"))
                .extract().path("revisionId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policyDocument\":" + jsonString(policy) + "}")
                .when()
                .put("/workspaces/" + workspaceId + "/policy")
                .then()
                .statusCode(200)
                .body("revisionId", equalTo(firstRevision));

        String detectorId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "alias":"spikes",
                          "evaluationIntervalInSeconds":60,
                          "missingDataAction":{"skip":true},
                          "configuration":{"randomCutForest":{"query":"avg(rate(http_requests_total[5m]))"}},
                          "tags":{"alchemy::id":"Detector"}
                        }
                        """)
                .when()
                .post("/workspaces/" + workspaceId + "/anomalydetectors")
                .then()
                .statusCode(200)
                .body("anomalyDetectorId", notNullValue())
                .extract().path("anomalyDetectorId");
        String detectorArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/anomalydetectors/" + detectorId)
                .then()
                .statusCode(200)
                .extract().path("anomalyDetector.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"alchemy::id\":\"Detector\",\"Environment\":\"test\"}}")
                .when()
                .post("/tags/" + detectorArn)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + detectorArn)
                .then()
                .statusCode(200)
                .body("tags['alchemy::id']", equalTo("Detector"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + workspaceArn)
                .then()
                .statusCode(200)
                .body("tags.Owner", equalTo("floci"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "evaluationIntervalInSeconds":120,
                          "missingDataAction":{"skip":true},
                          "configuration":{"randomCutForest":{"query":"avg(rate(http_requests_total[5m]))"}}
                        }
                        """)
                .when()
                .put("/workspaces/" + workspaceId + "/anomalydetectors/" + detectorId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/anomalydetectors/" + detectorId)
                .then()
                .statusCode(200)
                .body("anomalyDetector.evaluationIntervalInSeconds", equalTo(120))
                .body("anomalyDetector.tags['alchemy::id']", equalTo("Detector"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + workspaceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/logging")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/aps/aws4_request";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
