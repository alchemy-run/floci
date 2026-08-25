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

/** Verifies the Bedrock AgentCore Control restJson1 agent-runtime lifecycle. */
@QuarkusTest
class BedrockAgentCoreRuntimeIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000420:role/RuntimeRole";
    private static final String IMAGE =
            "000000000420.dkr.ecr.us-east-1.amazonaws.com/agent:latest";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAgentRuntimeOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000420", EAST))
                .when()
                .get("/runtimes/alchemy_nonexistent_probe-0000000000/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createContainerBackedRuntimeVerifyOutOfBandDestroy() {
        String authorization = auth("000000000421", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentRuntimeName":"TestAgent",
                          "description":"initial",
                          "agentRuntimeArtifact":{
                            "containerConfiguration":{"containerUri":"%s"}
                          },
                          "roleArn":"%s",
                          "networkConfiguration":{"networkMode":"PUBLIC"},
                          "tags":{"fixture":"agentcore-runtime","alchemy::id":"TestAgent"}
                        }
                        """.formatted(IMAGE, ROLE))
                .when()
                .put("/runtimes/")
                .then()
                .statusCode(200)
                .body("agentRuntimeId", notNullValue())
                .body("agentRuntimeArn", notNullValue())
                .body("agentRuntimeVersion", equalTo("1"))
                .body("status", equalTo("READY"))
                .extract()
                .response();

        String runtimeId = created.path("agentRuntimeId");
        String runtimeArn = created.path("agentRuntimeArn");
        assertTrue(runtimeArn.contains(":runtime/"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runtimes/" + runtimeId + "/")
                .then()
                .statusCode(200)
                .body("agentRuntimeId", equalTo(runtimeId))
                .body("agentRuntimeArn", equalTo(runtimeArn))
                .body("agentRuntimeName", equalTo("TestAgent"))
                .body("description", equalTo("initial"))
                .body("status", equalTo("READY"))
                .body("roleArn", equalTo(ROLE))
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"))
                .body("agentRuntimeArtifact.containerConfiguration.containerUri", equalTo(IMAGE));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(runtimeArn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("agentcore-runtime", tags.get("fixture"));
        assertEquals("TestAgent", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .post("/runtimes/")
                .then()
                .statusCode(200)
                .extract()
                .path("agentRuntimes");
        assertEquals(1, listed.size());
        assertEquals(runtimeId, listed.getFirst().get("agentRuntimeId"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentRuntimeArtifact":{
                            "containerConfiguration":{"containerUri":"%s"}
                          },
                          "roleArn":"%s",
                          "networkConfiguration":{"networkMode":"PUBLIC"},
                          "description":"updated"
                        }
                        """.formatted(IMAGE, ROLE))
                .when()
                .put("/runtimes/" + runtimeId + "/")
                .then()
                .statusCode(200)
                .body("agentRuntimeId", equalTo(runtimeId))
                .body("agentRuntimeVersion", equalTo("2"))
                .body("status", equalTo("READY"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runtimes/" + runtimeId + "/")
                .then()
                .statusCode(200)
                .body("description", equalTo("updated"))
                .body("agentRuntimeVersion", equalTo("2"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/runtimes/" + runtimeId + "/")
                .then()
                .statusCode(200)
                .body("agentRuntimeId", equalTo(runtimeId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runtimes/" + runtimeId + "/")
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
