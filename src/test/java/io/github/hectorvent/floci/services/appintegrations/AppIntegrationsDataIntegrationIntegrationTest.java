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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies AppIntegrations restJson1 data-integration lifecycle. */
@QuarkusTest
class AppIntegrationsDataIntegrationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String KMS =
            "arn:aws:kms:us-east-1:000000000000:key/11111111-1111-1111-1111-111111111111";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataIntegrationOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/dataIntegrations/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void dataIntegrationCreateUpdateTagsDeleteLifecycle() {
        String authorization = auth("000000000502", EAST);
        String name = "lifecycle-data-integration";
        Map<String, Object> created = create(authorization, """
                {
                  "Name":"%s",
                  "Description":"alchemy data integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://content-bucket",
                  "Tags":{"purpose":"alchemy-test"}
                }
                """.formatted(name, KMS));

        String id = (String) created.get("Id");
        String arn = (String) created.get("Arn");
        assertTrue(arn.contains(":data-integration/"));
        assertEquals(name, created.get("Name"));
        assertEquals("alchemy data integration", created.get("Description"));
        assertEquals("s3://content-bucket", created.get("SourceURI"));
        assertEquals(KMS, created.get("KmsKey"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations/" + id)
                .then()
                .statusCode(200)
                .body("Id", equalTo(id))
                .body("Arn", equalTo(arn))
                .body("Name", equalTo(name))
                .body("Description", equalTo("alchemy data integration"))
                .body("SourceURI", equalTo("s3://content-bucket"))
                .body("KmsKey", equalTo(KMS))
                .body("Tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Id", equalTo(id));

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
                        {
                          "Description":"alchemy data integration v2"
                        }
                        """)
                .when()
                .patch("/dataIntegrations/" + id)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations/" + id)
                .then()
                .statusCode(200)
                .body("Description", equalTo("alchemy data integration v2"))
                .body("Tags.purpose", equalTo("alchemy-test"))
                .body("Tags.phase", equalTo("two"))
                .body("Id", equalTo(id))
                .body("Arn", equalTo(arn));

        List<Map<String, Object>> listed = list(authorization).path("DataIntegrations");
        assertEquals(1, listed.size());
        assertEquals(arn, listed.getFirst().get("Arn"));
        assertEquals(name, listed.getFirst().get("Name"));
        assertEquals("s3://content-bucket", listed.getFirst().get("SourceURI"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/dataIntegrations/" + id)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDataIntegrationWithDuplicateNameFailsWithDuplicateResourceException() {
        String authorization = auth("000000000503", EAST);
        create(authorization, """
                {
                  "Name":"dup-data-integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://one"
                }
                """.formatted(KMS));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"dup-data-integration",
                          "KmsKey":"%s",
                          "SourceURI":"s3://two"
                        }
                        """.formatted(KMS))
                .when()
                .post("/dataIntegrations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("DuplicateResourceException"));
    }

    @Test
    void dataIntegrationsAreIsolatedByRegion() {
        String eastAuth = auth("000000000504", EAST);
        String westAuth = auth("000000000504", WEST);

        Map<String, Object> east = create(eastAuth, """
                {
                  "Name":"regional-data-integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://east"
                }
                """.formatted(KMS));
        Map<String, Object> west = create(westAuth, """
                {
                  "Name":"regional-data-integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://west"
                }
                """.formatted(KMS));

        assertNotEquals(east.get("Id"), west.get("Id"));
        get(eastAuth, (String) east.get("Id")).then().body("SourceURI", equalTo("s3://east"));
        get(westAuth, (String) west.get("Id")).then().body("SourceURI", equalTo("s3://west"));
    }

    @Test
    void createDataIntegrationReplaysMatchingClientToken() {
        String authorization = auth("000000000505", EAST);
        String token = UUID.randomUUID().toString();
        Map<String, Object> first = create(authorization, """
                {
                  "Name":"idempotent-data-integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://once",
                  "ClientToken":"%s"
                }
                """.formatted(KMS, token));

        Map<String, Object> second = create(authorization, """
                {
                  "Name":"idempotent-data-integration",
                  "KmsKey":"%s",
                  "SourceURI":"s3://once",
                  "ClientToken":"%s"
                }
                """.formatted(KMS, token));

        assertEquals(first.get("Id"), second.get("Id"));
        assertEquals(first.get("Arn"), second.get("Arn"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/app-integrations/aws4_request";
    }

    private static Map<String, Object> create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/dataIntegrations")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("Arn", startsWith("arn:aws:app-integrations:"))
                .extract()
                .jsonPath()
                .getMap(".");
    }

    private static Response get(String authorization, String identifier) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations/" + identifier)
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/dataIntegrations")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
