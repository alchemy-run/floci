package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies EFS restJson1 mount-target create, default SG, in-place SG modify, and delete. */
@QuarkusTest
class EfsMountTargetIntegrationTest {

    private static final String EAST = "us-east-1";

    @Inject
    Ec2Service ec2Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingMountTargetFailsWithMountTargetNotFound() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/2015-02-01/mount-targets?MountTargetId=fsmt-missing00000000")
                .then()
                .statusCode(404)
                .body("ErrorCode", equalTo("MountTargetNotFound"));
    }

    @Test
    void createDescribeModifyDeleteMountTargetUsesDefaultSecurityGroup() {
        String authorization = auth(EAST);
        Subnet subnet = defaultSubnet();
        String defaultSg = defaultSecurityGroupId(subnet.getVpcId());
        String extraSg = ec2Service.createSecurityGroup(
                EAST, "efs-mt-" + UUID.randomUUID().toString().substring(0, 8),
                "alchemy EFS mount target test", subnet.getVpcId()).getGroupId();

        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CreationToken\":\"" + UUID.randomUUID() + "\",\"Encrypted\":true}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("FileSystemId", startsWith("fs-"))
                .body("LifeCycleState", equalTo("available"))
                .extract()
                .path("FileSystemId");

        String mountTargetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"FileSystemId\":\"" + fileSystemId + "\",\"SubnetId\":\"" + subnet.getSubnetId() + "\"}")
                .when()
                .post("/2015-02-01/mount-targets")
                .then()
                .statusCode(200)
                .body("MountTargetId", startsWith("fsmt-"))
                .body("LifeCycleState", equalTo("available"))
                .body("SubnetId", equalTo(subnet.getSubnetId()))
                .body("FileSystemId", equalTo(fileSystemId))
                .body("IpAddress", notNullValue())
                .body("AvailabilityZoneName", equalTo(subnet.getAvailabilityZone()))
                .extract()
                .path("MountTargetId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/mount-targets?MountTargetId=" + mountTargetId)
                .then()
                .statusCode(200)
                .body("MountTargets", hasSize(1))
                .body("MountTargets[0].LifeCycleState", equalTo("available"))
                .body("MountTargets[0].SubnetId", equalTo(subnet.getSubnetId()));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
                .then()
                .statusCode(200)
                .body("SecurityGroups", equalTo(List.of(defaultSg)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SecurityGroups\":[\"" + defaultSg + "\",\"" + extraSg + "\"]}")
                .when()
                .put("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
                .then()
                .statusCode(200)
                .body("SecurityGroups", hasItems(defaultSg, extraSg))
                .body("SecurityGroups", hasSize(2));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems?FileSystemId=" + fileSystemId)
                .then()
                .statusCode(200)
                .body("FileSystems[0].NumberOfMountTargets", equalTo(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"FileSystemId\":\"" + fileSystemId + "\",\"SubnetId\":\"" + subnet.getSubnetId() + "\"}")
                .when()
                .post("/2015-02-01/mount-targets")
                .then()
                .statusCode(409)
                .body("ErrorCode", equalTo("MountTargetConflict"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(409)
                .body("ErrorCode", equalTo("FileSystemInUse"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/mount-targets/" + mountTargetId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/mount-targets?MountTargetId=" + mountTargetId)
                .then()
                .statusCode(404)
                .body("ErrorCode", equalTo("MountTargetNotFound"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems?FileSystemId=" + fileSystemId)
                .then()
                .statusCode(404)
                .body("ErrorCode", equalTo("FileSystemNotFound"));

        ec2Service.deleteSecurityGroup(EAST, extraSg);
    }

    @Test
    void createFileSystemIsIdempotentOnCreationToken() {
        String authorization = auth(EAST);
        String token = "token-" + UUID.randomUUID();
        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CreationToken\":\"" + token + "\",\"Encrypted\":true}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .body("LifeCycleState", equalTo("available"))
                .extract()
                .path("FileSystemId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"CreationToken\":\"" + token + "\",\"Encrypted\":true}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(409)
                .body("ErrorCode", equalTo("FileSystemAlreadyExists"))
                .body("FileSystemId", equalTo(fileSystemId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/lifecycle-configuration")
                .then()
                .statusCode(200)
                .body("LifecyclePolicies", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/policy")
                .then()
                .statusCode(404)
                .body("ErrorCode", equalTo("PolicyNotFound"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/2015-02-01/file-systems/" + fileSystemId)
                .then()
                .statusCode(200);
    }

    private Subnet defaultSubnet() {
        List<Subnet> subnets = ec2Service.describeSubnets(
                EAST, List.of(), Map.of("default-for-az", List.of("true")));
        return subnets.get(0);
    }

    private String defaultSecurityGroupId(String vpcId) {
        List<SecurityGroup> groups = ec2Service.describeSecurityGroups(
                EAST, List.of(), List.of("default"), Map.of("vpc-id", List.of(vpcId)));
        return groups.get(0).getGroupId();
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/elasticfilesystem/aws4_request";
    }
}
