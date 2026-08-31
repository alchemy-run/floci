package io.github.hectorvent.floci.services.schemas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies the EventBridge Schema Registry restJson1 lifecycle used by Alchemy. */
@QuarkusTest
class SchemasRegistryIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String OPENAPI = """
            {"openapi":"3.0.0","info":{"title":"UntrackedChild","version":"1.0.0"},"paths":{}}
            """.strip();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingRegistryReturnsNotFoundException() {
        given()
                .header("Authorization", auth("000000000210", EAST))
                .when()
                .get("/v1/registries/name/alchemy-nonexistent-schemas-registry")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createDescribeListUpdatePolicyTagAndDeleteRegistry() {
        String authorization = auth("000000000211", EAST);
        String name = "alchemy-schemas-lifecycle";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"alchemy schemas test registry\",\"tags\":{\"purpose\":\"alchemy-test\"}}")
                .when()
                .post("/v1/registries/name/" + name)
                .then()
                .statusCode(200)
                .body("RegistryName", equalTo(name))
                .body("RegistryArn", equalTo("arn:aws:schemas:" + EAST + ":000000000211:registry/" + name))
                .body("Description", equalTo("alchemy schemas test registry"))
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name)
                .then()
                .statusCode(200)
                .body("RegistryName", equalTo(name))
                .body("Description", equalTo("alchemy schemas test registry"))
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .queryParam("scope", "LOCAL")
                .when()
                .get("/v1/registries")
                .then()
                .statusCode(200)
                .body("Registries.find { it.RegistryName == '" + name + "' }.RegistryName", equalTo(name))
                .body("Registries.find { it.RegistryName == 'aws.events' }", nullValue());

        String arn = "arn:aws:schemas:" + EAST + ":000000000211:registry/" + name;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"alchemy::id\":\"TestRegistry\"}}")
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
                .body("tags['alchemy::id']", equalTo("TestRegistry"));

        String policy = """
                {"Version":"2012-10-17","Statement":[{"Sid":"AllowOwnAccountRead","Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000211:root"},"Action":["schemas:DescribeRegistry"],"Resource":"%s"}]}
                """.formatted(arn).strip();
        String revision = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .queryParam("registryName", name)
                .body("{\"Policy\":" + jsonString(policy) + "}")
                .when()
                .put("/v1/policy")
                .then()
                .statusCode(200)
                .body("Policy", equalTo(policy))
                .body("RevisionId", notNullValue())
                .extract()
                .path("RevisionId");
        assertNotNull(revision);

        given()
                .header("Authorization", authorization)
                .queryParam("registryName", name)
                .when()
                .get("/v1/policy")
                .then()
                .statusCode(200)
                .body("RevisionId", equalTo(revision))
                .body("Policy", equalTo(policy));

        given()
                .header("Authorization", authorization)
                .queryParam("registryName", name)
                .when()
                .delete("/v1/policy")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("registryName", name)
                .when()
                .get("/v1/policy")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Description\":\"updated description\"}")
                .when()
                .put("/v1/registries/name/" + name)
                .then()
                .statusCode(200)
                .body("Description", equalTo("updated description"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "purpose")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name)
                .then()
                .statusCode(200)
                .body("Description", equalTo("updated description"))
                .body("tags.purpose", nullValue())
                .body("tags['alchemy::id']", equalTo("TestRegistry"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"));
    }

    @Test
    void deleteRegistryWithSchemaFailsUntilSchemaIsRemoved() {
        String authorization = auth("000000000212", EAST);
        String name = "alchemy-schemas-cascade";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/v1/registries/name/" + name)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Type\":\"OpenApi3\",\"Content\":" + jsonString(OPENAPI) + "}")
                .when()
                .post("/v1/registries/name/" + name + "/schemas/name/UntrackedChild")
                .then()
                .statusCode(200)
                .body("SchemaName", equalTo("UntrackedChild"))
                .body("Type", equalTo("OpenApi3"))
                .body("SchemaVersion", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name + "/schemas")
                .then()
                .statusCode(200)
                .body("Schemas.size()", equalTo(1))
                .body("Schemas[0].SchemaName", equalTo("UntrackedChild"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + name)
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BadRequestException"))
                .body("__type", equalTo("BadRequestException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name + "/schemas/name/UntrackedChild")
                .then()
                .statusCode(200)
                .body("SchemaName", equalTo("UntrackedChild"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + name + "/schemas/name/UntrackedChild")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/registries/name/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/registries/name/" + name)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/schemas/aws4_request";
    }
}
