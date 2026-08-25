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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Bedrock AgentCore Control restJson1 code-interpreter lifecycle. */
@QuarkusTest
class BedrockAgentCoreCodeInterpreterIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCodeInterpreterOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/code-interpreters/alchemy_nonexistent_probe-0000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createSandboxInterpreterVerifyReplaceOnNetworkChangeAndDestroy() {
        String authorization = auth("000000000403", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"Sandbox",
                          "networkConfiguration":{"networkMode":"SANDBOX"},
                          "tags":{"fixture":"agentcore-code-interpreter"}
                        }
                        """)
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterId", notNullValue())
                .body("codeInterpreterArn", notNullValue())
                .body("status", equalTo("READY"))
                .extract()
                .response();

        String interpreterId = created.path("codeInterpreterId");
        String interpreterArn = created.path("codeInterpreterArn");
        assertTrue(interpreterArn.contains(":code-interpreter"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/code-interpreters/" + interpreterId)
                .then()
                .statusCode(200)
                .body("codeInterpreterId", equalTo(interpreterId))
                .body("codeInterpreterArn", equalTo(interpreterArn))
                .body("name", equalTo("Sandbox"))
                .body("status", equalTo("READY"))
                .body("networkConfiguration.networkMode", equalTo("SANDBOX"));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(interpreterArn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("agentcore-code-interpreter", tags.get("fixture"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(200)
                .extract()
                .path("codeInterpreterSummaries");
        assertEquals(1, listed.size());
        assertEquals(interpreterId, listed.getFirst().get("codeInterpreterId"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/code-interpreters/" + interpreterId)
                .then()
                .statusCode(200)
                .body("codeInterpreterId", equalTo(interpreterId))
                .body("status", equalTo("DELETED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/code-interpreters/" + interpreterId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETED"));

        Response replaced = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"Sandbox",
                          "networkConfiguration":{"networkMode":"PUBLIC"},
                          "tags":{"fixture":"agentcore-code-interpreter"}
                        }
                        """)
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterId", not(equalTo(interpreterId)))
                .body("status", equalTo("READY"))
                .extract()
                .response();

        String replacedId = replaced.path("codeInterpreterId");
        given()
                .header("Authorization", authorization)
                .when()
                .get("/code-interpreters/" + replacedId)
                .then()
                .statusCode(200)
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/code-interpreters/" + replacedId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETED"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/bedrock-agentcore/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
