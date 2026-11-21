package io.floci.aws.services.appflow;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AppFlow: S3-to-S3 flows that run their tasks, schedules, cancellation, profiles and connector metadata. */
@QuarkusTest
class AppFlowIntegrationTest {
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"appflow.amazonaws.com\"},\"Action\":[\"s3:GetObject\",\"s3:ListBucket\","
            + "\"s3:PutObject\",\"s3:GetBucketAcl\",\"s3:PutObjectAcl\"],\"Resource\":[\"arn:aws:s3:::%1$s\","
            + "\"arn:aws:s3:::%1$s/*\"]}]}";

    private static ValidatableResponse call(String operation, String body) {
        return given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/appflow/aws4_request")
                .body(body).post("/" + operation).then();
    }

    private static String bucket(boolean withPolicy) {
        String bucket = "appflow-" + UUID.randomUUID().toString().substring(0, 8);
        given().put("/" + bucket).then().statusCode(200);
        if (withPolicy) {
            given().body(String.format(POLICY, bucket)).put("/" + bucket + "?policy").then().statusCode(200);
        }
        given().body("id,name,amount,email\n1,alpha,10,ada@example.com\n2,beta,5,bob@example.com\n"
                + "3,gamma,20,cy@example.com\n").put("/" + bucket + "/input/data.csv").then().statusCode(200);
        return bucket;
    }

    private static String flow(String name, String bucket, String trigger, String output, String tasks) {
        return "{\"flowName\":\"" + name + "\",\"triggerConfig\":" + trigger + ",\"sourceFlowConfig\":"
                + "{\"connectorType\":\"S3\",\"sourceConnectorProperties\":{\"S3\":{\"bucketName\":\"" + bucket
                + "\",\"bucketPrefix\":\"input\"}}},\"destinationFlowConfigList\":[{\"connectorType\":\"S3\","
                + "\"destinationConnectorProperties\":{\"S3\":{\"bucketName\":\"" + bucket + "\",\"bucketPrefix\":"
                + "\"output\"" + output + "}}}],\"tasks\":" + tasks + ",\"tags\":{\"team\":\"data\"}}";
    }

    private static String awaitRun(String flowName, String executionId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() -> {
            List<String> statuses = call("describe-flow-execution-records", "{\"flowName\":\"" + flowName + "\"}")
                    .extract().path("flowExecutions.findAll { it.executionId == '" + executionId
                            + "' }.executionStatus");
            return !statuses.isEmpty() && !"InProgress".equals(statuses.getFirst())
                    && !"CancelStarted".equals(statuses.getFirst());
        });
        List<String> statuses = call("describe-flow-execution-records", "{\"flowName\":\"" + flowName + "\"}")
                .extract().path("flowExecutions.findAll { it.executionId == '" + executionId + "' }.executionStatus");
        return statuses.getFirst();
    }

    private static String onlyObject(String bucket, String prefix) {
        String listing = given().get("/" + bucket + "?list-type=2&prefix=" + prefix).then().statusCode(200)
                .extract().asString();
        String key = listing.replaceAll("(?s).*<Key>([^<]+)</Key>.*", "$1");
        assertTrue(key.startsWith(prefix), listing);
        return given().get("/" + bucket + "/" + key).then().statusCode(200).extract().asString();
    }

    @Test
    void anS3FlowRunsItsTasksIntoCsv() {
        String bucket = bucket(true);
        String name = "csv-" + UUID.randomUUID().toString().substring(0, 8);
        String tasks = "[{\"taskType\":\"Filter\",\"sourceFields\":[\"id\",\"name\",\"amount\",\"email\"],"
                + "\"connectorOperator\":{\"S3\":\"PROJECTION\"},\"taskProperties\":{}},"
                + "{\"taskType\":\"Filter\",\"sourceFields\":[\"amount\"],\"connectorOperator\":{\"S3\":"
                + "\"GREATER_THAN\"},\"taskProperties\":{\"VALUE\":\"6\",\"DATA_TYPE\":\"integer\"}},"
                + "{\"taskType\":\"Map\",\"sourceFields\":[\"name\"],\"connectorOperator\":{\"S3\":\"NO_OP\"},"
                + "\"destinationField\":\"label\",\"taskProperties\":{}},"
                + "{\"taskType\":\"Mask\",\"sourceFields\":[\"email\"],\"connectorOperator\":{\"S3\":\"MASK_FIRST_N\"},"
                + "\"destinationField\":\"email\",\"taskProperties\":{\"MASK_LENGTH\":\"3\",\"MASK_VALUE\":\"*\"}},"
                + "{\"taskType\":\"Arithmetic\",\"sourceFields\":[\"amount\",\"id\"],\"connectorOperator\":{\"S3\":"
                + "\"MULTIPLICATION\"},\"destinationField\":\"weighted\",\"taskProperties\":{}}]";
        String output = ",\"s3OutputFormatConfig\":{\"fileType\":\"CSV\",\"aggregationConfig\":{\"aggregationType\":"
                + "\"SingleFile\"}}";
        call("create-flow", flow(name, bucket, "{\"triggerType\":\"OnDemand\"}", output, tasks)).statusCode(200)
                .body("flowStatus", equalTo("Active")).body("flowArn", containsString(":flow/" + name));
        call("create-flow", flow(name, bucket, "{\"triggerType\":\"OnDemand\"}", output, tasks)).statusCode(409);

        String executionId = call("start-flow", "{\"flowName\":\"" + name + "\"}").statusCode(200)
                .extract().path("executionId");
        assertEquals("Successful", awaitRun(name, executionId));
        String csv = onlyObject(bucket, "output/" + name + "/" + executionId + "/");
        assertEquals("label,email,weighted\nalpha,***@example.com,10\ngamma,***example.com,60\n", csv);
        call("describe-flow-execution-records", "{\"flowName\":\"" + name + "\"}").statusCode(200)
                .body("flowExecutions[0].executionResult.recordsProcessed", equalTo(3));
        call("describe-flow", "{\"flowName\":\"" + name + "\"}").statusCode(200)
                .body("lastRunExecutionDetails.mostRecentExecutionStatus", equalTo("Successful"))
                .body("tags.team", equalTo("data"));

        call("stop-flow", "{\"flowName\":\"" + name + "\"}").statusCode(400)
                .body("__type", containsString("UnsupportedOperationException"));
        String second = call("start-flow", "{\"flowName\":\"" + name + "\"}").extract().path("executionId");
        call("cancel-flow-executions", "{\"flowName\":\"" + name + "\",\"executionIds\":[\"" + second
                + "\",\"missing\"]}").statusCode(200).body("invalidExecutions", hasItem("missing"));
        assertEquals("Canceled", awaitRun(name, second));

        call("list-flows", "{}").statusCode(200).body("flows.flowName", hasItem(name));
        call("delete-flow", "{\"flowName\":\"" + name + "\"}").statusCode(200);
        call("describe-flow", "{\"flowName\":\"" + name + "\"}").statusCode(404);
    }

    @Test
    void jsonOutputAScheduleAndBucketChecks() {
        String bucket = bucket(true);
        String name = "json-" + UUID.randomUUID().toString().substring(0, 8);
        String mapAll = "[{\"taskType\":\"Map_all\",\"sourceFields\":[],\"connectorOperator\":{\"S3\":\"NO_OP\"},"
                + "\"taskProperties\":{\"EXCLUDE_SOURCE_FIELDS_LIST\":\"[\\\"email\\\"]\"}}]";
        call("create-flow", flow(name, bucket, "{\"triggerType\":\"OnDemand\"}", "", mapAll)).statusCode(200);
        String executionId = call("start-flow", "{\"flowName\":\"" + name + "\"}").extract().path("executionId");
        assertEquals("Successful", awaitRun(name, executionId));
        String json = onlyObject(bucket, "output/" + name + "/" + executionId + "/");
        assertTrue(json.startsWith("{\"id\":\"1\",\"name\":\"alpha\",\"amount\":\"10\"}\n"), json);

        String scheduled = "sched-" + UUID.randomUUID().toString().substring(0, 8);
        call("create-flow", flow(scheduled, bucket, "{\"triggerType\":\"Scheduled\",\"triggerProperties\":"
                + "{\"Scheduled\":{\"scheduleExpression\":\"rate(1hours)\",\"dataPullMode\":\"Incremental\"}}}",
                "", mapAll)).statusCode(200).body("flowStatus", equalTo("Draft"));
        call("start-flow", "{\"flowName\":\"" + scheduled + "\"}").statusCode(200).body("flowStatus", equalTo("Active"));
        call("stop-flow", "{\"flowName\":\"" + scheduled + "\"}").statusCode(200)
                .body("flowStatus", equalTo("Suspended"));

        String noPolicy = bucket(false);
        call("create-flow", flow("nopolicy-" + UUID.randomUUID().toString().substring(0, 6), noPolicy,
                "{\"triggerType\":\"OnDemand\"}", "", mapAll)).statusCode(403);
        String empty = "appflow-" + UUID.randomUUID().toString().substring(0, 8);
        given().put("/" + empty).then().statusCode(200);
        given().body(String.format(POLICY, empty)).put("/" + empty + "?policy").then().statusCode(200);
        call("create-flow", flow("empty-" + UUID.randomUUID().toString().substring(0, 6), empty,
                "{\"triggerType\":\"OnDemand\"}", "", mapAll)).statusCode(400)
                .body("__type", containsString("ConnectorServerException"));
    }

    @Test
    void profilesConnectorsAndEntities() {
        String bucket = bucket(true);
        // Salesforce is checked at its instance: an address that can't be reached is refused.
        call("create-connector-profile", "{\"connectorProfileName\":\"sf-" + UUID.randomUUID().toString()
                .substring(0, 8) + "\",\"connectorType\":\"Salesforce\",\"connectionMode\":\"Public\","
                + "\"connectorProfileConfig\":{\"connectorProfileProperties\":{\"Salesforce\":{\"instanceUrl\":"
                + "\"https://floci-unreachable.invalid\"}},\"connectorProfileCredentials\":{\"Salesforce\":"
                + "{\"accessToken\":\"token\"}}}}").statusCode(400)
                .body("__type", containsString("ConnectorServerException"));

        String profile = "amp-" + UUID.randomUUID().toString().substring(0, 8);
        call("create-connector-profile", "{\"connectorProfileName\":\"" + profile + "\",\"connectorType\":"
                + "\"Amplitude\",\"connectionMode\":\"Public\",\"connectorProfileConfig\":"
                + "{\"connectorProfileProperties\":{},\"connectorProfileCredentials\":{\"Amplitude\":{\"apiKey\":"
                + "\"key\",\"secretKey\":\"secret\"}}}}").statusCode(200)
                .body("connectorProfileArn", containsString(":connectorprofile/" + profile));
        call("describe-connector-profiles", "{\"connectorProfileNames\":[\"" + profile + "\"]}").statusCode(200)
                .body("connectorProfileDetails[0].connectorType", equalTo("Amplitude"))
                .body("connectorProfileDetails[0]", not(hasKey("credentials")));

        String name = "amp-flow-" + UUID.randomUUID().toString().substring(0, 6);
        call("create-flow", "{\"flowName\":\"" + name + "\",\"triggerConfig\":{\"triggerType\":\"OnDemand\"},"
                + "\"sourceFlowConfig\":{\"connectorType\":\"Amplitude\",\"connectorProfileName\":\"" + profile
                + "\",\"sourceConnectorProperties\":{\"Amplitude\":{\"object\":\"EVENTS\"}}},"
                + "\"destinationFlowConfigList\":[{\"connectorType\":\"S3\",\"destinationConnectorProperties\":{\"S3\":"
                + "{\"bucketName\":\"" + bucket + "\"}}}],\"tasks\":[{\"taskType\":\"Map_all\",\"sourceFields\":[],"
                + "\"connectorOperator\":{\"Amplitude\":\"BETWEEN\"},\"taskProperties\":{}}]}").statusCode(200);
        String run = call("start-flow", "{\"flowName\":\"" + name + "\"}").extract().path("executionId");
        assertEquals("Error", awaitRun(name, run));
        call("delete-connector-profile", "{\"connectorProfileName\":\"" + profile + "\"}").statusCode(409);
        call("delete-flow", "{\"flowName\":\"" + name + "\"}").statusCode(200);
        call("delete-connector-profile", "{\"connectorProfileName\":\"" + profile + "\"}").statusCode(200);

        call("list-connector-entities", "{\"connectorType\":\"S3\"}").statusCode(200)
                .body("connectorEntityMap[''].name", hasItem(bucket));
        call("list-connector-entities", "{\"connectorType\":\"S3\",\"entitiesPath\":\"" + bucket + "\"}")
                .statusCode(200).body("connectorEntityMap['" + bucket + "'].label", hasItem("input"));
        call("describe-connector-entity", "{\"connectorType\":\"S3\",\"connectorEntityName\":\"" + bucket
                + "/input\"}").statusCode(200)
                .body("connectorEntityFields.identifier", hasItems("id", "name", "amount", "email"));
        call("describe-connectors", "{\"connectorTypes\":[\"S3\",\"Salesforce\"]}").statusCode(200)
                .body("connectorConfigurations.S3.canUseAsSource", equalTo(true))
                .body("connectorConfigurations.Salesforce.supportedTriggerTypes", hasItem("Event"));
        call("describe-connector", "{\"connectorType\":\"S3\"}").statusCode(200)
                .body("connectorConfiguration.canUseAsDestination", equalTo(true));
    }
}
