package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Verifies Macie2 restJson1 session enablement plus allow-list, custom data
 * identifier, and findings-filter lifecycle — the operations Alchemy
 * {@code Resources.test.ts} drives.
 */
@QuarkusTest
class Macie2IntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000921";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMacieSessionOnAFreshAccountIsAccessDenied() {
        get(auth("000000000902", EAST), "/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("Macie is not enabled"));
    }

    @Test
    void enableCreateUpdateReplaceAndDisable() {
        String authorization = auth(ACCOUNT, EAST);

        post(authorization, "/macie", "{\"status\":\"ENABLED\"}")
                .then()
                .statusCode(200);

        get(authorization, "/macie")
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("serviceRole", containsString("AWSServiceRoleForAmazonMacie"));

        String allowListId = post(authorization, "/allow-lists", """
                {"name":"Ignore","description":"internal ticket ids",\
                "criteria":{"regex":"TICKET-[0-9]{6}"},\
                "tags":{"env":"test","alchemy::id":"Ignore"}}
                """)
                .then()
                .statusCode(200)
                .body("arn", containsString(":allow-list/"))
                .extract()
                .path("id");

        get(authorization, "/allow-lists/" + allowListId)
                .then()
                .statusCode(200)
                .body("criteria.regex", equalTo("TICKET-[0-9]{6}"))
                .body("tags.env", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("Ignore"));

        String identifierId = post(authorization, "/custom-data-identifiers", """
                {"name":"EmployeeId","regex":"EMP-[0-9]{8}",\
                "description":"internal employee id",\
                "tags":{"env":"test","alchemy::id":"EmployeeId"}}
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("customDataIdentifierId");

        get(authorization, "/custom-data-identifiers/" + identifierId)
                .then()
                .statusCode(200)
                .body("arn", containsString(":custom-data-identifier/"))
                .body("regex", equalTo("EMP-[0-9]{8}"))
                .body("deleted", equalTo(false))
                .body("tags['alchemy::id']", equalTo("EmployeeId"));

        String filterId = post(authorization, "/findingsfilters", """
                {"name":"LowSeverity","action":"ARCHIVE","position":1,\
                "findingCriteria":{"criterion":{"severity.description":{"eq":["Low"]}}},\
                "tags":{"env":"test"}}
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        get(authorization, "/findingsfilters/" + filterId)
                .then()
                .statusCode(200)
                .body("action", equalTo("ARCHIVE"))
                .body("findingCriteria.criterion['severity.description'].eq", equalTo(List.of("Low")));

        put(authorization, "/allow-lists/" + allowListId, """
                {"name":"Ignore","description":"internal ticket ids v2",\
                "criteria":{"regex":"TICKET-[0-9]{7}"}}
                """)
                .then()
                .statusCode(200)
                .body("id", equalTo(allowListId));

        get(authorization, "/allow-lists/" + allowListId)
                .then()
                .statusCode(200)
                .body("criteria.regex", equalTo("TICKET-[0-9]{7}"))
                .body("description", equalTo("internal ticket ids v2"));

        patch(authorization, "/findingsfilters/" + filterId, "{\"action\":\"NOOP\"}")
                .then()
                .statusCode(200)
                .body("id", equalTo(filterId));

        String filterArn = get(authorization, "/findingsfilters/" + filterId)
                .then()
                .statusCode(200)
                .body("action", equalTo("NOOP"))
                .extract()
                .path("arn");

        String encoded = URLEncoder.encode(filterArn, StandardCharsets.UTF_8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"phase\":\"two\"}}")
                .when()
                .post("/tags/" + encoded)
                .then()
                .statusCode(204);

        get(authorization, "/findingsfilters/" + filterId)
                .then()
                .statusCode(200)
                .body("tags.phase", equalTo("two"));

        String replacedId = post(authorization, "/custom-data-identifiers", """
                {"name":"EmployeeIdV2","regex":"EMP-[0-9]{9}",\
                "description":"internal employee id"}
                """)
                .then()
                .statusCode(200)
                .extract()
                .path("customDataIdentifierId");

        delete(authorization, "/custom-data-identifiers/" + identifierId)
                .then()
                .statusCode(200);

        get(authorization, "/custom-data-identifiers/" + identifierId)
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));

        get(authorization, "/custom-data-identifiers/" + replacedId)
                .then()
                .statusCode(200)
                .body("regex", equalTo("EMP-[0-9]{9}"))
                .body("id", not(equalTo(identifierId)));

        post(authorization, "/custom-data-identifiers/list", "{}")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(replacedId));

        delete(authorization, "/macie")
                .then()
                .statusCode(200);

        get(authorization, "/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        get(authorization, "/allow-lists/" + allowListId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void enableWhenAlreadyEnabledIsConflict() {
        String authorization = auth("000000000903", EAST);
        post(authorization, "/macie", "{}").then().statusCode(200);
        post(authorization, "/macie", "{}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void getMissingAllowListWhenEnabledIsNotFound() {
        String authorization = auth("000000000904", EAST);
        post(authorization, "/macie", "{}").then().statusCode(200);
        get(authorization, "/allow-lists/missing")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response get(String authorization, String path) {
        return given()
                .header("Authorization", authorization)
                .header("Host", "macie2." + EAST + ".amazonaws.com")
                .when()
                .get(path);
    }

    private static Response post(String authorization, String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("Host", "macie2." + EAST + ".amazonaws.com")
                .body(body)
                .when()
                .post(path);
    }

    private static Response put(String authorization, String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("Host", "macie2." + EAST + ".amazonaws.com")
                .body(body)
                .when()
                .put(path);
    }

    private static Response patch(String authorization, String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .header("Host", "macie2." + EAST + ".amazonaws.com")
                .body(body)
                .when()
                .patch(path);
    }

    private static Response delete(String authorization, String path) {
        return given()
                .header("Authorization", authorization)
                .header("Host", "macie2." + EAST + ".amazonaws.com")
                .when()
                .delete(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/macie2/aws4_request";
    }
}
