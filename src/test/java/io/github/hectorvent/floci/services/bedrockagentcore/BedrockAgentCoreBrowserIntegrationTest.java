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

/** Verifies the Bedrock AgentCore Control restJson1 custom-browser lifecycle. */
@QuarkusTest
class BedrockAgentCoreBrowserIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getBrowserOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/browsers/alchemy_nonexistent_probe-0000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createCustomBrowserVerifyOutOfBandDestroy() {
        String authorization = auth("000000000402", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"AgentBrowser",
                          "description":"alchemy agentcore browser test",
                          "networkConfiguration":{"networkMode":"PUBLIC"},
                          "tags":{"fixture":"agentcore-browser","alchemy::id":"AgentBrowser"}
                        }
                        """)
                .when()
                .put("/browsers")
                .then()
                .statusCode(200)
                .body("browserId", notNullValue())
                .body("browserArn", notNullValue())
                .body("status", equalTo("READY"))
                .extract()
                .response();

        String browserId = created.path("browserId");
        String browserArn = created.path("browserArn");
        assertTrue(browserArn.contains(":browser"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/browsers/" + browserId)
                .then()
                .statusCode(200)
                .body("browserId", equalTo(browserId))
                .body("browserArn", equalTo(browserArn))
                .body("name", equalTo("AgentBrowser"))
                .body("status", equalTo("READY"))
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(browserArn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("agentcore-browser", tags.get("fixture"));
        assertEquals("AgentBrowser", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .post("/browsers")
                .then()
                .statusCode(200)
                .extract()
                .path("browserSummaries");
        assertEquals(1, listed.size());
        assertEquals(browserId, listed.getFirst().get("browserId"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/browsers/" + browserId)
                .then()
                .statusCode(200)
                .body("browserId", equalTo(browserId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/browsers/" + browserId)
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
