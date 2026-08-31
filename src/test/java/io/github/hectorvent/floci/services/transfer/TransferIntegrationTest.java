package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * AWS Transfer Family JSON 1.1 — {@code X-Amz-Target: TransferService.&lt;Action&gt;}.
 */
@QuarkusTest
class TransferIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/transfer/aws4_request";
    private static final String TARGET_PREFIX = "TransferService.";

    private static io.restassured.response.Response tf(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    void describeServer_missing_resourceNotFound() {
        tf("DescribeServer", "{\"ServerId\":\"s-00000000000000000\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("Resource", equalTo("s-00000000000000000"))
                .body("ResourceType", equalTo("Server"))
                .body("message", equalTo("Unknown server"));
    }

    @Test
    void createDescribeDeleteServer_roundTripWithoutStop() {
        String serverId = tf("CreateServer", """
                {"Protocols":["SFTP"],
                 "Domain":"S3",
                 "EndpointType":"PUBLIC",
                 "IdentityProviderType":"SERVICE_MANAGED",
                 "Tags":[{"Key":"env","Value":"test"}]}
                """)
                .then()
                .statusCode(200)
                .body("ServerId", startsWith("s-"))
                .extract()
                .path("ServerId");

        tf("DescribeServer", "{\"ServerId\":\"%s\"}".formatted(serverId))
                .then()
                .statusCode(200)
                .body("Server.ServerId", equalTo(serverId))
                .body("Server.Domain", equalTo("S3"))
                .body("Server.State", equalTo("ONLINE"))
                .body("Server.IdentityProviderType", equalTo("SERVICE_MANAGED"))
                .body("Server.Protocols", hasItem("SFTP"))
                .body("Server.EndpointType", equalTo("PUBLIC"));

        tf("ListTagsForResource",
                "{\"Arn\":\"arn:aws:transfer:us-east-1:000000000000:server/%s\"}".formatted(serverId))
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'env' }.Value", equalTo("test"));

        tf("DeleteServer", "{\"ServerId\":\"%s\"}".formatted(serverId))
                .then()
                .statusCode(200);

        tf("DescribeServer", "{\"ServerId\":\"%s\"}".formatted(serverId))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("Resource", equalTo(serverId))
                .body("ResourceType", equalTo("Server"));
    }

    @Test
    void createDescribeDeleteUser_onOnlineServer() {
        String serverId = tf("CreateServer", """
                {"Protocols":["SFTP"],"Domain":"S3","IdentityProviderType":"SERVICE_MANAGED"}
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("ServerId");

        tf("CreateUser", """
                {"ServerId":"%s","UserName":"alice",
                 "Role":"arn:aws:iam::000000000000:role/transfer",
                 "HomeDirectory":"/example-bucket/alice",
                 "Tags":[{"Key":"env","Value":"test"}]}
                """.formatted(serverId))
                .then()
                .statusCode(200)
                .body("UserName", equalTo("alice"));

        tf("DescribeUser", "{\"ServerId\":\"%s\",\"UserName\":\"alice\"}".formatted(serverId))
                .then()
                .statusCode(200)
                .body("User.UserName", equalTo("alice"))
                .body("User.HomeDirectory", equalTo("/example-bucket/alice"))
                .body("User.Role", equalTo("arn:aws:iam::000000000000:role/transfer"));

        tf("DeleteUser", "{\"ServerId\":\"%s\",\"UserName\":\"alice\"}".formatted(serverId))
                .then()
                .statusCode(200);

        tf("DeleteServer", "{\"ServerId\":\"%s\"}".formatted(serverId))
                .then()
                .statusCode(200);
    }
}
