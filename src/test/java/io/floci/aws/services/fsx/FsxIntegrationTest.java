package io.floci.aws.services.fsx;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Amazon FSx over JSON 1.1: Lustre file systems with S3 data repositories and tasks, OpenZFS volumes and snapshots,
 * ONTAP storage virtual machines and volumes, Windows aliases, backups, file caches, tags, and the errors FSx answers
 * missing resources with.
 */
@QuarkusTest
class FsxIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse fsx(String action, String body) {
        return given().header("Authorization", auth("fsx"))
                .header("X-Amz-Target", "AWSSimbaAPIService_v20180301." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then();
    }

    private static String ec2(String action, String path, String... params) {
        RequestSpecification request = given().header("Authorization", auth("ec2")).formParam("Action", action)
                .formParam("Version", "2016-11-15");
        for (int index = 0; index < params.length; index += 2) {
            request = request.formParam(params[index], params[index + 1]);
        }
        return request.post("/").then().statusCode(200).extract().xmlPath().getString(path);
    }

    private static String[] network(String octet) {
        String vpc = ec2("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", "10." + octet + ".0.0/16");
        String a = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10." + octet + ".1.0/24", "AvailabilityZone", "us-east-1a");
        String b = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10." + octet + ".2.0/24", "AvailabilityZone", "us-east-1b");
        return new String[] {vpc, a, b};
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void lustreFileSystemsRepositoriesTasksAndBackups() {
        String[] net = network("81");
        fsx("CreateFileSystem", "{\"FileSystemType\":\"LUSTRE\",\"StorageCapacity\":1000,\"SubnetIds\":[\"" + net[1]
                + "\"],\"LustreConfiguration\":{\"DeploymentType\":\"SCRATCH_2\"}}").statusCode(400)
                .body("__type", containsString("BadRequest"));
        fsx("CreateFileSystem", "{\"FileSystemType\":\"LUSTRE\",\"StorageCapacity\":1200,\"SubnetIds\":[\"" + net[1]
                + "\",\"" + net[2] + "\"],\"LustreConfiguration\":{\"DeploymentType\":\"SCRATCH_2\"}}")
                .statusCode(400).body("__type", containsString("InvalidNetworkSettings"));
        String scratch = fsx("CreateFileSystem", "{\"FileSystemType\":\"LUSTRE\",\"StorageCapacity\":1200,"
                + "\"SubnetIds\":[\"" + net[1] + "\"],\"LustreConfiguration\":{\"DeploymentType\":\"SCRATCH_2\"},"
                + "\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"test\"}]}").statusCode(200)
                .body("FileSystem.ResourceARN", containsString(":file-system/fs-"))
                .body("FileSystem.LustreConfiguration.MountName", not(equalTo("fsx")))
                .extract().path("FileSystem.FileSystemId");
        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"" + scratch + "\"]}").statusCode(200)
                .body("FileSystems[0].Lifecycle", equalTo("AVAILABLE")).body("FileSystems[0].StorageCapacity",
                        equalTo(1200)).body("FileSystems[0].NetworkInterfaceIds", hasSize(1))
                .body("FileSystems[0].DNSName", startsWith(scratch + ".fsx.us-east-1."));
        fsx("CreateBackup", "{\"FileSystemId\":\"" + scratch + "\"}").statusCode(400)
                .body("message", containsString("scratch"));
        String arn = "arn:aws:fsx:us-east-1:" + ACCOUNT + ":file-system/" + scratch;
        fsx("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"prod\"}]}")
                .statusCode(200);
        fsx("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags.Key", hasItem("stage")).body("Tags", hasSize(2));
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + scratch + "\"}").statusCode(200)
                .body("Lifecycle", equalTo("DELETING"));
        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"" + scratch + "\"]}").statusCode(400)
                .body("__type", containsString("FileSystemNotFound"));

        String bucket = "fsx-repo-" + suffix();
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        for (String key : new String[] {"data/a.csv", "data/b.csv", "other/c.csv"}) {
            given().header("Authorization", auth("s3")).body("x").put("/" + bucket + "/" + key).then().statusCode(200);
        }
        String persistent = fsx("CreateFileSystem", "{\"FileSystemType\":\"LUSTRE\",\"StorageCapacity\":1200,"
                + "\"SubnetIds\":[\"" + net[1] + "\"],\"LustreConfiguration\":{\"DeploymentType\":\"PERSISTENT_2\","
                + "\"PerUnitStorageThroughput\":125,\"CopyTagsToBackups\":true},\"Tags\":[{\"Key\":\"team\","
                + "\"Value\":\"hpc\"}]}").statusCode(200).extract().path("FileSystem.FileSystemId");
        fsx("CreateDataRepositoryTask", "{\"Type\":\"IMPORT_METADATA_FROM_REPOSITORY\",\"FileSystemId\":\""
                + persistent + "\",\"Report\":{\"Enabled\":false}}").statusCode(400)
                .body("message", containsString("not linked"));
        String association = fsx("CreateDataRepositoryAssociation", "{\"FileSystemId\":\"" + persistent
                + "\",\"FileSystemPath\":\"/ns1\",\"DataRepositoryPath\":\"s3://" + bucket + "/data\"}")
                .statusCode(200).body("Association.Lifecycle", equalTo("AVAILABLE")).extract()
                .path("Association.AssociationId");
        fsx("CreateDataRepositoryAssociation", "{\"FileSystemId\":\"" + persistent + "\",\"FileSystemPath\":"
                + "\"/ns1/sub\",\"DataRepositoryPath\":\"s3://" + bucket + "/other\"}").statusCode(400)
                .body("message", containsString("overlaps"));
        String task = fsx("CreateDataRepositoryTask", "{\"Type\":\"IMPORT_METADATA_FROM_REPOSITORY\",\"FileSystemId\":\""
                + persistent + "\",\"Report\":{\"Enabled\":true,\"Path\":\"s3://" + bucket + "/reports\","
                + "\"Format\":\"REPORT_CSV_20191124\",\"Scope\":\"FAILED_FILES_ONLY\"}}").statusCode(200)
                .extract().path("DataRepositoryTask.TaskId");
        fsx("DescribeDataRepositoryTasks", "{\"TaskIds\":[\"" + task + "\"]}").statusCode(200)
                .body("DataRepositoryTasks[0].Lifecycle", equalTo("SUCCEEDED"))
                .body("DataRepositoryTasks[0].Status.TotalCount", equalTo(2));
        given().header("Authorization", auth("s3")).get("/" + bucket + "/reports/" + task + "/failures/report.csv")
                .then().statusCode(200);
        fsx("CancelDataRepositoryTask", "{\"TaskId\":\"" + task + "\"}").statusCode(400)
                .body("__type", containsString("DataRepositoryTaskEnded"));

        String backup = fsx("CreateBackup", "{\"FileSystemId\":\"" + persistent + "\"}").statusCode(200)
                .body("Backup.Type", equalTo("USER_INITIATED")).body("Backup.Tags[0].Key", equalTo("team"))
                .extract().path("Backup.BackupId");
        String copy = fsx("CopyBackup", "{\"SourceBackupId\":\"" + backup + "\"}").statusCode(200)
                .extract().path("Backup.BackupId");
        fsx("DescribeBackups", "{\"Filters\":[{\"Name\":\"file-system-id\",\"Values\":[\"" + persistent + "\"]}]}")
                .statusCode(200).body("Backups", hasSize(2));
        String restored = fsx("CreateFileSystemFromBackup", "{\"BackupId\":\"" + copy + "\",\"SubnetIds\":[\""
                + net[1] + "\"]}").statusCode(200).body("FileSystem.FileSystemType", equalTo("LUSTRE"))
                .extract().path("FileSystem.FileSystemId");
        fsx("UpdateFileSystem", "{\"FileSystemId\":\"" + persistent + "\",\"StorageCapacity\":2400}").statusCode(200)
                .body("FileSystem.Lifecycle", equalTo("AVAILABLE"));
        fsx("DeleteDataRepositoryAssociation", "{\"AssociationId\":\"" + association + "\",\"DeleteDataInFileSystem\""
                + ":false}").statusCode(200);
        for (String id : new String[] {backup, copy}) {
            fsx("DeleteBackup", "{\"BackupId\":\"" + id + "\"}").statusCode(200);
        }
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + persistent + "\"}").statusCode(200);
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + restored + "\"}").statusCode(200);
        assertEquals("", ec2("DescribeNetworkInterfaces",
                "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", "Filter.1.Name",
                "vpc-id", "Filter.1.Value.1", net[0]));
    }

    @Test
    void openZfsOntapWindowsAndCaches() {
        String[] net = network("82");
        String zfs = fsx("CreateFileSystem", "{\"FileSystemType\":\"OPENZFS\",\"StorageCapacity\":64,\"SubnetIds\":[\""
                + net[1] + "\"],\"OpenZFSConfiguration\":{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":"
                + "64}}").statusCode(200).extract().path("FileSystem.FileSystemId");
        String root = fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"" + zfs + "\"]}").statusCode(200)
                .extract().path("FileSystems[0].OpenZFSConfiguration.RootVolumeId");
        String child = fsx("CreateVolume", "{\"VolumeType\":\"OPENZFS\",\"Name\":\"data\",\"OpenZFSConfiguration\":"
                + "{\"ParentVolumeId\":\"" + root + "\"}}").statusCode(200)
                .body("Volume.OpenZFSConfiguration.VolumePath", equalTo("/fsx/data")).extract().path("Volume.VolumeId");
        String first = fsx("CreateSnapshot", "{\"Name\":\"first\",\"VolumeId\":\"" + child + "\"}").statusCode(200)
                .extract().path("Snapshot.SnapshotId");
        fsx("CreateSnapshot", "{\"Name\":\"second\",\"VolumeId\":\"" + child + "\"}").statusCode(200);
        fsx("RestoreVolumeFromSnapshot", "{\"VolumeId\":\"" + child + "\",\"SnapshotId\":\"" + first + "\"}")
                .statusCode(400).body("message", containsString("DELETE_INTERMEDIATE_SNAPSHOTS"));
        fsx("RestoreVolumeFromSnapshot", "{\"VolumeId\":\"" + child + "\",\"SnapshotId\":\"" + first + "\","
                + "\"Options\":[\"DELETE_INTERMEDIATE_SNAPSHOTS\"]}").statusCode(200);
        fsx("DescribeSnapshots", "{\"Filters\":[{\"Name\":\"volume-id\",\"Values\":[\"" + child + "\"]}]}")
                .statusCode(200).body("Snapshots", hasSize(1));
        fsx("CreateAndAttachS3AccessPoint", "{\"Name\":\"zfs-ap\",\"Type\":\"OPENZFS\",\"OpenZFSConfiguration\":"
                + "{\"VolumeId\":\"" + child + "\",\"FileSystemIdentity\":{\"Type\":\"POSIX\",\"PosixUser\":"
                + "{\"Uid\":0,\"Gid\":0}}}}").statusCode(200)
                .body("S3AccessPointAttachment.S3AccessPoint.Alias", containsString("-ext-s3alias"));
        fsx("DetachAndDeleteS3AccessPoint", "{\"Name\":\"zfs-ap\"}").statusCode(200);
        fsx("DeleteVolume", "{\"VolumeId\":\"" + child + "\"}").statusCode(400);
        fsx("DeleteVolume", "{\"VolumeId\":\"" + child + "\",\"OpenZFSConfiguration\":{\"Options\":"
                + "[\"DELETE_CHILD_VOLUMES_AND_SNAPSHOTS\"]}}").statusCode(200);
        fsx("DeleteVolume", "{\"VolumeId\":\"" + root + "\"}").statusCode(400).body("message", containsString("root"));
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + zfs + "\",\"OpenZFSConfiguration\":{\"SkipFinalBackup\":"
                + "true}}").statusCode(200);

        String ontap = fsx("CreateFileSystem", "{\"FileSystemType\":\"ONTAP\",\"StorageCapacity\":1024,\"SubnetIds\":[\""
                + net[1] + "\"],\"OntapConfiguration\":{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":"
                + "128}}").statusCode(200).body("FileSystem.OntapConfiguration.Endpoints.Management.IpAddresses",
                        hasSize(1)).extract().path("FileSystem.FileSystemId");
        String svm = fsx("CreateStorageVirtualMachine", "{\"FileSystemId\":\"" + ontap + "\",\"Name\":\"svm1\"}")
                .statusCode(200).body("StorageVirtualMachine.Endpoints.Nfs.DNSName", startsWith("nfs.svm-"))
                .extract().path("StorageVirtualMachine.StorageVirtualMachineId");
        String volume = fsx("CreateVolume", "{\"VolumeType\":\"ONTAP\",\"Name\":\"vol1\",\"OntapConfiguration\":"
                + "{\"StorageVirtualMachineId\":\"" + svm + "\",\"JunctionPath\":\"/vol1\",\"SizeInMegabytes\":1024}}")
                .statusCode(200).body("Volume.Lifecycle", equalTo("CREATED")).extract().path("Volume.VolumeId");
        fsx("DescribeVolumes", "{\"Filters\":[{\"Name\":\"storage-virtual-machine-id\",\"Values\":[\"" + svm + "\"]}]}")
                .statusCode(200).body("Volumes", hasSize(2));
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + ontap + "\"}").statusCode(400);
        fsx("DeleteStorageVirtualMachine", "{\"StorageVirtualMachineId\":\"" + svm + "\"}").statusCode(400);
        fsx("DeleteVolume", "{\"VolumeId\":\"" + volume + "\"}").statusCode(200)
                .body("OntapResponse.FinalBackupId", startsWith("backup-"));
        fsx("DeleteStorageVirtualMachine", "{\"StorageVirtualMachineId\":\"" + svm + "\"}").statusCode(200);
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + ontap + "\"}").statusCode(200);

        fsx("CreateFileSystem", "{\"FileSystemType\":\"WINDOWS\",\"StorageCapacity\":32,\"SubnetIds\":[\"" + net[1]
                + "\"],\"WindowsConfiguration\":{\"ThroughputCapacity\":8}}").statusCode(400)
                .body("__type", containsString("MissingFileSystemConfiguration"));
        String windows = fsx("CreateFileSystem", "{\"FileSystemType\":\"WINDOWS\",\"StorageCapacity\":32,"
                + "\"SubnetIds\":[\"" + net[1] + "\"],\"WindowsConfiguration\":{\"ThroughputCapacity\":8,"
                + "\"SelfManagedActiveDirectoryConfiguration\":{\"DomainName\":\"corp.example.com\",\"UserName\":"
                + "\"admin\",\"Password\":\"Secret123!\",\"DnsIps\":[\"10.82.1.10\"]}}}").statusCode(200)
                .body("FileSystem.DNSName", containsString(".corp.example.com")).extract()
                .path("FileSystem.FileSystemId");
        fsx("AssociateFileSystemAliases", "{\"FileSystemId\":\"" + windows + "\",\"Aliases\":[\"Files.Corp.Example.com\"]}")
                .statusCode(200);
        fsx("DescribeFileSystemAliases", "{\"FileSystemId\":\"" + windows + "\"}").statusCode(200)
                .body("Aliases[0].Name", equalTo("files.corp.example.com"));
        fsx("DeleteFileSystem", "{\"FileSystemId\":\"" + windows + "\"}").statusCode(200)
                .body("WindowsResponse.FinalBackupId", startsWith("backup-"));

        String cache = fsx("CreateFileCache", "{\"FileCacheType\":\"LUSTRE\",\"FileCacheTypeVersion\":\"2.12\","
                + "\"StorageCapacity\":1200,\"SubnetIds\":[\"" + net[1] + "\"],\"LustreConfiguration\":"
                + "{\"PerUnitStorageThroughput\":1000,\"DeploymentType\":\"CACHE_1\",\"MetadataConfiguration\":"
                + "{\"StorageCapacity\":2400}},\"DataRepositoryAssociations\":[{\"FileCachePath\":\"/nfs\","
                + "\"DataRepositoryPath\":\"nfs://10.82.1.20/export\",\"NFS\":{\"Version\":\"NFS3\"}}]}")
                .statusCode(200).body("FileCache.DataRepositoryAssociationIds", hasSize(1))
                .extract().path("FileCache.FileCacheId");
        fsx("DeleteFileCache", "{\"FileCacheId\":\"" + cache + "\"}").statusCode(200);
        fsx("UpdateSharedVpcConfiguration", "{\"EnableFsxRouteTableUpdatesFromParticipantAccounts\":\"true\"}")
                .statusCode(200);
        fsx("DescribeSharedVpcConfiguration", "{}").statusCode(200)
                .body("EnableFsxRouteTableUpdatesFromParticipantAccounts", equalTo("true"));
        fsx("UpdateSharedVpcConfiguration", "{\"EnableFsxRouteTableUpdatesFromParticipantAccounts\":\"false\"}")
                .statusCode(200);
    }

    @Test
    void missingResourcesAnswerWithFsxErrors() {
        fsx("DescribeFileSystems", "{\"FileSystemIds\":[\"fs-00000000000000000\"]}").statusCode(400)
                .body("__type", containsString("FileSystemNotFound"));
        fsx("DeleteBackup", "{\"BackupId\":\"backup-00000000000000000\"}").statusCode(400)
                .body("__type", containsString("BackupNotFound"));
        fsx("CopyBackup", "{\"SourceBackupId\":\"backup-00000000000000000\"}").statusCode(400)
                .body("__type", containsString("BackupNotFound"));
        fsx("UpdateSnapshot", "{\"SnapshotId\":\"fsvolsnap-00000000000000000\",\"Name\":\"x\"}").statusCode(400)
                .body("message", containsString("the snapshot is not found"));
        fsx("DeleteSnapshot", "{\"SnapshotId\":\"fsvolsnap-00000000000000000\"}").statusCode(400)
                .body("__type", containsString("SnapshotNotFound"));
        fsx("CreateSnapshot", "{\"Name\":\"x\",\"VolumeId\":\"fsvol-00000000000000000\"}").statusCode(400)
                .body("message", containsString("volume was not found"));
        fsx("RestoreVolumeFromSnapshot", "{\"VolumeId\":\"fsvol-00000000000000000\",\"SnapshotId\":"
                + "\"fsvolsnap-00000000000000000\"}").statusCode(400)
                .body("message", containsString("snapshot cannot be found"));
        fsx("CopySnapshotAndUpdateVolume", "{\"VolumeId\":\"fsvol-00000000000000000\",\"SourceSnapshotARN\":"
                + "\"arn:aws:fsx:us-west-2:" + ACCOUNT + ":snapshot/fsvolsnap-00000000000000000\"}").statusCode(400)
                .body("message", containsString("SourceSnapshotARN provided is not a valid ARN"));
        fsx("CancelDataRepositoryTask", "{\"TaskId\":\"task-00000000000000000\"}").statusCode(400)
                .body("__type", containsString("DataRepositoryTaskNotFound"));
        fsx("ListTagsForResource", "{\"ResourceARN\":\"arn:aws:fsx:us-east-1:" + ACCOUNT
                + ":file-system/fs-00000000000000000\"}").statusCode(400).body("__type", containsString("ResourceNotFound"));
    }
}
