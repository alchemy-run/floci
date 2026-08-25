package io.github.hectorvent.floci.services.apprunner;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies App Runner JSON 1.0 VPC connector operations used by Alchemy
 * {@code VpcConnector.test.ts}: create, list, describe, tags, and delete-to-INACTIVE.
 */
@QuarkusTest
class AppRunnerVpcConnectorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listVpcConnectorsOnEmptyAccountReturnsEmptyList() {
        String authorization = auth("000000000601");
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListVpcConnectors")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnectors.size()", equalTo(0));
    }

    @Test
    void vpcConnectorCreateDescribeDeleteLifecycle() {
        String authorization = auth("000000000602");
        String name = "conn-lifecycle";
        String arn = create(authorization, name, "[\"subnet-aaa\"]", "[\"sg-bbb\"]");
        assertTrue(arn.contains(":vpcconnector/"));
        assertTrue(arn.contains("/" + name + "/"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnector.VpcConnectorName", equalTo(name))
                .body("VpcConnector.VpcConnectorArn", equalTo(arn))
                .body("VpcConnector.Status", equalTo("ACTIVE"))
                .body("VpcConnector.VpcConnectorRevision", greaterThanOrEqualTo(1))
                .body("VpcConnector.Subnets", hasItem("subnet-aaa"))
                .body("VpcConnector.SecurityGroups", hasItem("sg-bbb"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListVpcConnectors")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnectors.VpcConnectorArn", hasItem(arn))
                .body("VpcConnectors.Status", hasItem("ACTIVE"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DeleteVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnector.Status", equalTo("INACTIVE"))
                .body("VpcConnector.VpcConnectorArn", equalTo(arn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnector.Status", equalTo("INACTIVE"));
    }

    @Test
    void createVpcConnectorAcceptsTagsAndTagApisRoundTrip() {
        String authorization = auth("000000000603");
        String name = "conn-tags";
        String arn = create(authorization, name, "[\"subnet-ccc\"]", "[\"sg-ddd\"]",
                "[{\"Key\":\"alchemy:stage\",\"Value\":\"test\"}]");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListTagsForResource")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy:stage"))
                .body("Tags.Value", hasItem("test"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.TagResource")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"local\"}]}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListTagsForResource")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItems("alchemy:stage", "env"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.UntagResource")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"alchemy:stage\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.ListTagsForResource")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));
    }

    @Test
    void describeUnknownVpcConnectorReturnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeVpcConnector")
                .header("Authorization", auth("000000000604"))
                .body("{\"VpcConnectorArn\":\"arn:aws:apprunner:us-east-1:000000000604:vpcconnector/missing/1/deadbeef\"}")
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDuplicateActiveNameReturnsInvalidRequest() {
        String authorization = auth("000000000605");
        String name = "conn-dup-name";
        create(authorization, name, "[\"subnet-eee\"]", "[\"sg-fff\"]");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.CreateVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorName\":\"" + name + "\",\"Subnets\":[\"subnet-eee\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createAfterDeleteIncrementsRevision() {
        String authorization = auth("000000000606");
        String name = "conn-revision";
        String first = create(authorization, name, "[\"subnet-ggg\"]", "[\"sg-hhh\"]");
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DeleteVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorArn\":\"" + first + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        String second = create(authorization, name, "[\"subnet-ggg\"]", "[\"sg-hhh\"]");
        assertTrue(second.contains("/2/"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.DescribeVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorArn\":\"" + second + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("VpcConnector.VpcConnectorRevision", equalTo(2))
                .body("VpcConnector.Status", equalTo("ACTIVE"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/apprunner/aws4_request";
    }

    private static String create(String authorization, String name, String subnets, String securityGroups) {
        return create(authorization, name, subnets, securityGroups, "[]");
    }

    private static String create(String authorization, String name, String subnets, String securityGroups, String tags) {
        Response response = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AppRunner.CreateVpcConnector")
                .header("Authorization", authorization)
                .body("{\"VpcConnectorName\":\"" + name + "\",\"Subnets\":" + subnets
                        + ",\"SecurityGroups\":" + securityGroups + ",\"Tags\":" + tags + "}")
                .when()
                .post("/");
        assertEquals(200, response.statusCode(), response.asString());
        return response.jsonPath().getString("VpcConnector.VpcConnectorArn");
    }
}
