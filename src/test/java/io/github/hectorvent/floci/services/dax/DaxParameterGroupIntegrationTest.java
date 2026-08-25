package io.github.hectorvent.floci.services.dax;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * JSON 1.1 DAX parameter-group lifecycle: AmazonDAXV3.DescribeParameterGroups /
 * CreateParameterGroup / DescribeParameters / UpdateParameterGroup / DeleteParameterGroup.
 */
@QuarkusTest
class DaxParameterGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/dax/aws4_request";
    private static final String GROUP = "it-dax-pg-lifecycle";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeParameterGroupsMissingName_returnsParameterGroupNotFoundFault() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonDAXV3.DescribeParameterGroups")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ParameterGroupNames\":[\"alchemy-nonexistent-dax-params-probe\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    @Test
    void createUpdateDescribeDeleteParameterGroup() {
        try {
            dax("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + GROUP + "\"}");
        } catch (Exception ignored) {
            // best-effort cleanup from a previous run
        }

        dax("CreateParameterGroup",
                "{\"ParameterGroupName\":\"" + GROUP + "\",\"Description\":\"alchemy dax parameter group\"}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.ParameterGroupName", equalTo(GROUP))
                .body("ParameterGroup.Description", equalTo("alchemy dax parameter group"));

        dax("DescribeParameterGroups", "{\"ParameterGroupNames\":[\"" + GROUP + "\"]}")
            .then()
                .statusCode(200)
                .body("ParameterGroups[0].ParameterGroupName", equalTo(GROUP));

        dax("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.ParameterName", hasItems("query-ttl-millis", "record-ttl-millis"))
                .body("Parameters.find { it.ParameterName == 'query-ttl-millis' }.ParameterValue",
                        equalTo("300000"));

        dax("UpdateParameterGroup",
                "{\"ParameterGroupName\":\"" + GROUP + "\","
                        + "\"ParameterNameValues\":["
                        + "{\"ParameterName\":\"query-ttl-millis\",\"ParameterValue\":\"60000\"},"
                        + "{\"ParameterName\":\"record-ttl-millis\",\"ParameterValue\":\"300000\"}"
                        + "]}")
            .then()
                .statusCode(200)
                .body("ParameterGroup.ParameterGroupName", equalTo(GROUP));

        dax("DescribeParameters", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200)
                .body("Parameters.find { it.ParameterName == 'query-ttl-millis' }.ParameterValue",
                        equalTo("60000"))
                .body("Parameters.find { it.ParameterName == 'query-ttl-millis' }.Source",
                        equalTo("user"));

        dax("DeleteParameterGroup", "{\"ParameterGroupName\":\"" + GROUP + "\"}")
            .then()
                .statusCode(200);

        dax("DescribeParameterGroups", "{\"ParameterGroupNames\":[\"" + GROUP + "\"]}")
            .then()
                .statusCode(404)
                .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    private static io.restassured.response.Response dax(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonDAXV3." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
