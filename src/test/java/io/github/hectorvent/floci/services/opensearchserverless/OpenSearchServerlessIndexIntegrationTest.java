package io.github.hectorvent.floci.services.opensearchserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Index CRUD matching Alchemy's OpenSearch Serverless IndexBindings fixture:
 * CreateIndex / GetIndex / UpdateIndex / DeleteIndex against an ACTIVE
 * collection, plus typed ConflictException and ResourceNotFoundException.
 */
@QuarkusTest
class OpenSearchServerlessIndexIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/aoss/aws4_request";
    private static final String TARGET = "OpenSearchServerless.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getIndex_missingCollection_returnsResourceNotFoundException() {
        aoss("GetIndex", "{\"id\":\"missing-collection-id\",\"indexName\":\"alchemy-roundtrip\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void indexCreateReadUpdateDeleteRoundtrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String collectionName = "floci-aoss-idx-" + suffix;
        String encName = "floci-enc-idx-" + suffix;
        String indexName = "alchemy-roundtrip";

        aoss("CreateSecurityPolicy", """
                {
                  "type": "encryption",
                  "name": "%s",
                  "policy": "{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/%s\\"]}],\\"AWSOwnedKey\\":true}"
                }
                """.formatted(encName, collectionName))
                .then()
                .statusCode(200);

        String collectionId = aoss("CreateCollection", """
                {
                  "name": "%s",
                  "type": "SEARCH",
                  "standbyReplicas": "DISABLED"
                }
                """.formatted(collectionName))
                .then()
                .statusCode(200)
                .body("createCollectionDetail.status", equalTo("ACTIVE"))
                .extract().path("createCollectionDetail.id");

        aoss("GetIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        aoss("CreateIndex", """
                {
                  "id": "%s",
                  "indexName": "%s",
                  "indexSchema": {"mappings":{"properties":{"title":{"type":"text"}}}}
                }
                """.formatted(collectionId, indexName))
                .then()
                .statusCode(200);

        aoss("GetIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(200)
                .body("indexSchema", notNullValue())
                .body("indexSchema.mappings.properties.title.type", equalTo("text"));

        aoss("CreateIndex", """
                {
                  "id": "%s",
                  "indexName": "%s",
                  "indexSchema": {"mappings":{"properties":{"title":{"type":"text"}}}}
                }
                """.formatted(collectionId, indexName))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        aoss("UpdateIndex", """
                {
                  "id": "%s",
                  "indexName": "%s",
                  "indexSchema": {"mappings":{"properties":{"body":{"type":"text"}}}}
                }
                """.formatted(collectionId, indexName))
                .then()
                .statusCode(200);

        aoss("GetIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(200)
                .body("indexSchema.mappings.properties.body.type", equalTo("text"))
                .body("indexSchema.mappings.properties.title", nullValue());

        aoss("DeleteIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(200);

        aoss("GetIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        aoss("DeleteIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"" + indexName + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        aoss("DeleteCollection", "{\"id\":\"" + collectionId + "\"}")
                .then()
                .statusCode(200);
        aoss("DeleteSecurityPolicy",
                "{\"type\":\"encryption\",\"name\":\"" + encName + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response aoss(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
