package io.floci.aws.services.rbin;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Recycle Bin: retention rules with their pending status, tags and locks, and what they do in EC2: a deleted
 * snapshot or volume a rule covers waits in the Recycle Bin until it's restored, with its tags, or its retention ends.
 */
@QuarkusTest
class RbinIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification rbin() {
        return given().header("Authorization", auth("rbin")).contentType("application/json");
    }

    static RequestSpecification ec2(String action) {
        return given().header("Authorization", auth("ec2")).formParam("Action", action)
                .formParam("Version", "2016-11-15");
    }

    static String rule(String body) {
        String id = rbin().body(body).post("/rules").then().statusCode(201).body("Status", equalTo("pending"))
                .extract().path("Identifier");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> "available".equals(
                rbin().get("/rules/" + id).then().extract().path("Status")));
        return id;
    }

    static String volume(String tagKey) {
        RequestSpecification create = ec2("CreateVolume").formParam("AvailabilityZone", "us-east-1a")
                .formParam("Size", "1");
        if (tagKey != null) {
            create.formParam("TagSpecification.1.ResourceType", "volume")
                    .formParam("TagSpecification.1.Tag.1.Key", tagKey)
                    .formParam("TagSpecification.1.Tag.1.Value", "yes");
        }
        return create.post("/").then().statusCode(200).extract().xmlPath().getString("CreateVolumeResponse.volumeId");
    }

    @Test
    void rulesKeepDeletedSnapshotsAndVolumes() {
        rbin().body("{\"ResourceType\":\"EBS_SNAPSHOT\",\"RetentionPeriod\":{\"RetentionPeriodValue\":400,"
                + "\"RetentionPeriodUnit\":\"DAYS\"}}").post("/rules").then().statusCode(400);
        String snapshots = rule("{\"ResourceType\":\"EBS_SNAPSHOT\",\"RetentionPeriod\":{\"RetentionPeriodValue\":7,"
                + "\"RetentionPeriodUnit\":\"DAYS\"},\"Description\":\"keep\",\"ResourceTags\":[{\"ResourceTagKey\":"
                + "\"keep\",\"ResourceTagValue\":\"yes\"}],\"Tags\":[{\"Key\":\"team\",\"Value\":\"ops\"}]}");
        String arn = rbin().get("/rules/" + snapshots).then().statusCode(200).extract().path("RuleArn");
        rbin().get("/tags/" + arn).then().body("Tags.Key", hasItem("team"));
        rbin().body("{\"ResourceType\":\"EBS_SNAPSHOT\"}").post("/list-rules").then().statusCode(200)
                .body("Rules.Identifier", hasItem(snapshots));

        // A tagged snapshot goes to the Recycle Bin and comes back with its tags; an untagged one is gone.
        String source = volume(null);
        String kept = ec2("CreateSnapshot").formParam("VolumeId", source)
                .formParam("TagSpecification.1.ResourceType", "snapshot")
                .formParam("TagSpecification.1.Tag.1.Key", "keep").formParam("TagSpecification.1.Tag.1.Value", "yes")
                .post("/").then().statusCode(200).extract().xmlPath().getString("CreateSnapshotResponse.snapshotId");
        String dropped = ec2("CreateSnapshot").formParam("VolumeId", source).post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateSnapshotResponse.snapshotId");
        ec2("DeleteSnapshot").formParam("SnapshotId", kept).post("/").then().statusCode(200);
        ec2("DeleteSnapshot").formParam("SnapshotId", dropped).post("/").then().statusCode(200);
        ec2("DescribeSnapshots").formParam("SnapshotId.1", kept).post("/").then().statusCode(400);
        ec2("ListSnapshotsInRecycleBin").post("/").then().statusCode(200)
                .body(containsString(kept)).body(not(containsString(dropped)));
        ec2("RestoreSnapshotFromRecycleBin").formParam("SnapshotId", dropped).post("/").then().statusCode(400);
        ec2("RestoreSnapshotFromRecycleBin").formParam("SnapshotId", kept).post("/").then().statusCode(200)
                .body(containsString(kept));
        ec2("DescribeSnapshots").formParam("SnapshotId.1", kept).post("/").then().statusCode(200)
                .body(containsString("<key>keep</key>"));
        ec2("ListSnapshotsInRecycleBin").post("/").then().body(not(containsString(kept)));

        // A Region-level volume rule keeps every volume but the excluded ones.
        String volumes = rule("{\"ResourceType\":\"EBS_VOLUME\",\"RetentionPeriod\":{\"RetentionPeriodValue\":3,"
                + "\"RetentionPeriodUnit\":\"DAYS\"},\"ExcludeResourceTags\":[{\"ResourceTagKey\":\"scratch\"}]}");
        String keptVolume = volume(null);
        String scratch = volume("scratch");
        ec2("DeleteVolume").formParam("VolumeId", keptVolume).post("/").then().statusCode(200);
        ec2("DeleteVolume").formParam("VolumeId", scratch).post("/").then().statusCode(200);
        ec2("ListVolumesInRecycleBin").post("/").then().statusCode(200).body(containsString(keptVolume))
                .body(not(containsString(scratch)));
        ec2("RestoreVolumeFromRecycleBin").formParam("VolumeId", keptVolume).post("/").then().statusCode(200);
        ec2("DescribeVolumes").formParam("VolumeId.1", keptVolume).post("/").then().statusCode(200)
                .body(containsString("available"));

        // Locks: only Region-level rules; a locked rule can't be changed until its unlock delay passes.
        rbin().body("{\"LockConfiguration\":{\"UnlockDelay\":{\"UnlockDelayValue\":7,\"UnlockDelayUnit\":\"DAYS\"}}}")
                .patch("/rules/" + snapshots + "/lock").then().statusCode(400);
        rbin().body("{\"LockConfiguration\":{\"UnlockDelay\":{\"UnlockDelayValue\":7,\"UnlockDelayUnit\":\"DAYS\"}}}")
                .patch("/rules/" + volumes + "/lock").then().statusCode(200).body("LockState", equalTo("locked"));
        rbin().delete("/rules/" + volumes).then().statusCode(409);
        rbin().body("{\"Description\":\"x\"}").patch("/rules/" + volumes).then().statusCode(409);
        rbin().patch("/rules/" + volumes + "/unlock").then().statusCode(200)
                .body("LockState", equalTo("pending_unlock"));
        rbin().delete("/rules/" + volumes).then().statusCode(409);
        rbin().body("{\"ResourceType\":\"EBS_VOLUME\",\"LockState\":\"pending_unlock\"}").post("/list-rules").then()
                .body("Rules.Identifier", equalTo(List.of(volumes)));

        // Updates go pending again; deletion ends a rule.
        rbin().body("{\"RetentionPeriod\":{\"RetentionPeriodValue\":14,\"RetentionPeriodUnit\":\"DAYS\"}}")
                .patch("/rules/" + snapshots).then().statusCode(200).body("Status", equalTo("pending"));
        rbin().delete("/rules/" + snapshots).then().statusCode(204);
        rbin().get("/rules/" + snapshots).then().statusCode(404).body("__type",
                containsString("ResourceNotFoundException"));
    }
}
