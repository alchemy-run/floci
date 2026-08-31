package io.github.hectorvent.floci.services.fsx;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class FsxIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/fsx/aws4_request";
    private static final String TARGET = "AWSSimbaAPIService_v20180301.";
    private static final String UNKNOWN_FS = "fs-00000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFileSystems_unknownId_fileSystemNotFound() {
        invoke("DescribeFileSystems", "{\"FileSystemIds\":[\"" + UNKNOWN_FS + "\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    @Test
    void deleteFileSystem_missing_fileSystemNotFound() {
        invoke("DeleteFileSystem", "{\"FileSystemId\":\"" + UNKNOWN_FS + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    @Test
    void createDescribeTagUpdateAndDelete() {
        Response created = invoke("CreateFileSystem", """
                {
                  "ClientRequestToken": "floci-fsx-integration-token",
                  "FileSystemType": "LUSTRE",
                  "StorageCapacity": 1200,
                  "SubnetIds": ["subnet-aaaa1111"],
                  "LustreConfiguration": {"DeploymentType": "SCRATCH_2"},
                  "Tags": [{"Key": "purpose", "Value": "alchemy-fsx-test"}]
                }
                """);
        created.then()
                .statusCode(200)
                .body("FileSystem.FileSystemId", startsWith("fs-"))
                .body("FileSystem.FileSystemType", equalTo("LUSTRE"))
                .body("FileSystem.Lifecycle", equalTo("AVAILABLE"))
                .body("FileSystem.StorageCapacity", equalTo(1200))
                .body("FileSystem.LustreConfiguration.DeploymentType", equalTo("SCRATCH_2"));
        String fileSystemId = created.jsonPath().getString("FileSystem.FileSystemId");
        String arn = created.jsonPath().getString("FileSystem.ResourceARN");

        invoke("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(200)
                .body("FileSystems", hasSize(1))
                .body("FileSystems[0].FileSystemId", equalTo(fileSystemId))
                .body("FileSystems[0].Lifecycle", equalTo("AVAILABLE"))
                .body("FileSystems[0].Tags.find { it.Key == 'purpose' }.Value",
                        equalTo("alchemy-fsx-test"));

        invoke("TagResource",
                "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"prod\"}]}")
                .then()
                .statusCode(200);

        invoke("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(200)
                .body("FileSystems[0].Tags.find { it.Key == 'stage' }.Value", equalTo("prod"));

        invoke("UpdateFileSystem", "{\"FileSystemId\":\"" + fileSystemId + "\",\"StorageCapacity\":2400}")
                .then()
                .statusCode(200)
                .body("FileSystem.StorageCapacity", equalTo(2400));

        invoke("CreateFileSystem", """
                {
                  "ClientRequestToken": "floci-fsx-integration-token",
                  "FileSystemType": "LUSTRE",
                  "StorageCapacity": 1200,
                  "SubnetIds": ["subnet-aaaa1111"]
                }
                """)
                .then()
                .statusCode(200)
                .body("FileSystem.FileSystemId", equalTo(fileSystemId));

        invoke("DeleteFileSystem", "{\"FileSystemId\":\"" + fileSystemId + "\"}")
                .then()
                .statusCode(200)
                .body("FileSystemId", equalTo(fileSystemId))
                .body("Lifecycle", equalTo("DELETING"));

        invoke("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fileSystemId + "\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("FileSystemNotFound"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
