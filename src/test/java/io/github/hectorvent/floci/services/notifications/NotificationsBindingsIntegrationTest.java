package io.github.hectorvent.floci.services.notifications;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies User Notifications restJson1 binding operations used by Alchemy. */
@QuarkusTest
class NotificationsBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000001911";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listNotificationConfigurationsIsEmptyUntilCreated() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/notification-configurations")
                .then()
                .statusCode(200)
                .body("notificationConfigurations.size()", equalTo(0));
    }

    @Test
    void managedConfigurationsEventsAndChannelsSatisfyBindings() {
        String authorization = auth(ACCOUNT, EAST);

        String configArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"bindings-config",
                          "description":"notifications bindings fixture"
                        }
                        """)
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .queryParam("notificationConfigurationArn", configArn)
                .when()
                .get("/channels")
                .then()
                .statusCode(200)
                .body("channels.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-events")
                .then()
                .statusCode(200)
                .body("notificationEvents.size()", equalTo(0));

        String fakeEventArn = "arn:aws:notifications::" + ACCOUNT
                + ":configuration/a01000000000000000000000000/event/a01000000000000000000000000";
        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-events/" + encode(fakeEventArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", notNullValue());

        String managedArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/managed-notification-configurations")
                .then()
                .statusCode(200)
                .body("managedNotificationConfigurations.size()", greaterThan(0))
                .extract().path("managedNotificationConfigurations[0].arn");
        assertTrue(managedArn.contains(":managed-notification-configuration/"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/managed-notification-configurations/" + encode(managedArn))
                .then()
                .statusCode(200)
                .body("arn", equalTo(managedArn))
                .body("name", notNullValue())
                .body("category", notNullValue())
                .body("subCategory", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/managed-notification-events")
                .then()
                .statusCode(200)
                .body("managedNotificationEvents.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .queryParam("managedNotificationConfigurationArn", managedArn)
                .when()
                .get("/channels/list-managed-notification-channel-associations")
                .then()
                .statusCode(200)
                .body("channelAssociations.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/managed-notification-child-events/" + encode(fakeEventArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/list-managed-notification-child-events/" + encode(fakeEventArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/notifications/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
