package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Connection CRUD matching Alchemy's CodeConnections Connection resource:
 * create GitHub PENDING, get, list, tags, delete, then get-not-found.
 */
@QuarkusTest
class CodeConnectionsConnectionIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "CodeConnections_20231201.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void connectionLifecycleCreateGetListTagDelete() {
        String name = "floci-cc-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateConnection")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "ConnectionName": "%s",
                          "ProviderType": "GitHub",
                          "Tags": [{"Key": "env", "Value": "test"}]
                        }
                        """.formatted(name))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ConnectionArn", containsString(":connection/"))
                .body("Tags.Key", hasItem("env"))
                .extract().path("ConnectionArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connection.ConnectionName", equalTo(name))
                .body("Connection.ConnectionStatus", equalTo("PENDING"))
                .body("Connection.ProviderType", equalTo("GitHub"))
                .body("Connection.ConnectionArn", equalTo(arn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListConnections")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connections.ConnectionArn", hasItem(arn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "TagResource")
                .header("Authorization", AUTH)
                .body("""
                        {"ResourceArn":"%s","Tags":[{"Key":"owner","Value":"floci"}]}
                        """.formatted(arn))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UntagResource")
                .header("Authorization", AUTH)
                .body("""
                        {"ResourceArn":"%s","TagKeys":["env"]}
                        """.formatted(arn))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("owner"))
                .body("Tags.Key", not(hasItem("env")));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getConnection_missingArn_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetConnection")
                .header("Authorization", AUTH)
                .body("""
                        {"ConnectionArn":"arn:aws:codeconnections:us-east-1:000000000000:connection/missing"}
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
