package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * RunInstances and CreateVolume with a ClientToken are idempotent, as on AWS: a call retried after
 * its reply was lost returns what the first one made instead of making more.
 */
@QuarkusTest
class Ec2RunInstancesClientTokenIntegrationTest {

    private static final String REGION = "ap-southeast-4";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20261105/" + REGION + "/ec2/aws4_request";

    private static RequestSpecification ec2(String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }

    private static List<String> launch(String clientToken, int count) {
        return ec2("RunInstances",
                "ImageId", "ami-0abcdef1234567890",
                "InstanceType", "t3.micro",
                "MinCount", String.valueOf(count),
                "MaxCount", String.valueOf(count),
                "ClientToken", clientToken)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getList("RunInstancesResponse.instancesSet.item.instanceId", String.class);
    }

    private static long instancesWithToken(String clientToken) {
        List<String> tokens = ec2("DescribeInstances", "Filter.1.Name", "instance-state-name",
                "Filter.1.Value.1", "pending", "Filter.1.Value.2", "running")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.clientToken", String.class);
        return tokens.stream().filter(clientToken::equals).count();
    }

    @Test
    void aRetriedLaunchReturnsTheInstancesTheFirstOneStarted() {
        List<String> first = launch("retry-token-a", 2);
        assertEquals(2, first.size());

        assertEquals(first, launch("retry-token-a", 2));
        assertEquals(2, instancesWithToken("retry-token-a"));
    }

    @Test
    void anotherTokenLaunchesAnotherInstance() {
        List<String> first = launch("retry-token-b", 1);
        List<String> second = launch("retry-token-c", 1);
        assertNotEquals(first, second);
    }

    @Test
    void aTokenOfATerminatedInstanceStillReturnsThatInstance() {
        String instanceId = launch("retry-token-d", 1).getFirst();
        ec2("TerminateInstances", "InstanceId.1", instanceId).when().post("/").then().statusCode(200);

        assertEquals(List.of(instanceId), launch("retry-token-d", 1));
    }

    @Test
    void aRetriedCreateVolumeReturnsTheFirstVolume() {
        String first = createVolume("volume-token-a");
        assertEquals(first, createVolume("volume-token-a"));
        assertNotEquals(first, createVolume("volume-token-b"));
    }

    private static String createVolume(String clientToken) {
        return ec2("CreateVolume", "AvailabilityZone", REGION + "a", "Size", "1", "ClientToken", clientToken)
                .when().post("/").then().statusCode(200)
                .extract().path("CreateVolumeResponse.volumeId");
    }
}
