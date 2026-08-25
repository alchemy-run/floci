package io.github.hectorvent.floci.services.iotmanagedintegrations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Binding-probe operations used by Alchemy's IoT Managed Integrations
 * Bindings.test.ts: typed 404s plus the public capability schema catalog.
 */
@QuarkusTest
class BindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getManagedThingStateOnANonexistentThingFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000009101", EAST))
                .when()
                .get("/managed-thing-states/alchemynonexistentthingprobe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getDeviceDiscoveryOnANonexistentDiscoveryFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000009102", EAST))
                .when()
                .get("/device-discoveries/alchemynonexistentdiscoveryprobe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void sendConnectorEventToANonexistentConnectorFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000009103", EAST))
                .body("{\"Operation\":\"DEVICE_EVENT\"}")
                .when()
                .post("/connector-event/alchemynonexistentconnectorprobe")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listSchemaVersionsReadsThePublicCapabilityCatalog() {
        given()
                .header("Authorization", auth("000000009104", EAST))
                .queryParam("MaxResults", "3")
                .when()
                .get("/schema-versions/capability")
                .then()
                .statusCode(200)
                .body("Items.size()", greaterThan(0));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/iotmanagedintegrations/aws4_request";
    }
}
