package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Verifies IVS Chat restJson1 logging-configuration operations used by Alchemy
 * {@code LoggingConfiguration.test.ts}: Get of an unknown identifier is a typed
 * ResourceNotFoundException, and create/attach-to-room/tag/delete converge.
 */
@QuarkusTest
class IvsChatLoggingConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getLoggingConfigurationOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivschat:" + EAST + ":000000000000:logging-configuration/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetLoggingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createAttachAndDeleteLoggingConfigurationLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivschat-logging-" + UUID.randomUUID().toString().substring(0, 8);
        String roomName = "alchemy-test-ivschat-logged-room-" + UUID.randomUUID().toString().substring(0, 8);
        String logGroup = "/alchemy-test/ivschat-logging";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "destinationConfiguration":{"cloudWatchLogs":{"logGroupName":"%s"}},
                          "tags":{"fixture":"ivschat-logging","alchemy::id":"ChatLogs"}
                        }
                        """.formatted(name, logGroup))
                .when()
                .post("/CreateLoggingConfiguration")
                .then()
                .statusCode(200)
                .body("arn", containsString(":logging-configuration/"))
                .body("id", org.hamcrest.Matchers.notNullValue())
                .body("name", equalTo(name))
                .body("state", equalTo("ACTIVE"))
                .body("destinationConfiguration.cloudWatchLogs.logGroupName", equalTo(logGroup))
                .body("tags.fixture", equalTo("ivschat-logging"))
                .body("tags.'alchemy::id'", equalTo("ChatLogs"))
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetLoggingConfiguration")
                .then()
                .statusCode(200)
                .body("arn", equalTo(arn))
                .body("name", equalTo(name))
                .body("state", equalTo("ACTIVE"))
                .body("destinationConfiguration.cloudWatchLogs.logGroupName", equalTo(logGroup))
                .body("tags.'alchemy::id'", equalTo("ChatLogs"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListLoggingConfigurations")
                .then()
                .statusCode(200)
                .body("loggingConfigurations.size()", greaterThanOrEqualTo(1))
                .body("loggingConfigurations.find { it.arn == '" + arn + "' }.name", equalTo(name))
                .body("loggingConfigurations.find { it.arn == '" + arn + "' }.state", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"yes\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("ivschat-logging"))
                .body("tags.extra", equalTo("yes"));

        String roomArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "loggingConfigurationIdentifiers":["%s"]
                        }
                        """.formatted(roomName, arn))
                .when()
                .post("/CreateRoom")
                .then()
                .statusCode(200)
                .body("arn", containsString(":room/"))
                .body("name", equalTo(roomName))
                .body("loggingConfigurationIdentifiers", hasItem(arn))
                .extract()
                .path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + roomArn + "\"}")
                .when()
                .post("/GetRoom")
                .then()
                .statusCode(200)
                .body("arn", equalTo(roomArn))
                .body("loggingConfigurationIdentifiers", hasItem(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteLoggingConfiguration")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + roomArn + "\"}")
                .when()
                .post("/DeleteRoom")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteLoggingConfiguration")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/GetLoggingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"identifier\":\"" + arn + "\"}")
                .when()
                .post("/DeleteLoggingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivschat/aws4_request";
    }
}
