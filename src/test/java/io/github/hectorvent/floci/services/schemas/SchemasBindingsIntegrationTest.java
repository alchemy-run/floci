package io.github.hectorvent.floci.services.schemas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SchemasBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000801";
    private static final String CONTENT = """
            {"openapi":"3.0.0","info":{"version":"1.0.0","title":"OrderCreated"},"paths":{},\
            "components":{"schemas":{"OrderCreated":{"type":"object","properties":{\
            "orderId":{"type":"string"},"amount":{"type":"number"}}}}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeSchemaListVersionsSearchExportAndCodeBinding() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"bindings registry\"}")
                .when()
                .post("/v1/registries/name/bindings-registry")
                .then()
                .statusCode(200)
                .body("RegistryName", equalTo("bindings-registry"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Type\":\"OpenApi3\",\"Content\":" + jsonString(CONTENT)
                        + ",\"Description\":\"order created\"}")
                .when()
                .post("/v1/registries/name/bindings-registry/schemas/name/OrderCreated")
                .then()
                .statusCode(200)
                .body("SchemaName", equalTo("OrderCreated"))
                .body("Type", equalTo("OpenApi3"))
                .body("SchemaVersion", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/name/OrderCreated")
                .then()
                .statusCode(200)
                .body("Content", containsString("orderId"))
                .body("Type", equalTo("OpenApi3"))
                .body("SchemaVersion", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/versions")
                .then()
                .statusCode(200)
                .body("SchemaVersions.SchemaVersion", hasItem("1"));

        given()
                .header("Authorization", authorization)
                .queryParam("keywords", "order")
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/search")
                .then()
                .statusCode(200)
                .body("Schemas.SchemaName", hasItem("OrderCreated"));

        given()
                .header("Authorization", authorization)
                .queryParam("type", "JSONSchemaDraft4")
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/export")
                .then()
                .statusCode(403)
                .header("X-Amzn-Errortype", equalTo("ForbiddenException"))
                .body("__type", equalTo("ForbiddenException"))
                .body("message", containsString("export"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/language/Python36")
                .then()
                .statusCode(200)
                .body("Status", equalTo("CREATE_COMPLETE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/language/Python36")
                .then()
                .statusCode(200)
                .body("Status", equalTo("CREATE_COMPLETE"));

        byte[] zip = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/language/Python36/source")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertTrue(zip.length > 0);

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/registries/name/bindings-registry/schemas/name/OrderCreated/language/Python36")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));
    }

    @Test
    void getDiscoveredSchemaInfersOpenApiFromSampleEvent() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000802", EAST))
                .body("""
                        {
                          "Type": "OpenApi3",
                          "Events": [
                            "{\\"version\\":\\"0\\",\\"detail-type\\":\\"OrderCreated\\",\\"source\\":\\"alchemy.test\\",\\"detail\\":{\\"orderId\\":\\"abc\\",\\"amount\\":42}}"
                          ]
                        }
                        """)
                .when()
                .post("/v1/discover")
                .then()
                .statusCode(200)
                .body("Content", containsString("openapi"))
                .body("Content.length()", greaterThan(0));
    }

    @Test
    void startAndStopDiscoverer() {
        String authorization = auth("000000000803", EAST);
        String discovererId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SourceArn": "arn:aws:events:us-east-1:000000000803:event-bus/bindings-bus",
                          "Description": "bindings discoverer"
                        }
                        """)
                .when()
                .post("/v1/discoverers")
                .then()
                .statusCode(200)
                .body("State", equalTo("STARTED"))
                .extract()
                .path("DiscovererId");

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/discoverers/id/" + discovererId + "/stop")
                .then()
                .statusCode(200)
                .body("State", equalTo("STOPPED"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/v1/discoverers/id/" + discovererId + "/start")
                .then()
                .statusCode(200)
                .body("State", equalTo("STARTED"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/schemas/aws4_request";
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
