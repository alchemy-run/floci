package io.github.hectorvent.floci.services.keyspaces;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 Keyspaces coverage used by Alchemy Keyspace.test.ts:
 * create, tag upsert, delete, and GetKeyspace-not-found.
 */
@QuarkusTest
class KeyspacesIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cassandra/aws4_request";
    private static final String TARGET = "KeyspacesService.";
    private static final String NAME = "alchemy_ks_test";

    static {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKeyspace_missing_returnsResourceNotFoundException() {
        keyspaces("GetKeyspace", "{\"keyspaceName\":\"" + NAME + "_missing\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createTagUpdateDeleteKeyspace() {
        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + NAME + "\"}");

        String arn = keyspaces("CreateKeyspace", """
                {
                  "keyspaceName": "%s",
                  "tags": [{"key": "team", "value": "platform"}]
                }
                """.formatted(NAME))
                .then()
                .statusCode(200)
                .body("resourceArn", startsWith("arn:aws:cassandra:"))
                .body("resourceArn", org.hamcrest.Matchers.containsString("keyspace/" + NAME))
                .extract().path("resourceArn");

        keyspaces("GetKeyspace", "{\"keyspaceName\":\"" + NAME + "\"}")
                .then()
                .statusCode(200)
                .body("keyspaceName", equalTo(NAME))
                .body("resourceArn", equalTo(arn))
                .body("replicationStrategy", equalTo("SINGLE_REGION"));

        keyspaces("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("team"))
                .body("tags.find { it.key == 'team' }.value", equalTo("platform"));

        keyspaces("TagResource", """
                {"resourceArn":"%s","tags":[{"key":"team","value":"data"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        keyspaces("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.find { it.key == 'team' }.value", equalTo("data"));

        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"" + NAME + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + NAME + "\"}")
                .then()
                .statusCode(200);

        keyspaces("GetKeyspace", "{\"keyspaceName\":\"" + NAME + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listKeyspaces_includesCreated() {
        String name = NAME + "_list";
        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + name + "\"}");
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"" + name + "\"}")
                .then()
                .statusCode(200)
                .body("resourceArn", notNullValue());

        keyspaces("ListKeyspaces", "{}")
                .then()
                .statusCode(200)
                .body("keyspaces.keyspaceName", hasItem(name));

        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + name + "\"}")
                .then()
                .statusCode(200);

        keyspaces("ListKeyspaces", "{}")
                .then()
                .statusCode(200)
                .body("keyspaces.keyspaceName", not(hasItem(name)));
    }

    private static Response keyspaces(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
