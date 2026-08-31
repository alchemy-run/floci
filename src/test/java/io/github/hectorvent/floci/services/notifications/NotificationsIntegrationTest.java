package io.github.hectorvent.floci.services.notifications;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies User Notifications restJson1 configuration, rule, channel, and hub APIs. */
@QuarkusTest
class NotificationsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMissingConfigurationFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000801", EAST))
                .when()
                .get("/notification-configurations/" + encode("arn:aws:notifications::000000000801:configuration/missing"))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void configurationEventRuleChannelAndHubLifecycle() {
        String authorization = auth("000000000802", EAST);

        String configArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-test-notif-config",
                          "description":"created by alchemy test",
                          "aggregationDuration":"SHORT",
                          "tags":{"purpose":"alchemy-test"}
                        }
                        """)
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(200)
                .body("arn", containsString(":configuration/"))
                .body("status", equalTo("INACTIVE"))
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations/" + encode(configArn))
                .then()
                .statusCode(200)
                .body("name", equalTo("alchemy-test-notif-config"))
                .body("description", equalTo("created by alchemy test"))
                .body("aggregationDuration", equalTo("SHORT"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(configArn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"alchemy::id\":\"Config\"}}")
                .when()
                .post("/tags/" + encode(configArn))
                .then()
                .statusCode(204);

        String ruleArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "notificationConfigurationArn":"%s",
                          "source":"aws.s3",
                          "eventType":"Object Created",
                          "regions":["us-west-2"]
                        }
                        """.formatted(configArn))
                .when()
                .post("/event-rules")
                .then()
                .statusCode(200)
                .body("arn", containsString("/rule/"))
                .body("statusSummaryByRegion.us-west-2.status", equalTo("ACTIVE"))
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/event-rules/" + encode(ruleArn))
                .then()
                .statusCode(200)
                .body("source", equalTo("aws.s3"))
                .body("eventType", equalTo("Object Created"))
                .body("regions", hasItems("us-west-2"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-test-notif-config-renamed",
                          "description":"updated by alchemy test",
                          "aggregationDuration":"LONG"
                        }
                        """)
                .when()
                .put("/notification-configurations/" + encode(configArn))
                .then()
                .statusCode(200)
                .body("arn", equalTo(configArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "eventPattern":"{\\"detail\\":{\\"bucket\\":{\\"name\\":[\\"alchemy-test\\"]}}}",
                          "regions":["us-west-2","us-east-2"]
                        }
                        """)
                .when()
                .put("/event-rules/" + encode(ruleArn))
                .then()
                .statusCode(200)
                .body("arn", equalTo(ruleArn));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/event-rules/" + encode(ruleArn))
                .then()
                .statusCode(200)
                .body("eventPattern", containsString("alchemy-test"))
                .body("regions", hasItems("us-west-2", "us-east-2"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/event-rules/" + encode(ruleArn))
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/event-rules/" + encode(ruleArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        String replacementArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "notificationConfigurationArn":"%s",
                          "source":"aws.s3",
                          "eventType":"Object Deleted",
                          "regions":["us-west-2"]
                        }
                        """.formatted(configArn))
                .when()
                .post("/event-rules")
                .then()
                .statusCode(200)
                .extract().path("arn");
        assertNotEquals(ruleArn, replacementArn);

        String contactArn = given()
                .contentType("application/json")
                .header("Authorization", contactsAuth("000000000802", EAST))
                .body("""
                        {
                          "name":"alchemy-test-notif-contact",
                          "emailAddress":"alchemy-test-notif-channel@example.com"
                        }
                        """)
                .when()
                .post("/2022-09-19/emailcontacts")
                .then()
                .statusCode(201)
                .body("arn", containsString(":emailcontact/"))
                .extract().path("arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"notificationConfigurationArn\":\"" + configArn + "\"}")
                .when()
                .post("/channels/associate/" + encode(contactArn))
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/channels?notificationConfigurationArn=" + encode(configArn))
                .then()
                .statusCode(200)
                .body("channels", hasItems(contactArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"notificationHubRegion\":\"us-west-2\"}")
                .when()
                .post("/notification-hubs")
                .then()
                .statusCode(200)
                .body("notificationHubRegion", equalTo("us-west-2"))
                .body("statusSummary.status", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"notificationHubRegion\":\"us-east-2\"}")
                .when()
                .post("/notification-hubs")
                .then()
                .statusCode(200)
                .body("notificationHubRegion", equalTo("us-east-2"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-hubs")
                .then()
                .statusCode(200)
                .body("notificationHubs.notificationHubRegion", hasItems("us-west-2", "us-east-2"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/notification-hubs/us-east-2")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-hubs")
                .then()
                .statusCode(200)
                .body("notificationHubs.notificationHubRegion", hasItems("us-west-2"))
                .body("notificationHubs.notificationHubRegion", not(hasItems("us-east-2")));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/notification-hubs/us-west-2")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/notification-configurations/" + encode(configArn))
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/notification-configurations/" + encode(configArn))
                .then()
                .statusCode(404);
    }

    @Test
    void duplicateConfigurationNameConflicts() {
        String authorization = auth("000000000803", EAST);
        createConfig(authorization, "duplicate-config");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"duplicate-config","description":"again"}
                        """)
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void registerHubIsIdempotent() {
        String authorization = auth("000000000804", EAST);
        Response first = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"notificationHubRegion\":\"us-west-2\"}")
                .when()
                .post("/notification-hubs");
        first.then().statusCode(200);
        String createdAt = first.path("creationTime");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"notificationHubRegion\":\"us-west-2\"}")
                .when()
                .post("/notification-hubs")
                .then()
                .statusCode(200)
                .body("creationTime", equalTo(createdAt))
                .body("statusSummary.status", equalTo("ACTIVE"));
    }

    private static String createConfig(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "\",\"description\":\"test\"}")
                .when()
                .post("/notification-configurations")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract().path("arn");
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/notifications/aws4_request";
    }

    private static String contactsAuth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/notifications-contacts/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
