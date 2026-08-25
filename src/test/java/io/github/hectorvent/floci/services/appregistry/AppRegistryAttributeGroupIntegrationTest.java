package io.github.hectorvent.floci.services.appregistry;

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

/** Verifies AppRegistry restJson1 attribute-group lifecycle. */
@QuarkusTest
class AppRegistryAttributeGroupIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAttributeGroupOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000601", EAST))
                .when()
                .get("/attribute-groups/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void attributeGroupCreateUpdateTagsDeleteLifecycle() {
        String authorization = auth("000000000602", EAST);
        String name = "lifecycle-attribute-group";
        Map<String, Object> created = create(authorization, """
                {
                  "name":"%s",
                  "description":"attribute group lifecycle test",
                  "attributes":"{\\"owner\\":\\"platform-team\\",\\"costCenter\\":\\"1234\\"}",
                  "tags":{"purpose":"lifecycle"},
                  "clientToken":"%s"
                }
                """.formatted(name, UUID.randomUUID()));

        String id = (String) created.get("id");
        String arn = (String) created.get("arn");
        assertTrue(arn.contains(":servicecatalog:"));
        assertTrue(arn.contains(":/attribute-groups/"));
        assertEquals(name, created.get("name"));
        assertEquals("attribute group lifecycle test", created.get("description"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("arn", equalTo(arn))
                .body("name", equalTo(name))
                .body("description", equalTo("attribute group lifecycle test"))
                .body("attributes", equalTo("{\"owner\":\"platform-team\",\"costCenter\":\"1234\"}"))
                .body("tags.purpose", equalTo("lifecycle"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + name)
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + encode(arn))
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("lifecycle"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"updated description",
                          "attributes":"{\\"owner\\":\\"commerce-team\\",\\"costCenter\\":\\"5678\\",\\"tier\\":1}"
                        }
                        """)
                .when()
                .patch("/attribute-groups/" + id)
                .then()
                .statusCode(200)
                .body("attributeGroup.id", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"purpose\":\"lifecycle-updated\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + id)
                .then()
                .statusCode(200)
                .body("description", equalTo("updated description"))
                .body("attributes", equalTo("{\"owner\":\"commerce-team\",\"costCenter\":\"5678\",\"tier\":1}"))
                .body("tags.purpose", equalTo("lifecycle-updated"))
                .body("id", equalTo(id))
                .body("arn", equalTo(arn));

        List<Map<String, Object>> listed = list(authorization).path("attributeGroups");
        assertEquals(1, listed.size());
        assertEquals(arn, listed.getFirst().get("arn"));
        assertEquals(name, listed.getFirst().get("name"));
        assertEquals(id, listed.getFirst().get("id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/attribute-groups/" + id)
                .then()
                .statusCode(200)
                .body("attributeGroup.id", equalTo(id));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + id)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createAttributeGroupWithDuplicateNameFailsWithConflictException() {
        String authorization = auth("000000000603", EAST);
        create(authorization, """
                {
                  "name":"dup-attribute-group",
                  "attributes":"{}",
                  "clientToken":"%s"
                }
                """.formatted(UUID.randomUUID()));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"dup-attribute-group",
                          "attributes":"{\\"x\\":1}",
                          "clientToken":"%s"
                        }
                        """.formatted(UUID.randomUUID()))
                .when()
                .post("/attribute-groups")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"))
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void attributeGroupsAreIsolatedByRegion() {
        String eastAuth = auth("000000000604", EAST);
        String westAuth = auth("000000000604", WEST);

        Map<String, Object> east = create(eastAuth, """
                {
                  "name":"regional-attribute-group",
                  "attributes":"{\\"region\\":\\"east\\"}",
                  "clientToken":"%s"
                }
                """.formatted(UUID.randomUUID()));
        Map<String, Object> west = create(westAuth, """
                {
                  "name":"regional-attribute-group",
                  "attributes":"{\\"region\\":\\"west\\"}",
                  "clientToken":"%s"
                }
                """.formatted(UUID.randomUUID()));

        assertNotEquals(east.get("id"), west.get("id"));
        get(eastAuth, (String) east.get("id")).then().body("attributes", equalTo("{\"region\":\"east\"}"));
        get(westAuth, (String) west.get("id")).then().body("attributes", equalTo("{\"region\":\"west\"}"));
    }

    @Test
    void createAttributeGroupReplaysMatchingClientToken() {
        String authorization = auth("000000000605", EAST);
        String token = UUID.randomUUID().toString();
        Map<String, Object> first = create(authorization, """
                {
                  "name":"idempotent-attribute-group",
                  "attributes":"{\\"once\\":true}",
                  "clientToken":"%s"
                }
                """.formatted(token));

        Map<String, Object> second = create(authorization, """
                {
                  "name":"idempotent-attribute-group",
                  "attributes":"{\\"once\\":true}",
                  "clientToken":"%s"
                }
                """.formatted(token));

        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("arn"), second.get("arn"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/servicecatalog/aws4_request";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/attribute-groups")
                .then()
                .statusCode(200)
                .body("attributeGroup.id", notNullValue())
                .body("attributeGroup.arn", startsWith("arn:aws:servicecatalog:"))
                .extract()
                .jsonPath()
                .getMap("attributeGroup");
    }

    private static Response get(String authorization, String identifier) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups/" + identifier)
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/attribute-groups")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
