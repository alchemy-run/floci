package io.github.hectorvent.floci.services.bedrockagent;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Bedrock Agents restJson1 agent/alias lifecycle and tags. */
@QuarkusTest
class BedrockAgentIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String INSTRUCTION =
            "You are a helpful assistant. Answer every question as concisely as you can.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAgentOnAMissingIdReturnsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/agents/missingagent/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPrepareAliasUpdateTagAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String roleArn = "arn:aws:iam::000000000401:role/BedrockAgentExecution";

        String agentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentName":"lifecycle-agent",
                          "foundationModel":"us.amazon.nova-micro-v1:0",
                          "instruction":"%s",
                          "description":"alchemy bedrock agent test",
                          "agentResourceRoleArn":"%s",
                          "tags":{"Environment":"test"}
                        }
                        """.formatted(INSTRUCTION, roleArn))
                .when()
                .put("/agents/")
                .then()
                .statusCode(200)
                .body("agent.agentId", notNullValue())
                .body("agent.agentName", equalTo("lifecycle-agent"))
                .body("agent.agentStatus", equalTo("NOT_PREPARED"))
                .body("agent.foundationModel", equalTo("us.amazon.nova-micro-v1:0"))
                .body("agent.agentResourceRoleArn", equalTo(roleArn))
                .extract().path("agent.agentId");

        String agentArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/agents/" + agentId + "/")
                .then()
                .statusCode(200)
                .body("agent.agentId", equalTo(agentId))
                .body("agent.description", equalTo("alchemy bedrock agent test"))
                .extract().path("agent.agentArn");

        assertTrue(agentArn.contains(":agent/" + agentId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/agents/")
                .then()
                .statusCode(200)
                .body("agentSummaries.agentId", hasItem(agentId))
                .body("agentSummaries.agentName", hasItem("lifecycle-agent"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(agentArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"alchemy::id\":\"TestAgent\"}}")
                .when()
                .post("/tags/" + encode(agentArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(agentArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"))
                .body("tags.'alchemy::id'", equalTo("TestAgent"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/agents/" + agentId + "/")
                .then()
                .statusCode(200)
                .body("agentId", equalTo(agentId))
                .body("agentStatus", equalTo("PREPARED"))
                .body("preparedAt", notNullValue());

        String aliasId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentAliasName":"prod-alias",
                          "description":"prod alias"
                        }
                        """)
                .when()
                .put("/agents/" + agentId + "/agentaliases/")
                .then()
                .statusCode(200)
                .body("agentAlias.agentAliasId", notNullValue())
                .body("agentAlias.agentAliasStatus", equalTo("PREPARED"))
                .body("agentAlias.routingConfiguration[0].agentVersion", equalTo("1"))
                .extract().path("agentAlias.agentAliasId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/agents/" + agentId + "/agentaliases/" + aliasId + "/")
                .then()
                .statusCode(200)
                .body("agentAlias.agentAliasName", equalTo("prod-alias"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentName":"lifecycle-agent",
                          "foundationModel":"us.amazon.nova-micro-v1:0",
                          "instruction":"%s Always cite your reasoning.",
                          "description":"alchemy bedrock agent test (updated)",
                          "agentResourceRoleArn":"%s"
                        }
                        """.formatted(INSTRUCTION, roleArn))
                .when()
                .put("/agents/" + agentId + "/")
                .then()
                .statusCode(200)
                .body("agent.description", equalTo("alchemy bedrock agent test (updated)"))
                .body("agent.agentStatus", equalTo("NOT_PREPARED"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/agents/" + agentId + "/")
                .then()
                .statusCode(200)
                .body("agentStatus", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/agents/" + agentId + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void invokeAgentMemoryAndRerank() {
        String authorization = auth(EAST);
        String roleArn = "arn:aws:iam::000000000401:role/BedrockAgentExecution";
        String agentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "agentName":"runtime-agent",
                          "foundationModel":"us.amazon.nova-micro-v1:0",
                          "instruction":"%s",
                          "agentResourceRoleArn":"%s",
                          "memoryConfiguration":{"enabledMemoryTypes":["SESSION_SUMMARY"],"storageDays":30}
                        }
                        """.formatted(INSTRUCTION, roleArn))
                .when()
                .put("/agents/")
                .then()
                .statusCode(200)
                .extract().path("agent.agentId");

        String aliasId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"agentAliasName\":\"runtime-alias\"}")
                .when()
                .put("/agents/" + agentId + "/agentaliases/")
                .then()
                .statusCode(200)
                .extract().path("agentAlias.agentAliasId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"inputText\":\"What is the capital of France?\"}")
                .when()
                .post("/agents/" + agentId + "/agentAliases/" + aliasId
                        + "/sessions/sess-1/text")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.containsString(
                        "application/vnd.amazon.eventstream"))
                .header("x-amz-bedrock-agent-session-id", equalTo("sess-1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/agents/" + agentId + "/agentAliases/" + aliasId
                        + "/memories?memoryType=SESSION_SUMMARY&memoryId=alchemy-bindings-test-memory")
                .then()
                .statusCode(200)
                .body("memoryContents.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/agents/" + agentId + "/agentAliases/" + aliasId
                        + "/memories?memoryId=alchemy-bindings-test-memory")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "queries":[{"type":"TEXT","textQuery":{"text":"What is alchemy?"}}],
                          "sources":[
                            {"type":"INLINE","inlineDocumentSource":{"type":"TEXT","textDocument":{"text":"alchemy is an infrastructure-as-effects framework"}}},
                            {"type":"INLINE","inlineDocumentSource":{"type":"TEXT","textDocument":{"text":"bananas are yellow"}}}
                          ],
                          "rerankingConfiguration":{"type":"BEDROCK_RERANKING_MODEL","bedrockRerankingConfiguration":{"modelConfiguration":{"modelArn":"arn:aws:bedrock:us-east-1::foundation-model/amazon.rerank-v1:0"}}}
                        }
                        """)
                .when()
                .post("/rerank")
                .then()
                .statusCode(200)
                .body("results.size()", equalTo(2))
                .body("results[0].index", equalTo(0));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000401/20260205/" + region + "/bedrock/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
