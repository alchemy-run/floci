package io.github.hectorvent.floci.services.keyspaces;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Keyspaces UDT coverage used by Alchemy Type.test.ts: create a type
 * in a keyspace, replace by deleting and recreating with extra fields, then
 * delete.
 */
@QuarkusTest
class KeyspacesTypeIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cassandra/aws4_request";
    private static final String TARGET = KeyspacesJsonHandler.TARGET_PREFIX;
    private static final String KS = "alchemy_type_test_ks";
    private static final String TYPE = "address";
    private static final String TYPE_REPLACED = "address_v2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getType_missing_returnsResourceNotFoundException() {
        keyspaces("GetType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createType_missingKeyspace_returnsResourceNotFoundException() {
        keyspaces("CreateType", """
                {
                  "keyspaceName":"alchemy_missing_ks",
                  "typeName":"address",
                  "fieldDefinitions":[{"name":"street","type":"text"}]
                }
                """)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createReplaceDeleteType() {
        keyspaces("DeleteType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE + "\"}");
        keyspaces("DeleteType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE_REPLACED + "\"}");
        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + KS + "\"}");

        String arn = keyspaces("CreateKeyspace", "{\"keyspaceName\":\"" + KS + "\"}")
                .then()
                .statusCode(200)
                .body("resourceArn", containsString("keyspace/" + KS))
                .extract().path("resourceArn");

        keyspaces("CreateType", """
                {
                  "keyspaceName":"%s",
                  "typeName":"%s",
                  "fieldDefinitions":[
                    {"name":"street","type":"text"},
                    {"name":"city","type":"text"}
                  ]
                }
                """.formatted(KS, TYPE))
                .then()
                .statusCode(200)
                .body("keyspaceArn", equalTo(arn))
                .body("typeName", equalTo(TYPE));

        keyspaces("GetType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE + "\"}")
                .then()
                .statusCode(200)
                .body("keyspaceName", equalTo(KS))
                .body("typeName", equalTo(TYPE))
                .body("keyspaceArn", equalTo(arn))
                .body("status", equalTo("ACTIVE"))
                .body("fieldDefinitions.name", hasItem("street"))
                .body("fieldDefinitions.name", hasItem("city"))
                .body("lastModifiedTimestamp", notNullValue());

        keyspaces("CreateType", """
                {
                  "keyspaceName":"%s",
                  "typeName":"%s",
                  "fieldDefinitions":[{"name":"street","type":"text"}]
                }
                """.formatted(KS, TYPE))
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        keyspaces("CreateType", """
                {
                  "keyspaceName":"%s",
                  "typeName":"%s",
                  "fieldDefinitions":[
                    {"name":"street","type":"text"},
                    {"name":"city","type":"text"},
                    {"name":"zip","type":"text"}
                  ]
                }
                """.formatted(KS, TYPE_REPLACED))
                .then()
                .statusCode(200)
                .body("typeName", equalTo(TYPE_REPLACED));

        keyspaces("GetType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE_REPLACED + "\"}")
                .then()
                .statusCode(200)
                .body("fieldDefinitions.name", hasItem("zip"));

        keyspaces("DeleteType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE + "\"}")
                .then()
                .statusCode(200)
                .body("keyspaceArn", equalTo(arn))
                .body("typeName", equalTo(TYPE));

        keyspaces("GetType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        keyspaces("ListTypes", "{\"keyspaceName\":\"" + KS + "\"}")
                .then()
                .statusCode(200)
                .body("types", hasItem(TYPE_REPLACED))
                .body("types", not(hasItem(TYPE)));

        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + KS + "\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        keyspaces("DeleteType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE_REPLACED + "\"}")
                .then()
                .statusCode(200);

        keyspaces("DeleteType", "{\"keyspaceName\":\"" + KS + "\",\"typeName\":\"" + TYPE_REPLACED + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + KS + "\"}")
                .then()
                .statusCode(200);
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
