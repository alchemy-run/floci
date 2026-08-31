package io.github.hectorvent.floci.services.bedrockagent;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Bedrock Agent restJson1 knowledge-base, data-source, and ingestion-job
 * APIs, including ResourceNotFoundException on missing parents.
 */
@QuarkusTest
class BedrockKnowledgeBaseIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING_KB = "AAAAAAAAAA";
    private static final String MISSING_DS = "BBBBBBBBBB";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKnowledgeBaseOnAMissingIdReturnsResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/knowledgebases/" + MISSING_KB)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getDataSourceOnAMissingIdReturnsResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/knowledgebases/" + MISSING_KB + "/datasources/" + MISSING_DS)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listIngestionJobsOnAMissingDataSourceReturnsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/knowledgebases/" + MISSING_KB + "/datasources/" + MISSING_DS + "/ingestionjobs/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listKnowledgeBaseDocumentsOnAMissingDataSourceReturnsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/knowledgebases/" + MISSING_KB + "/datasources/" + MISSING_DS + "/documents")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createKnowledgeBaseAndDataSourceThenListIngestionJobsIsEmpty() {
        String authorization = auth(EAST);
        String kbId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"kb-lifecycle",
                          "roleArn":"arn:aws:iam::000000000000:role/bedrock-kb",
                          "knowledgeBaseConfiguration":{"type":"VECTOR"}
                        }
                        """)
                .when()
                .put("/knowledgebases/")
                .then()
                .statusCode(200)
                .body("knowledgeBase.knowledgeBaseId", notNullValue())
                .body("knowledgeBase.status", equalTo("ACTIVE"))
                .extract().path("knowledgeBase.knowledgeBaseId");

        String dsId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"ds-lifecycle",
                          "dataSourceConfiguration":{"type":"S3","s3Configuration":{"bucketArn":"arn:aws:s3:::docs"}}
                        }
                        """)
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/")
                .then()
                .statusCode(200)
                .body("dataSource.dataSourceId", notNullValue())
                .body("dataSource.status", equalTo("AVAILABLE"))
                .extract().path("dataSource.dataSourceId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
                .then()
                .statusCode(200)
                .body("ingestionJobSummaries", empty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + dsId + "/documents")
                .then()
                .statusCode(200)
                .body("documentDetails", empty());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .put("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
                .then()
                .statusCode(200)
                .body("ingestionJob.ingestionJobId", notNullValue())
                .body("ingestionJob.status", equalTo("COMPLETE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/knowledgebases/" + kbId + "/datasources/" + dsId + "/ingestionjobs/")
                .then()
                .statusCode(200)
                .body("ingestionJobSummaries", hasSize(1))
                .body("ingestionJobSummaries[0].status", equalTo("COMPLETE"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/bedrock/aws4_request";
    }
}
