package io.github.hectorvent.floci.services.datazone;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies DataZone restJson1 domain, project, profile, search, and portal APIs. */
@QuarkusTest
class DataZoneIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth(EAST);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listDomainsOnAnEmptyAccountReturnsNoItems() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains")
                .then()
                .statusCode(200)
                .body("items", notNullValue());
    }

    @Test
    void getDomainOnANonexistentIdFailsWithAccessDeniedException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/dzd_doesnotexist")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void createGetListUpdateTagsSearchPortalAndDeleteLifecycle() {
        String name = "governance-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = "arn:aws:iam::000000000000:role/datazone-exec-" + name;
        String functionRole = "arn:aws:iam::000000000000:role/fn-" + name;

        String domainId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name":"%s",
                          "description":"bindings fixture",
                          "domainExecutionRole":"%s",
                          "tags":{"Environment":"test"}
                        }
                        """.formatted(name, roleArn))
                .when()
                .post("/v2/domains")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("status", equalTo("AVAILABLE"))
                .body("arn", notNullValue())
                .body("name", equalTo(name))
                .body("rootDomainUnitId", notNullValue())
                .body("portalUrl", notNullValue())
                .body("tags.Environment", equalTo("test"))
                .extract().path("id");

        String domainArn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("id", equalTo(domainId))
                .body("status", equalTo("AVAILABLE"))
                .body("domainExecutionRole", equalTo(roleArn))
                .extract().path("arn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains")
                .then()
                .statusCode(200)
                .body("items.find { it.id == '" + domainId + "' }.name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        { "description":"updated description" }
                        """)
                .when()
                .put("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("id", equalTo(domainId))
                .body("description", equalTo("updated description"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + encode(domainArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        { "tags": { "Team": "data" } }
                        """)
                .when()
                .post("/tags/" + encode(domainArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("tags.Team", equalTo("data"))
                .body("tags.Environment", equalTo("test"));

        String projectName = "analytics-" + UUID.randomUUID().toString().substring(0, 8);
        String projectId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        { "name":"%s", "description":"project" }
                        """.formatted(projectName))
                .when()
                .post("/v2/domains/" + domainId + "/projects")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("projectStatus", equalTo("ACTIVE"))
                .body("name", equalTo(projectName))
                .extract().path("id");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/projects?name=" + projectName)
                .then()
                .statusCode(200)
                .body("items.find { it.id == '" + projectId + "' }.name", equalTo(projectName));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/projects/" + projectId)
                .then()
                .statusCode(200)
                .body("id", equalTo(projectId))
                .body("domainId", equalTo(domainId));

        String profileId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "userIdentifier":"%s",
                          "userType":"IAM_ROLE"
                        }
                        """.formatted(functionRole))
                .when()
                .post("/v2/domains/" + domainId + "/user-profiles")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("type", equalTo("IAM"))
                .body("details.iam.arn", equalTo(functionRole))
                .extract().path("id");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/user-profiles/" + encode(functionRole) + "?type=IAM")
                .then()
                .statusCode(200)
                .body("id", equalTo(profileId));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "member": { "userIdentifier": "%s" },
                          "designation": "PROJECT_CONTRIBUTOR"
                        }
                        """.formatted(profileId))
                .when()
                .post("/v2/domains/" + domainId + "/projects/" + projectId + "/createMembership")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        { "searchScope": "ASSET", "owningProjectIdentifier": "%s" }
                        """.formatted(projectId))
                .when()
                .post("/v2/domains/" + domainId + "/search")
                .then()
                .statusCode(200)
                .body("items", hasSize(0))
                .body("totalMatchCount", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{}")
                .when()
                .post("/v2/domains/" + domainId + "/listings/search")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/subscriptions?status=APPROVED&owningProjectId=" + projectId)
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/subscription-requests?status=PENDING&owningProjectId="
                        + projectId)
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{}")
                .when()
                .post("/v2/domains/" + domainId + "/get-portal-login-url")
                .then()
                .statusCode(200)
                .body("authCodeUrl", notNullValue())
                .body("userProfileId", notNullValue());

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/v2/domains/" + domainId + "/projects/" + projectId + "?skipDeletionCheck=true")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/v2/domains/" + domainId + "?skipDeletionCheck=true")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/datazone/aws4_request";
    }
}
