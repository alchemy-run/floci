package io.github.hectorvent.floci.services.datazone;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies DataZone restJson1 project create/get/list/update/delete, matching
 * the alchemy Project resource lifecycle (create, update description, delete).
 */
@QuarkusTest
class DataZoneProjectIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/datazone-domain-execution";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getProjectOnANonexistentIdFailsWithAccessDeniedException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/v2/domains/dzd_doesnotexist01/projects/missingproject")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void createGetListUpdateAndDeleteProjectLifecycle() {
        String authorization = auth(EAST);
        String domainName = "dz-proj-" + UUID.randomUUID().toString().substring(0, 8);

        String domainId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy datazone project test",
                          "domainExecutionRole":"%s"
                        }
                        """.formatted(domainName, ROLE))
                .when()
                .post("/v2/domains")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("status", equalTo("AVAILABLE"))
                .extract().path("id");

        String projectId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"analytics-project",
                          "description":"analytics project"
                        }
                        """)
                .when()
                .post("/v2/domains/" + domainId + "/projects")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("domainId", equalTo(domainId))
                .body("name", equalTo("analytics-project"))
                .body("description", equalTo("analytics project"))
                .body("projectStatus", equalTo("ACTIVE"))
                .body("createdBy", notNullValue())
                .body("domainUnitId", notNullValue())
                .extract().path("id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/projects/" + projectId)
                .then()
                .statusCode(200)
                .body("id", equalTo(projectId))
                .body("description", equalTo("analytics project"))
                .body("projectStatus", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/projects?name=analytics-project")
                .then()
                .statusCode(200)
                .body("items.find { it.id == '" + projectId + "' }.name",
                        equalTo("analytics-project"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"analytics-project",
                          "description":"analytics project (updated)"
                        }
                        """)
                .when()
                .patch("/v2/domains/" + domainId + "/projects/" + projectId)
                .then()
                .statusCode(200)
                .body("id", equalTo(projectId))
                .body("description", equalTo("analytics project (updated)"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/projects/" + projectId)
                .then()
                .statusCode(200)
                .body("description", equalTo("analytics project (updated)"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId + "/projects/" + projectId + "?skipDeletionCheck=true")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/projects/" + projectId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId + "?skipDeletionCheck=true")
                .then()
                .statusCode(200);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/datazone/aws4_request";
    }
}
