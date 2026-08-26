package io.github.hectorvent.floci.services.synthetics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies CloudWatch Synthetics restJson1 operations used by Alchemy
 * {@code Bindings.test.ts}: GetCanary of an unknown name is a typed
 * ResourceNotFoundException, StopCanary on READY is ConflictException,
 * StartCanary records a run that GetCanaryRuns / DescribeCanariesLastRun see,
 * Function URL {@code /bindings} and {@code /canary} are not claimed, and
 * StartCanary publishes {@code aws.synthetics} events that
 * {@code consumeCanaryEvents} rules (ListRuleNamesByTarget) match.
 */
@QuarkusTest
class SyntheticsControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000701";
    private static final String CREATE_BODY = """
            {
              "Name":"bindings-canary",
              "Code":{"Handler":"index.handler","ZipFile":"UEsDBAoAAAAA"},
              "ArtifactS3Location":"s3://synth-artifacts/bindings",
              "ExecutionRoleArn":"arn:aws:iam::000000000701:role/CanaryRole",
              "Schedule":{"Expression":"rate(0 minute)"},
              "RunConfig":{"TimeoutInSeconds":60},
              "RuntimeVersion":"syn-nodejs-puppeteer-16.1",
              "SuccessRetentionPeriodInDays":1,
              "FailureRetentionPeriodInDays":1,
              "Tags":{"fixture":"synthetics","alchemy::id":"BindingsCanary"}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void functionUrlBindingsPathIsNotClaimedBySynthetics() {
        given()
                .header("Host", "deadbeefdeadbeefdeadbeefdeadbeef.lambda-url.us-east-1.localhost:4566")
                .when()
                .get("/bindings")
                .then()
                .statusCode(404)
                .body("message", containsString("URL ID"));
    }

    @Test
    void functionUrlGetCanaryPathIsNotClaimedBySynthetics() {
        given()
                .header("Host", "deadbeefdeadbeefdeadbeefdeadbeef.lambda-url.us-east-1.localhost:4566")
                .when()
                .get("/canary")
                .then()
                .statusCode(404)
                .body("message", containsString("URL ID"));
    }

    @Test
    void getCanaryOnANonexistentCanaryFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/canary/alchemy-nonexistent-synthetics-canary")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void canaryCreateGetDescribeTagUpdateDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "bindings-canary";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY)
                .when()
                .post("/canary")
                .then()
                .statusCode(200)
                .body("Canary.Name", equalTo(name))
                .body("Canary.Status.State", equalTo("READY"))
                .body("Canary.RuntimeVersion", equalTo("syn-nodejs-puppeteer-16.1"))
                .body("Canary.EngineArn", notNullValue())
                .body("Canary.Tags.fixture", equalTo("synthetics"))
                .body("Canary.Timeline.Created", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + name)
                .then()
                .statusCode(200)
                .body("Canary.Name", equalTo(name))
                .body("Canary.Status.State", equalTo("READY"))
                .body("Canary.Schedule.Expression", equalTo("rate(0 minute)"))
                .body("Canary.RunConfig.TimeoutInSeconds", equalTo(60));

        List<Map<String, Object>> described = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/canaries")
                .then()
                .statusCode(200)
                .extract()
                .path("Canaries");
        assertTrue(described.stream().anyMatch(canary -> name.equals(canary.get("Name"))));

        String arn = "arn:aws:synthetics:" + EAST + ":" + ACCOUNT + ":canary:" + name;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + name)
                .then()
                .statusCode(200)
                .body("Canary.Tags.phase", equalTo("two"))
                .body("Canary.Tags.fixture", equalTo("synthetics"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RunConfig\":{\"TimeoutInSeconds\":90}}")
                .when()
                .patch("/canary/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + name)
                .then()
                .statusCode(200)
                .body("Canary.RunConfig.TimeoutInSeconds", equalTo(90));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/canary/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + name)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void stopCanaryWhenNotRunningReturnsConflictException() {
        String authorization = auth("000000000702", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY.replace("bindings-canary", "stopped-canary")
                        .replace("000000000701", "000000000702"))
                .when()
                .post("/canary")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/canary/stopped-canary/stop")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void startCanaryRecordsARunVisibleToGetCanaryRunsAndLastRun() {
        String authorization = auth("000000000703", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY.replace("bindings-canary", "oneshot-canary")
                        .replace("000000000701", "000000000703"))
                .when()
                .post("/canary")
                .then()
                .statusCode(200)
                .body("Canary.Status.State", equalTo("READY"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxResults\":10}")
                .when()
                .post("/canary/oneshot-canary/runs")
                .then()
                .statusCode(200)
                .body("CanaryRuns.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/canary/oneshot-canary/start")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/canary/oneshot-canary")
                .then()
                .statusCode(200)
                .body("Canary.Status.State", equalTo("STOPPED"));

        int runCount = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxResults\":10}")
                .when()
                .post("/canary/oneshot-canary/runs")
                .then()
                .statusCode(200)
                .body("CanaryRuns[0].Status.State", equalTo("PASSED"))
                .extract()
                .path("CanaryRuns.size()");
        assertTrue(runCount > 0);

        List<Map<String, Object>> lastRuns = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/canaries/last-run")
                .then()
                .statusCode(200)
                .extract()
                .path("CanariesLastRun");
        assertTrue(lastRuns.stream().anyMatch(run -> "oneshot-canary".equals(run.get("CanaryName"))));
    }

    @Test
    void duplicateCreateReturnsConflict() {
        String authorization = auth("000000000704", EAST);
        String body = CREATE_BODY.replace("bindings-canary", "duplicate-canary")
                .replace("000000000701", "000000000704");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/canary")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/canary")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void groupCreateAssociateListAndDelete() {
        String authorization = auth("000000000705", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY.replace("bindings-canary", "grouped-canary")
                        .replace("000000000701", "000000000705"))
                .when()
                .post("/canary")
                .then()
                .statusCode(200);

        String groupId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"ApiCanaries\",\"Tags\":{\"team\":\"platform\"}}")
                .when()
                .post("/group")
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo("ApiCanaries"))
                .body("Group.Arn", notNullValue())
                .extract()
                .path("Group.Id");

        String canaryArn = "arn:aws:synthetics:" + EAST + ":000000000705:canary:grouped-canary";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + canaryArn + "\"}")
                .when()
                .patch("/group/ApiCanaries/associate")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/group/ApiCanaries/resources")
                .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("Resources[0]", equalTo(canaryArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/group/" + groupId)
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo("ApiCanaries"))
                .body("Group.Tags.team", equalTo("platform"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/group/ApiCanaries")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/group/ApiCanaries")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startCanaryPublishesEventsMatchedByConsumeCanaryEventsRule() {
        String account = "000000000707";
        String authorization = auth(account, EAST);
        String eventsAuth = "AWS4-HMAC-SHA256 Credential=" + account + "/20260205/" + EAST
                + "/events/aws4_request";
        String canaryName = "eb-canary";
        String ruleName = "SyntheticsCanaryEvents";
        String lambdaArn = "arn:aws:lambda:" + EAST + ":" + account
                + ":function:SyntheticsBindingsFunction";
        String queueName = "synthetics-canary-events-707";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(CREATE_BODY.replace("bindings-canary", canaryName)
                        .replace("000000000701", account))
                .when()
                .post("/canary")
                .then()
                .statusCode(200)
                .body("Canary.Status.State", equalTo("READY"));

        String queueUrl = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260205/" + EAST + "/sqs/aws4_request")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        String queueArn = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260205/" + EAST + "/sqs/aws4_request")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "QueueArn")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", eventsAuth)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "%s",
                          "EventPattern": "{\\"source\\":[\\"aws.synthetics\\"],\\"detail-type\\":[\\"Synthetics Canary Status Change\\",\\"Synthetics Canary TestRun Successful\\",\\"Synthetics Canary TestRun Failure\\"],\\"detail\\":{\\"canary-name\\":[\\"%s\\"]}}",
                          "State": "ENABLED"
                        }
                        """.formatted(ruleName, canaryName))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", eventsAuth)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "%s",
                          "Targets": [
                            {"Id": "lambda", "Arn": "%s"},
                            {"Id": "queue", "Arn": "%s"}
                          ]
                        }
                        """.formatted(ruleName, lambdaArn, queueArn))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        List<String> ruleNames = given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", eventsAuth)
                .header("X-Amz-Target", "AWSEvents.ListRuleNamesByTarget")
                .body("{\"TargetArn\":\"" + lambdaArn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("RuleNames");
        assertTrue(ruleNames.contains(ruleName));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/canary/" + canaryName + "/start")
                .then()
                .statusCode(200);

        String messageBody = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260205/" + EAST + "/sqs/aws4_request")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "10")
                .formParam("WaitTimeSeconds", "0")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().xmlPath()
                .getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");

        assertNotNull(messageBody, "Expected aws.synthetics events on the consumeCanaryEvents target");
        assertTrue(messageBody.contains("aws.synthetics"), "Expected source aws.synthetics in: " + messageBody);
        assertTrue(
                messageBody.contains("Synthetics Canary TestRun Successful")
                        || messageBody.contains("Synthetics Canary Status Change"),
                "Expected Synthetics detail-type in: " + messageBody);
        assertTrue(messageBody.contains(canaryName), "Expected canary-name " + canaryName + " in: " + messageBody);
        assertTrue(messageBody.contains("canary-name"), "Expected kebab-case canary-name in: " + messageBody);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/synthetics/aws4_request";
    }
}
