package io.github.hectorvent.floci.services.datasync;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class DataSyncExecutionIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    S3Service s3;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void emptyBucketsCompleteAndRejectTerminalMutations() {
        try (Transfer transfer = new Transfer("empty", ACCOUNT, REGION, "", "", "")) {
            String execution = transfer.start("");
            transfer.awaitSuccess(execution);
            transfer.describe(execution).then()
                    .body("BytesTransferred", equalTo(0))
                    .body("FilesTransferred", equalTo(0))
                    .body("Result.PrepareStatus", equalTo("SUCCESS"))
                    .body("Result.TransferStatus", equalTo("SUCCESS"));
            transfer.action("UpdateTaskExecution", """
                    {"TaskExecutionArn":"%s","Options":{"BytesPerSecond":1048576}}
                    """.formatted(execution)).then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"));
            transfer.action("CancelTaskExecution", executionBody(execution)).then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"));
            transfer.action("ListTaskExecutions", transfer.taskBody()).then().statusCode(200)
                    .body("TaskExecutions.TaskExecutionArn", hasItem(execution));
            transfer.action("DescribeTask", transfer.taskBody()).then().statusCode(200)
                    .body("Status", equalTo("AVAILABLE"));
        }
    }

    @Test
    void runningExecutionCanBeThrottledCancelledAndDeletedWithoutWritingLater() {
        try (Transfer transfer = new Transfer("cancel", ACCOUNT, REGION, "", "", "")) {
            transfer.put("data.txt", new byte[4096]);
            String execution = transfer.start(",\"OverrideOptions\":{\"BytesPerSecond\":1}");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> transfer.describe(execution).then()
                    .statusCode(200).body("Status", equalTo("TRANSFERRING")));
            transfer.action("DescribeTask", transfer.taskBody()).then()
                    .body("Status", equalTo("RUNNING"))
                    .body("CurrentTaskExecutionArn", equalTo(execution));
            transfer.action("UpdateTaskExecution", """
                    {"TaskExecutionArn":"%s","Options":{"BytesPerSecond":2}}
                    """.formatted(execution)).then().statusCode(200);
            transfer.describe(execution).then().body("Options.BytesPerSecond", equalTo(2));
            transfer.action("DescribeTask", transfer.taskBody()).then().body("Options.BytesPerSecond", equalTo(-1));
            String queued = transfer.start(",\"OverrideOptions\":{\"BytesPerSecond\":1}");
            transfer.describe(queued).then().body("Status", equalTo("QUEUED"));
            transfer.action("CancelTaskExecution", executionBody(queued)).then().statusCode(200);
            transfer.action("CancelTaskExecution", executionBody(execution)).then().statusCode(200);
            transfer.describe(execution).then().body("Status", equalTo("ERROR"))
                    .body("Result.ErrorCode", equalTo("Cancelled"))
                    .body("BytesTransferred", equalTo(0));
            RequestScopes.runAs(ACCOUNT, () -> assertFalse(s3.objectExists(transfer.destinationBucket, "data.txt")));

            String deleting = transfer.start(",\"OverrideOptions\":{\"BytesPerSecond\":1}");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> transfer.describe(deleting).then()
                    .body("Status", equalTo("TRANSFERRING")));
            transfer.deleteTask();
            transfer.describe(deleting).then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
            transfer.action("ListTaskExecutions", "{}").then().statusCode(200)
                    .body("TaskExecutions.TaskExecutionArn", not(hasItem(deleting)));
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    RequestScopes.runAs(ACCOUNT, () -> assertFalse(s3.objectExists(transfer.destinationBucket, "data.txt"))));
        }
    }

    @Test
    void transfersRealObjectsWithPrefixesFiltersTagsVerificationAndDeletion() {
        try (Transfer transfer = new Transfer("copy", ACCOUNT, REGION, "/input", "/backup", """
                ,"Includes":[{"FilterType":"SIMPLE_PATTERN","Value":"/*.txt"}],
                "Excludes":[{"FilterType":"SIMPLE_PATTERN","Value":"/skip*|/ignored"}],
                "Options":{"PreserveDeletedFiles":"REMOVE","VerifyMode":"ONLY_FILES_TRANSFERRED"}
                """)) {
            byte[] content = "real DataSync transfer".getBytes(StandardCharsets.UTF_8);
            transfer.put("input/hello.txt", content);
            transfer.put("input/skip.txt", new byte[]{1});
            transfer.put("input/ignored/nested.txt", new byte[]{1});
            transfer.put("outside.txt", new byte[]{2});
            RequestScopes.runAs(ACCOUNT, () -> {
                s3.putObjectTagging(transfer.sourceBucket, "input/hello.txt", Map.of("owner", "datasync"));
                s3.putObject(transfer.destinationBucket, "backup/stale.txt", new byte[]{3}, "text/plain", Map.of());
                s3.putObject(transfer.destinationBucket, "untouched.txt", new byte[]{4}, "text/plain", Map.of());
            });
            String execution = transfer.start("");
            transfer.awaitSuccess(execution);
            transfer.describe(execution).then().body("BytesTransferred", equalTo(content.length))
                    .body("FilesTransferred", equalTo(1)).body("FilesVerified", equalTo(1))
                    .body("FilesDeleted", equalTo(1));
            RequestScopes.runAs(ACCOUNT, () -> {
                S3Object copied = s3.getObject(transfer.destinationBucket, "backup/hello.txt");
                assertArrayEquals(content, copied.getData());
                assertEquals(Map.of("fixture", "datasync"), copied.getMetadata());
                assertEquals(Map.of("owner", "datasync"), s3.getObjectTagging(transfer.destinationBucket, "backup/hello.txt"));
                assertEquals(List.of("backup/hello.txt", "untouched.txt"), s3.listObjects(transfer.destinationBucket, "", null, 100)
                        .stream().map(S3Object::getKey).toList());
            });
            String unchanged = transfer.start("");
            transfer.awaitSuccess(unchanged);
            transfer.describe(unchanged).then().body("FilesTransferred", equalTo(0)).body("FilesSkipped", equalTo(1));
        }
    }

    @Test
    void bandwidthUpdatesReleaseTheTransferAndQueuedExecutionsRunInOrder() {
        try (Transfer transfer = new Transfer("queue", ACCOUNT, REGION, "", "", "")) {
            transfer.put("data.txt", new byte[4096]);
            String execution = transfer.start(",\"OverrideOptions\":{\"BytesPerSecond\":1}");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> transfer.describe(execution).then()
                    .body("Status", equalTo("TRANSFERRING")));
            String queued = transfer.start("");
            transfer.describe(queued).then().body("Status", equalTo("QUEUED"));
            transfer.action("UpdateTaskExecution", """
                    {"TaskExecutionArn":"%s","Options":{"BytesPerSecond":-1}}
                    """.formatted(execution)).then().statusCode(200);
            transfer.awaitSuccess(execution);
            transfer.awaitSuccess(queued);
            transfer.describe(execution).then().body("BytesTransferred", equalTo(4096));
            transfer.describe(queued).then().body("BytesTransferred", equalTo(0)).body("FilesSkipped", equalTo(1));
            transfer.action("ListTaskExecutions", "{\"TaskArn\":\"" + transfer.task + "\",\"MaxResults\":1}")
                    .then().statusCode(200).body("TaskExecutions.size()", equalTo(1));
        }
    }

    @Test
    void sourceInventoryContinuesPastOneS3ListPage() {
        try (Transfer transfer = new Transfer("pages", ACCOUNT, REGION, "", "", """
                ,"Options":{"VerifyMode":"NONE"}
                """)) {
            RequestScopes.runAs(ACCOUNT, () -> {
                for (int index = 0; index < 1001; index++) {
                    s3.putObject(transfer.sourceBucket, "object-" + index, new byte[]{1}, "text/plain", Map.of());
                }
            });
            String execution = transfer.start("");
            transfer.awaitSuccess(execution);
            transfer.describe(execution).then().body("FilesTransferred", equalTo(1001))
                    .body("BytesTransferred", equalTo(1001));
            RequestScopes.runAs(ACCOUNT, () -> assertEquals(1001,
                    s3.listObjects(transfer.destinationBucket, "", null, -1).size()));
        }
    }

    @Test
    void executionRetainsAccountAndRegionOwnershipOnTheBackgroundWorker() {
        String account = "111111111111";
        String region = "us-west-2";
        try (Transfer transfer = new Transfer("owned", account, region, "", "", "")) {
            transfer.put("hello.txt", "owned bytes".getBytes(StandardCharsets.UTF_8));
            String execution = transfer.start("");
            transfer.awaitSuccess(execution);
            RequestScopes.runAs(account, () -> assertEquals("owned bytes",
                    new String(s3.getObject(transfer.destinationBucket, "hello.txt").getData(), StandardCharsets.UTF_8)));
            for (String[] foreign : List.of(new String[]{ACCOUNT, region}, new String[]{account, REGION})) {
                action(foreign[0], foreign[1], "DescribeTaskExecution", executionBody(execution)).then().statusCode(400);
                action(foreign[0], foreign[1], "CancelTaskExecution", executionBody(execution)).then().statusCode(400);
                action(foreign[0], foreign[1], "UpdateTaskExecution", """
                        {"TaskExecutionArn":"%s","Options":{"BytesPerSecond":1}}
                        """.formatted(execution)).then().statusCode(400);
                action(foreign[0], foreign[1], "ListTaskExecutions", "{}").then().statusCode(200)
                        .body("TaskExecutions.TaskExecutionArn", not(hasItem(execution)));
                action(foreign[0], foreign[1], "ListTaskExecutions", transfer.taskBody()).then().statusCode(400);
                action(foreign[0], foreign[1], "DeleteTask", transfer.taskBody()).then().statusCode(400);
            }
            transfer.describe(execution).then().statusCode(200).body("Status", equalTo("SUCCESS"));
        }
    }

    @Test
    void missingBucketFailsWithoutFabricatingTransferredBytes() {
        try (Transfer transfer = new Transfer("missing", ACCOUNT, REGION, "", "", "")) {
            RequestScopes.runAs(ACCOUNT, () -> s3.deleteBucket(transfer.destinationBucket));
            transfer.destinationDeleted = true;
            String execution = transfer.start("");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> transfer.describe(execution).then()
                    .statusCode(200).body("Status", equalTo("ERROR"))
                    .body("Result.ErrorCode", equalTo("LocationAccessTestFailed"))
                    .body("Result.ErrorDetail", containsString("must exist"))
                    .body("FilesTransferred", equalTo(0)).body("BytesTransferred", equalTo(0)));
        }
    }

    private static Response action(String account, String region, String action, String body) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region
                        + "/datasync/aws4_request, SignedHeaders=host, Signature=test")
                .header("X-Amz-Target", "FmrsService." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/");
    }

    private static String executionBody(String arn) {
        return "{\"TaskExecutionArn\":\"" + arn + "\"}";
    }

    private final class Transfer implements AutoCloseable {
        private final String account;
        private final String region;
        private final String sourceBucket;
        private final String destinationBucket;
        private final String sourceLocation;
        private final String destinationLocation;
        private final String task;
        private boolean taskDeleted;
        private boolean destinationDeleted;

        private Transfer(String name, String account, String region, String sourcePrefix, String destinationPrefix, String options) {
            this.account = account;
            this.region = region;
            this.sourceBucket = "datasync-execution-" + name + "-source";
            this.destinationBucket = "datasync-execution-" + name + "-destination";
            RequestScopes.runAs(account, () -> {
                s3.createBucket(sourceBucket, region);
                s3.createBucket(destinationBucket, region);
            });
            sourceLocation = location(sourceBucket, sourcePrefix);
            destinationLocation = location(destinationBucket, destinationPrefix);
            task = action("CreateTask", """
                    {"SourceLocationArn":"%s","DestinationLocationArn":"%s"%s}
                    """.formatted(sourceLocation, destinationLocation, options))
                    .then().statusCode(200).extract().path("TaskArn");
        }

        private String location(String bucket, String prefix) {
            return action("CreateLocationS3", """
                    {"S3BucketArn":"arn:aws:s3:::%s","Subdirectory":"%s",
                     "S3Config":{"BucketAccessRoleArn":"arn:aws:iam::%s:role/datasync"}}
                    """.formatted(bucket, prefix, account)).then().statusCode(200).extract().path("LocationArn");
        }

        private void put(String key, byte[] content) {
            RequestScopes.runAs(account, () -> s3.putObject(sourceBucket, key, content, "text/plain", Map.of("fixture", "datasync")));
        }

        private String start(String options) {
            return action("StartTaskExecution", "{\"TaskArn\":\"" + task + "\"" + options + "}")
                    .then().statusCode(200).extract().path("TaskExecutionArn");
        }

        private Response describe(String arn) {
            return action("DescribeTaskExecution", executionBody(arn));
        }

        private void awaitSuccess(String arn) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> describe(arn).then()
                    .statusCode(200).body("Status", equalTo("SUCCESS")));
        }

        private String taskBody() {
            return "{\"TaskArn\":\"" + task + "\"}";
        }

        private Response action(String action, String body) {
            return DataSyncExecutionIntegrationTest.action(account, region, action, body);
        }

        private void deleteTask() {
            action("DeleteTask", taskBody()).then().statusCode(200);
            taskDeleted = true;
        }

        @Override
        public void close() {
            if (!taskDeleted) {
                deleteTask();
            }
            for (String location : List.of(sourceLocation, destinationLocation)) {
                action("DeleteLocation", "{\"LocationArn\":\"" + location + "\"}").then().statusCode(200);
            }
            RequestScopes.runAs(account, () -> {
                for (String bucket : destinationDeleted ? List.of(sourceBucket) : List.of(sourceBucket, destinationBucket)) {
                    for (S3Object object : s3.listObjects(bucket, "", null, -1)) {
                        s3.deleteObject(bucket, object.getKey());
                    }
                    s3.deleteBucket(bucket);
                }
            });
        }
    }
}
