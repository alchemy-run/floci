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

/** Verifies the Bedrock AgentCore Control restJson1 memory lifecycle. */
@QuarkusTest
class BedrockAgentCoreMemoryIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMemoryOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000430", EAST))
                .when()
                .get("/memories/alchemy_nonexistent_probe-0000000000/details")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createShortTermMemoryVerifyUpdateExpiryDestroy() {
        String authorization = auth("000000000431", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"SessionMemory",
                          "eventExpiryDuration":7,
                          "tags":{"fixture":"agentcore-memory","alchemy::id":"SessionMemory"}
                        }
                        """)
                .when()
                .post("/memories/create")
                .then()
                .statusCode(200)
                .body("memory.id", notNullValue())
                .body("memory.arn", notNullValue())
                .body("memory.status", equalTo("ACTIVE"))
                .body("memory.eventExpiryDuration", equalTo(7))
                .extract()
                .response();

        String memoryId = created.path("memory.id");
        String memoryArn = created.path("memory.arn");
        assertTrue(memoryArn.contains(":memory/"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/memories/" + memoryId + "/details")
                .then()
                .statusCode(200)
                .body("memory.id", equalTo(memoryId))
                .body("memory.arn", equalTo(memoryArn))
                .body("memory.name", equalTo("SessionMemory"))
                .body("memory.status", equalTo("ACTIVE"))
                .body("memory.eventExpiryDuration", equalTo(7));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(memoryArn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("agentcore-memory", tags.get("fixture"));
        assertEquals("SessionMemory", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/memories/")
                .then()
                .statusCode(200)
                .extract()
                .path("memories");
        assertEquals(1, listed.size());
        assertEquals(memoryId, listed.getFirst().get("id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "eventExpiryDuration":14
                        }
                        """)
                .when()
                .put("/memories/" + memoryId + "/update")
                .then()
                .statusCode(200)
                .body("memory.id", equalTo(memoryId))
                .body("memory.eventExpiryDuration", equalTo(14));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/memories/" + memoryId + "/details")
                .then()
                .statusCode(200)
                .body("memory.eventExpiryDuration", equalTo(14));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/memories/" + memoryId + "/delete")
                .then()
                .statusCode(200)
                .body("memoryId", equalTo(memoryId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/memories/" + memoryId + "/details")
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
