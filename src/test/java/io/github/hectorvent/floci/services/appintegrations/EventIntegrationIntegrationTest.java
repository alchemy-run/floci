package io.github.hectorvent.floci.services.appintegrations;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the AppIntegrations restJson1 event-integration lifecycle used by Alchemy. */
@QuarkusTest
class EventIntegrationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String SOURCE = "aws.partner/examplepartner.com";
    private static final String OTHER_SOURCE = "aws.partner/otherpartner.com";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getEventIntegrationOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000610", EAST))
                .when()
                .get("/eventIntegrations/alchemy-nonexistent-event-integration-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void eventIntegrationCreateUpdateTagsReplaceDeleteLifecycle() {
        String authorization = auth("000000000611", EAST);
        String arn = create(authorization, """
                {
                  "Name":"lifecycle-events",
                  "Description":"alchemy event integration",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"},
                  "Tags":{"purpose":"alchemy-test"}
                }
                """.formatted(SOURCE));

        assertTrue(arn.contains(":event-integration/"));
        assertEquals(arn("000000000611", EAST, "lifecycle-events"), arn);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/lifecycle-events")
                .then()
                .statusCode(200)
                .body("Name", equalTo("lifecycle-events"))
                .body("Description", equalTo("alchemy event integration"))
                .body("EventBridgeBus", equalTo("default"))
                .body("EventFilter.Source", equalTo(SOURCE))
                .body("EventIntegrationArn", equalTo(arn))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Description":"alchemy event integration v2"}
                        """)
                .when()
                .patch("/eventIntegrations/lifecycle-events")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/lifecycle-events")
                .then()
                .statusCode(200)
                .body("Description", equalTo("alchemy event integration v2"))
                .body("EventIntegrationArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"phase\":\"two\"}}")
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
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags.phase", equalTo("two"));

        List<Map<String, Object>> listed = list(authorization).path("EventIntegrations");
        assertEquals(1, listed.size());
        assertEquals("lifecycle-events", listed.getFirst().get("Name"));
        assertEquals(arn, listed.getFirst().get("EventIntegrationArn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/lifecycle-events/associations")
                .then()
                .statusCode(200)
                .body("EventIntegrationAssociations.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"replaced-events",
                          "Description":"alchemy event integration v2",
                          "EventBridgeBus":"default",
                          "EventFilter":{"Source":"%s"}
                        }
                        """.formatted(OTHER_SOURCE))
                .when()
                .post("/eventIntegrations")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/eventIntegrations/lifecycle-events")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/lifecycle-events")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/replaced-events")
                .then()
                .statusCode(200)
                .body("EventFilter.Source", equalTo(OTHER_SOURCE));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/eventIntegrations/replaced-events")
                .then()
                .statusCode(200);
    }

    @Test
    void createDuplicateEventIntegrationFailsWithDuplicateResourceException() {
        String authorization = auth("000000000612", EAST);
        create(authorization, """
                {
                  "Name":"dup-events",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"}
                }
                """.formatted(SOURCE));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"dup-events",
                          "EventBridgeBus":"default",
                          "EventFilter":{"Source":"%s"}
                        }
                        """.formatted(SOURCE))
                .when()
                .post("/eventIntegrations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("DuplicateResourceException"));
    }

    @Test
    void eventIntegrationsAreIsolatedByAccount() {
        String firstAuth = auth("000000000613", EAST);
        String secondAuth = auth("000000000614", EAST);

        String firstArn = create(firstAuth, """
                {
                  "Name":"shared-name",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"}
                }
                """.formatted(SOURCE));
        String secondArn = create(secondAuth, """
                {
                  "Name":"shared-name",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"}
                }
                """.formatted(OTHER_SOURCE));

        assertNotEquals(firstArn, secondArn);
        get(firstAuth, "shared-name").then().body("EventFilter.Source", equalTo(SOURCE));
        get(secondAuth, "shared-name").then().body("EventFilter.Source", equalTo(OTHER_SOURCE));
    }

    @Test
    void eventIntegrationsAreIsolatedByRegion() {
        String eastAuth = auth("000000000615", EAST);
        String westAuth = auth("000000000615", WEST);

        create(eastAuth, """
                {
                  "Name":"regional-events",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"}
                }
                """.formatted(SOURCE));
        create(westAuth, """
                {
                  "Name":"regional-events",
                  "EventBridgeBus":"default",
                  "EventFilter":{"Source":"%s"}
                }
                """.formatted(OTHER_SOURCE));

        get(eastAuth, "regional-events").then()
                .body("EventFilter.Source", equalTo(SOURCE))
                .body("EventIntegrationArn", equalTo(arn("000000000615", EAST, "regional-events")));
        get(westAuth, "regional-events").then()
                .body("EventFilter.Source", equalTo(OTHER_SOURCE))
                .body("EventIntegrationArn", equalTo(arn("000000000615", WEST, "regional-events")));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/app-integrations/aws4_request";
    }

    private static String arn(String accountId, String region, String name) {
        return "arn:aws:app-integrations:" + region + ":" + accountId + ":event-integration/" + name;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/eventIntegrations")
                .then()
                .statusCode(200)
                .body("EventIntegrationArn", notNullValue())
                .extract()
                .path("EventIntegrationArn");
    }

    private static Response get(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations/" + name);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/eventIntegrations")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
