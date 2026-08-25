package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Verifies IVS restJson1 recording-configuration operations used by Alchemy
 * {@code RecordingConfiguration.test.ts}: Get of an unknown ARN is a typed
 * ResourceNotFoundException, and create/list/tag/delete converge.
 */
@QuarkusTest
class IvsRecordingConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRecordingConfigurationOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:ivs:" + EAST + ":000000000000:recording-configuration/AbCdEfGh1234";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetRecordingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetListTagsAndDeleteRecordingConfigurationLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-ivs-recording-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "destinationConfiguration":{"s3":{"bucketName":"archive-bucket"}},
                          "recordingReconnectWindowSeconds":120,
                          "thumbnailConfiguration":{
                            "recordingMode":"INTERVAL",
                            "targetIntervalSeconds":30
                          },
                          "tags":{"fixture":"ivs-recording","alchemy::id":"Recording"}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateRecordingConfiguration")
                .then()
                .statusCode(200)
                .body("recordingConfiguration.arn", containsString(":recording-configuration/"))
                .body("recordingConfiguration.name", equalTo(name))
                .body("recordingConfiguration.state", equalTo("ACTIVE"))
                .body("recordingConfiguration.destinationConfiguration.s3.bucketName", equalTo("archive-bucket"))
                .body("recordingConfiguration.recordingReconnectWindowSeconds", equalTo(120))
                .body("recordingConfiguration.thumbnailConfiguration.recordingMode", equalTo("INTERVAL"))
                .body("recordingConfiguration.thumbnailConfiguration.targetIntervalSeconds", equalTo(30))
                .body("recordingConfiguration.tags.fixture", equalTo("ivs-recording"))
                .body("recordingConfiguration.tags.'alchemy::id'", equalTo("Recording"))
                .extract()
                .path("recordingConfiguration.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetRecordingConfiguration")
                .then()
                .statusCode(200)
                .body("recordingConfiguration.arn", equalTo(arn))
                .body("recordingConfiguration.name", equalTo(name))
                .body("recordingConfiguration.state", equalTo("ACTIVE"))
                .body("recordingConfiguration.recordingReconnectWindowSeconds", equalTo(120))
                .body("recordingConfiguration.thumbnailConfiguration.targetIntervalSeconds", equalTo(30))
                .body("recordingConfiguration.tags.'alchemy::id'", equalTo("Recording"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListRecordingConfigurations")
                .then()
                .statusCode(200)
                .body("recordingConfigurations.size()", greaterThanOrEqualTo(1))
                .body("recordingConfigurations.find { it.arn == '" + arn + "' }.name", equalTo(name))
                .body("recordingConfigurations.find { it.arn == '" + arn + "' }.state", equalTo("ACTIVE"));

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
                .body("tags.fixture", equalTo("ivs-recording"))
                .body("tags.extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteRecordingConfiguration")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/GetRecordingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + arn + "\"}")
                .when()
                .post("/DeleteRecordingConfiguration")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteRecordingConfigurationAttachedToAChannelIsConflictException() {
        String authorization = auth(EAST);
        String name = "attached-" + UUID.randomUUID().toString().substring(0, 8);

        String recordingArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "destinationConfiguration":{"s3":{"bucketName":"archive-bucket"}}
                        }
                        """.formatted(name))
                .when()
                .post("/CreateRecordingConfiguration")
                .then()
                .statusCode(200)
                .extract()
                .path("recordingConfiguration.arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "-ch\",\"recordingConfigurationArn\":\"" + recordingArn + "\"}")
                .when()
                .post("/CreateChannel")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"arn\":\"" + recordingArn + "\"}")
                .when()
                .post("/DeleteRecordingConfiguration")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/ivs/aws4_request";
    }
}
