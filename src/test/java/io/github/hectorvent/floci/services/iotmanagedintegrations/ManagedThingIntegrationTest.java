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

/**
 * Verifies GetManagedThing 404 and the managed-thing restJson1 lifecycle used by
 * Alchemy ManagedThing tests.
 */
@QuarkusTest
class ManagedThingIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String ZIGBEE_QR = "Z:24FD5B0000015C63$I:83FED3407A939738";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getManagedThingOnANonexistentThingFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .get("/managed-things/alchemynonexistentthingprobe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateTagDeleteManagedThingLifecycle() {
        String authorization = auth("000000000702", EAST);
        String lockerId = createLocker(authorization);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Role":"DEVICE",
                          "AuthenticationMaterial":"%s",
                          "AuthenticationMaterialType":"ZIGBEE_QR_BAR_CODE",
                          "CredentialLockerId":"%s",
                          "Brand":"alchemy",
                          "SerialNumber":"SN-ALCHEMY-0001",
                          "Tags":{"fixture":"iot-mi-managed-thing"}
                        }
                        """.formatted(ZIGBEE_QR, lockerId))
                .when()
                .post("/managed-things")
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
                .body("Role", equalTo("DEVICE"))
                .body("Brand", equalTo("alchemy"))
                .body("SerialNumber", equalTo("SN-ALCHEMY-0001"))
                .body("CredentialLockerId", equalTo(lockerId))
                .body("Tags.fixture", equalTo("iot-mi-managed-thing"))
                .extract()
                .path("Arn");
        assertTrue(arn.contains(":managed-thing/"));

        List<Map<String, Object>> listed = list(authorization).path("Items");
        assertEquals(1, listed.size());
        assertEquals(id, listed.getFirst().get("Id"));
        assertEquals(arn, listed.getFirst().get("Arn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Brand\":\"alchemy-two\"}")
                .when()
                .put("/managed-things/" + id)
                .then()
                .statusCode(200);

        get(authorization, id).then()
                .statusCode(200)
                .body("Id", equalTo(id))
                .body("Brand", equalTo("alchemy-two"));

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
                .body("Tags.fixture", equalTo("iot-mi-managed-thing"))
                .body("Tags.phase", equalTo("two"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/managed-things/" + id)
                .then()
                .statusCode(200);

        get(authorization, id).then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void managedThingsAreIsolatedByAccountAndRegion() {
        String firstAuth = auth("000000000703", EAST);
        String secondAuth = auth("000000000704", EAST);
        String westAuth = auth("000000000703", WEST);

        String firstId = createThing(firstAuth, "east-one");
        String secondId = createThing(secondAuth, "east-two");
        String westId = createThing(westAuth, "west-one");

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

    private static String createLocker(String authorization) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"managed-thing-locker\"}")
                .when()
                .post("/credential-lockers")
                .then()
                .statusCode(200)
                .extract()
                .path("Id");
    }

    private static String createThing(String authorization, String brand) {
        String lockerId = createLocker(authorization);
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Role":"DEVICE",
                          "AuthenticationMaterial":"%s",
                          "AuthenticationMaterialType":"ZIGBEE_QR_BAR_CODE",
                          "CredentialLockerId":"%s",
                          "Brand":"%s"
                        }
                        """.formatted(ZIGBEE_QR, lockerId, brand))
                .when()
                .post("/managed-things")
                .then()
                .statusCode(200)
                .extract()
                .path("Id");
    }

    private static Response get(String authorization, String id) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/managed-things/" + id);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/managed-things")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
