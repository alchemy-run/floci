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

/** Verifies FinSpace kdb environment restJson1 get-not-found and lifecycle. */
@QuarkusTest
class FinSpaceKxEnvironmentIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "zzzzzzzzzzzzzzzzzzzzzzzzzz";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKxEnvironmentOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/kx/environments/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxDatabaseOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/kx/environments/" + MISSING + "/databases/nodb")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxClusterOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/kx/environments/" + MISSING + "/clusters/nocluster")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxScalingGroupOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/kx/environments/" + MISSING + "/scalingGroups/nogroup")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxVolumeOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/kx/environments/" + MISSING + "/kxvolumes/novolume")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void kxEnvironmentDatabaseCreateGetUpdateTagDeleteLifecycle() {
        String authorization = auth(EAST);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "kdb-" + suffix;

        String environmentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy kdb test environment",
                          "kmsKeyId":"arn:aws:kms:%s:000000000000:key/%s",
                          "tags":{"fixture":"finspace-kx"}
                        }
                        """.formatted(name, EAST, suffix))
                .when()
                .post("/kx/environments")
                .then()
                .statusCode(200)
                .body("name", equalTo(name))
                .body("status", equalTo("CREATED"))
                .body("environmentId", notNullValue())
                .body("environmentArn", notNullValue())
                .extract().path("environmentId");

        String environmentArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId)
                .then()
                .statusCode(200)
                .body("environmentId", equalTo(environmentId))
                .body("name", equalTo(name))
                .body("status", equalTo("CREATED"))
                .body("description", equalTo("alchemy kdb test environment"))
                .extract().path("environmentArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(environmentArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("finspace-kx"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"description":"alchemy kdb test environment (updated)"}
                        """)
                .when()
                .put("/kx/environments/" + environmentId)
                .then()
                .statusCode(200)
                .body("description", equalTo("alchemy kdb test environment (updated)"));

        String databaseArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "databaseName":"ticks-%s",
                          "description":"alchemy kdb test database",
                          "clientToken":"%s",
                          "tags":{"fixture":"finspace-kx"}
                        }
                        """.formatted(suffix, UUID.randomUUID()))
                .when()
                .post("/kx/environments/" + environmentId + "/databases")
                .then()
                .statusCode(200)
                .body("databaseName", equalTo("ticks-" + suffix))
                .body("environmentId", equalTo(environmentId))
                .body("databaseArn", notNullValue())
                .extract().path("databaseArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/databases/ticks-" + suffix)
                .then()
                .statusCode(200)
                .body("databaseArn", equalTo(databaseArn))
                .body("description", equalTo("alchemy kdb test database"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"description":"alchemy kdb test database (updated)","clientToken":"%s"}
                        """.formatted(UUID.randomUUID()))
                .when()
                .put("/kx/environments/" + environmentId + "/databases/ticks-" + suffix)
                .then()
                .statusCode(200)
                .body("description", equalTo("alchemy kdb test database (updated)"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/kx/environments/" + environmentId + "/databases/ticks-" + suffix)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/kx/environments/" + environmentId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260205/" + region
                + "/finspace/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
