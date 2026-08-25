package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * EMR Studio JSON 1.1 lifecycle: create → describe → list → update → tags → delete.
 * Describe/delete of a missing Studio overloads InvalidRequestException with
 * "Studio does not exist." (distilled remaps that to StudioNotFound).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrStudioIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";
    private static final String MISSING_ID = "es-AAAAAAAAAAAAAAAAAAAAAAAAA";

    private static String studioId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    @Order(1)
    void describeUnknownStudioIsStudioDoesNotExist() {
        call("DescribeStudio", "{\"StudioId\":\"" + MISSING_ID + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("Studio does not exist"));
    }

    @Test
    @Order(2)
    void createStudio() {
        Response resp = call("CreateStudio",
                "{\"Name\":\"floci-studio\",\"AuthMode\":\"IAM\",\"VpcId\":\"vpc-123\","
                        + "\"SubnetIds\":[\"subnet-a\",\"subnet-b\"],"
                        + "\"ServiceRole\":\"arn:aws:iam::000000000000:role/studio\","
                        + "\"WorkspaceSecurityGroupId\":\"sg-ws\",\"EngineSecurityGroupId\":\"sg-eng\","
                        + "\"DefaultS3Location\":\"s3://floci-studio/studio/\","
                        + "\"Description\":\"alchemy test studio\","
                        + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"emr-studio\"}]}");
        resp.then().statusCode(200)
                .body("StudioId", startsWith("es-"))
                .body("Url", notNullValue());
        studioId = resp.jsonPath().getString("StudioId");
    }

    @Test
    @Order(3)
    void describeStudio() {
        call("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.StudioId", equalTo(studioId))
                .body("Studio.StudioArn", containsString(":studio/"))
                .body("Studio.AuthMode", equalTo("IAM"))
                .body("Studio.Description", equalTo("alchemy test studio"))
                .body("Studio.DefaultS3Location", equalTo("s3://floci-studio/studio/"))
                .body("Studio.Tags.find { it.Key == 'fixture' }.Value", equalTo("emr-studio"));
    }

    @Test
    @Order(4)
    void listStudiosIncludesCreated() {
        call("ListStudios", "{}")
                .then().statusCode(200)
                .body("Studios.find { it.StudioId == '" + studioId + "' }.Name", equalTo("floci-studio"));
    }

    @Test
    @Order(5)
    void updateStudioInPlace() {
        call("UpdateStudio", "{\"StudioId\":\"" + studioId + "\","
                + "\"Description\":\"alchemy test studio v2\","
                + "\"DefaultS3Location\":\"s3://floci-studio/studio-v2/\"}")
                .then().statusCode(200);
        call("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.Description", equalTo("alchemy test studio v2"))
                .body("Studio.DefaultS3Location", containsString("/studio-v2/"))
                .body("Studio.StudioId", equalTo(studioId));
    }

    @Test
    @Order(6)
    void addAndRemoveStudioTags() {
        call("AddTags", "{\"ResourceId\":\"" + studioId + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .then().statusCode(200);
        call("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.Tags.find { it.Key == 'env' }.Value", equalTo("test"));
        call("RemoveTags", "{\"ResourceId\":\"" + studioId + "\",\"TagKeys\":[\"env\"]}")
                .then().statusCode(200);
        call("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.Tags.find { it.Key == 'env' }", equalTo(null));
    }

    @Test
    @Order(7)
    void deleteStudioThenDescribeIsStudioDoesNotExist() {
        call("DeleteStudio", "{\"StudioId\":\"" + studioId + "\"}").then().statusCode(200);
        call("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("Studio does not exist"));
    }

    @Test
    @Order(8)
    void deleteUnknownStudioIsStudioDoesNotExist() {
        call("DeleteStudio", "{\"StudioId\":\"" + MISSING_ID + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("Studio does not exist"));
    }
}
