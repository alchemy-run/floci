package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wire-level coverage for the Step Functions operations Alchemy's local-dev
 * suite exercises: UpdateStateMachine (revision + tracing), TestState,
 * RedriveExecution, Distributed Map Runs, reverse-order history, and
 * SendTaskHeartbeat token validation.
 */
@QuarkusTest
class StepFunctionsAlchemyParityIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String PASS_V1 =
            "{\"Comment\":\"v1\",\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"Result\":{\"ok\":true},\"End\":true}}}";
    private static final String PASS_V2 =
            "{\"Comment\":\"v2\",\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"Result\":{\"ok\":true},\"End\":true}}}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateStateMachine_bumpsRevisionAndPersistsTracing() {
        String arn = createStateMachine("parity-update-sm", PASS_V1, "STANDARD", true);

        Response created = describeStateMachine(arn);
        created.then().statusCode(200)
                .body("tracingConfiguration.enabled", equalTo(true))
                .body("loggingConfiguration.level", equalTo("OFF"));
        String revision1 = created.jsonPath().getString("revisionId");
        assertNotNull(revision1);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.UpdateStateMachine")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + arn + "\",\"definition\":" + quote(PASS_V2)
                        + ",\"tracingConfiguration\":{\"enabled\":false}}")
                .when().post("/")
                .then().statusCode(200)
                .body("revisionId", notNullValue())
                .body("updateDate", notNullValue());

        Response after = describeStateMachine(arn);
        after.then().statusCode(200)
                .body("tracingConfiguration.enabled", equalTo(false));
        assertEquals("v2", after.jsonPath().getString("definition").contains("v2") ? "v2" : "missing");
        assertNotEquals(revision1, after.jsonPath().getString("revisionId"));
    }

    @Test
    void testState_executesJsonataPass() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TestState")
                .contentType(CT)
                .body("{\"definition\":" + quote("""
                        {
                          "Type": "Pass",
                          "QueryLanguage": "JSONata",
                          "Output": "{% $states.input.value * 2 %}",
                          "End": true
                        }
                        """) + ",\"input\":" + quote("{\"value\":21}") + "}")
                .when().post("/")
                .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("output", equalTo("42"));
    }

    @Test
    void redriveExecution_rejectsSucceeded() throws Exception {
        String arn = createStateMachine("parity-redrive-sm", PASS_V1, "STANDARD", false);
        String execArn = startExecution(arn, "{}");
        waitForSucceeded(execArn);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.RedriveExecution")
                .contentType(CT)
                .body("{\"executionArn\":\"" + execArn + "\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ExecutionNotRedrivable"));
    }

    @Test
    void distributedMapRun_listDescribeUpdate() throws Exception {
        String definition = """
                {
                  "StartAt": "MapItems",
                  "States": {
                    "MapItems": {
                      "Type": "Map",
                      "ItemsPath": "$.items",
                      "MaxConcurrency": 1,
                      "ItemProcessor": {
                        "ProcessorConfig": {
                          "Mode": "DISTRIBUTED",
                          "ExecutionType": "EXPRESS"
                        },
                        "StartAt": "PassItem",
                        "States": { "PassItem": { "Type": "Pass", "End": true } }
                      },
                      "End": true
                    }
                  }
                }
                """;
        String arn = createStateMachine("parity-map-sm", definition, "STANDARD", false);
        String execArn = startExecution(arn, "{\"items\":[1,2]}");
        waitForSucceeded(execArn);

        Response list = given()
                .header("X-Amz-Target", "AWSStepFunctions.ListMapRuns")
                .contentType(CT)
                .body("{\"executionArn\":\"" + execArn + "\"}")
                .when().post("/");
        list.then().statusCode(200).body("mapRuns", hasSize(1));
        String mapRunArn = list.jsonPath().getString("mapRuns[0].mapRunArn");
        assertNotNull(mapRunArn);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeMapRun")
                .contentType(CT)
                .body("{\"mapRunArn\":\"" + mapRunArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("itemCounts.succeeded", equalTo(2))
                .body("itemCounts.total", equalTo(2))
                .body("maxConcurrency", equalTo(1));

        given()
                .header("X-Amz-Target", "AWSStepFunctions.UpdateMapRun")
                .contentType(CT)
                .body("{\"mapRunArn\":\"" + mapRunArn + "\",\"maxConcurrency\":2}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getExecutionHistory_reverseOrderPutsSucceededFirst() throws Exception {
        String arn = createStateMachine("parity-history-sm", PASS_V1, "STANDARD", false);
        String execArn = startExecution(arn, "{}");
        waitForSucceeded(execArn);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(CT)
                .body("{\"executionArn\":\"" + execArn + "\",\"reverseOrder\":true,\"maxResults\":20}")
                .when().post("/")
                .then().statusCode(200)
                .body("events.size()", greaterThan(0))
                .body("events[0].type", equalTo("ExecutionSucceeded"));
    }

    @Test
    void failState_surfacesAwsShapedExecutionFailureNotJavaException() {
        String definition = """
                {
                  "StartAt": "Reject",
                  "States": {
                    "Reject": {
                      "Type": "Fail",
                      "Error": "OrderRejected",
                      "Cause": "no stock"
                    }
                  }
                }
                """;
        String arn = createStateMachine("parity-fail-shape-sm", definition, "EXPRESS", false);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.StartSyncExecution")
                .header("Host", "sync-states.us-east-1.amazonaws.com")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + arn + "\",\"input\":\"{}\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("error", equalTo("OrderRejected"))
                .body("cause", equalTo("no stock"));
    }

    @Test
    void parallelCatch_recoversTypedFailState() {
        // Alchemy Sfn.catchTag(Sfn.fail(...)) compiles to a single-branch Parallel
        // whose Catch matches the Fail state's Error. Future.get must unwrap
        // FailStateException or the Java class name leaks and Catch never fires.
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Try",
                  "States": {
                    "Try": {
                      "Type": "Parallel",
                      "Branches": [
                        {
                          "StartAt": "Reject",
                          "States": {
                            "Reject": {
                              "Type": "Fail",
                              "Error": "OrderRejected",
                              "Cause": "no stock"
                            }
                          }
                        }
                      ],
                      "Catch": [
                        {
                          "ErrorEquals": ["OrderRejected"],
                          "Next": "Recover",
                          "Assign": {
                            "recovered": "{% $states.errorOutput.Cause %}"
                          }
                        }
                      ]
                    },
                    "Recover": {
                      "Type": "Pass",
                      "Output": {
                        "recovered": "{% $recovered %}"
                      },
                      "End": true
                    }
                  }
                }
                """;
        String arn = createStateMachine("parity-fail-catch-sm", definition, "EXPRESS", false);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.StartSyncExecution")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + arn + "\",\"input\":\"{}\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("output", equalTo("{\"recovered\":\"no stock\"}"));
    }

    @Test
    void startSyncExecution_acceptsAmazonStatesServiceTargetAndSyncHost() {
        String arn = createStateMachine("parity-sync-host-sm", PASS_V1, "EXPRESS", false);

        given()
                .header("X-Amz-Target", "AmazonStatesService.StartSyncExecution")
                .header("Host", "sync-states.us-east-1.amazonaws.com")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + arn + "\",\"input\":\"{}\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"));
    }

    @Test
    void sendTaskHeartbeat_rejectsUnknownToken() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.SendTaskHeartbeat")
                .contentType(CT)
                .body("{\"taskToken\":\"not-a-token\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidToken"));
    }

    private String createStateMachine(String name, String definition, String type, boolean tracing) {
        String body = "{\"name\":\"" + name + "\",\"definition\":" + quote(definition)
                + ",\"roleArn\":\"" + ROLE_ARN + "\",\"type\":\"" + type + "\""
                + ",\"tracingConfiguration\":{\"enabled\":" + tracing + "}}";
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(CT)
                .body(body)
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private Response describeStateMachine(String arn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + arn + "\"}")
                .when().post("/");
    }

    private String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + smArn + "\",\"input\":" + quote(input) + "}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private void waitForSucceeded(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(CT)
                    .body("{\"executionArn\":\"" + execArn + "\"}")
                    .when().post("/");
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return;
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
