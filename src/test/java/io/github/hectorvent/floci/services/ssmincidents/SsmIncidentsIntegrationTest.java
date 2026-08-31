package io.github.hectorvent.floci.services.ssmincidents;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies SSM Incident Manager restJson1 replication-set and response-plan APIs. */
@QuarkusTest
class SsmIncidentsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002501";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listReplicationSetsSucceedsWhenIncidentManagerIsNotOnboarded() {
        List<String> arns = given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{}")
                .when()
                .post("/listReplicationSets")
                .then()
                .statusCode(200)
                .extract()
                .path("replicationSetArns");
        assertTrue(arns == null || arns.isEmpty() || arns instanceof List);
    }

    @Test
    void getReplicationSetOnANonexistentArnFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .queryParam("arn",
                        "arn:aws:ssm-incidents::" + ACCOUNT + ":replication-set/00000000-0000-4000-8000-000000000000")
                .when()
                .get("/getReplicationSet")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getResponsePlanOnANonexistentArnFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .queryParam("arn",
                        "arn:aws:ssm-incidents::" + ACCOUNT + ":response-plan/alchemy-nonexistent-probe")
                .when()
                .get("/getResponsePlan")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void replicationSetAndResponsePlanLifecycle() {
        String authorization = auth("000000002502", EAST);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "regions":{"us-east-1":{}},
                          "tags":{"env":"test","alchemy::id":"Incidents"}
                        }
                        """)
                .when()
                .post("/createReplicationSet")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract()
                .path("arn");
        assertTrue(arn.contains(":replication-set/"));
        assertTrue(arn.startsWith("arn:aws:ssm-incidents::000000002502:"));

        given()
                .header("Authorization", authorization)
                .queryParam("arn", arn)
                .when()
                .get("/getReplicationSet")
                .then()
                .statusCode(200)
                .body("replicationSet.status", equalTo("ACTIVE"))
                .body("replicationSet.deletionProtected", equalTo(false))
                .body("replicationSet.regionMap['us-east-1'].status", equalTo("ACTIVE"));

        List<String> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/listReplicationSets")
                .then()
                .statusCode(200)
                .extract()
                .path("replicationSetArns");
        assertEquals(List.of(arn), listed);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("Incidents"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"env\":\"prod\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("prod"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\",\"deletionProtected\":true}")
                .when()
                .post("/updateDeletionProtection")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("arn", arn)
                .when()
                .get("/getReplicationSet")
                .then()
                .statusCode(200)
                .body("replicationSet.deletionProtected", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "arn":"%s",
                          "actions":[{"addRegionAction":{"regionName":"us-west-2"}}]
                        }
                        """.formatted(arn))
                .when()
                .post("/updateReplicationSet")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("arn", arn)
                .when()
                .get("/getReplicationSet")
                .then()
                .statusCode(200)
                .body("replicationSet.regionMap['us-west-2'].status", equalTo("ACTIVE"));

        String planName = "alchemy-test-response-plan";
        String planArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "displayName":"Critical incidents",
                          "incidentTemplate":{
                            "title":"Critical failure",
                            "impact":3,
                            "summary":"Automated test response plan"
                          },
                          "tags":{"fixture":"ssm-incidents"}
                        }
                        """.formatted(planName))
                .when()
                .post("/createResponsePlan")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract()
                .path("arn");
        assertTrue(planArn.contains(":response-plan/"));

        given()
                .header("Authorization", authorization)
                .queryParam("arn", planArn)
                .when()
                .get("/getResponsePlan")
                .then()
                .statusCode(200)
                .body("name", equalTo(planName))
                .body("displayName", equalTo("Critical incidents"))
                .body("incidentTemplate.title", equalTo("Critical failure"))
                .body("incidentTemplate.impact", equalTo(3));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "arn":"%s",
                          "displayName":"Critical incidents (updated)",
                          "incidentTemplateImpact":2
                        }
                        """.formatted(planArn))
                .when()
                .post("/updateResponsePlan")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("arn", planArn)
                .when()
                .get("/getResponsePlan")
                .then()
                .statusCode(200)
                .body("displayName", equalTo("Critical incidents (updated)"))
                .body("incidentTemplate.impact", equalTo(2));

        List<Map<String, Object>> summaries = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/listResponsePlans")
                .then()
                .statusCode(200)
                .extract()
                .path("responsePlanSummaries");
        assertEquals(1, summaries.size());
        assertEquals(planArn, summaries.get(0).get("arn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + planArn + "\"}")
                .when()
                .post("/deleteResponsePlan")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("arn", planArn)
                .when()
                .get("/getResponsePlan")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\",\"deletionProtected\":false}")
                .when()
                .post("/updateDeletionProtection")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("arn", arn)
                .when()
                .post("/deleteReplicationSet")
                .then()
                .statusCode(200);

        Response after = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/listReplicationSets")
                .then()
                .statusCode(200)
                .extract()
                .response();
        List<String> remaining = after.path("replicationSetArns");
        assertTrue(remaining == null || remaining.isEmpty());
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/ssm-incidents/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
