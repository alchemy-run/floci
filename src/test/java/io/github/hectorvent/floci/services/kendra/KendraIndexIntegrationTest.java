package io.github.hectorvent.floci.services.kendra;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Index + S3 data-source coverage matching Alchemy's Kendra SearchIndex resource:
 * describe-not-found, create ACTIVE, update description, tags, delete until gone.
 */
@QuarkusTest
class KendraIndexIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/kendra/aws4_request";
    private static final String TARGET = "AWSKendraFrontendService.";
    private static final String MISSING = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/kendra-index";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeIndex_missingId_returnsResourceNotFoundException() {
        kendra("DescribeIndex", "{\"Id\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeDataSource_missingIndex_returnsResourceNotFoundException() {
        kendra("DescribeDataSource",
                "{\"Id\":\"" + MISSING + "\",\"IndexId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void indexAndDataSourceLifecycle_createUpdateTagDelete() {
        String name = "floci-kendra-" + UUID.randomUUID().toString().substring(0, 8);

        String indexId = kendra("CreateIndex", """
                {
                  "Name": "%s",
                  "Edition": "DEVELOPER_EDITION",
                  "RoleArn": "%s",
                  "Tags": [{"Key": "Environment", "Value": "test"}]
                }
                """.formatted(name, ROLE))
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");

        kendra("DescribeIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("Id", equalTo(indexId))
                .body("Name", equalTo(name))
                .body("Status", equalTo("ACTIVE"))
                .body("Edition", equalTo("DEVELOPER_EDITION"));

        kendra("ListIndices", "{}")
                .then()
                .statusCode(200)
                .body("IndexConfigurationSummaryItems.Id", hasItem(indexId));

        String arn = "arn:aws:kendra:us-east-1:000000000000:index/" + indexId;
        kendra("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Environment"));

        kendra("UpdateIndex", """
                {"Id":"%s","Description":"updated by test"}
                """.formatted(indexId))
                .then()
                .statusCode(200);

        kendra("DescribeIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("Description", equalTo("updated by test"));

        String sourceId = kendra("CreateDataSource", """
                {
                  "Name": "%s-docs",
                  "IndexId": "%s",
                  "Type": "S3",
                  "RoleArn": "%s",
                  "Configuration": {"S3Configuration": {"BucketName": "docs"}}
                }
                """.formatted(name, indexId, ROLE))
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().path("Id");

        kendra("DescribeDataSource",
                "{\"Id\":\"" + sourceId + "\",\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(200)
                .body("Id", equalTo(sourceId))
                .body("IndexId", equalTo(indexId))
                .body("Status", equalTo("ACTIVE"))
                .body("Type", equalTo("S3"));

        kendra("DeleteDataSource",
                "{\"Id\":\"" + sourceId + "\",\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(200);

        kendra("DescribeDataSource",
                "{\"Id\":\"" + sourceId + "\",\"IndexId\":\"" + indexId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        kendra("DeleteIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(200);

        kendra("DescribeIndex", "{\"Id\":\"" + indexId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response kendra(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
