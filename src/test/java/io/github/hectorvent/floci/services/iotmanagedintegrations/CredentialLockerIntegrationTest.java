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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the IoT Managed Integrations credential-locker restJson1 lifecycle used by Alchemy. */
@QuarkusTest
class CredentialLockerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCredentialLockerOnANonexistentLockerFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000601", EAST))
                .when()
                .get("/credential-lockers/alchemynonexistentlockerprobe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetTagDeleteCredentialLockerLifecycle() {
        String authorization = auth("000000000602", EAST);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"lifecycle-locker",
                          "Tags":{"fixture":"iot-mi-credential-locker"}
                        }
                        """)
                .when()
                .post("/credential-lockers")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("Arn", notNullValue())
                .body("CreatedAt", notNullValue())
                .extract()
                .path("Id");

        String arn = get(authorization, id).then()
                .statusCode(200)
                .body("Id", equalTo(id))
                .body("Name", equalTo("lifecycle-locker"))
                .body("Tags.fixture", equalTo("iot-mi-credential-locker"))
                .extract()
                .path("Arn");
        assertTrue(arn.contains(":credential-locker/"));

        List<Map<String, Object>> listed = list(authorization).path("Items");
        assertEquals(1, listed.size());
        assertEquals(id, listed.getFirst().get("Id"));
        assertEquals(arn, listed.getFirst().get("Arn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        get(authorization, id).then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("iot-mi-credential-locker"))
                .body("Tags.phase", equalTo("two"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/credential-lockers/" + id)
                .then()
                .statusCode(200);

        get(authorization, id).then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void lockersAreIsolatedByAccountAndRegion() {
        String firstAuth = auth("000000000603", EAST);
        String secondAuth = auth("000000000604", EAST);
        String westAuth = auth("000000000603", WEST);

        String firstId = create(firstAuth, "shared-name");
        String secondId = create(secondAuth, "shared-name");
        String westId = create(westAuth, "shared-name");

        assertNotEquals(firstId, secondId);
        assertNotEquals(firstId, westId);
        get(firstAuth, firstId).then().statusCode(200);
        get(secondAuth, secondId).then().statusCode(200);
        get(westAuth, westId).then().statusCode(200);
        get(firstAuth, secondId).then().statusCode(404);
        get(firstAuth, westId).then().statusCode(404);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/iotmanagedintegrations/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String create(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"" + name + "\"}")
                .when()
                .post("/credential-lockers")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract()
                .path("Id");
    }

    private static Response get(String authorization, String id) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/credential-lockers/" + id);
    }

    private static io.restassured.response.Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/credential-lockers")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
