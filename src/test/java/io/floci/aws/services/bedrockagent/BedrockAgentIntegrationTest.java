package io.floci.aws.services.bedrockagent;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Agents for Amazon Bedrock: the agent lifecycle with aliases and tags,
 * knowledge bases with S3 ingestion and Retrieve, and the agent runtime.
 */
@QuarkusTest
class BedrockAgentIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/agent";
    private static final String MODEL = "us.amazon.nova-micro-v1:0";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body);
    }

    private static RequestSpecification bare() {
        return given().header("Authorization", AUTH);
    }

    @Test
    void anAgentIsPreparedAliasedTaggedUpdatedAndDeleted() {
        String agentId = json("""
                {"agentName":"helper","foundationModel":"%s","instruction":"Be brief.","agentResourceRoleArn":"%s",
                 "memoryConfiguration":{"enabledMemoryTypes":["SESSION_SUMMARY"],"storageDays":30},
                 "tags":{"Environment":"test"}}""".formatted(MODEL, ROLE))
            .when().put("/agents/").then()
            .statusCode(202)
            .body("agent.agentStatus", equalTo("NOT_PREPARED"))
            .body("agent.agentArn", containsString(":agent/"))
            .extract().path("agent.agentId");
        String arn = bare().when().get("/agents/" + agentId + "/").then().statusCode(200)
            .extract().path("agent.agentArn");

        json("{}").when().put("/agents/" + agentId + "/agentaliases/").then().statusCode(400);
        json("{\"agentAliasName\":\"early\"}").when().put("/agents/" + agentId + "/agentaliases/").then()
            .statusCode(400).body("__type", containsString("ValidationException"));

        bare().when().post("/agents/" + agentId + "/").then()
            .statusCode(202).body("agentStatus", equalTo("PREPARING"));
        bare().when().get("/agents/" + agentId + "/").then().body("agent.agentStatus", equalTo("PREPARED"));

        String aliasId = json("{\"agentAliasName\":\"prod\",\"description\":\"prod alias\"}")
            .when().put("/agents/" + agentId + "/agentaliases/").then()
            .statusCode(202)
            .body("agentAlias.routingConfiguration[0].agentVersion", equalTo("1"))
            .extract().path("agentAlias.agentAliasId");
        bare().when().get("/agents/" + agentId + "/agentaliases/" + aliasId + "/").then()
            .statusCode(200).body("agentAlias.agentAliasStatus", equalTo("PREPARED"));
        json("{}").when().post("/agents/" + agentId + "/agentversions/").then()
            .statusCode(200).body("agentVersionSummaries.agentVersion", contains("DRAFT", "1"));

        bare().when().get("/tags/" + arn).then()
            .statusCode(200).body("tags.Environment", equalTo("test"));
        json("{\"tags\":{\"team\":\"ml\"}}").when().post("/tags/" + arn).then().statusCode(200);
        bare().queryParam("tagKeys", "Environment").when().delete("/tags/" + arn).then().statusCode(200);
        bare().when().get("/tags/" + arn).then().body("tags", equalTo(Map.of("team", "ml")));

        String session = "s-1";
        String stream = json("{\"inputText\":\"What is Floci?\",\"memoryId\":\"m-1\",\"endSession\":false}")
            .when().post("/agents/" + agentId + "/agentAliases/" + aliasId + "/sessions/" + session + "/text").then()
            .statusCode(200)
            .header("x-amz-bedrock-agent-session-id", equalTo(session))
            .extract().asString();
        assertThat(stream, containsString(":event-type"));
        assertThat(stream, containsString("chunk"));
        json("{\"inputText\":\"Thanks\",\"memoryId\":\"m-1\",\"endSession\":true}")
            .when().post("/agents/" + agentId + "/agentAliases/" + aliasId + "/sessions/" + session + "/text").then()
            .statusCode(200);
        bare().queryParam("memoryType", "SESSION_SUMMARY").queryParam("memoryId", "m-1")
            .when().get("/agents/" + agentId + "/agentAliases/" + aliasId + "/memories").then()
            .statusCode(200)
            .body("memoryContents[0].sessionSummary.sessionId", equalTo(session))
            .body("memoryContents[0].sessionSummary.summaryText", containsString("What is Floci?"));
        bare().queryParam("memoryId", "m-1")
            .when().delete("/agents/" + agentId + "/agentAliases/" + aliasId + "/memories").then().statusCode(202);
        bare().queryParam("memoryType", "SESSION_SUMMARY").queryParam("memoryId", "m-1")
            .when().get("/agents/" + agentId + "/agentAliases/" + aliasId + "/memories").then()
            .body("memoryContents", hasSize(0));

        json("""
                {"agentName":"helper","foundationModel":"%s","agentResourceRoleArn":"%s","description":"v2"}"""
                .formatted(MODEL, ROLE))
            .when().put("/agents/" + agentId + "/").then()
            .statusCode(202).body("agent.description", equalTo("v2")).body("agent.agentStatus", equalTo("NOT_PREPARED"));

        bare().when().delete("/agents/" + agentId + "/").then()
            .statusCode(409).body("__type", containsString("ConflictException"));
        bare().when().delete("/agents/" + agentId + "/agentaliases/" + aliasId + "/").then().statusCode(202);
        bare().when().delete("/agents/" + agentId + "/").then()
            .statusCode(202).body("agentStatus", equalTo("DELETING"));
        bare().when().get("/agents/" + agentId + "/").then()
            .statusCode(404).body("__type", containsString("ResourceNotFoundException"));
        bare().when().get("/agents/AAAAAAAAAA/").then().statusCode(404);
    }

    @Test
    void aKnowledgeBaseIngestsS3AndAnswersRetrieve() {
        s3Service.createBucket("kb-docs", "us-east-1");
        s3Service.putObject("kb-docs", "docs/alchemy.txt",
                "Alchemy deploys infrastructure as Effect programs.".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3Service.putObject("kb-docs", "docs/bananas.txt",
                "Bananas are yellow fruit rich in potassium.".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        String kbId = json("""
                {"name":"docs","roleArn":"%s","knowledgeBaseConfiguration":{"type":"VECTOR",
                 "vectorKnowledgeBaseConfiguration":{"embeddingModelArn":"arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2:0"}},
                 "storageConfiguration":{"type":"OPENSEARCH_SERVERLESS","opensearchServerlessConfiguration":{}}}"""
                .formatted(ROLE))
            .when().put("/knowledgebases/").then()
            .statusCode(202).body("knowledgeBase.status", equalTo("CREATING"))
            .extract().path("knowledgeBase.knowledgeBaseId");
        bare().when().get("/knowledgebases/" + kbId).then().statusCode(200).body("knowledgeBase.status", equalTo("ACTIVE"));

        String dsId = json("""
                {"name":"s3","dataSourceConfiguration":{"type":"S3","s3Configuration":{"bucketArn":"arn:aws:s3:::kb-docs",
                 "inclusionPrefixes":["docs/"]}}}""")
            .when().put("/knowledgebases/" + kbId + "/datasources/").then()
            .statusCode(200).body("dataSource.status", equalTo("AVAILABLE"))
            .extract().path("dataSource.dataSourceId");
        String jobId = json("{}").when().put("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
            .then().statusCode(202).extract().path("ingestionJob.ingestionJobId");
        bare().when().get("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/" + jobId).then()
            .statusCode(200)
            .body("ingestionJob.status", equalTo("COMPLETE"))
            .body("ingestionJob.statistics.numberOfNewDocumentsIndexed", equalTo(2));

        json("{\"retrievalQuery\":{\"text\":\"How does Alchemy deploy infrastructure?\"}}")
            .when().post("/knowledgebases/" + kbId + "/retrieve").then()
            .statusCode(200)
            .body("retrievalResults", hasSize(1))
            .body("retrievalResults[0].location.s3Location.uri", equalTo("s3://kb-docs/docs/alchemy.txt"));

        json("""
                {"documents":[{"content":{"dataSourceType":"CUSTOM","custom":{"customDocumentIdentifier":{"id":"note-1"},
                 "sourceType":"IN_LINE","inlineContent":{"type":"TEXT","textContent":{"data":"Floci emulates AWS locally."}}}}}]}""")
            .when().put("/knowledgebases/" + kbId + "/datasources/" + dsId + "/documents").then()
            .statusCode(202).body("documentDetails[0].status", equalTo("INDEXED"));
        json("{}").when().post("/knowledgebases/" + kbId + "/datasources/" + dsId + "/documents").then()
            .statusCode(200).body("documentDetails", hasSize(3));

        json("""
                {"input":{"text":"What does Floci emulate?"},"retrieveAndGenerateConfiguration":{"type":"KNOWLEDGE_BASE",
                 "knowledgeBaseConfiguration":{"knowledgeBaseId":"%s",
                 "modelArn":"arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0"}}}""".formatted(kbId))
            .when().post("/retrieveAndGenerate").then()
            .statusCode(200)
            .body("output.text", not(emptyString()))
            .body("citations[0].retrievedReferences[0].location.customDocumentLocation.id", equalTo("note-1"));

        bare().when().get("/knowledgebases/AAAAAAAAAA").then().statusCode(404);
        json("{}").when().post("/knowledgebases/AAAAAAAAAA/datasources/BBBBBBBBBB/ingestionjobs/").then()
            .statusCode(404);
        bare().when().delete("/knowledgebases/" + kbId + "/datasources/" + dsId).then().statusCode(202);
        bare().when().delete("/knowledgebases/" + kbId).then().statusCode(202);
    }

    @Test
    void rerankOrdersDocumentsByRelevance() {
        json("""
                {"queries":[{"type":"TEXT","textQuery":{"text":"What is Alchemy?"}}],
                 "sources":[
                   {"type":"INLINE","inlineDocumentSource":{"type":"TEXT","textDocument":{"text":"Bananas are yellow."}}},
                   {"type":"INLINE","inlineDocumentSource":{"type":"TEXT","textDocument":{"text":"Alchemy is an infrastructure framework."}}}],
                 "rerankingConfiguration":{"type":"BEDROCK_RERANKING_MODEL","bedrockRerankingConfiguration":{
                   "modelConfiguration":{"modelArn":"arn:aws:bedrock:us-east-1::foundation-model/amazon.rerank-v1:0"}}}}""")
            .when().post("/rerank").then()
            .statusCode(200)
            .body("results.index", contains(1, 0))
            .body("results[0].relevanceScore", greaterThan(0.5f));
        json("""
                {"queries":[{"type":"TEXT","textQuery":{"text":"x"}}],
                 "sources":[{"type":"INLINE","inlineDocumentSource":{"type":"TEXT","textDocument":{"text":"x"}}}],
                 "rerankingConfiguration":{"type":"BEDROCK_RERANKING_MODEL","bedrockRerankingConfiguration":{
                   "modelConfiguration":{"modelArn":"arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0"}}}}""")
            .when().post("/rerank").then().statusCode(400);
    }
}
