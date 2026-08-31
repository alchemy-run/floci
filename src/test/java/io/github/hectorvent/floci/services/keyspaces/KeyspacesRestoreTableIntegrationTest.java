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
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 Keyspaces RestoreTable coverage used by Alchemy RestoreTable.test.ts:
 * a missing source table returns ResourceNotFoundException; a PITR source
 * restores into a new table whose ARN uses the cassandra /keyspace/.../table/
 * shape.
 */
@QuarkusTest
class KeyspacesRestoreTableIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "KeyspacesService.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cassandra/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void restoreTable_missingSource_returnsResourceNotFoundException() {
        keyspaces("RestoreTable", """
                {
                  "sourceKeyspaceName":"alchemy_nonexistent_ks",
                  "sourceTableName":"nonexistent_tbl",
                  "targetKeyspaceName":"alchemy_nonexistent_ks",
                  "targetTableName":"restored_tbl"
                }
                """)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void restoreTable_pitrSource_copiesSchemaIntoNewTable() {
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"alchemy_restore_test_ks\"}")
                .then()
                .statusCode(200)
                .body("resourceArn", startsWith("arn:aws:cassandra:"))
                .body("resourceArn", containsString("/keyspace/alchemy_restore_test_ks/"));

        keyspaces("CreateTable", """
                {
                  "keyspaceName":"alchemy_restore_test_ks",
                  "tableName":"orders",
                  "schemaDefinition":{
                    "allColumns":[
                      {"name":"id","type":"uuid"},
                      {"name":"total","type":"int"}
                    ],
                    "partitionKeys":[{"name":"id"}]
                  },
                  "pointInTimeRecovery":{"status":"ENABLED"}
                }
                """)
                .then()
                .statusCode(200);

        keyspaces("RestoreTable", """
                {
                  "sourceKeyspaceName":"alchemy_restore_test_ks",
                  "sourceTableName":"orders",
                  "targetKeyspaceName":"alchemy_restore_test_ks",
                  "targetTableName":"orders_restored"
                }
                """)
                .then()
                .statusCode(200)
                .body("restoredTableARN", startsWith("arn:aws:cassandra:"))
                .body("restoredTableARN",
                        containsString("/keyspace/alchemy_restore_test_ks/table/orders_restored"));

        keyspaces("GetTable", """
                {"keyspaceName":"alchemy_restore_test_ks","tableName":"orders_restored"}
                """)
                .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("schemaDefinition.allColumns[0].name", equalTo("id"))
                .body("schemaDefinition.allColumns[1].name", equalTo("total"))
                .body("resourceArn",
                        containsString("/keyspace/alchemy_restore_test_ks/table/orders_restored"));

        keyspaces("RestoreTable", """
                {
                  "sourceKeyspaceName":"alchemy_restore_test_ks",
                  "sourceTableName":"orders",
                  "targetKeyspaceName":"alchemy_restore_test_ks",
                  "targetTableName":"orders_restored"
                }
                """)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void restoreTable_withoutPitr_returnsValidationException() {
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"alchemy_restore_nopitr_ks\"}")
                .then()
                .statusCode(200);
        keyspaces("CreateTable", """
                {
                  "keyspaceName":"alchemy_restore_nopitr_ks",
                  "tableName":"plain",
                  "schemaDefinition":{
                    "allColumns":[{"name":"id","type":"uuid"}],
                    "partitionKeys":[{"name":"id"}]
                  }
                }
                """)
                .then()
                .statusCode(200);

        keyspaces("RestoreTable", """
                {
                  "sourceKeyspaceName":"alchemy_restore_nopitr_ks",
                  "sourceTableName":"plain",
                  "targetKeyspaceName":"alchemy_restore_nopitr_ks",
                  "targetTableName":"plain_restored"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
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
