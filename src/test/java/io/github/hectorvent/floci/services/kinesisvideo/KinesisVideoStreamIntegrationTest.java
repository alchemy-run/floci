package io.github.hectorvent.floci.services.kinesisvideo;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies Kinesis Video restJson1 stream operations used by Alchemy
 * {@code Stream.test.ts}: Describe of an unknown name is a typed
 * ResourceNotFoundException, and create/update retention/mediaType/tags/delete
 * converge with an immediate ACTIVE status and bumped Version.
 */
@QuarkusTest
class KinesisVideoStreamIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeStreamOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"StreamName\":\"missing-kvs-" + UUID.randomUUID() + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateRetentionTagsDeleteStreamLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-kvs-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamName":"%s",
                          "DataRetentionInHours":24,
                          "Tags":{"Environment":"test","alchemy::id":"TestVideoStream"}
                        }
                        """.formatted(name))
                .when()
                .post("/createStream")
                .then()
                .statusCode(200)
                .body("StreamARN", startsWith("arn:aws:kinesisvideo:" + EAST + ":"))
                .body("StreamARN", org.hamcrest.Matchers.containsString(":stream/" + name + "/"))
                .extract()
                .path("StreamARN");

        String version = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(200)
                .body("StreamInfo.StreamName", equalTo(name))
                .body("StreamInfo.StreamARN", equalTo(arn))
                .body("StreamInfo.Status", equalTo("ACTIVE"))
                .body("StreamInfo.DataRetentionInHours", equalTo(24))
                .body("StreamInfo.Version", notNullValue())
                .extract()
                .path("StreamInfo.Version");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + arn + "\"}")
                .when()
                .post("/listTagsForStream")
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"))
                .body("Tags.'alchemy::id'", equalTo("TestVideoStream"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "CurrentVersion":"%s",
                          "MediaType":"video/h264",
                          "DeviceName":"camera-1"
                        }
                        """.formatted(arn, version))
                .when()
                .post("/updateStream")
                .then()
                .statusCode(200);

        String afterUpdateVersion = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(200)
                .body("StreamInfo.MediaType", equalTo("video/h264"))
                .body("StreamInfo.DeviceName", equalTo("camera-1"))
                .body("StreamInfo.Status", equalTo("ACTIVE"))
                .body("StreamInfo.Version", not(equalTo(version)))
                .extract()
                .path("StreamInfo.Version");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "CurrentVersion":"%s",
                          "Operation":"INCREASE_DATA_RETENTION",
                          "DataRetentionChangeInHours":24
                        }
                        """.formatted(arn, afterUpdateVersion))
                .when()
                .post("/updateDataRetention")
                .then()
                .statusCode(200);

        String afterIncreaseVersion = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(200)
                .body("StreamInfo.DataRetentionInHours", equalTo(48))
                .body("StreamInfo.Version", not(equalTo(afterUpdateVersion)))
                .extract()
                .path("StreamInfo.Version");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "CurrentVersion":"%s",
                          "Operation":"DECREASE_DATA_RETENTION",
                          "DataRetentionChangeInHours":24
                        }
                        """.formatted(arn, afterIncreaseVersion))
                .when()
                .post("/updateDataRetention")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(200)
                .body("StreamInfo.DataRetentionInHours", equalTo(24));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "Tags":{"Extra":"yes"}
                        }
                        """.formatted(arn))
                .when()
                .post("/tagStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + arn + "\"}")
                .when()
                .post("/listTagsForStream")
                .then()
                .statusCode(200)
                .body("Tags.Extra", equalTo("yes"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamARN":"%s",
                          "TagKeyList":["Extra"]
                        }
                        """.formatted(arn))
                .when()
                .post("/untagStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + arn + "\"}")
                .when()
                .post("/listTagsForStream")
                .then()
                .statusCode(200)
                .body("Tags.Extra", nullValue())
                .body("Tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"MaxResults\":10000}")
                .when()
                .post("/listStreams")
                .then()
                .statusCode(200)
                .body("StreamInfoList.find { it.StreamName == '" + name + "' }.StreamARN", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + arn + "\"}")
                .when()
                .post("/deleteStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/describeStream")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamARN\":\"" + arn + "\"}")
                .when()
                .post("/deleteStream")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createStreamWithExistingNameFailsWithResourceInUseException() {
        String authorization = auth(EAST);
        String name = "alchemy-test-kvs-dup-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/createStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/createStream")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ResourceInUseException"));
    }

    @Test
    void updateStreamWithStaleVersionFailsWithVersionMismatchException() {
        String authorization = auth(EAST);
        String name = "alchemy-test-kvs-ver-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StreamName\":\"" + name + "\"}")
                .when()
                .post("/createStream")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "StreamName":"%s",
                          "CurrentVersion":"999",
                          "MediaType":"video/h264"
                        }
                        """.formatted(name))
                .when()
                .post("/updateStream")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("VersionMismatchException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/kinesisvideo/aws4_request";
    }
}
