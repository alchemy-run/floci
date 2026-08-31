package io.github.hectorvent.floci.services.devopsguru;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies DevOps Guru restJson1 describeInsight and notification-channel lifecycle. */
@QuarkusTest
class DevOpsGuruIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String TOPIC =
            "arn:aws:sns:us-east-1:000000000611:alchemy-devopsguru-alerts";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeInsightOnANonexistentInsightFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000611", EAST))
                .when()
                .get("/insights/alchemy-nonexistent-devopsguru-insight-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("Insight"))
                .body("ResourceId", equalTo("alchemy-nonexistent-devopsguru-insight-probe"));
    }

    @Test
    void notificationChannelAddListConvergeFiltersAndRemove() {
        String authorization = auth("000000000612", EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/channels")
                .then()
                .statusCode(200)
                .body("Channels", hasSize(0));

        String createdId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Config": {
                            "Sns": {"TopicArn": "%s"},
                            "Filters": {"Severities": ["HIGH"]}
                          }
                        }
                        """.formatted(TOPIC))
                .when()
                .put("/channels")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/channels")
                .then()
                .statusCode(200)
                .body("Channels", hasSize(1))
                .body("Channels[0].Id", equalTo(createdId))
                .body("Channels[0].Config.Sns.TopicArn", equalTo(TOPIC))
                .body("Channels[0].Config.Filters.Severities", equalTo(java.util.List.of("HIGH")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Config": {
                            "Sns": {"TopicArn": "%s"},
                            "Filters": {"Severities": ["HIGH"]}
                          }
                        }
                        """.formatted(TOPIC))
                .when()
                .put("/channels")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("ResourceType", equalTo("NotificationChannel"))
                .body("ResourceId", equalTo(createdId));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/channels/" + createdId)
                .then()
                .statusCode(200);

        String updatedId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Config": {
                            "Sns": {"TopicArn": "%s"},
                            "Filters": {
                              "Severities": ["HIGH", "MEDIUM"],
                              "MessageTypes": ["NEW_INSIGHT"]
                            }
                          }
                        }
                        """.formatted(TOPIC))
                .when()
                .put("/channels")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");
        assertNotEquals(createdId, updatedId);

        Response listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/channels");
        listed.then()
                .statusCode(200)
                .body("Channels", hasSize(1))
                .body("Channels[0].Id", equalTo(updatedId))
                .body("Channels[0].Config.Sns.TopicArn", equalTo(TOPIC))
                .body("Channels[0].Config.Filters.MessageTypes", equalTo(java.util.List.of("NEW_INSIGHT")));
        assertEquals(
                java.util.List.of("HIGH", "MEDIUM"),
                listed.jsonPath().getList("Channels[0].Config.Filters.Severities"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/channels/" + updatedId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/channels")
                .then()
                .statusCode(200)
                .body("Channels", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/channels/" + updatedId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("ResourceType", equalTo("NotificationChannel"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/devops-guru/aws4_request";
    }
}
