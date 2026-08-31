package io.github.hectorvent.floci.services.repostspace;

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
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the re:Post Private restJson1 space lifecycle used by Alchemy. */
@QuarkusTest
class RepostspaceIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String MISSING = "SPalchemynonexistentprobe0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSpaceOnANonexistentSpaceFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", EAST))
                .when()
                .get("/spaces/" + MISSING)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING))
                .body("resourceType", equalTo("AWS::repostspace::space"));
    }

    @Test
    void getChannelOnANonexistentSpaceFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/spaces/" + MISSING + "/channels/CHalchemynonexistentprobe0")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING));
    }

    @Test
    void listChannelsOnANonexistentSpaceFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000502", EAST))
                .when()
                .get("/spaces/" + MISSING + "/channels")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING));
    }

    @Test
    void sendInvitesOnANonexistentSpaceFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000503", EAST))
                .body("""
                        {
                          "accessorIds":["00000000-0000-0000-0000-000000000000"],
                          "title":"alchemy probe",
                          "body":"alchemy probe"
                        }
                        """)
                .when()
                .post("/spaces/" + MISSING + "/invite")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void batchAddRoleOnANonexistentSpaceFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000504", EAST))
                .body("""
                        {
                          "accessorIds":["00000000-0000-0000-0000-000000000000"],
                          "role":"EXPERT"
                        }
                        """)
                .when()
                .post("/spaces/" + MISSING + "/roles")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateTagsAndDeleteSpaceLifecycle() {
        String authorization = auth("000000000302", EAST);
        String spaceId = create(authorization, """
                {
                  "name":"AlchemySpace",
                  "subdomain":"alchemy-e2e-repost-space",
                  "tier":"BASIC",
                  "description":"alchemy repostspace test",
                  "tags":{"fixture":"repostspace-space"}
                }
                """);

        assertTrue(spaceId.startsWith("SP"));
        assertEquals(24, spaceId.length());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/spaces/" + spaceId)
                .then()
                .statusCode(200)
                .body("spaceId", equalTo(spaceId))
                .body("arn", equalTo("arn:aws:repostspace:us-east-1:000000000302:space/" + spaceId))
                .body("status", equalTo("CREATE_COMPLETED"))
                .body("tier", equalTo("BASIC"))
                .body("description", equalTo("alchemy repostspace test"))
                .body("vanityDomain", equalTo("alchemy-e2e-repost-space"))
                .body("randomDomain", startsWith("alchemy-e2e-repost-space-"))
                .body("clientId", notNullValue())
                .body("configurationStatus", equalTo("CONFIGURED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"description\":\"alchemy repostspace test (updated)\"}")
                .when()
                .put("/spaces/" + spaceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/spaces/" + spaceId)
                .then()
                .statusCode(200)
                .body("spaceId", equalTo(spaceId))
                .body("description", equalTo("alchemy repostspace test (updated)"))
                .body("tier", equalTo("BASIC"));

        String arn = "arn:aws:repostspace:us-east-1:000000000302:space/" + spaceId;
        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("repostspace-space", tags.get("fixture"));

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
                .body("tags.fixture", equalTo("repostspace-space"))
                .body("tags.team", equalTo("platform"));

        List<Map<String, Object>> listed = list(authorization).path("spaces");
        assertEquals(1, listed.size());
        assertEquals(spaceId, listed.getFirst().get("spaceId"));
        assertEquals("CREATE_COMPLETED", listed.getFirst().get("status"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/spaces/" + spaceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/spaces/" + spaceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createConflictsOnDuplicateName() {
        String authorization = auth("000000000303", EAST);
        String first = create(authorization, """
                {"name":"SharedName","subdomain":"shared-name-one","tier":"BASIC"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"SharedName\",\"subdomain\":\"shared-name-two\",\"tier\":\"BASIC\"}")
                .when()
                .post("/spaces")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("resourceId", equalTo(first));
    }

    @Test
    void spacesAreIsolatedByAccount() {
        String firstAuth = auth("000000000304", EAST);
        String secondAuth = auth("000000000305", EAST);

        String first = create(firstAuth, """
                {"name":"SharedSpace","subdomain":"shared-space-acct","tier":"BASIC","description":"first"}
                """);
        String second = create(secondAuth, """
                {"name":"SharedSpace","subdomain":"shared-space-acct","tier":"STANDARD","description":"second"}
                """);

        assertNotEquals(first, second);
        get(firstAuth, first).then().body("description", equalTo("first")).body("tier", equalTo("BASIC"));
        get(secondAuth, second).then().body("description", equalTo("second")).body("tier", equalTo("STANDARD"));
    }

    @Test
    void spacesAreIsolatedByRegion() {
        String eastAuth = auth("000000000306", EAST);
        String westAuth = auth("000000000306", WEST);

        String eastId = create(eastAuth, """
                {"name":"RegionalSpace","subdomain":"regional-space","tier":"BASIC","description":"east"}
                """);
        String westId = create(westAuth, """
                {"name":"RegionalSpace","subdomain":"regional-space","tier":"STANDARD","description":"west"}
                """);

        assertNotEquals(eastId, westId);
        get(eastAuth, eastId).then().body("description", equalTo("east"));
        get(westAuth, westId).then().body("description", equalTo("west"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/repostspace/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/spaces")
                .then()
                .statusCode(200)
                .body("spaceId", notNullValue())
                .extract().path("spaceId");
    }

    private static Response get(String authorization, String spaceId) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/spaces/" + spaceId)
                .then()
                .statusCode(200)
                .extract().response();
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/spaces")
                .then()
                .statusCode(200)
                .extract().response();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
