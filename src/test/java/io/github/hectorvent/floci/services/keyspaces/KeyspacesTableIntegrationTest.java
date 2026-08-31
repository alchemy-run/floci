package io.github.hectorvent.floci.services.keyspaces;

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
import static org.hamcrest.Matchers.startsWith;

/**
 * Table CRUD matching Alchemy's Keyspaces Table resource: create with PITR,
 * add a column in place, delete.
 */
@QuarkusTest
class KeyspacesTableIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cassandra/aws4_request";
    private static final String TARGET = "KeyspacesService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTable_missing_returnsResourceNotFoundException() {
        keyspaces("GetTable", """
                {"keyspaceName":"missing_ks","tableName":"missing_table"}
                """)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void tableLifecycleCreateAddColumnDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).replace("-", "_");
        String keyspaceName = "floci_tbl_" + suffix;
        String tableName = "events";

        keyspaces("CreateKeyspace", """
                {"keyspaceName":"%s","tags":[{"key":"fixture","value":"keyspaces-table"}]}
                """.formatted(keyspaceName))
                .then()
                .statusCode(200)
                .body("resourceArn", startsWith("arn:aws:cassandra:"));

        keyspaces("CreateTable", """
                {
                  "keyspaceName": "%s",
                  "tableName": "%s",
                  "schemaDefinition": {
                    "allColumns": [
                      {"name": "device", "type": "text"},
                      {"name": "ts", "type": "timestamp"},
                      {"name": "payload", "type": "blob"}
                    ],
                    "partitionKeys": [{"name": "device"}],
                    "clusteringKeys": [{"name": "ts", "orderBy": "DESC"}]
                  },
                  "pointInTimeRecovery": {"status": "ENABLED"},
                  "tags": [{"key": "fixture", "value": "keyspaces-table"}]
                }
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(200)
                .body("resourceArn", startsWith("arn:aws:cassandra:"));

        String arn = keyspaces("GetTable", """
                {"keyspaceName":"%s","tableName":"%s"}
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(200)
                .body("tableName", equalTo(tableName))
                .body("status", equalTo("ACTIVE"))
                .body("pointInTimeRecovery.status", equalTo("ENABLED"))
                .body("schemaDefinition.allColumns.name", hasItem("device"))
                .body("schemaDefinition.allColumns.name", hasItem("payload"))
                .extract().path("resourceArn");

        keyspaces("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("fixture"));

        keyspaces("UpdateTable", """
                {
                  "keyspaceName": "%s",
                  "tableName": "%s",
                  "addColumns": [{"name": "region", "type": "text"}]
                }
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(200)
                .body("resourceArn", equalTo(arn));

        keyspaces("GetTable", """
                {"keyspaceName":"%s","tableName":"%s"}
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(200)
                .body("schemaDefinition.allColumns.name", hasItem("region"));

        keyspaces("DeleteTable", """
                {"keyspaceName":"%s","tableName":"%s"}
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(200);

        keyspaces("GetTable", """
                {"keyspaceName":"%s","tableName":"%s"}
                """.formatted(keyspaceName, tableName))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"" + keyspaceName + "\"}")
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
