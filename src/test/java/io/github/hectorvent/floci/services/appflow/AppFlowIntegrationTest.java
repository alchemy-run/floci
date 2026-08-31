package io.github.hectorvent.floci.services.appflow;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies AppFlow restJson1 flow lifecycle, S3-to-S3 StartFlow, and tags. */
@QuarkusTest
class AppFlowIntegrationTest {

    private static final String EAST = "us-east-1";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFlowOnANonexistentFlowFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"flowName\":\"missing-flow\"}")
                .when()
                .post("/describe-flow")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createFlowWithAnEmptyS3PrefixFailsWithConnectorServerException() {
        String bucket = "appflow-it-empty-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(bucket, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body(flowBody("empty-prefix-flow", bucket, "input", "initial description"))
                .when()
                .post("/create-flow")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ConnectorServerException"));
    }

    @Test
    void createDescribeStartStopCancelDeleteAndTagsLifecycle() {
        String bucket = "appflow-it-" + UUID.randomUUID().toString().substring(0, 8);
        String flowName = "lifecycle-flow";
        s3Service.createBucket(bucket, EAST);
        s3Service.putObject(bucket, "input/data.csv", "id,name\n1,alpha\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", Map.of());

        String authorization = auth(EAST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(flowBody(flowName, bucket, "input", "initial description"))
                .when()
                .post("/create-flow")
                .then()
                .statusCode(200)
                .body("flowArn", notNullValue())
                .body("flowStatus", equalTo("Active"))
                .extract().path("flowArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/describe-flow")
                .then()
                .statusCode(200)
                .body("flowName", equalTo(flowName))
                .body("flowArn", equalTo(arn))
                .body("description", equalTo("initial description"))
                .body("flowStatus", equalTo("Active"))
                .body("triggerConfig.triggerType", equalTo("OnDemand"))
                .body("sourceFlowConfig.connectorType", equalTo("S3"))
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(flowBody(flowName, bucket, "input", "updated description"))
                .when()
                .post("/update-flow")
                .then()
                .statusCode(200)
                .body("flowStatus", equalTo("Active"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/describe-flow")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated description"))
                .body("flowArn", equalTo(arn));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"platform\"}}")
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
                .body("tags.Team", equalTo("platform"));

        String executionId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/start-flow")
                .then()
                .statusCode(200)
                .body("executionId", notNullValue())
                .body("flowArn", equalTo(arn))
                .extract().path("executionId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/describe-flow-execution-records")
                .then()
                .statusCode(200)
                .body("flowExecutions[0].executionId", equalTo(executionId))
                .body("flowExecutions[0].executionStatus", equalTo("Successful"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/stop-flow")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnsupportedOperationException"));

        String secondId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/start-flow")
                .then()
                .statusCode(200)
                .extract().path("executionId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\",\"executionIds\":[\"" + secondId + "\"]}")
                .when()
                .post("/cancel-flow-executions")
                .then()
                .statusCode(200)
                .body("invalidExecutions", hasItem(secondId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\",\"forceDelete\":true}")
                .when()
                .post("/delete-flow")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"flowName\":\"" + flowName + "\"}")
                .when()
                .post("/describe-flow")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String flowBody(String flowName, String bucket, String prefix, String description) {
        return """
                {
                  "flowName":"%s",
                  "description":"%s",
                  "triggerConfig":{"triggerType":"OnDemand"},
                  "sourceFlowConfig":{
                    "connectorType":"S3",
                    "sourceConnectorProperties":{"S3":{"bucketName":"%s","bucketPrefix":"%s"}}
                  },
                  "destinationFlowConfigList":[{
                    "connectorType":"S3",
                    "destinationConnectorProperties":{"S3":{"bucketName":"%s","bucketPrefix":"output"}}
                  }],
                  "tasks":[{
                    "taskType":"Map_all",
                    "sourceFields":[],
                    "connectorOperator":{"S3":"NO_OP"},
                    "taskProperties":{}
                  }],
                  "tags":{"Environment":"test"}
                }
                """.formatted(flowName, description, bucket, prefix, bucket);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/appflow/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
