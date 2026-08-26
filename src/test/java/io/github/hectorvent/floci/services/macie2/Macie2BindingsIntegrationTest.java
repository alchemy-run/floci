package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Macie2 restJson1 operations Alchemy {@code Bindings.test.ts}
 * drives through the Lambda fixture.
 */
@QuarkusTest
class Macie2BindingsIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void testCustomDataIdentifierCountsRegexMatches() {
        String authorization = auth("000000000821", EAST);
        enable(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"regex":"EMP-[0-9]{8}","sampleText":"ids EMP-12345678 and EMP-87654321 but not EMP-123"}
                        """)
                .when()
                .post("/custom-data-identifiers/test")
                .then()
                .statusCode(200)
                .body("matchCount", equalTo(2));
    }

    @Test
    void sampleFindingsAndBindingReads() {
        String authorization = auth("000000000822", EAST);
        enable(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/findings/sample")
                .then()
                .statusCode(200);

        String findingId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/findings")
                .then()
                .statusCode(200)
                .body("findingIds", hasSize(greaterThan(0)))
                .extract()
                .path("findingIds[0]");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"findingIds\":[\"" + findingId + "\"]}")
                .when()
                .post("/findings/describe")
                .then()
                .statusCode(200)
                .body("findings", hasSize(1))
                .body("findings[0].sample", equalTo(true))
                .body("findings[0].type", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"groupBy\":\"severity.description\"}")
                .when()
                .post("/findings/statistics")
                .then()
                .statusCode(200)
                .body("countsByGroup", hasSize(greaterThan(0)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/datasources/s3/statistics")
                .then()
                .statusCode(200)
                .body("bucketCount", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/datasources/search-resources")
                .then()
                .statusCode(200)
                .body("matchingResources", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/managed-data-identifiers/list")
                .then()
                .statusCode(200)
                .body("items", hasSize(greaterThan(0)));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/allow-lists")
                .then()
                .statusCode(200)
                .body("allowLists", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/findingsfilters")
                .then()
                .statusCode(200)
                .body("findingsFilterListItems", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/jobs/list")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/classification-export-configuration")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/usage")
                .then()
                .statusCode(200)
                .body("usageTotals", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/automated-discovery/configuration")
                .then()
                .statusCode(200)
                .body("status", equalTo("DISABLED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/classification-scopes")
                .then()
                .statusCode(200)
                .body("classificationScopes", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/reveal-configuration")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/administrator")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitations/count")
                .then()
                .statusCode(200)
                .body("invitationsCount", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/invitations")
                .then()
                .statusCode(200)
                .body("invitations", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/members")
                .then()
                .statusCode(200)
                .body("members", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/admin")
                .then()
                .statusCode(200)
                .body("adminAccounts", hasSize(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/admin/configuration")
                .then()
                .statusCode(200)
                .body("autoEnable", equalTo(false));
    }

    private static void enable(String authorization) {
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"ENABLED\"}")
                .when()
                .post("/macie")
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/macie2/aws4_request";
    }
}
