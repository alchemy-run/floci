package io.github.hectorvent.floci.services.bedrockagent;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies Bedrock Agent restJson1 knowledge-base lifecycle, documents, jobs, and retrieve. */
@QuarkusTest
class BedrockAgentKnowledgeBaseIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/service-role/AmazonBedrockExecutionRoleForKnowledgeBase";
    private static final String EMBEDDING =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2:0";
    private static final String COLLECTION =
            "arn:aws:aoss:us-east-1:000000000000:collection/bedrock-kb";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKnowledgeBaseOnAMissingIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/knowledgebases/AAAAAAAAAA")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void knowledgeBaseDataSourceDocumentsJobsRetrieveAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String name = "kb-" + suffix();
        String kbId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(kbBody(name, "initial"))
                .when()
                .put("/knowledgebases/")
                .then()
                .statusCode(200)
                .body("knowledgeBase.status", equalTo("ACTIVE"))
                .body("knowledgeBase.name", equalTo(name))
                .body("knowledgeBase.knowledgeBaseArn", startsWith("arn:aws:bedrock:"))
                .extract().path("knowledgeBase.knowledgeBaseId");

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/knowledgebases/" + kbId)
                .then()
                .statusCode(200)
                .body("knowledgeBase.status", equalTo("ACTIVE"))
                .body("knowledgeBase.description", equalTo("initial"))
                .extract().path("knowledgeBase.knowledgeBaseArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/")
                .then()
                .statusCode(200)
                .body("knowledgeBaseSummaries.knowledgeBaseId", hasItem(kbId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(kbBody(name, "updated"))
                .when()
                .put("/knowledgebases/" + kbId)
                .then()
                .statusCode(200)
                .body("knowledgeBase.description", equalTo("updated"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Environment\":\"test\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        String dsId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"docs-%s",
                          "dataSourceConfiguration":{"type":"S3","s3Configuration":{"bucketArn":"arn:aws:s3:::kb-docs"}},
                          "dataDeletionPolicy":"DELETE"
                        }
                        """.formatted(suffix()))
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/")
                .then()
                .statusCode(200)
                .body("dataSource.status", equalTo("AVAILABLE"))
                .extract().path("dataSource.dataSourceId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/knowledgebases/" + kbId + "/datasources/" + dsId)
                .then()
                .statusCode(200)
                .body("dataSource.status", equalTo("AVAILABLE"))
                .body("dataSource.dataDeletionPolicy", equalTo("DELETE"));

        String customId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"custom-%s",
                          "dataSourceConfiguration":{"type":"CUSTOM"},
                          "dataDeletionPolicy":"DELETE"
                        }
                        """.formatted(suffix()))
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/")
                .then()
                .statusCode(200)
                .extract().path("dataSource.dataSourceId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "documents":[{
                            "content":{
                              "dataSourceType":"CUSTOM",
                              "custom":{
                                "customDocumentIdentifier":{"id":"welcome-doc"},
                                "sourceType":"IN_LINE",
                                "inlineContent":{"type":"TEXT","textContent":{"data":"Alchemy is an Infrastructure-as-Effects framework."}}
                              }
                            }
                          }]
                        }
                        """)
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/" + customId + "/documents")
                .then()
                .statusCode(200)
                .body("documentDetails[0].status", equalTo("INDEXED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"documentIdentifiers":[{"dataSourceType":"CUSTOM","custom":{"id":"welcome-doc"}}]}
                        """)
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + customId + "/documents/getDocuments")
                .then()
                .statusCode(200)
                .body("documentDetails[0].status", equalTo("INDEXED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + customId + "/documents")
                .then()
                .statusCode(200)
                .body("documentDetails.size()", greaterThan(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"retrievalQuery\":{\"text\":\"What is Alchemy?\"}}")
                .when()
                .post("/knowledgebases/" + kbId + "/retrieve")
                .then()
                .statusCode(200)
                .body("retrievalResults.size()", greaterThan(0))
                .body("retrievalResults[0].content.text", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "input":{"text":"What is Alchemy?"},
                          "retrieveAndGenerateConfiguration":{
                            "type":"KNOWLEDGE_BASE",
                            "knowledgeBaseConfiguration":{"knowledgeBaseId":"%s","modelArn":"us.amazon.nova-micro-v1:0"}
                          }
                        }
                        """.formatted(kbId))
                .when()
                .post("/retrieveAndGenerate")
                .then()
                .statusCode(200)
                .body("output.text", notNullValue())
                .body("sessionId", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "input":{"text":"What is Alchemy?"},
                          "retrieveAndGenerateConfiguration":{
                            "type":"KNOWLEDGE_BASE",
                            "knowledgeBaseConfiguration":{"knowledgeBaseId":"%s","modelArn":"us.amazon.nova-micro-v1:0"}
                          }
                        }
                        """.formatted(kbId))
                .when()
                .post("/retrieveAndGenerateStream")
                .then()
                .statusCode(200)
                .header("x-amzn-bedrock-knowledge-base-session-id", notNullValue())
                .header("Content-Type", containsString("application/vnd.amazon.eventstream"));

        String jobId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
                .then()
                .statusCode(200)
                .body("ingestionJob.status", equalTo("COMPLETE"))
                .extract().path("ingestionJob.ingestionJobId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/" + jobId)
                .then()
                .statusCode(200)
                .body("ingestionJob.status", equalTo("COMPLETE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
                .then()
                .statusCode(200)
                .body("ingestionJobSummaries.size()", greaterThan(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"documentIdentifiers":[{"dataSourceType":"CUSTOM","custom":{"id":"welcome-doc"}}]}
                        """)
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + customId + "/documents/deleteDocuments")
                .then()
                .statusCode(200)
                .body("documentDetails[0].status", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/knowledgebases/" + kbId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/knowledgebases/" + kbId + "/datasources/" + dsId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/knowledgebases/" + kbId + "/datasources/" + customId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/knowledgebases/" + kbId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/knowledgebases/" + kbId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String kbBody(String name, String description) {
        return """
                {
                  "name":"%s",
                  "description":"%s",
                  "roleArn":"%s",
                  "knowledgeBaseConfiguration":{
                    "type":"VECTOR",
                    "vectorKnowledgeBaseConfiguration":{"embeddingModelArn":"%s"}
                  },
                  "storageConfiguration":{
                    "type":"OPENSEARCH_SERVERLESS",
                    "opensearchServerlessConfiguration":{
                      "collectionArn":"%s",
                      "vectorIndexName":"bedrock-index",
                      "fieldMapping":{"vectorField":"bedrock-vector","textField":"bedrock-text","metadataField":"bedrock-metadata"}
                    }
                  },
                  "tags":{"Environment":"test"}
                }
                """.formatted(name, description, ROLE, EMBEDDING, COLLECTION);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/bedrock/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
