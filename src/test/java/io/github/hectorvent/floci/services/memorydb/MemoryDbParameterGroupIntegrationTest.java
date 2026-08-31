package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * JSON 1.1 MemoryDB parameter-group lifecycle used by Alchemy:
 * AmazonMemoryDB.DescribeParameterGroups / CreateParameterGroup / DescribeParameters /
 * UpdateParameterGroup / ResetParameterGroup / DeleteParameterGroup / tags.
 */
@QuarkusTest
class MemoryDbParameterGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/memorydb/aws4_request";
    private static final String GROUP = "it-mdb-pg-lifecycle";
    private static final String FAMILY = "memorydb_valkey7";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeParameterGroupsMissingName_returnsParameterGroupNotFoundFault() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonMemoryDB.DescribeParameterGroups")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ParameterGroupName\":\"alchemy-nonexistent-memorydb-params-probe\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    @Test
    void createUpdateResetDeleteParameterGroup() {
        try {
            memorydb("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + GROUP + "\"}");
        } catch (Exception ignored) {
            // best-effort cleanup from a previous run
        }

        String arn = memorydb("CreateParameterGroup", "{"
                + "\"ParameterGroupName\":\"" + GROUP + "\","
                + "\"Family\":\"" + FAMILY + "\","
                + "\"Description\":\"alchemy memorydb parameter group fixture\","
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"memorydb-parameter-group\"}]}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.Name", equalTo(GROUP))
                .body("ParameterGroup.Family", equalTo(FAMILY))
                .body("ParameterGroup.Description", equalTo("alchemy memorydb parameter group fixture"))
                .body("ParameterGroup.ARN", containsString(":parametergroup/"))
            .extract()
                .path("ParameterGroup.ARN");

        memorydb("DescribeParameterGroups", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("ParameterGroups[0].Name", equalTo(GROUP))
                .body("ParameterGroups[0].Family", equalTo(FAMILY));

        memorydb("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.Name", hasItems("maxmemory-policy"))
                .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value",
                        equalTo("noeviction"));

        memorydb("UpdateParameterGroup", "{"
                + "\"ParameterGroupName\":\"" + GROUP + "\","
                + "\"ParameterNameValues\":["
                + "{\"ParameterName\":\"maxmemory-policy\",\"ParameterValue\":\"allkeys-lru\"}"
                + "]}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.Name", equalTo(GROUP));

        memorydb("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value",
                        equalTo("allkeys-lru"));

        memorydb("UpdateParameterGroup", "{"
                + "\"ParameterGroupName\":\"" + GROUP + "\","
                + "\"ParameterNameValues\":["
                + "{\"ParameterName\":\"maxmemory-policy\",\"ParameterValue\":\"volatile-lru\"}"
                + "]}")
            .then()
                .statusCode(200);

        memorydb("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value",
                        equalTo("volatile-lru"));

        memorydb("ResetParameterGroup", "{"
                + "\"ParameterGroupName\":\"" + GROUP + "\","
                + "\"ParameterNames\":[\"maxmemory-policy\"]}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.Name", equalTo(GROUP));

        memorydb("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value",
                        equalTo("noeviction"));

        memorydb("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
            .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'fixture' }.Value",
                        equalTo("memorydb-parameter-group"));

        memorydb("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
            .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'env' }.Value", equalTo("test"));

        memorydb("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"env\"]}")
            .then()
                .statusCode(200);

        memorydb("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.Name", equalTo(GROUP));

        memorydb("DescribeParameterGroups", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(404)
                .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    private static Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
