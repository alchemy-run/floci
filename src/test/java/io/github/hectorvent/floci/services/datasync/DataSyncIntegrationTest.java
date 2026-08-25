package io.github.hectorvent.floci.services.datasync;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the AWS DataSync stub.
 * Protocol: JSON 1.1 — {@code X-Amz-Target: FmrsService.&lt;Action&gt;}
 */
@QuarkusTest
class DataSyncIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/datasync/aws4_request";
    private static final String TARGET_PREFIX = "FmrsService.";

    private static io.restassured.response.Response ds(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    void listLocations_emptyAccount_returnsEmptyList() {
        ds("ListLocations", "{}")
                .then()
                .statusCode(200)
                .body("Locations", notNullValue());
    }

    @Test
    void createDescribeDeleteS3LocationAndTask_executionRoundTrip() {
        String srcArn = ds("CreateLocationS3", """
                {"S3BucketArn":"arn:aws:s3:::floci-ds-src",
                 "S3Config":{"BucketAccessRoleArn":"arn:aws:iam::000000000000:role/ds"},
                 "Tags":[{"Key":"team","Value":"data"}]}
                """)
                .then()
                .statusCode(200)
                .body("LocationArn", containsString(":location/loc-"))
                .extract()
                .path("LocationArn");

        ds("DescribeLocationS3", "{\"LocationArn\":\"%s\"}".formatted(srcArn))
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("s3://floci-ds-src/"))
                .body("S3StorageClass", equalTo("STANDARD"))
                .body("S3Config.BucketAccessRoleArn",
                        equalTo("arn:aws:iam::000000000000:role/ds"));

        ds("ListLocations", "{}")
                .then()
                .statusCode(200)
                .body("Locations.LocationArn", hasItem(srcArn));

        ds("ListTagsForResource", "{\"ResourceArn\":\"%s\"}".formatted(srcArn))
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'team' }.Value", equalTo("data"));

        String dstArn = ds("CreateLocationS3", """
                {"S3BucketArn":"arn:aws:s3:::floci-ds-dst",
                 "S3Config":{"BucketAccessRoleArn":"arn:aws:iam::000000000000:role/ds"}}
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("LocationArn");

        String taskArn = ds("CreateTask", """
                {"SourceLocationArn":"%s","DestinationLocationArn":"%s","Name":"it-ds-task"}
                """.formatted(srcArn, dstArn))
                .then()
                .statusCode(200)
                .body("TaskArn", containsString(":task/task-"))
                .extract()
                .path("TaskArn");

        ds("DescribeTask", "{\"TaskArn\":\"%s\"}".formatted(taskArn))
                .then()
                .statusCode(200)
                .body("Status", equalTo("AVAILABLE"))
                .body("Name", equalTo("it-ds-task"))
                .body("SourceLocationArn", equalTo(srcArn));

        String execArn = ds("StartTaskExecution", "{\"TaskArn\":\"%s\"}".formatted(taskArn))
                .then()
                .statusCode(200)
                .body("TaskExecutionArn", containsString("/execution/exec-"))
                .extract()
                .path("TaskExecutionArn");

        ds("DescribeTaskExecution", "{\"TaskExecutionArn\":\"%s\"}".formatted(execArn))
                .then()
                .statusCode(200)
                .body("Status", equalTo("TRANSFERRING"));

        ds("ListTaskExecutions", "{\"TaskArn\":\"%s\"}".formatted(taskArn))
                .then()
                .statusCode(200)
                .body("TaskExecutions.TaskExecutionArn", hasItem(execArn));

        ds("UpdateTaskExecution", """
                {"TaskExecutionArn":"%s","Options":{"BytesPerSecond":1048576}}
                """.formatted(execArn))
                .then()
                .statusCode(200);

        ds("CancelTaskExecution", "{\"TaskExecutionArn\":\"%s\"}".formatted(execArn))
                .then()
                .statusCode(200);

        ds("DescribeTaskExecution", "{\"TaskExecutionArn\":\"%s\"}".formatted(execArn))
                .then()
                .statusCode(200)
                .body("Status", equalTo("CANCELLING"));

        ds("DeleteTask", "{\"TaskArn\":\"%s\"}".formatted(taskArn)).then().statusCode(200);
        ds("DeleteLocation", "{\"LocationArn\":\"%s\"}".formatted(srcArn)).then().statusCode(200);
        ds("DeleteLocation", "{\"LocationArn\":\"%s\"}".formatted(dstArn)).then().statusCode(200);
    }

    @Test
    void describeLocation_missing_invalidRequest() {
        ds("DescribeLocationS3",
                "{\"LocationArn\":\"arn:aws:datasync:us-east-1:000000000000:location/loc-missing00000000\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("Location "))
                .body("message", containsString("is not found"));
    }

    @Test
    void describeLocationEfs_missing_invalidRequest() {
        ds("DescribeLocationEfs",
                "{\"LocationArn\":\"arn:aws:datasync:us-west-2:391965393224:location/loc-00000000000000000\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("Location "))
                .body("message", containsString("is not found"));
    }

    @Test
    void createLocationEfs_roundTrip() {
        String arn = ds("CreateLocationEfs", """
                {"EfsFilesystemArn":"arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/fs-abc",
                 "Ec2Config":{"SubnetArn":"arn:aws:ec2:us-east-1:000000000000:subnet/subnet-1",
                              "SecurityGroupArns":["arn:aws:ec2:us-east-1:000000000000:security-group/sg-1"]}}
                """)
                .then()
                .statusCode(200)
                .body("LocationArn", containsString(":location/loc-"))
                .extract()
                .path("LocationArn");

        ds("DescribeLocationEfs", "{\"LocationArn\":\"%s\"}".formatted(arn))
                .then()
                .statusCode(200)
                .body("LocationUri", equalTo("efs://fs-abc/"))
                .body("Ec2Config.SubnetArn", containsString("subnet-1"));

        ds("DeleteLocation", "{\"LocationArn\":\"%s\"}".formatted(arn)).then().statusCode(200);
    }
}
