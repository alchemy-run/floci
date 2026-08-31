package io.github.hectorvent.floci.services.ssmincidents;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * Alchemy {@code test/AWS/SSMIncidents/Bindings.test.ts}: typed not-found /
 * empty-list / idempotent-delete probes on the incident data plane.
 */
@QuarkusTest
class SsmIncidentsBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String PLAN_ARN =
            "arn:aws:ssm-incidents::" + ACCOUNT + ":response-plan/alchemy-nonexistent-probe";
    private static final String RECORD_ARN =
            "arn:aws:ssm-incidents::" + ACCOUNT
                    + ":incident-record/alchemy-nonexistent-probe/11111111-1111-1111-1111-111111111111";
    private static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void startIncidentOnANonexistentResponsePlanFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"responsePlanArn\":\"" + PLAN_ARN + "\"}")
                .when()
                .post("/startIncident")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listIncidentRecordsAnswersWithASummaryList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{}")
                .when()
                .post("/listIncidentRecords")
                .then()
                .statusCode(200)
                .body("incidentRecordSummaries", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
    }

    @Test
    void getIncidentRecordOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("arn", RECORD_ARN)
                .when()
                .get("/getIncidentRecord")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateIncidentRecordOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"arn\":\"" + RECORD_ARN + "\",\"title\":\"probe\"}")
                .when()
                .post("/updateIncidentRecord")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteIncidentRecordOnANonexistentRecordIsIdempotent() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"arn\":\"" + RECORD_ARN + "\"}")
                .when()
                .post("/deleteIncidentRecord")
                .then()
                .statusCode(200);
    }

    @Test
    void createTimelineEventOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "incidentRecordArn":"%s",
                          "eventTime":1710000000,
                          "eventType":"Custom Event",
                          "eventData":"{\\"note\\":\\"probe\\"}"
                        }
                        """.formatted(RECORD_ARN))
                .when()
                .post("/createTimelineEvent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getTimelineEventOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .queryParam("incidentRecordArn", RECORD_ARN)
                .queryParam("eventId", EVENT_ID)
                .when()
                .get("/getTimelineEvent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void updateTimelineEventOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "incidentRecordArn":"%s",
                          "eventId":"%s",
                          "eventData":"{\\"note\\":\\"probe\\"}"
                        }
                        """.formatted(RECORD_ARN, EVENT_ID))
                .when()
                .post("/updateTimelineEvent")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteTimelineEventOnANonexistentRecordIsIdempotent() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "incidentRecordArn":"%s",
                          "eventId":"%s"
                        }
                        """.formatted(RECORD_ARN, EVENT_ID))
                .when()
                .post("/deleteTimelineEvent")
                .then()
                .statusCode(200);
    }

    @Test
    void listTimelineEventsOnANonexistentRecordReturnsAnEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"incidentRecordArn\":\"" + RECORD_ARN + "\"}")
                .when()
                .post("/listTimelineEvents")
                .then()
                .statusCode(200)
                .body("eventSummaries", hasSize(0));
    }

    @Test
    void listRelatedItemsOnANonexistentRecordReturnsAnEmptyList() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"incidentRecordArn\":\"" + RECORD_ARN + "\"}")
                .when()
                .post("/listRelatedItems")
                .then()
                .statusCode(200)
                .body("relatedItems", hasSize(0));
    }

    @Test
    void updateRelatedItemsOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "incidentRecordArn":"%s",
                          "relatedItemsUpdate":{
                            "itemToAdd":{
                              "title":"probe",
                              "identifier":{"type":"OTHER","value":{"url":"https://alchemy.run"}}
                            }
                          }
                        }
                        """.formatted(RECORD_ARN))
                .when()
                .post("/updateRelatedItems")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listIncidentFindingsOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"incidentRecordArn\":\"" + RECORD_ARN + "\"}")
                .when()
                .post("/listIncidentFindings")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void batchGetIncidentFindingsOnANonexistentRecordFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("""
                        {
                          "incidentRecordArn":"%s",
                          "findingIds":["%s"]
                        }
                        """.formatted(RECORD_ARN, EVENT_ID))
                .when()
                .post("/batchGetIncidentFindings")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startIncidentCreatesARecordWhenTheResponsePlanExists() {
        String authorization = auth("000000000910");
        String name = "alchemy-bindings-plan";
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "incidentTemplate":{"title":"alchemy bindings test incident","impact":5}
                        }
                        """.formatted(name))
                .when()
                .post("/createResponsePlan")
                .then()
                .statusCode(200)
                .body("arn", startsWith("arn:aws:ssm-incidents::000000000910:response-plan/"))
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"responsePlanArn\":\"" + arn + "\"}")
                .when()
                .post("/startIncident")
                .then()
                .statusCode(200)
                .body("incidentRecordArn", startsWith(
                        "arn:aws:ssm-incidents::000000000910:incident-record/" + name + "/"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION + "/ssm-incidents/aws4_request";
    }
}
