package io.github.hectorvent.floci.services.finspace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies FinSpace restJson1 classic environment lifecycle used by Alchemy Environment tests. */
@QuarkusTest
class FinSpaceEnvironmentIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING_ID = "zzzzzzzzzzzzzzzzzzzzzzzzzz";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getEnvironmentOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/environment/" + MISSING_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getEnvironmentWithAMalformedIdFailsWithValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/environment/not valid!")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createGetUpdateTagsDeleteLifecycle() {
        String authorization = auth(EAST);
        String name = "lifecycle-env-" + UUID.randomUUID().toString().substring(0, 8);

        String environmentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"initial description",
                          "tags":{"fixture":"finspace-environment"}
                        }
                        """.formatted(name))
                .when()
                .post("/environment")
                .then()
                .statusCode(200)
                .body("environmentId", notNullValue())
                .body("environmentArn", notNullValue())
                .extract()
                .path("environmentId");

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/environment/" + environmentId)
                .then()
                .statusCode(200)
                .body("environment.environmentId", equalTo(environmentId))
                .body("environment.name", equalTo(name))
                .body("environment.status", equalTo("CREATED"))
                .body("environment.description", equalTo("initial description"))
                .body("environment.environmentArn", notNullValue())
                .extract()
                .path("environment.environmentArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"description\":\"updated description\"}")
                .when()
                .put("/environment/" + environmentId)
                .then()
                .statusCode(200)
                .body("environment.environmentId", equalTo(environmentId))
                .body("environment.description", equalTo("updated description"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("finspace-environment"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"team\":\"platform\"}}")
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
                .body("tags.team", equalTo("platform"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/environment/" + environmentId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/environment/" + environmentId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getDatasetWithoutAnEnvironmentFailsWithPlainTextAccessDenied() {
        String body = given()
                .header("Authorization", dataAuth(EAST))
                .when()
                .get("/datasetsv2/" + MISSING_ID)
                .then()
                .statusCode(403)
                .contentType("text/plain")
                .extract()
                .asString();
        assertEquals("Failed to retrieve environment", body.trim());
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260205/" + region
                + "/finspace/aws4_request";
    }

    private static String dataAuth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260205/" + region
                + "/finspace-api/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
