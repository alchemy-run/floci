package io.github.hectorvent.floci.services.iotmanagedintegrations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the IoT Managed Integrations notification-configuration restJson1
 * lifecycle used by Alchemy.
 */
@QuarkusTest
class NotificationConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String STREAM_ARN =
            "arn:aws:kinesis:us-east-1:000000002501:stream/events";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000002501:role/DeliveryRole";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getNotificationConfigurationOnAnUnconfiguredEventTypeFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000002501", EAST))
                .when()
                .get("/notification-configurations/CONNECTOR_ERROR_REPORT")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetRetargetTagsListDeleteNotificationConfigurationLifecycle() {
        String authorization = auth("000000002502", EAST);
        createDestination(authorization, "lifecycle-a");
        createDestination(authorization, "lifecycle-b");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "EventType":"DEVICE_LIFE_CYCLE",
                          "DestinationName":"lifecycle-a",
                          "Tags":{"fixture":"iot-mi-notification-configuration"}
                        }
                        """)
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(200)
                .body("EventType", equalTo("DEVICE_LIFE_CYCLE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations/DEVICE_LIFE_CYCLE")
                .then()
                .statusCode(200)
                .body("EventType", equalTo("DEVICE_LIFE_CYCLE"))
                .body("DestinationName", equalTo("lifecycle-a"))
                .body("Tags.fixture", equalTo("iot-mi-notification-configuration"))
                .body("CreatedAt", notNullValue());

        String arn = "arn:aws:iotmanagedintegrations:" + EAST
                + ":000000002502:notification-configuration/DEVICE_LIFE_CYCLE";
        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("iot-mi-notification-configuration"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"DestinationName\":\"lifecycle-b\"}")
                .when()
                .put("/notification-configurations/DEVICE_LIFE_CYCLE")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations/DEVICE_LIFE_CYCLE")
                .then()
                .statusCode(200)
                .body("DestinationName", equalTo("lifecycle-b"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("iot-mi-notification-configuration"))
                .body("Tags.phase", equalTo("two"));

        List<Map<String, Object>> listed = list(authorization).path("NotificationConfigurationList");
        assertEquals(1, listed.size());
        assertEquals("DEVICE_LIFE_CYCLE", listed.getFirst().get("EventType"));
        assertEquals("lifecycle-b", listed.getFirst().get("DestinationName"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/notification-configurations/DEVICE_LIFE_CYCLE")
                .then()
                .statusCode(200);

        get(authorization, "DEVICE_LIFE_CYCLE").then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDuplicateNotificationConfigurationFailsWithConflictException() {
        String authorization = auth("000000002503", EAST);
        createDestination(authorization, "dup-dest");
        createConfiguration(authorization, "DEVICE_STATE", "dup-dest");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "EventType":"DEVICE_STATE",
                          "DestinationName":"dup-dest"
                        }
                        """)
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void notificationConfigurationsAreIsolatedByAccountAndRegion() {
        String firstAuth = auth("000000002504", EAST);
        String secondAuth = auth("000000002505", EAST);
        String westAuth = auth("000000002504", WEST);

        createDestination(firstAuth, "shared-dest");
        createDestination(secondAuth, "shared-dest");
        createDestination(westAuth, "shared-dest");
        createConfiguration(firstAuth, "DEVICE_OTA", "shared-dest");
        createConfiguration(secondAuth, "DEVICE_OTA", "shared-dest");
        createConfiguration(westAuth, "DEVICE_OTA", "shared-dest");

        get(firstAuth, "DEVICE_OTA").then()
                .statusCode(200)
                .body("DestinationName", equalTo("shared-dest"));
        get(secondAuth, "DEVICE_OTA").then().statusCode(200);
        get(westAuth, "DEVICE_OTA").then().statusCode(200);
        get(firstAuth, "DEVICE_EVENT").then().statusCode(404);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/iotmanagedintegrations/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void createDestination(String authorization, String name) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "DeliveryDestinationArn":"%s",
                          "DeliveryDestinationType":"KINESIS",
                          "RoleArn":"%s"
                        }
                        """.formatted(name, STREAM_ARN, ROLE_ARN))
                .when()
                .post("/destinations")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name));
    }

    private static void createConfiguration(String authorization, String eventType, String destinationName) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "EventType":"%s",
                          "DestinationName":"%s"
                        }
                        """.formatted(eventType, destinationName))
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(200)
                .body("EventType", equalTo(eventType));
    }

    private static Response get(String authorization, String eventType) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations/" + eventType);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
