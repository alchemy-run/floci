package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AppRunnerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/apprunner/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeService_missingArn_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeService")
                .header("Authorization", AUTH)
                .body("""
                        {"ServiceArn":"arn:aws:apprunner:us-east-1:000000000000:service/missing/00000000000000000000000000000000"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void serviceLifecycle_pauseResumeDeployAndCustomDomains() {
        String name = "alchemy-test-apprunner-bind";
        String createBody = """
                {
                  "ServiceName": "%s",
                  "SourceConfiguration": {
                    "ImageRepository": {
                      "ImageIdentifier": "public.ecr.aws/aws-containers/hello-app-runner:latest",
                      "ImageRepositoryType": "ECR_PUBLIC",
                      "ImageConfiguration": { "Port": "8000" }
                    }
                  },
                  "InstanceConfiguration": { "Cpu": "256", "Memory": "512" },
                  "Tags": [{"Key":"env","Value":"test"}]
                }
                """.formatted(name + "-" + UUID.randomUUID().toString().substring(0, 8));

        String serviceArn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.CreateService")
                .header("Authorization", AUTH)
                .body(createBody)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.Status", equalTo("RUNNING"))
                .body("Service.ServiceArn", containsString(":service/"))
                .body("Service.ServiceUrl", containsString("awsapprunner.com"))
                .body("Service.InstanceConfiguration.Cpu", equalTo("256"))
                .body("OperationId", notNullValue())
                .extract().path("Service.ServiceArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListOperations")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\",\"MaxResults\":20}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("OperationSummaryList.Type", hasItem("CREATE_SERVICE"))
                .body("OperationSummaryList.Status", hasItem("SUCCEEDED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeCustomDomains")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("CustomDomains", empty())
                .body("DNSTarget", containsString("awsapprunner.com"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.PauseService")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.Status", equalTo("PAUSED"))
                .body("Service.ServiceArn", equalTo(serviceArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ResumeService")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.Status", equalTo("RUNNING"));

        String operationId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.StartDeployment")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("OperationId", notNullValue())
                .extract().path("OperationId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListOperations")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("OperationSummaryList.Id", hasItem(operationId))
                .body("OperationSummaryList.Type", hasItem("START_DEPLOYMENT"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DeleteService")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.Status", equalTo("DELETED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeService")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + serviceArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createService_duplicateName_returnsInvalidRequest() {
        String name = "dup-apprunner-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "ServiceName": "%s",
                  "SourceConfiguration": {
                    "ImageRepository": {
                      "ImageIdentifier": "public.ecr.aws/aws-containers/hello-app-runner:latest",
                      "ImageRepositoryType": "ECR_PUBLIC"
                    }
                  }
                }
                """.formatted(name);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.CreateService")
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.CreateService")
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }
}
