package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Nitro Enclaves are chosen at launch, from the request's EnclaveOptions or the launch template's,
 * and DescribeInstances reports what the instance launched with. Enabling them is also how an
 * instance runs Nitro Sandboxes.
 */
@QuarkusTest
class Ec2InstanceEnclaveOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String LAUNCHED = "RunInstancesResponse.instancesSet.item.";
    private static final String DESCRIBED = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    private static String launch(String... params) {
        RequestSpecification request = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200)
                .extract().path(LAUNCHED + "instanceId");
    }

    private static void assertEnclaves(String instanceId, String enabled) {
        given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(DESCRIBED + "enclaveOptions.enabled", equalTo(enabled));
    }

    @Test
    void aLaunchWithoutEnclaveOptionsHasThemDisabled() {
        assertEnclaves(launch("InstanceType", "m6i.xlarge"), "false");
    }

    @Test
    void aLaunchThatEnablesEnclavesReportsThem() {
        assertEnclaves(launch("InstanceType", "m6i.xlarge", "EnclaveOptions.Enabled", "true"), "true");
    }

    @Test
    void aLaunchTemplateEnablesEnclavesForItsInstances() {
        String name = "enclaves-" + UUID.randomUUID();
        given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                .formParam("LaunchTemplateData.InstanceType", "m6i.xlarge")
                .formParam("LaunchTemplateData.EnclaveOptions.Enabled", "true")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        assertEnclaves(launch("LaunchTemplate.LaunchTemplateName", name), "true");
        // The request's EnclaveOptions win over the template's.
        assertEnclaves(launch("LaunchTemplate.LaunchTemplateName", name, "EnclaveOptions.Enabled", "false"), "false");
    }
}
