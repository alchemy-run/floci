package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Bedrock AgentCore Control restJson1 gateway lifecycle. */
@QuarkusTest
class BedrockAgentCoreGatewayIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000410:role/GatewayRole";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getGatewayOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000410", EAST))
                .when()
                .get("/gateways/alchemy-nonexistent-probe-0000000000/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createIamAuthorizedMcpGatewayVerifyUpdateDescriptionDestroy() {
        String authorization = auth("000000000411", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"McpGateway",
                          "description":"initial",
                          "roleArn":"%s",
                          "authorizerType":"AWS_IAM",
                          "protocolType":"MCP",
                          "tags":{"fixture":"agentcore-gateway","alchemy::id":"McpGateway"}
                        }
                        """.formatted(ROLE))
                .when()
                .post("/gateways/")
                .then()
                .statusCode(200)
                .body("gatewayId", notNullValue())
                .body("gatewayArn", notNullValue())
                .body("gatewayUrl", notNullValue())
                .body("status", equalTo("READY"))
                .body("authorizerType", equalTo("AWS_IAM"))
                .body("protocolType", equalTo("MCP"))
                .extract()
                .response();

        String gatewayId = created.path("gatewayId");
        String gatewayArn = created.path("gatewayArn");
        assertTrue(gatewayArn.contains(":gateway/"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/gateways/" + gatewayId + "/")
                .then()
                .statusCode(200)
                .body("gatewayId", equalTo(gatewayId))
                .body("gatewayArn", equalTo(gatewayArn))
                .body("name", equalTo("McpGateway"))
                .body("description", equalTo("initial"))
                .body("status", equalTo("READY"))
                .body("authorizerType", equalTo("AWS_IAM"))
                .body("protocolType", equalTo("MCP"));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(gatewayArn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("agentcore-gateway", tags.get("fixture"));
        assertEquals("McpGateway", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .get("/gateways/")
                .then()
                .statusCode(200)
                .extract()
                .path("items");
        assertEquals(1, listed.size());
        assertEquals(gatewayId, listed.getFirst().get("gatewayId"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"McpGateway",
                          "description":"updated",
                          "roleArn":"%s",
                          "authorizerType":"AWS_IAM",
                          "protocolType":"MCP"
                        }
                        """.formatted(ROLE))
                .when()
                .put("/gateways/" + gatewayId + "/")
                .then()
                .statusCode(200)
                .body("gatewayId", equalTo(gatewayId))
                .body("description", equalTo("updated"))
                .body("status", equalTo("READY"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/gateways/" + gatewayId + "/")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/gateways/" + gatewayId + "/")
                .then()
                .statusCode(200)
                .body("gatewayId", equalTo(gatewayId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/gateways/" + gatewayId + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/bedrock-agentcore/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
