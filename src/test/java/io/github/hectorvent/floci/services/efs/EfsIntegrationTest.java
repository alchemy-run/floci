package io.github.hectorvent.floci.services.efs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EfsIntegrationTest {

    @Inject
    Ec2Service ec2;

    private String fileSystemId;
    private String mountTargetId;
    private String accessPointId;
    private String vpcId;
    private Subnet subnet;
    private String securityGroupId;
    private String replacementSecurityGroupId;

    @BeforeAll
    void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        vpcId = ec2.createVpc("us-east-1", "10.87.0.0/16", false).getVpcId();
        subnet = ec2.createSubnet("us-east-1", vpcId, "10.87.1.0/24", "us-east-1b");
        securityGroupId = ec2.createSecurityGroup("us-east-1", "efs-initial", "EFS initial", vpcId).getGroupId();
        replacementSecurityGroupId = ec2.createSecurityGroup(
                "us-east-1", "efs-replacement", "EFS replacement", vpcId).getGroupId();
    }

    @AfterAll
    void deleteNetwork() {
        ec2.deleteSecurityGroup("us-east-1", replacementSecurityGroupId);
        ec2.deleteSecurityGroup("us-east-1", securityGroupId);
        ec2.deleteSubnet("us-east-1", subnet.getSubnetId());
        ec2.deleteVpc("us-east-1", vpcId);
    }

    @Test
    @Order(1)
    void createFileSystem() {
        fileSystemId = given()
            .contentType("application/json")
            .body("""
                {
                    "CreationToken": "my-token",
                    "PerformanceMode": "generalPurpose",
                    "Encrypted": true,
                    "Tags": [{"Key": "Name", "Value": "MyFS"}]
                }
                """)
        .when()
            .post("/2015-02-01/file-systems")
        .then()
            .statusCode(201)
            .body("FileSystemId", startsWith("fs-"))
            .body("Encrypted", equalTo(true))
            .body("NumberOfMountTargets", equalTo(0))
            .body("FileSystemProtection.ReplicationOverwriteProtection", equalTo("ENABLED"))
            .body("Tags[0].Value", equalTo("MyFS"))
            .extract().jsonPath().getString("FileSystemId");
    }

    @Test
    @Order(2)
    void describeFileSystemsByFileSystemId() {
    given()
        .queryParam("FileSystemId", fileSystemId)
    .when()
        .get("/2015-02-01/file-systems")
    .then()
        .statusCode(200)
        .body("FileSystems.size()", equalTo(1))
        .body("FileSystems[0].FileSystemId", equalTo(fileSystemId));
    }

    @Test
    @Order(3)
    void updateFileSystem() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ThroughputMode": "provisioned",
                    "ProvisionedThroughputInMibps": 50.0
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId)
        .then()
            .statusCode(202)
            .body("ProvisionedThroughputInMibps", equalTo(50.0f));
    }

    @Test
    @Order(4)
    void createMountTarget() {
        mountTargetId = given()
            .contentType("application/json")
            .body("""
                {
                    "FileSystemId": "%s",
                    "SubnetId": "%s",
                    "SecurityGroups": ["%s"]
                }
                """.formatted(fileSystemId, subnet.getSubnetId(), securityGroupId))
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargetId", startsWith("fsmt-"))
            .body("FileSystemId", equalTo(fileSystemId))
            .body("SubnetId", equalTo(subnet.getSubnetId()))
            .body("VpcId", equalTo(vpcId))
            .body("AvailabilityZoneName", equalTo(subnet.getAvailabilityZone()))
            .body("AvailabilityZoneId", equalTo(subnet.getAvailabilityZoneId()))
            .body("IpAddress", startsWith("10.87.1."))
            .body("OwnerId", equalTo(subnet.getOwnerId()))
            .extract().jsonPath().getString("MountTargetId");
    }

    @Test
    @Order(5)
    void describeMountTargets() {
        given()
            .contentType("application/json")
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/mount-targets")
        .then()
            .statusCode(200)
            .body("MountTargets.MountTargetId", hasItem(mountTargetId));
    }
    
    @Test
    @Order(6)
    void modifyMountTargetSecurityGroups() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "SecurityGroups": ["%s"]
                }
                """.formatted(replacementSecurityGroupId))
        .when()
            .put("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
        .then()
            .statusCode(204);
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/mount-targets/" + mountTargetId + "/security-groups")
        .then()
            .statusCode(200)
            .body("SecurityGroups", contains(replacementSecurityGroupId));
    }

    @Test
    @Order(7)
    void createAccessPoint() {
        accessPointId = given()
            .contentType("application/json")
            .body("""
                {
                    "ClientToken": "ap-token",
                    "FileSystemId": "%s"
                }
                """.formatted(fileSystemId))
        .when()
            .post("/2015-02-01/access-points")
        .then()
            .statusCode(200)
            .body("AccessPointId", startsWith("fsap-"))
            .body("FileSystemId", equalTo(fileSystemId))
            .extract().jsonPath().getString("AccessPointId");
    }

    @Test
    @Order(8)
    void describeAccessPoints() {
        given()
            .contentType("application/json")
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/access-points")
        .then()
            .statusCode(200)
            .body("AccessPoints.AccessPointId", hasItem(accessPointId));
    }
    
    @Test
    @Order(9)
    void manageFileSystemPolicy() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Policy": "{\\"Statement\\": []}"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId + "/policy")
        .then()
            .statusCode(200)
            .body("Policy", equalTo("{\"Statement\": []}"));
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/file-systems/" + fileSystemId + "/policy")
        .then()
            .statusCode(200)
            .body("Policy", equalTo("{\"Statement\": []}"));
    }

    @Test
    @Order(10)
    void describeReportsProtectionEnabledByDefault() {
        given()
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemProtection.ReplicationOverwriteProtection",
                    equalTo("ENABLED"));
    }

    @Test
    @Order(11)
    void updateFileSystemProtection() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ReplicationOverwriteProtection": "DISABLED"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId + "/protection")
        .then()
            .statusCode(200)
            .body("ReplicationOverwriteProtection", equalTo("DISABLED"));

        // The change has to survive the round trip, not just echo back: the terraform
        // provider reads protection back through DescribeFileSystems.
        given()
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems[0].FileSystemProtection.ReplicationOverwriteProtection",
                    equalTo("DISABLED"));
    }

    @Test
    @Order(12)
    void updateFileSystemProtectionOnMissingFileSystemIs404() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "ReplicationOverwriteProtection": "DISABLED"
                }
                """)
        .when()
            .put("/2015-02-01/file-systems/fs-does-not-exist/protection")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(12)
    void mountTargetUsesDefaultGroupAndOwnsItsNetworkInterface() {
        String fsId = given()
                .contentType("application/json")
                .body("""
                    {"CreationToken":"efs-default-group"}
                    """)
                .post("/2015-02-01/file-systems")
                .then().statusCode(201).extract().path("FileSystemId");
        String targetId = null;
        try {
            String defaultGroupId = ec2.describeSecurityGroups("us-east-1", List.of(), List.of(),
                    Map.of("vpc-id", List.of(vpcId), "group-name", List.of("default")))
                    .getFirst().getGroupId();
            JsonPath created = given()
                    .contentType("application/json")
                    .body("""
                        {"FileSystemId":"%s","SubnetId":"%s"}
                        """.formatted(fsId, subnet.getSubnetId()))
                    .post("/2015-02-01/mount-targets")
                    .then().statusCode(200)
                    .body("VpcId", equalTo(vpcId))
                    .body("AvailabilityZoneName", equalTo(subnet.getAvailabilityZone()))
                    .body("AvailabilityZoneId", equalTo(subnet.getAvailabilityZoneId()))
                    .extract().jsonPath();
            targetId = created.getString("MountTargetId");
            String eniId = created.getString("NetworkInterfaceId");
            String ipAddress = created.getString("IpAddress");
            NetworkInterface eni = ec2.describeNetworkInterfaces("us-east-1", List.of(eniId), Map.of(), 0, null)
                    .networkInterfaces().getFirst();
            assertEquals(subnet.getSubnetId(), eni.getSubnetId());
            assertEquals(ipAddress, eni.getPrivateIpAddress());
            assertEquals(defaultGroupId, eni.getGroups().getFirst().getGroupId());
            given()
                    .get("/2015-02-01/mount-targets/" + targetId + "/security-groups")
                    .then().statusCode(200).body("SecurityGroups", contains(defaultGroupId));

            given()
                    .delete("/2015-02-01/mount-targets/" + targetId)
                    .then().statusCode(204);
            given()
                    .queryParam("MountTargetId", targetId)
                    .get("/2015-02-01/mount-targets")
                    .then().statusCode(404)
                    .body("__type", equalTo("MountTargetNotFound"))
                    .body("ErrorCode", equalTo("MountTargetNotFound"));
            targetId = null;
            AwsException gone = assertThrows(AwsException.class, () -> ec2.describeNetworkInterfaces(
                    "us-east-1", List.of(eniId), Map.of(), 0, null));
            assertEquals("InvalidNetworkInterfaceID.NotFound", gone.getErrorCode());
        } finally {
            if (targetId != null) {
                given().delete("/2015-02-01/mount-targets/" + targetId).then().statusCode(204);
            }
            given().delete("/2015-02-01/file-systems/" + fsId).then().statusCode(204);
        }
    }

    @Test
    @Order(12)
    void regionalBackupDefaultsToDisabledAndPutPersistsBothStates() {
        given()
        .when()
            .get("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
        .then()
            .statusCode(200)
            .body("BackupPolicy.Status", equalTo("DISABLED"));

        for (String status : new String[]{"ENABLED", "DISABLED"}) {
            given()
                .contentType("application/json")
                .body("""
                    {"BackupPolicy":{"Status":"%s"}}
                    """.formatted(status))
            .when()
                .put("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
            .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo(status));

            given()
            .when()
                .get("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
            .then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo(status));
        }

        given()
            .contentType("application/json")
            .body("""
                {"BackupPolicy":{"Status":"ENABLING"}}
                """)
        .when()
            .put("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequest"));

        given()
        .when()
            .get("/2015-02-01/file-systems/" + fileSystemId + "/backup-policy")
        .then()
            .statusCode(200)
            .body("BackupPolicy.Status", equalTo("DISABLED"));
    }

    @Test
    @Order(12)
    void describeReplicationReturnsTypedNotFoundForAnUnreplicatedFileSystem() {
        given()
            .queryParam("FileSystemId", fileSystemId)
        .when()
            .get("/2015-02-01/file-systems/replication-configurations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ReplicationNotFound"));

        given()
            .queryParam("FileSystemId", "fs-does-not-exist")
        .when()
            .get("/2015-02-01/file-systems/replication-configurations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("FileSystemNotFound"));

        given()
        .when()
            .get("/2015-02-01/file-systems/replication-configurations")
        .then()
            .statusCode(200)
            .body("Replications", empty());
    }

    @Test
    @Order(13)
    void deleteResources() {
        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/access-points/" + accessPointId)
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/mount-targets/" + mountTargetId)
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
        .when()
            .delete("/2015-02-01/file-systems/" + fileSystemId)
        .then()
            .statusCode(204);
            
        given()
            .contentType("application/json")
        .when()
            .get("/2015-02-01/file-systems")
        .then()
            .statusCode(200)
            .body("FileSystems.FileSystemId", not(hasItem(fileSystemId)));
    }
}
