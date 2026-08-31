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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the IoT Managed Integrations destination restJson1 lifecycle used by Alchemy. */
@QuarkusTest
class DestinationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String STREAM_ARN =
            "arn:aws:kinesis:us-east-1:000000002401:stream/events";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000002401:role/DeliveryRole";
    private static final String OTHER_STREAM_ARN =
            "arn:aws:kinesis:us-east-1:000000002401:stream/other";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDestinationOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000002401", EAST))
                .when()
                .get("/destinations/alchemy-nonexistent-destination")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void destinationCreateUpdateTagsListDeleteLifecycle() {
        String authorization = auth("000000002402", EAST);
        String name = create(authorization, """
                {
                  "Name":"lifecycle-events",
                  "DeliveryDestinationArn":"%s",
                  "DeliveryDestinationType":"KINESIS",
                  "RoleArn":"%s",
                  "Description":"phase one",
                  "Tags":{"fixture":"iot-mi-destination"}
                }
                """.formatted(STREAM_ARN, ROLE_ARN));
        assertEquals("lifecycle-events", name);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/destinations/lifecycle-events")
                .then()
                .statusCode(200)
                .body("Name", equalTo("lifecycle-events"))
                .body("DeliveryDestinationType", equalTo("KINESIS"))
                .body("DeliveryDestinationArn", equalTo(STREAM_ARN))
                .body("RoleArn", equalTo(ROLE_ARN))
                .body("Description", equalTo("phase one"))
                .body("Tags.fixture", equalTo("iot-mi-destination"))
                .body("CreatedAt", notNullValue());

        String arn = "arn:aws:iotmanagedintegrations:" + EAST + ":000000002402:destination/lifecycle-events";
        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("iot-mi-destination"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DeliveryDestinationArn":"%s",
                          "DeliveryDestinationType":"KINESIS",
                          "RoleArn":"%s",
                          "Description":"phase two"
                        }
                        """.formatted(STREAM_ARN, ROLE_ARN))
                .when()
                .put("/destinations/lifecycle-events")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/destinations/lifecycle-events")
                .then()
                .statusCode(200)
                .body("Description", equalTo("phase two"))
                .body("Name", equalTo("lifecycle-events"));

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
                .body("Tags.fixture", equalTo("iot-mi-destination"))
                .body("Tags.phase", equalTo("two"));

        List<Map<String, Object>> listed = list(authorization).path("DestinationList");
        assertEquals(1, listed.size());
        assertEquals("lifecycle-events", listed.getFirst().get("Name"));
        assertEquals(STREAM_ARN, listed.getFirst().get("DeliveryDestinationArn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/destinations/lifecycle-events")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/destinations/lifecycle-events")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDuplicateDestinationFailsWithConflictException() {
        String authorization = auth("000000002403", EAST);
        create(authorization, """
                {
                  "Name":"dup-events",
                  "DeliveryDestinationArn":"%s",
                  "DeliveryDestinationType":"KINESIS",
                  "RoleArn":"%s"
                }
                """.formatted(STREAM_ARN, ROLE_ARN));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"dup-events",
                          "DeliveryDestinationArn":"%s",
                          "DeliveryDestinationType":"KINESIS",
                          "RoleArn":"%s"
                        }
                        """.formatted(OTHER_STREAM_ARN, ROLE_ARN))
                .when()
                .post("/destinations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void destinationsAreIsolatedByAccountAndRegion() {
        String firstAuth = auth("000000002404", EAST);
        String secondAuth = auth("000000002405", EAST);
        String westAuth = auth("000000002404", WEST);

        create(firstAuth, """
                {
                  "Name":"shared-name",
                  "DeliveryDestinationArn":"%s",
                  "DeliveryDestinationType":"KINESIS",
                  "RoleArn":"%s",
                  "Description":"east-first"
                }
                """.formatted(STREAM_ARN, ROLE_ARN));
        create(secondAuth, """
                {
                  "Name":"shared-name",
                  "DeliveryDestinationArn":"%s",
                  "DeliveryDestinationType":"KINESIS",
                  "RoleArn":"%s",
                  "Description":"east-second"
                }
                """.formatted(STREAM_ARN, ROLE_ARN));
        create(westAuth, """
                {
                  "Name":"shared-name",
                  "DeliveryDestinationArn":"%s",
                  "DeliveryDestinationType":"KINESIS",
                  "RoleArn":"%s",
                  "Description":"west"
                }
                """.formatted(STREAM_ARN, ROLE_ARN));

        get(firstAuth, "shared-name").then()
                .statusCode(200)
                .body("Description", equalTo("east-first"));
        get(secondAuth, "shared-name").then()
                .statusCode(200)
                .body("Description", equalTo("east-second"));
        get(westAuth, "shared-name").then()
                .statusCode(200)
                .body("Description", equalTo("west"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/iotmanagedintegrations/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String create(String authorization, String body) {
        String name = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/destinations")
                .then()
                .statusCode(200)
                .body("Name", notNullValue())
                .extract()
                .path("Name");
        assertTrue(name != null && !name.isBlank());
        return name;
    }

    private static Response get(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/destinations/" + name);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/destinations")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
