package io.github.hectorvent.floci.services.kinesisvideo;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Kinesis Video restJson1 signaling-channel operations used by Alchemy
 * {@code SignalingChannel.test.ts}: Describe of an unknown name is a typed
 * ResourceNotFoundException, and create/update TTL/tags/delete converge.
 */
@QuarkusTest
class KinesisVideoSignalingChannelIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"ChannelName\":\"missing-kvs-channel\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateTagsDeleteSignalingChannelLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-test-kvs-signaling-channel";

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ChannelName":"%s",
                          "Tags":[
                            {"Key":"Environment","Value":"test"},
                            {"Key":"alchemy::id","Value":"TestSignalingChannel"}
                          ]
                        }
                        """.formatted(name))
                .when()
                .post("/createSignalingChannel")
                .then()
                .statusCode(200)
                .body("ChannelARN", containsString(":channel/"))
                .extract()
                .path("ChannelARN");

        String version = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + name + "\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(200)
                .body("ChannelInfo.ChannelName", equalTo(name))
                .body("ChannelInfo.ChannelARN", equalTo(arn))
                .body("ChannelInfo.ChannelStatus", equalTo("ACTIVE"))
                .body("ChannelInfo.ChannelType", equalTo("SINGLE_MASTER"))
                .body("ChannelInfo.SingleMasterConfiguration.MessageTtlSeconds", equalTo(60))
                .body("ChannelInfo.Version", notNullValue())
                .extract()
                .path("ChannelInfo.Version");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Environment", equalTo("test"))
                .body("Tags.'alchemy::id'", equalTo("TestSignalingChannel"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ChannelARN":"%s",
                          "CurrentVersion":"%s",
                          "SingleMasterConfiguration":{"MessageTtlSeconds":30}
                        }
                        """.formatted(arn, version))
                .when()
                .post("/updateSignalingChannel")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + name + "\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(200)
                .body("ChannelInfo.ChannelARN", equalTo(arn))
                .body("ChannelInfo.ChannelStatus", equalTo("ACTIVE"))
                .body("ChannelInfo.SingleMasterConfiguration.MessageTtlSeconds", equalTo(30))
                .body("ChannelInfo.Version", not(equalTo(version)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "Tags":[{"Key":"Extra","Value":"yes"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/TagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Extra", equalTo("yes"))
                .body("Tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelARN\":\"" + arn + "\"}")
                .when()
                .post("/deleteSignalingChannel")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + name + "\"}")
                .when()
                .post("/describeSignalingChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelARN\":\"" + arn + "\"}")
                .when()
                .post("/deleteSignalingChannel")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createWithDuplicateNameFailsWithResourceInUseException() {
        String authorization = auth(EAST);
        String name = "alchemy-test-kvs-signaling-channel-dup";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + name + "\"}")
                .when()
                .post("/createSignalingChannel")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ChannelName\":\"" + name + "\"}")
                .when()
                .post("/createSignalingChannel")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ResourceInUseException"))
                .body("__type", equalTo("ResourceInUseException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/kinesisvideo/aws4_request";
    }
}
