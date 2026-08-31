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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Collection group CRUD matching Alchemy's CollectionGroup resource:
 * create, batch-get, list tags, update description, delete, verify gone.
 */
@QuarkusTest
class CollectionGroupIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/aoss/aws4_request";
    private static final String TARGET = "OpenSearchServerless.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void batchGetCollectionGroup_missing_returnsErrorDetails() {
        aoss("BatchGetCollectionGroup", "{\"names\":[\"missing-group-name\"]}")
                .then()
                .statusCode(200)
                .body("collectionGroupErrorDetails[0].name", equalTo("missing-group-name"))
                .body("collectionGroupErrorDetails[0].errorCode", equalTo("NOT_FOUND"));
    }

    @Test
    void collectionGroupLifecycleCreateListTagUpdateDelete() {
        String name = "floci-aoss-cg-" + UUID.randomUUID().toString().substring(0, 8);

        String id = aoss("CreateCollectionGroup", """
                {
                  "name": "%s",
                  "standbyReplicas": "DISABLED",
                  "description": "alchemy test group",
                  "tags": [{"key": "purpose", "value": "alchemy-test"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("createCollectionGroupDetail.id", notNullValue())
                .body("createCollectionGroupDetail.name", equalTo(name))
                .body("createCollectionGroupDetail.standbyReplicas", equalTo("DISABLED"))
                .body("createCollectionGroupDetail.arn", startsWith("arn:aws:aoss:"))
                .extract().path("createCollectionGroupDetail.id");

        String arn = aoss("BatchGetCollectionGroup", "{\"names\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("collectionGroupDetails[0].id", equalTo(id))
                .body("collectionGroupDetails[0].name", equalTo(name))
                .body("collectionGroupDetails[0].description", equalTo("alchemy test group"))
                .body("collectionGroupDetails[0].standbyReplicas", equalTo("DISABLED"))
                .extract().path("collectionGroupDetails[0].arn");

        aoss("ListCollectionGroups", "{}")
                .then()
                .statusCode(200)
                .body("collectionGroupSummaries.id", hasItem(id));

        aoss("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("purpose"));

        aoss("TagResource", """
                {"resourceArn":"%s","tags":[{"key":"alchemy::stage","value":"testing"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        aoss("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("purpose"))
                .body("tags.key", hasItem("alchemy::stage"));

        aoss("UpdateCollectionGroup", """
                {"id":"%s","description":"alchemy test group v2"}
                """.formatted(id))
                .then()
                .statusCode(200)
                .body("updateCollectionGroupDetail.id", equalTo(id))
                .body("updateCollectionGroupDetail.description", equalTo("alchemy test group v2"));

        aoss("BatchGetCollectionGroup", "{\"names\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("collectionGroupDetails[0].description", equalTo("alchemy test group v2"));

        aoss("CreateCollectionGroup", """
                {"name":"%s","standbyReplicas":"DISABLED"}
                """.formatted(name))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        aoss("DeleteCollectionGroup", "{\"id\":\"" + id + "\"}")
                .then()
                .statusCode(200);

        aoss("BatchGetCollectionGroup", "{\"names\":[\"" + name + "\"]}")
                .then()
                .statusCode(200)
                .body("collectionGroupDetails.id", not(hasItem(id)))
                .body("collectionGroupErrorDetails[0].errorCode", equalTo("NOT_FOUND"));

        aoss("DeleteCollectionGroup", "{\"id\":\"" + id + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
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
