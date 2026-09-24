package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.datasync.DataSyncClient;
import software.amazon.awssdk.services.datasync.model.AgentStatus;
import software.amazon.awssdk.services.datasync.model.CreateTaskResponse;
import software.amazon.awssdk.services.datasync.model.DescribeAgentResponse;
import software.amazon.awssdk.services.datasync.model.DescribeLocationNfsResponse;
import software.amazon.awssdk.services.datasync.model.DescribeLocationS3Response;
import software.amazon.awssdk.services.datasync.model.DescribeTaskResponse;
import software.amazon.awssdk.services.datasync.model.DescribeTaskExecutionResponse;
import software.amazon.awssdk.services.datasync.model.InvalidRequestException;
import software.amazon.awssdk.services.datasync.model.S3StorageClass;
import software.amazon.awssdk.services.datasync.model.TagListEntry;
import software.amazon.awssdk.services.datasync.model.TaskMode;
import software.amazon.awssdk.services.datasync.model.TaskStatus;
import software.amazon.awssdk.services.datasync.model.VerifyMode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataSync")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSyncTest {

    private static DataSyncClient datasync;
    private static String suffix;
    private static String agentArn;
    private static String s3LocationArn;
    private static String nfsLocationArn;
    private static String taskArn;
    private static String enhancedTaskArn;

    @BeforeAll
    static void setup() {
        datasync = TestFixtures.dataSyncClient();
        suffix = String.valueOf(System.currentTimeMillis());
    }

    @AfterAll
    static void cleanup() {
        if (datasync == null) {
            return;
        }
        deleteQuietly("task", enhancedTaskArn, arn -> datasync.deleteTask(r -> r.taskArn(arn)));
        deleteQuietly("task", taskArn, arn -> datasync.deleteTask(r -> r.taskArn(arn)));
        deleteQuietly("location", nfsLocationArn, arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
        deleteQuietly("location", s3LocationArn, arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
        deleteQuietly("agent", agentArn, arn -> datasync.deleteAgent(r -> r.agentArn(arn)));
        datasync.close();
    }

    private static void deleteQuietly(String kind, String arn, java.util.function.Consumer<String> delete) {
        if (arn == null) {
            return;
        }
        try {
            delete.accept(arn);
        } catch (RuntimeException alreadyGone) {
            System.out.println("DataSync cleanup left a " + kind + " behind: " + arn
                    + " (" + alreadyGone.getMessage() + ")");
        }
    }

    @Test
    @Order(1)
    void createAgentIsOnlineOnTheFirstDescribe() {
        agentArn = datasync.createAgent(r -> r
                        .activationKey("AAAAA-1AAAA-BB1CC-DDDDD-EEEEE")
                        .agentName("sdk-test-agent-" + suffix)
                        .tags(TagListEntry.builder().key("team").value("platform").build()))
                .agentArn();

        assertThat(agentArn).contains(":datasync:").contains(":agent/agent-");

        DescribeAgentResponse described = datasync.describeAgent(r -> r.agentArn(agentArn));

        assertThat(described.agentArn()).isEqualTo(agentArn);
        assertThat(described.name()).isEqualTo("sdk-test-agent-" + suffix);
        assertThat(described.status()).isEqualTo(AgentStatus.ONLINE);
        assertThat(described.endpointType()).isNotNull();
        assertThat(described.creationTime()).isNotNull();
    }

    @Test
    @Order(2)
    void createS3LocationAppliesTheDocumentedStorageClassDefault() {
        s3LocationArn = datasync.createLocationS3(r -> r
                        .s3BucketArn("arn:aws:s3:::sdk-test-datasync-" + suffix)
                        .subdirectory("/backups")
                        .s3Config(c -> c.bucketAccessRoleArn("arn:aws:iam::000000000000:role/datasync")))
                .locationArn();

        assertThat(s3LocationArn).contains(":location/loc-");

        DescribeLocationS3Response described = datasync.describeLocationS3(r -> r.locationArn(s3LocationArn));

        assertThat(described.locationArn()).isEqualTo(s3LocationArn);
        assertThat(described.locationUri()).startsWith("s3://sdk-test-datasync-" + suffix);
        assertThat(described.s3StorageClass()).isEqualTo(S3StorageClass.STANDARD);
        assertThat(described.s3Config().bucketAccessRoleArn())
                .isEqualTo("arn:aws:iam::000000000000:role/datasync");
        assertThat(described.creationTime()).isNotNull();
    }

    @Test
    @Order(3)
    void createNfsLocationReadsBackItsAgentAndMountOptions() {
        nfsLocationArn = datasync.createLocationNfs(r -> r
                        .serverHostname("nfs-" + suffix + ".example.com")
                        .subdirectory("/export/home")
                        .onPremConfig(c -> c.agentArns(agentArn)))
                .locationArn();

        DescribeLocationNfsResponse described = datasync.describeLocationNfs(r -> r.locationArn(nfsLocationArn));

        assertThat(described.locationUri()).startsWith("nfs://nfs-" + suffix + ".example.com");
        assertThat(described.onPremConfig().agentArns()).containsExactly(agentArn);
        assertThat(described.mountOptions().versionAsString()).isEqualTo("AUTOMATIC");
    }

    @Test
    @Order(4)
    void basicTaskIsAvailableAndVerifiesPointInTime() {
        CreateTaskResponse created = datasync.createTask(r -> r
                .sourceLocationArn(nfsLocationArn)
                .destinationLocationArn(s3LocationArn)
                .name("sdk-test-task-" + suffix)
                .tags(TagListEntry.builder().key("env").value("test").build()));

        taskArn = created.taskArn();
        assertThat(taskArn).contains(":task/task-");

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(taskArn));

        assertThat(described.status()).isEqualTo(TaskStatus.AVAILABLE);
        assertThat(described.taskMode()).isEqualTo(TaskMode.BASIC);
        assertThat(described.sourceLocationArn()).isEqualTo(nfsLocationArn);
        assertThat(described.destinationLocationArn()).isEqualTo(s3LocationArn);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.POINT_IN_TIME_CONSISTENT);
    }

    @Test
    @Order(5)
    void enhancedTaskVerifiesOnlyFilesTransferred() {
        enhancedTaskArn = datasync.createTask(r -> r
                        .sourceLocationArn(nfsLocationArn)
                        .destinationLocationArn(s3LocationArn)
                        .name("sdk-test-task-enhanced-" + suffix)
                        .taskMode(TaskMode.ENHANCED))
                .taskArn();

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(enhancedTaskArn));

        assertThat(described.taskMode()).isEqualTo(TaskMode.ENHANCED);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.ONLY_FILES_TRANSFERRED);
    }

    @Test
    @Order(6)
    void enhancedTaskRejectsPointInTimeConsistentVerification() {
        assertThatThrownBy(() -> datasync.createTask(r -> r
                .sourceLocationArn(nfsLocationArn)
                .destinationLocationArn(s3LocationArn)
                .name("sdk-test-task-rejected-" + suffix)
                .taskMode(TaskMode.ENHANCED)
                .options(o -> o.verifyMode(VerifyMode.POINT_IN_TIME_CONSISTENT))))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> datasync.updateTask(r -> r
                .taskArn(enhancedTaskArn)
                .options(o -> o.verifyMode(VerifyMode.POINT_IN_TIME_CONSISTENT))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Order(7)
    void updateTaskKeepsTheModeItWasCreatedWith() {
        datasync.updateTask(r -> r
                .taskArn(enhancedTaskArn)
                .name("sdk-test-task-enhanced-renamed-" + suffix));

        DescribeTaskResponse described = datasync.describeTask(r -> r.taskArn(enhancedTaskArn));

        assertThat(described.name()).isEqualTo("sdk-test-task-enhanced-renamed-" + suffix);
        assertThat(described.taskMode()).isEqualTo(TaskMode.ENHANCED);
        assertThat(described.options().verifyMode()).isEqualTo(VerifyMode.ONLY_FILES_TRANSFERRED);
    }

    @Test
    @Order(8)
    void listTasksIncludesBothTasks() {
        assertThat(datasync.listTasks(r -> r.maxResults(100)).tasks())
                .extracting(t -> t.taskArn())
                .contains(taskArn, enhancedTaskArn);
    }

    @Test
    @Order(9)
    void tagRoundTripOnTheTask() {
        datasync.tagResource(r -> r
                .resourceArn(taskArn)
                .tags(TagListEntry.builder().key("owner").value("platform").build()));

        assertThat(datasync.listTagsForResource(r -> r.resourceArn(taskArn)).tags())
                .contains(TagListEntry.builder().key("env").value("test").build())
                .contains(TagListEntry.builder().key("owner").value("platform").build());

        datasync.untagResource(r -> r.resourceArn(taskArn).keys("env"));

        assertThat(datasync.listTagsForResource(r -> r.resourceArn(taskArn)).tags())
                .extracting(TagListEntry::key)
                .contains("owner")
                .doesNotContain("env");
    }

    @Test
    @Order(11)
    @DisplayName("S3 execution supports cancellation, bandwidth updates, real transfer and cleanup")
    void s3ExecutionLifecycleTransfersRealBytes() throws InterruptedException {
        String sourceBucket = "sdk-datasync-execution-src-" + suffix;
        String destinationBucket = "sdk-datasync-execution-dst-" + suffix;
        String[] resources = new String[3];
        try (S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(r -> r.bucket(sourceBucket));
            s3.createBucket(r -> r.bucket(destinationBucket));
            try {
                resources[0] = datasync.createLocationS3(r -> r.s3BucketArn("arn:aws:s3:::" + sourceBucket)
                        .s3Config(c -> c.bucketAccessRoleArn("arn:aws:iam::000000000000:role/datasync"))).locationArn();
                resources[1] = datasync.createLocationS3(r -> r.s3BucketArn("arn:aws:s3:::" + destinationBucket)
                        .s3Config(c -> c.bucketAccessRoleArn("arn:aws:iam::000000000000:role/datasync"))).locationArn();
                resources[2] = datasync.createTask(r -> r.sourceLocationArn(resources[0]).destinationLocationArn(resources[1]))
                        .taskArn();
                String content = "DataSync copies bytes, not just execution statuses.";
                s3.putObject(r -> r.bucket(sourceBucket).key("data.txt"), RequestBody.fromString(content));
                String cancelled = datasync.startTaskExecution(r -> r.taskArn(resources[2])
                        .overrideOptions(o -> o.bytesPerSecond(1L))).taskExecutionArn();
                awaitExecution(cancelled, "TRANSFERRING");
                assertThat(datasync.listTaskExecutions(r -> r.taskArn(resources[2])).taskExecutions())
                        .extracting(e -> e.taskExecutionArn()).contains(cancelled);
                datasync.cancelTaskExecution(r -> r.taskExecutionArn(cancelled));
                DescribeTaskExecutionResponse stopped = awaitExecution(cancelled, "ERROR");
                assertThat(stopped.result().errorCode()).isEqualTo("Cancelled");
                assertThat(stopped.bytesTransferred()).isZero();
                assertThat(s3.listObjectsV2(r -> r.bucket(destinationBucket)).contents()).isEmpty();
                assertThatThrownBy(() -> datasync.updateTaskExecution(r -> r.taskExecutionArn(cancelled)
                        .options(o -> o.bytesPerSecond(1048576L)))).isInstanceOf(InvalidRequestException.class);

                String execution = datasync.startTaskExecution(r -> r.taskArn(resources[2])
                        .overrideOptions(o -> o.bytesPerSecond(1L))).taskExecutionArn();
                awaitExecution(execution, "TRANSFERRING");
                datasync.updateTaskExecution(r -> r.taskExecutionArn(execution).options(o -> o.bytesPerSecond(-1L)));
                DescribeTaskExecutionResponse completed = awaitExecution(execution, "SUCCESS");
                assertThat(completed.bytesTransferred()).isEqualTo((long) content.length());
                assertThat(completed.filesTransferred()).isEqualTo(1L);
                assertThat(s3.getObjectAsBytes(r -> r.bucket(destinationBucket).key("data.txt")).asUtf8String())
                        .isEqualTo(content);
                assertThatThrownBy(() -> datasync.cancelTaskExecution(r -> r.taskExecutionArn(execution)))
                        .isInstanceOf(InvalidRequestException.class);
                datasync.deleteTask(r -> r.taskArn(resources[2]));
                resources[2] = null;
                assertThatThrownBy(() -> datasync.describeTaskExecution(r -> r.taskExecutionArn(execution)))
                        .isInstanceOf(InvalidRequestException.class);
            } finally {
                deleteQuietly("task", resources[2], arn -> datasync.deleteTask(r -> r.taskArn(arn)));
                deleteQuietly("location", resources[0], arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
                deleteQuietly("location", resources[1], arn -> datasync.deleteLocation(r -> r.locationArn(arn)));
                for (String bucket : List.of(sourceBucket, destinationBucket)) {
                    for (S3Object object : s3.listObjectsV2(r -> r.bucket(bucket)).contents()) {
                        s3.deleteObject(r -> r.bucket(bucket).key(object.key()));
                    }
                    s3.deleteBucket(r -> r.bucket(bucket));
                }
            }
        }
    }

    private static DescribeTaskExecutionResponse awaitExecution(String arn, String status) throws InterruptedException {
        DescribeTaskExecutionResponse response = datasync.describeTaskExecution(r -> r.taskExecutionArn(arn));
        for (int attempt = 0; attempt < 100 && !status.equals(response.statusAsString()); attempt++) {
            if ("ERROR".equals(response.statusAsString()) || "SUCCESS".equals(response.statusAsString())) {
                break;
            }
            Thread.sleep(100);
            response = datasync.describeTaskExecution(r -> r.taskExecutionArn(arn));
        }
        assertThat(response.statusAsString()).as("Execution %s: %s", arn, response.result()).isEqualTo(status);
        return response;
    }

    @Test
    @Order(10)
    void deleteTaskThenDescribeIsAnInvalidRequest() {
        datasync.deleteTask(r -> r.taskArn(taskArn));
        String deleted = taskArn;
        taskArn = null;

        assertThatThrownBy(() -> datasync.describeTask(r -> r.taskArn(deleted)))
                .isInstanceOf(InvalidRequestException.class);
    }
}
