package io.floci.aws.services.apprunner;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** App Runner's configurations, connectors, connections and request checks (no container needed). */
@QuarkusTest
class AppRunnerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/apprunner/aws4_request")
                .header("X-Amz-Target", "AppRunner." + action).body(body).post("/").then();
    }

    @Test
    void autoScalingConfigurationsAreRevisioned() {
        String name = "asc-" + UUID.randomUUID().toString().substring(0, 8);
        String first = call("CreateAutoScalingConfiguration", "{\"AutoScalingConfigurationName\":\"" + name
                + "\",\"MaxConcurrency\":50,\"MinSize\":1,\"MaxSize\":3,\"Tags\":[{\"Key\":\"team\",\"Value\":\"web\"}]}")
                .statusCode(200).body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(1))
                .body("AutoScalingConfiguration.Latest", equalTo(true))
                .body("AutoScalingConfiguration.AutoScalingConfigurationArn",
                        containsString(":autoscalingconfiguration/" + name + "/1/"))
                .extract().path("AutoScalingConfiguration.AutoScalingConfigurationArn");
        call("CreateAutoScalingConfiguration", "{\"AutoScalingConfigurationName\":\"" + name + "\",\"MaxSize\":5}")
                .statusCode(200).body("AutoScalingConfiguration.AutoScalingConfigurationRevision", equalTo(2));
        call("DescribeAutoScalingConfiguration", "{\"AutoScalingConfigurationArn\":\"" + first + "\"}").statusCode(200)
                .body("AutoScalingConfiguration.Latest", equalTo(false))
                .body("AutoScalingConfiguration.MaxConcurrency", equalTo(50));
        call("ListAutoScalingConfigurations", "{\"AutoScalingConfigurationName\":\"" + name + "\",\"LatestOnly\":true}")
                .statusCode(200).body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationRevision",
                        hasItem(2));
        call("ListAutoScalingConfigurations", "{}").statusCode(200)
                .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationName", hasItem("DefaultConfiguration"));
        call("ListTagsForResource", "{\"ResourceArn\":\"" + first + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));
        call("CreateAutoScalingConfiguration", "{\"AutoScalingConfigurationName\":\"" + name + "\",\"MinSize\":9,"
                + "\"MaxSize\":2}").statusCode(400).body("__type", containsString("InvalidRequestException"));
        call("DeleteAutoScalingConfiguration", "{\"AutoScalingConfigurationArn\":\"" + first
                + "\",\"DeleteAllRevisions\":true}").statusCode(200);
        call("ListAutoScalingConfigurations", "{\"AutoScalingConfigurationName\":\"" + name + "\"}").statusCode(200)
                .body("AutoScalingConfigurationSummaryList.AutoScalingConfigurationName", not(hasItem(name)));
    }

    @Test
    void observabilityConnectorsAndConnections() {
        String name = "obs-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = call("CreateObservabilityConfiguration", "{\"ObservabilityConfigurationName\":\"" + name
                + "\",\"TraceConfiguration\":{\"Vendor\":\"AWSXRAY\"}}").statusCode(200)
                .body("ObservabilityConfiguration.TraceConfiguration.Vendor", equalTo("AWSXRAY"))
                .extract().path("ObservabilityConfiguration.ObservabilityConfigurationArn");
        call("ListObservabilityConfigurations", "{}").statusCode(200)
                .body("ObservabilityConfigurationSummaryList.ObservabilityConfigurationName", hasItem(name));
        call("DeleteObservabilityConfiguration", "{\"ObservabilityConfigurationArn\":\"" + arn + "\"}")
                .statusCode(200).body("ObservabilityConfiguration.Status", equalTo("INACTIVE"));

        String connector = call("CreateVpcConnector", "{\"VpcConnectorName\":\"vpc-" + name + "\",\"Subnets\":"
                + "[\"subnet-0123456789abcdef0\"],\"SecurityGroups\":[\"sg-0123456789abcdef0\"]}").statusCode(200)
                .body("VpcConnector.Status", equalTo("ACTIVE")).extract().path("VpcConnector.VpcConnectorArn");
        call("ListVpcConnectors", "{}").statusCode(200).body("VpcConnectors.VpcConnectorArn", hasItem(connector));
        call("DeleteVpcConnector", "{\"VpcConnectorArn\":\"" + connector + "\"}").statusCode(200)
                .body("VpcConnector.Status", equalTo("INACTIVE"));

        String connection = call("CreateConnection", "{\"ConnectionName\":\"gh-" + name.substring(4)
                + "\",\"ProviderType\":\"GITHUB\"}").statusCode(200)
                .body("Connection.Status", equalTo("PENDING_HANDSHAKE")).extract().path("Connection.ConnectionArn");
        call("DeleteConnection", "{\"ConnectionArn\":\"" + connection + "\"}").statusCode(200);
    }

    @Test
    void serviceRequestsAreChecked() {
        call("CreateService", "{\"ServiceName\":\"private-" + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"SourceConfiguration\":{\"ImageRepository\":{\"ImageIdentifier\":"
                + "\"000000000000.dkr.ecr.us-east-1.amazonaws.com/app:latest\",\"ImageRepositoryType\":\"ECR\"}}}")
                .statusCode(400).body("message", containsString("AccessRoleArn"));
        call("DescribeService", "{\"ServiceArn\":\"arn:aws:apprunner:us-east-1:000000000000:service/missing/"
                + "0123456789abcdef0123456789abcdef\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        call("CreateService", "{\"ServiceName\":\"x\"}").statusCode(400);
        call("ListServices", "{}").statusCode(200).body("ServiceSummaryList", notNullValue());
        String url = call("CreateService", "{\"ServiceName\":\"bad-image-" + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"SourceConfiguration\":{\"CodeRepository\":{\"RepositoryUrl\":\"https://github.com/example/app\","
                + "\"SourceCodeVersion\":{\"Type\":\"BRANCH\",\"Value\":\"main\"}},\"AuthenticationConfiguration\":"
                + "{\"ConnectionArn\":\"arn:aws:apprunner:us-east-1:000000000000:connection/gh/0123\"}}}")
                .statusCode(200).body("Service.Status", equalTo("OPERATION_IN_PROGRESS"))
                .extract().path("Service.ServiceUrl");
        assertTrue(url.endsWith(".us-east-1.awsapprunner.com"), url);
        given().header("Host", "nothing.us-east-1.awsapprunner.com").get("/").then().statusCode(404)
                .body(startsWith("404"));
    }
}
