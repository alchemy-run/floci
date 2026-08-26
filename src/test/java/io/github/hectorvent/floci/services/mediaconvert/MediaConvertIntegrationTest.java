package io.github.hectorvent.floci.services.mediaconvert;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies MediaConvert restJson1 queue/preset/template/job APIs used by Alchemy tests. */
@QuarkusTest
class MediaConvertIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth(EAST);
    private static final String PRESET_SETTINGS = """
            {
              "containerSettings": {"container": "MP4", "mp4Settings": {}},
              "videoDescription": {
                "width": 1280,
                "height": 720,
                "codecSettings": {
                  "codec": "H_264",
                  "h264Settings": {
                    "rateControlMode": "QVBR",
                    "maxBitrate": 3000000
                  }
                }
              }
            }
            """;
    private static final String TEMPLATE_SETTINGS = """
            {
              "inputs": [{"timecodeSource": "ZEROBASED", "videoSelector": {}, "audioSelectors": {}}],
              "outputGroups": [{
                "name": "File Group",
                "outputGroupSettings": {"type": "FILE_GROUP_SETTINGS", "fileGroupSettings": {}},
                "outputs": [{
                  "containerSettings": {"container": "MP4", "mp4Settings": {}},
                  "videoDescription": {
                    "codecSettings": {
                      "codec": "H_264",
                      "h264Settings": {"rateControlMode": "QVBR", "maxBitrate": 3000000}
                    }
                  }
                }]
              }]
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getQueuePresetAndJobTemplateOnAMissingNameFailWithNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/queues/alchemy-does-not-exist-000")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/presets/alchemy-does-not-exist-000")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/jobTemplates/alchemy-does-not-exist-000")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createJobWithAnInvalidRoleIsRejectedWithBadRequestException() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "role": "arn:aws:iam::000000000000:role/does-not-exist",
                          "settings": {
                            "inputs": [{"fileInput": "s3://alchemy-nonexistent/in.mp4"}],
                            "outputGroups": []
                          }
                        }
                        """)
                .when()
                .post("/2017-08-29/jobs")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BadRequestException"))
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void createUpdateTagAndDeleteOnDemandQueue() {
        String name = "alchemy-test-mc-queue";
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "description": "alchemy test queue",
                          "tags": {"Environment": "test", "alchemy::id": "Q"}
                        }
                        """.formatted(name))
                .when()
                .post("/2017-08-29/queues")
                .then()
                .statusCode(200)
                .body("queue.name", equalTo(name))
                .body("queue.arn", notNullValue())
                .body("queue.pricingPlan", equalTo("ON_DEMAND"))
                .body("queue.status", equalTo("ACTIVE"))
                .body("queue.type", equalTo("CUSTOM"));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/queues/" + name)
                .then()
                .statusCode(200)
                .body("queue.description", equalTo("alchemy test queue"))
                .extract().path("queue.arn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("resourceTags.tags['alchemy::id']", equalTo("Q"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {"description": "alchemy test queue v2", "status": "PAUSED"}
                        """)
                .when()
                .put("/2017-08-29/queues/" + name)
                .then()
                .statusCode(200)
                .body("queue.status", equalTo("PAUSED"))
                .body("queue.description", equalTo("alchemy test queue v2"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {"arn": "%s", "tags": {"Extra": "yes"}}
                        """.formatted(arn))
                .when()
                .post("/2017-08-29/tags")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("resourceTags.tags.Extra", equalTo("yes"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/2017-08-29/queues/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/queues/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createUpdateAndDeleteOutputPreset() {
        String name = "alchemy-test-mc-preset";
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "description": "alchemy test preset",
                          "settings": %s,
                          "tags": {"Environment": "test", "alchemy::id": "P"}
                        }
                        """.formatted(name, PRESET_SETTINGS))
                .when()
                .post("/2017-08-29/presets")
                .then()
                .statusCode(200)
                .body("preset.name", equalTo(name))
                .body("preset.type", equalTo("CUSTOM"));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/presets/" + name)
                .then()
                .statusCode(200)
                .body("preset.description", equalTo("alchemy test preset"))
                .extract().path("preset.arn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("resourceTags.tags['alchemy::id']", equalTo("P"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "description": "alchemy test preset v2",
                          "category": "alchemy",
                          "settings": %s
                        }
                        """.formatted(PRESET_SETTINGS))
                .when()
                .put("/2017-08-29/presets/" + name)
                .then()
                .statusCode(200)
                .body("preset.category", equalTo("alchemy"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/presets/" + name)
                .then()
                .statusCode(200)
                .body("preset.description", equalTo("alchemy test preset v2"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/2017-08-29/presets/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/presets/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createUpdateAndDeleteJobTemplate() {
        String name = "alchemy-test-mc-jobtemplate";
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "description": "alchemy test template",
                          "settings": %s,
                          "tags": {"Environment": "test", "alchemy::id": "T"}
                        }
                        """.formatted(name, TEMPLATE_SETTINGS))
                .when()
                .post("/2017-08-29/jobTemplates")
                .then()
                .statusCode(200)
                .body("jobTemplate.name", equalTo(name))
                .body("jobTemplate.type", equalTo("CUSTOM"));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/jobTemplates/" + name)
                .then()
                .statusCode(200)
                .body("jobTemplate.description", equalTo("alchemy test template"))
                .extract().path("jobTemplate.arn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("resourceTags.tags['alchemy::id']", equalTo("T"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "description": "alchemy test template v2",
                          "priority": 10,
                          "settings": %s
                        }
                        """.formatted(TEMPLATE_SETTINGS))
                .when()
                .put("/2017-08-29/jobTemplates/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/jobTemplates/" + name)
                .then()
                .statusCode(200)
                .body("jobTemplate.description", equalTo("alchemy test template v2"))
                .body("jobTemplate.priority", equalTo(10));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/2017-08-29/jobTemplates/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2017-08-29/jobTemplates/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediaconvert/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
