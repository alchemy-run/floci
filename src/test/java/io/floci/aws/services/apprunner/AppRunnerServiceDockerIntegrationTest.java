package io.floci.aws.services.apprunner;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.apprunner.AppRunnerIntegrationTest.call;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;

/** An App Runner service runs its ECR Public image in a container and serves it at its URL. */
@QuarkusTest
class AppRunnerServiceDockerIntegrationTest {
    private static final String IMAGE = "public.ecr.aws/nginx/nginx:alpine";

    @Inject
    DockerClient docker;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            docker.pingCmd().exec();
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available");
    }

    private static String status(String arn) {
        return call("DescribeService", "{\"ServiceArn\":\"" + arn + "\"}").extract().path("Service.Status");
    }

    private static void awaitStatus(String arn, String wanted) {
        Awaitility.await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1))
                .until(() -> wanted.equals(status(arn)));
    }

    @Test
    void aServiceServesItsImagePausesResumesAndDeletes() {
        String name = "nginx-" + UUID.randomUUID().toString().substring(0, 8);
        ExtractableResponse<Response> created = call("CreateService", "{\"ServiceName\":\"" + name + "\",\"SourceConfiguration\":"
                + "{\"ImageRepository\":{\"ImageIdentifier\":\"" + IMAGE + "\",\"ImageRepositoryType\":\"ECR_PUBLIC\","
                + "\"ImageConfiguration\":{\"Port\":\"80\",\"RuntimeEnvironmentVariables\":{\"STAGE\":\"test\"}}},"
                + "\"AutoDeploymentsEnabled\":false},\"InstanceConfiguration\":{\"Cpu\":\"0.25 vCPU\",\"Memory\":"
                + "\"0.5 GB\"},\"HealthCheckConfiguration\":{\"Protocol\":\"HTTP\",\"Path\":\"/\"}}")
                .statusCode(200).extract();
        String arn = created.path("Service.ServiceArn");
        String url = created.path("Service.ServiceUrl");
        String id = created.path("Service.ServiceId");
        awaitStatus(arn, "RUNNING");
        call("DescribeService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200)
                .body("Service.InstanceConfiguration.Cpu", equalTo("256"))
                .body("Service.InstanceConfiguration.Memory", equalTo("512"))
                .body("Service.AutoScalingConfigurationSummary.AutoScalingConfigurationName",
                        equalTo("DefaultConfiguration"));

        given().header("Host", url).get("/").then().statusCode(200).body(containsString("Welcome to nginx"));
        call("ListOperations", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200)
                .body("OperationSummaryList.Type", hasItem("CREATE_SERVICE"))
                .body("OperationSummaryList.Status", hasItem("SUCCEEDED"));
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/logs/aws4_request")
                .contentType("application/x-amz-json-1.1").header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                .body("{\"logGroupNamePrefix\":\"/aws/apprunner/" + name + "/" + id + "/\"}").post("/").then()
                .statusCode(200).body("logGroups.logGroupName", hasItems("/aws/apprunner/" + name + "/" + id
                        + "/application", "/aws/apprunner/" + name + "/" + id + "/service"));

        call("PauseService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200);
        awaitStatus(arn, "PAUSED");
        given().header("Host", url).get("/").then().statusCode(404);
        call("PauseService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(400)
                .body("__type", containsString("InvalidStateException"));
        call("ResumeService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200);
        awaitStatus(arn, "RUNNING");
        given().header("Host", url).get("/").then().statusCode(200);

        call("StartDeployment", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200);
        awaitStatus(arn, "RUNNING");
        call("UpdateService", "{\"ServiceArn\":\"" + arn + "\",\"HealthCheckConfiguration\":{\"Protocol\":\"HTTP\","
                + "\"Path\":\"/missing\"}}").statusCode(200);
        Awaitility.await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() ->
                call("ListOperations", "{\"ServiceArn\":\"" + arn + "\"}").extract()
                        .<List<String>>path("OperationSummaryList.Status").contains("ROLLBACK_SUCCEEDED"));
        call("DescribeService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200)
                .body("Service.Status", equalTo("RUNNING")).body("Service.HealthCheckConfiguration.Path", equalTo("/"));

        call("DeleteService", "{\"ServiceArn\":\"" + arn + "\"}").statusCode(200);
        awaitStatus(arn, "DELETED");
        given().header("Host", url).get("/").then().statusCode(404);
    }
}
