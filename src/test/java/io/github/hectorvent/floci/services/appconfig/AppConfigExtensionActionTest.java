package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppConfigExtensionActionTest {

    private static final String REGION = "us-east-1";

    @Inject
    EventBridgeService eventBridgeService;
    @Inject
    SqsService sqsService;
    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deploymentFiresOnDeploymentCompleteToEventBridge() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String appId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"ext-app-" + suffix + "\"}")
                .when().post("/applications")
                .then()
                .statusCode(201)
                .extract().path("Id");
        String envId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"ext-env\"}")
                .when().post("/applications/" + appId + "/environments")
                .then()
                .statusCode(201)
                .extract().path("Id");
        String profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"ext-profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"}")
                .when().post("/applications/" + appId + "/configurationprofiles")
                .then()
                .statusCode(201)
                .extract().path("Id");
        given()
                .header("Content-Type", "application/json")
                .body("{\"ok\": true}".getBytes())
                .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(201);
        String strategyId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"ext-immediate-" + suffix + "\", \"DeploymentDurationInMinutes\": 0, \"GrowthFactor\": 100, \"FinalBakeTimeInMinutes\": 0}")
                .when().post("/deploymentstrategies")
                .then()
                .statusCode(201)
                .extract().path("Id");

        String queueName = "appconfig-ext-" + suffix;
        var queue = sqsService.createQueue(queueName, Map.of(), REGION);
        String queueArn = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("QueueArn"), REGION).get("QueueArn");
        eventBridgeService.putRule(
                "appconfig-ext-" + suffix,
                "default",
                "{\"source\":[\"aws.appconfig\"]}",
                null,
                RuleState.ENABLED,
                "AppConfig extension delivery",
                null,
                null,
                REGION);
        Target target = new Target();
        target.setId("sqs");
        target.setArn(queueArn);
        eventBridgeService.putTargets("appconfig-ext-" + suffix, "default", List.of(target), REGION);

        String extensionId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"ext-notify-" + suffix + "\", \"Actions\": {\"ON_DEPLOYMENT_START\": [{\"Name\": \"start\", \"Uri\": \"arn:aws:events:us-east-1:000000000000:event-bus/default\"}], \"ON_DEPLOYMENT_COMPLETE\": [{\"Name\": \"done\", \"Uri\": \"arn:aws:events:us-east-1:000000000000:event-bus/default\"}]}}")
                .when().post("/extensions")
                .then()
                .statusCode(201)
                .extract().path("Id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"ExtensionIdentifier\": \"" + extensionId + "\", \"ResourceIdentifier\": \"arn:aws:appconfig:us-east-1:000000000000:application/" + appId + "/environment/" + envId + "\"}")
                .when().post("/extensionassociations")
                .then()
                .statusCode(201);

        int deploymentNumber = given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"1\", \"DeploymentStrategyId\": \"" + strategyId + "\"}")
                .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
                .then()
                .statusCode(201)
                .body("State", equalTo("COMPLETE"))
                .extract().path("DeploymentNumber");

        JsonNode complete = awaitEvent(queue.getQueueUrl(), "OnDeploymentComplete", 8);
        assertEquals(deploymentNumber, complete.path("detail").path("DeploymentNumber").asInt());
        assertFalse(complete.path("detail").path("InvocationId").asText().isBlank());
        assertEquals(envId, complete.path("detail").path("Environment").path("Id").asText());
        assertEquals(appId, complete.path("detail").path("Application").path("Id").asText());
        assertEquals("On Deployment Complete", complete.path("detail-type").asText());
        assertEquals("aws.appconfig", complete.path("source").asText());
        assertTrue(complete.path("detail").path("Application").isObject());
    }

    @Test
    void deploymentInvokesLambdaExtensionWithoutFailingStart() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String appId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"lambda-ext-app-" + suffix + "\"}")
                .when().post("/applications")
                .then()
                .statusCode(201)
                .extract().path("Id");
        String envId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"lambda-ext-env\"}")
                .when().post("/applications/" + appId + "/environments")
                .then()
                .statusCode(201)
                .extract().path("Id");
        String profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"lambda-ext-profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"}")
                .when().post("/applications/" + appId + "/configurationprofiles")
                .then()
                .statusCode(201)
                .extract().path("Id");
        given()
                .header("Content-Type", "application/json")
                .body("{\"ok\": true}".getBytes())
                .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(201);
        String strategyId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"lambda-ext-immediate-" + suffix + "\", \"DeploymentDurationInMinutes\": 0, \"GrowthFactor\": 100, \"FinalBakeTimeInMinutes\": 0}")
                .when().post("/deploymentstrategies")
                .then()
                .statusCode(201)
                .extract().path("Id");

        String functionName = "appconfig-ext-fn-" + suffix;
        given()
                .contentType(ContentType.JSON)
                .body("{\"FunctionName\":\"" + functionName + "\",\"Runtime\":\"nodejs18.x\",\"Handler\":\"index.handler\",\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\"}")
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);
        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:" + functionName;

        String extensionId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"lambda-ext-" + suffix + "\", \"Actions\": {\"ON_DEPLOYMENT_COMPLETE\": [{\"Name\": \"invoke\", \"Uri\": \"" + functionArn + "\", \"RoleArn\": \"arn:aws:iam::000000000000:role/appconfig\"}]}}")
                .when().post("/extensions")
                .then()
                .statusCode(201)
                .extract().path("Id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"ExtensionIdentifier\": \"" + extensionId + "\", \"ResourceIdentifier\": \"arn:aws:appconfig:us-east-1:000000000000:application/" + appId + "/environment/" + envId + "\"}")
                .when().post("/extensionassociations")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"1\", \"DeploymentStrategyId\": \"" + strategyId + "\"}")
                .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
                .then()
                .statusCode(201)
                .body("State", equalTo("COMPLETE"));
    }

    private JsonNode awaitEvent(String queueUrl, String type, int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 5, 1, REGION);
            for (Message message : messages) {
                JsonNode envelope = objectMapper.readTree(message.getBody());
                if (type.equals(envelope.path("detail").path("Type").asText())) {
                    return envelope;
                }
            }
        }
        throw new AssertionError("Did not receive AppConfig extension event of type " + type);
    }
}
