package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CodeConnectionsRepositoryLinkIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "CodeConnections_20231201.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRepositoryLink_missing_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetRepositoryLink")
                .header("Authorization", AUTH)
                .body("{\"RepositoryLinkId\":\"00000000-0000-0000-0000-000000000000\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void repositoryLinkLifecycle_createOnPendingConnection_updateListDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String connectionName = "floci-rl-" + suffix;
        String ownerId = "alchemy-test-" + suffix;
        String repositoryName = "alchemy-test-repo-" + suffix;

        var createdConnection = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateConnection")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "ConnectionName": "%s",
                          "ProviderType": "GitHub",
                          "Tags": [{"Key":"env","Value":"test"}]
                        }
                        """.formatted(connectionName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ConnectionArn", startsWith("arn:aws:codeconnections:"))
        .extract();
        String connectionArn = createdConnection.path("ConnectionArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + connectionArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connection.ConnectionName", equalTo(connectionName))
                .body("Connection.ConnectionStatus", equalTo("PENDING"))
                .body("Connection.ProviderType", equalTo("GitHub"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListConnections")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connections.ConnectionArn", hasItem(connectionArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + connectionArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        var createdLink = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateRepositoryLink")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "ConnectionArn": "%s",
                          "OwnerId": "%s",
                          "RepositoryName": "%s"
                        }
                        """.formatted(connectionArn, ownerId, repositoryName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RepositoryLinkInfo.RepositoryLinkId", org.hamcrest.Matchers.notNullValue())
                .body("RepositoryLinkInfo.RepositoryLinkArn", org.hamcrest.Matchers.containsString(":repository-link/"))
                .body("RepositoryLinkInfo.ConnectionArn", equalTo(connectionArn))
                .body("RepositoryLinkInfo.OwnerId", equalTo(ownerId))
                .body("RepositoryLinkInfo.RepositoryName", equalTo(repositoryName))
                .body("RepositoryLinkInfo.ProviderType", equalTo("GitHub"))
        .extract();
        String linkId = createdLink.path("RepositoryLinkInfo.RepositoryLinkId");
        String linkArn = createdLink.path("RepositoryLinkInfo.RepositoryLinkArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetRepositoryLink")
                .header("Authorization", AUTH)
                .body("{\"RepositoryLinkId\":\"" + linkId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RepositoryLinkInfo.ConnectionArn", equalTo(connectionArn));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListRepositoryLinks")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RepositoryLinks.RepositoryLinkId", hasItem(linkId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "TagResource")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "ResourceArn": "%s",
                          "Tags": [{"Key":"owner","Value":"alchemy"}]
                        }
                        """.formatted(linkArn))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceArn\":\"" + linkArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("owner"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UpdateRepositoryLink")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "RepositoryLinkId": "%s",
                          "EncryptionKeyArn": "arn:aws:kms:us-east-1:000000000000:key/test"
                        }
                        """.formatted(linkId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("RepositoryLinkInfo.EncryptionKeyArn",
                        equalTo("arn:aws:kms:us-east-1:000000000000:key/test"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateRepositoryLink")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "ConnectionArn": "%s",
                          "OwnerId": "%s",
                          "RepositoryName": "%s"
                        }
                        """.formatted(connectionArn, ownerId, repositoryName))
        .when()
                .post("/")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceAlreadyExistsException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteRepositoryLink")
                .header("Authorization", AUTH)
                .body("{\"RepositoryLinkId\":\"" + linkId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetRepositoryLink")
                .header("Authorization", AUTH)
                .body("{\"RepositoryLinkId\":\"" + linkId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + connectionArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "GetConnection")
                .header("Authorization", AUTH)
                .body("{\"ConnectionArn\":\"" + connectionArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", org.hamcrest.Matchers.not(nullValue()));
    }
}
