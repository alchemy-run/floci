package io.github.hectorvent.floci.services.schemas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies EventBridge Schema Registry restJson1 operations used by Alchemy
 * {@code Schema.test.ts}: registry + schema create, no-op (same content keeps
 * version 1), content update publishes version 2, tags, and delete.
 */
@QuarkusTest
class SchemasIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000401";
    private static final String CONTENT_V1 = """
            {"openapi":"3.0.0","info":{"version":"1.0.0","title":"OrderCreated"},"paths":{},\
            "components":{"schemas":{"OrderCreated":{"type":"object","properties":{"orderId":{"type":"string"}}}}}}
            """;
    private static final String CONTENT_V2 = """
            {"openapi":"3.0.0","info":{"version":"1.0.0","title":"OrderCreated"},"paths":{},\
            "components":{"schemas":{"OrderCreated":{"type":"object","properties":{"orderId":{"type":"string"},\
            "amount":{"type":"number"}}}}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeRegistryOnAMissingNameFailsWithNotFoundException() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/v1/registries/name/does-not-exist")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void schemaCreatePublishVersionAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String registryName = "OrderEvents";
        String schemaName = "OrderCreated";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"Order events\"}")
                .when()
                .post("/v1/registries/name/" + registryName)
                .then()
                .statusCode(200)
                .body("RegistryName", equalTo(registryName))
                .body("RegistryArn", org.hamcrest.Matchers.containsString(":registry/" + registryName))
                .body("Description", equalTo("Order events"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + registryName)
                .then()
                .statusCode(200)
                .body("RegistryName", equalTo(registryName))
                .body("Description", equalTo("Order events"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/policy?registryName=" + registryName)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        Map<String, Object> created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Content":%s,
                          "Description":"Order created event",
                          "Type":"OpenApi3",
                          "tags":{"purpose":"alchemy-test","alchemy::id":"OrderCreated"}
                        }
                        """.formatted(quote(CONTENT_V1)))
                .when()
                .post("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200)
                .body("SchemaName", equalTo(schemaName))
                .body("SchemaVersion", equalTo("1"))
                .body("Type", equalTo("OpenApi3"))
                .body("Description", equalTo("Order created event"))
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("Content", notNullValue())
                .extract()
                .jsonPath()
                .getMap(".");

        String schemaArn = (String) created.get("SchemaArn");
        assertEquals(true, schemaArn.contains(":schema/" + registryName + "/" + schemaName));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200)
                .body("SchemaVersion", equalTo("1"))
                .body("Type", equalTo("OpenApi3"))
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + schemaArn)
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"Order created event\"}")
                .when()
                .put("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200)
                .body("SchemaVersion", equalTo("1"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Content":%s,
                          "Type":"OpenApi3"
                        }
                        """.formatted(quote(CONTENT_V2)))
                .when()
                .put("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200)
                .body("SchemaVersion", equalTo("2"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200)
                .body("SchemaVersion", equalTo("2"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + registryName + "/schemas/name/" + schemaName)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + registryName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + registryName)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/schemas/aws4_request";
    }
}
