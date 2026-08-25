package io.github.hectorvent.floci.services.mediatailor;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-plane MediaTailor ops the alchemy Bindings suite exercises:
 * prefetch-schedule CRUD, ListAlerts on a playback-configuration ARN, and
 * typed not-found for missing channel/program operations.
 */
@QuarkusTest
class MediaTailorBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING_CHANNEL = "alchemy-nonexistent-mediatailor-channel";
    private static final String MISSING_PROGRAM = "alchemy-nonexistent-mediatailor-program";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void prefetchScheduleLifecycleAndChannelAssemblyNotFound() {
        String authorization = auth(EAST);
        String configName = "bind-" + UUID.randomUUID().toString().substring(0, 8);
        String scheduleName = "alchemy-test-prefetch-schedule";
        long end = System.currentTimeMillis() / 1000L + 3600;

        String configArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "AdDecisionServerUrl":"https://ads.example.com/vast",
                          "VideoContentSourceUrl":"https://origin.example.com/live",
                          "tags":{"alchemy::id":"BindingsConfig"}
                        }
                        """.formatted(configName))
                .when()
                .put("/playbackConfiguration")
                .then()
                .statusCode(200)
                .body("PlaybackConfigurationArn", notNullValue())
                .extract()
                .path("PlaybackConfigurationArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Retrieval":{"EndTime":%d},
                          "Consumption":{"EndTime":%d}
                        }
                        """.formatted(end, end + 3600))
                .when()
                .post("/prefetchSchedule/" + configName + "/" + scheduleName)
                .then()
                .statusCode(200)
                .body("Arn", containsString(":prefetchSchedule/"))
                .body("Name", equalTo(scheduleName));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prefetchSchedule/" + configName + "/" + scheduleName)
                .then()
                .statusCode(200)
                .body("Name", equalTo(scheduleName));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/prefetchSchedule/" + configName)
                .then()
                .statusCode(200)
                .body("Items.Name", hasItem(scheduleName));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/prefetchSchedule/" + configName + "/" + scheduleName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/prefetchSchedule/" + configName + "/" + scheduleName)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/alerts?resourceArn=" + configArn)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BadRequestException"))
                .body("__type", equalTo("BadRequestException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/channel/" + MISSING_CHANNEL + "/schedule")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .put("/channel/" + MISSING_CHANNEL + "/start")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .put("/channel/" + MISSING_CHANNEL + "/stop")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SourceLocationName":"missing",
                          "VodSourceName":"missing",
                          "ScheduleConfiguration":{"Transition":{"Type":"RELATIVE"}}
                        }
                        """)
                .when()
                .post("/channel/" + MISSING_CHANNEL + "/program/" + MISSING_PROGRAM)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/channel/" + MISSING_CHANNEL + "/program/" + MISSING_PROGRAM)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ScheduleConfiguration\":{}}")
                .when()
                .put("/channel/" + MISSING_CHANNEL + "/program/" + MISSING_PROGRAM)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/channel/" + MISSING_CHANNEL + "/program/" + MISSING_PROGRAM)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/playbackConfiguration/" + configName)
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/mediatailor/aws4_request";
    }
}
