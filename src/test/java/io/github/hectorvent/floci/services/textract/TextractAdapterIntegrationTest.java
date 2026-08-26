package io.github.hectorvent.floci.services.textract;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Textract adapter management (Create/Get/List/Update/Delete + tags).
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1, X-Amz-Target: Textract.&lt;Action&gt;
 */
@QuarkusTest
class TextractAdapterIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/textract/aws4_request";

    @Inject
    TextractService textractService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        textractService.clear();
    }

    @Test
    void adapterLifecycle_createGetListUpdateTagDelete() {
        String name = "alchemy-test-textract-adapter";
        Response created = action("CreateAdapter", """
                {
                  "AdapterName": "%s",
                  "FeatureTypes": ["QUERIES"],
                  "Description": "alchemy adapter v1",
                  "AutoUpdate": "DISABLED",
                  "Tags": {"env": "test", "alchemy::id": "TestAdapter"}
                }
                """.formatted(name));
        created.then()
                .statusCode(200)
                .body("AdapterId", notNullValue());
        String adapterId = created.jsonPath().getString("AdapterId");
        assertThat(adapterId.length(), greaterThanOrEqualTo(12));

        action("GetAdapter", "{\"AdapterId\":\"" + adapterId + "\"}")
                .then()
                .statusCode(200)
                .body("AdapterId", equalTo(adapterId))
                .body("AdapterName", equalTo(name))
                .body("Description", equalTo("alchemy adapter v1"))
                .body("AutoUpdate", equalTo("DISABLED"))
                .body("FeatureTypes", hasItem("QUERIES"))
                .body("Tags.env", equalTo("test"))
                .body("Tags.'alchemy::id'", equalTo("TestAdapter"))
                .body("CreationTime", notNullValue());

        action("ListAdapters", "{}")
                .then()
                .statusCode(200)
                .body("Adapters.AdapterId", hasItem(adapterId))
                .body("Adapters.AdapterName", hasItem(name));

        action("UpdateAdapter", """
                {
                  "AdapterId": "%s",
                  "Description": "alchemy adapter v2",
                  "AutoUpdate": "ENABLED"
                }
                """.formatted(adapterId))
                .then()
                .statusCode(200)
                .body("AdapterId", equalTo(adapterId))
                .body("Description", equalTo("alchemy adapter v2"))
                .body("AutoUpdate", equalTo("ENABLED"));

        String arn = "arn:aws:textract:us-east-1:000000000000:/adapters/" + adapterId;
        action("TagResource", """
                {
                  "ResourceARN": "%s",
                  "Tags": {"env": "test2", "extra": "1"}
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        action("UntagResource", """
                {
                  "ResourceARN": "%s",
                  "TagKeys": ["alchemy::id"]
                }
                """.formatted(arn))
                .then()
                .statusCode(200);

        action("GetAdapter", "{\"AdapterId\":\"" + adapterId + "\"}")
                .then()
                .statusCode(200)
                .body("Description", equalTo("alchemy adapter v2"))
                .body("AutoUpdate", equalTo("ENABLED"))
                .body("Tags.env", equalTo("test2"))
                .body("Tags.extra", equalTo("1"))
                .body("Tags.'alchemy::id'", nullValue());

        action("DeleteAdapter", "{\"AdapterId\":\"" + adapterId + "\"}")
                .then()
                .statusCode(200);

        action("GetAdapter", "{\"AdapterId\":\"" + adapterId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createAdapter_duplicateName_conflictException() {
        String name = "duplicate-textract-adapter";
        action("CreateAdapter", """
                {"AdapterName":"%s","FeatureTypes":["QUERIES"]}
                """.formatted(name))
                .then()
                .statusCode(200);

        action("CreateAdapter", """
                {"AdapterName":"%s","FeatureTypes":["QUERIES"]}
                """.formatted(name))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void getAdapter_missing_resourceNotFound() {
        action("GetAdapter", "{\"AdapterId\":\"missing-adapter-id-xxxx\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createAdapter_missingName_validationException() {
        action("CreateAdapter", "{\"FeatureTypes\":[\"QUERIES\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listTagsForResource_returnsAdapterTags() {
        Response created = action("CreateAdapter", """
                {
                  "AdapterName": "tagged-adapter",
                  "FeatureTypes": ["QUERIES"],
                  "Tags": {"env": "test"}
                }
                """);
        String adapterId = created.then().statusCode(200).extract().path("AdapterId");
        String arn = "arn:aws:textract:us-east-1:000000000000:/adapters/" + adapterId;
        action("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("test"));
    }

    private static Response action(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Textract." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
