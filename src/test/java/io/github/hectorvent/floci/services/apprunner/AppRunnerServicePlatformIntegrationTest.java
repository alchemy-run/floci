package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1;
import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * Coverage for the Effect-native App Runner service path: create, log groups,
 * local virtual-host URL, tags, and delete.
 */
@QuarkusTest
class AppRunnerServicePlatformIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/apprunner/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createServiceMintsLogGroupsAndALocalVirtualHost() {
        String name = "plat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String createBody = """
                {
                  "ServiceName": "%s",
                  "SourceConfiguration": {
                    "ImageRepository": {
                      "ImageIdentifier": "public.ecr.aws/aws-containers/hello-app-runner:latest",
                      "ImageRepositoryType": "ECR",
                      "ImageConfiguration": { "Port": "3000" },
                      "AuthenticationConfiguration": {
                        "AccessRoleArn": "arn:aws:iam::000000000000:role/access"
                      }
                    },
                    "AutoDeploymentsEnabled": false
                  },
                  "InstanceConfiguration": {
                    "Cpu": "256",
                    "Memory": "512",
                    "InstanceRoleArn": "arn:aws:iam::000000000000:role/instance"
                  },
                  "HealthCheckConfiguration": { "Protocol": "HTTP", "Path": "/health" },
                  "Tags": [{"Key": "alchemy:app", "Value": "test"}]
                }
                """.formatted(name);

        var created = given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "AppRunner.CreateService")
                .header("Authorization", AUTH)
                .body(createBody)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.ServiceName", equalTo(name))
                .body("Service.Status", equalTo("RUNNING"))
                .body("Service.ServiceUrl", containsString("awsapprunner.com"))
                .body("Service.ServiceUrl", containsString("localhost"))
                .body("Service.SourceConfiguration.ImageRepository.ImageRepositoryType", equalTo("ECR"))
                .body("Service.InstanceConfiguration.InstanceRoleArn",
                        startsWith("arn:aws:iam::"))
                .extract();

        String arn = created.path("Service.ServiceArn");
        String serviceId = created.path("Service.ServiceId");
        String application = "/aws/apprunner/" + name + "/" + serviceId + "/application";
        String serviceLogs = "/aws/apprunner/" + name + "/" + serviceId + "/service";

        given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_1)
                .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/logs/aws4_request")
                .body("{\"logGroupNamePrefix\":\"" + application + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("logGroups.logGroupName", hasItem(application));

        given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_1)
                .header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/logs/aws4_request")
                .body("{\"logGroupNamePrefix\":\"" + serviceLogs + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("logGroups.logGroupName", hasItem(serviceLogs));

        given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "AppRunner.ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy:app"));

        given()
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "AppRunner.DeleteService")
                .header("Authorization", AUTH)
                .body("{\"ServiceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Service.Status", equalTo("DELETED"));
    }
}
