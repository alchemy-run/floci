package io.github.hectorvent.floci.services.fsx;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for Amazon FSx JSON 1.1 — the operations Alchemy
 * {@code test/AWS/FSx/Bindings.test.ts} (and the FileSystem not-found probe) exercise.
 */
@QuarkusTest
class FsxBindingsIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/fsx/aws4_request";
    private static final String TARGET_PREFIX = "AWSSimbaAPIService_v20180301.";
    private static final String MISSING_BACKUP = "backup-00000000000000000";
    private static final String MISSING_SNAPSHOT = "fsvolsnap-00000000000000000";
    private static final String MISSING_VOLUME = "fsvol-00000000000000000";
    private static final String MISSING_TASK = "task-00000000000000000";
    private static final String MISSING_SNAPSHOT_ARN =
            "arn:aws:fsx:us-west-2:000000000000:snapshot/fsvolsnap-00000000000000000";

    private static io.restassured.response.Response fsx(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    void describeBackups_emptyAccount_returnsEmptyList() {
        fsx("DescribeBackups", "{}")
                .then()
                .statusCode(200)
                .body("Backups", notNullValue());
    }

    @Test
    void describeSnapshots_emptyAccount_returnsEmptyList() {
        fsx("DescribeSnapshots", "{}")
                .then()
                .statusCode(200)
                .body("Snapshots", notNullValue());
    }

    @Test
    void describeVolumes_emptyAccount_returnsEmptyList() {
        fsx("DescribeVolumes", "{}")
                .then()
                .statusCode(200)
                .body("Volumes", notNullValue());
    }

    @Test
    void describeStorageVirtualMachines_emptyAccount_returnsEmptyList() {
        fsx("DescribeStorageVirtualMachines", "{}")
                .then()
                .statusCode(200)
                .body("StorageVirtualMachines", notNullValue());
    }

    @Test
    void describeDataRepositoryTasks_emptyAccount_returnsEmptyList() {
        fsx("DescribeDataRepositoryTasks", "{}")
                .then()
                .statusCode(200)
                .body("DataRepositoryTasks", notNullValue());
    }

    @Test
    void describeDataRepositoryAssociations_emptyAccount_returnsEmptyList() {
        fsx("DescribeDataRepositoryAssociations", "{}")
                .then()
                .statusCode(200)
                .body("Associations", notNullValue());
    }

    @Test
    void deleteBackup_missing_backupNotFound() {
        fsx("DeleteBackup", "{\"BackupId\":\"" + MISSING_BACKUP + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BackupNotFound"));
    }

    @Test
    void copyBackup_missing_backupNotFound() {
        fsx("CopyBackup", "{\"SourceBackupId\":\"" + MISSING_BACKUP + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BackupNotFound"));
    }

    @Test
    void updateSnapshot_missing_badRequestMessage() {
        fsx("UpdateSnapshot",
                "{\"SnapshotId\":\"" + MISSING_SNAPSHOT + "\",\"Name\":\"alchemy-fsx-bindings-probe\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequest"))
                .body("message", containsString("the snapshot is not found"));
    }

    @Test
    void deleteSnapshot_missing_snapshotNotFound() {
        fsx("DeleteSnapshot", "{\"SnapshotId\":\"" + MISSING_SNAPSHOT + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("SnapshotNotFound"));
    }

    @Test
    void createSnapshot_missingVolume_badRequestMessage() {
        fsx("CreateSnapshot",
                "{\"Name\":\"alchemy-fsx-bindings-probe\",\"VolumeId\":\"" + MISSING_VOLUME + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequest"))
                .body("message", containsString("volume was not found"));
    }

    @Test
    void restoreVolumeFromSnapshot_missing_badRequestMessage() {
        fsx("RestoreVolumeFromSnapshot",
                "{\"VolumeId\":\"" + MISSING_VOLUME + "\",\"SnapshotId\":\"" + MISSING_SNAPSHOT + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequest"))
                .body("message", containsString("snapshot cannot be found"));
    }

    @Test
    void copySnapshotAndUpdateVolume_missing_badRequestMessage() {
        fsx("CopySnapshotAndUpdateVolume",
                "{\"VolumeId\":\"" + MISSING_VOLUME + "\",\"SourceSnapshotARN\":\"" + MISSING_SNAPSHOT_ARN + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequest"))
                .body("message", containsString("SourceSnapshotARN provided is not a valid ARN"));
    }

    @Test
    void cancelDataRepositoryTask_missing_taskNotFound() {
        fsx("CancelDataRepositoryTask", "{\"TaskId\":\"" + MISSING_TASK + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("DataRepositoryTaskNotFound"));
    }

    @Test
    void describeFileSystems_missing_fileSystemNotFound() {
        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"fs-00000000000000000\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    @Test
    void createDescribeTagDeleteFileSystem_roundTrip() {
        String fileSystemId = fsx("CreateFileSystem", """
                {"FileSystemType":"LUSTRE","StorageCapacity":1200,
                 "SubnetIds":["subnet-00000000"],
                 "LustreConfiguration":{"DeploymentType":"SCRATCH_2"},
                 "Tags":[{"Key":"purpose","Value":"alchemy-fsx-test"}]}
                """)
                .then()
                .statusCode(200)
                .body("FileSystem.FileSystemId", startsWith("fs-"))
                .body("FileSystem.FileSystemType", equalTo("LUSTRE"))
                .body("FileSystem.Lifecycle", equalTo("AVAILABLE"))
                .body("FileSystem.StorageCapacity", equalTo(1200))
                .body("FileSystem.ResourceARN", containsString(":file-system/fs-"))
                .extract()
                .path("FileSystem.FileSystemId");

        String arn = fsx("DescribeFileSystems",
                "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(200)
                .body("FileSystems[0].FileSystemId", equalTo(fileSystemId))
                .body("FileSystems[0].Tags.find { it.Key == 'purpose' }.Value",
                        equalTo("alchemy-fsx-test"))
                .extract()
                .path("FileSystems[0].ResourceARN");

        fsx("TagResource",
                "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"prod\"}]}")
                .then()
                .statusCode(200);

        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(200)
                .body("FileSystems[0].Tags.find { it.Key == 'stage' }.Value", equalTo("prod"));

        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + fileSystemId + "\"}")
                .then()
                .statusCode(200);

        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("FileSystemNotFound"));
    }
}
