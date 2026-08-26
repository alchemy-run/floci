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
 * Verifies DataZone restJson1 GetEnvironment: missing domain is AccessDenied,
 * missing environment is ResourceNotFound, and create/get/list/update/delete
 * converge to ACTIVE without CloudFormation.
 */
@QuarkusTest
class DataZoneEnvironmentIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth(EAST);
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/datazone-domain-execution";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getEnvironmentOnANonexistentDomainFailsWithAccessDeniedException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/dzd_000000000000/environments/0000000000")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void getEnvironmentOnANonexistentIdFailsWithResourceNotFoundException() {
        String domainId = createDomain("env-missing-" + UUID.randomUUID().toString().substring(0, 8));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/environments/0000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/v2/domains/" + domainId)
                .then()
                .statusCode(200);
    }

    @Test
    void createGetListUpdateAndDeleteEnvironment() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String domainId = createDomain("env-domain-" + suffix);
        String projectId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"name\":\"env-project-" + suffix + "\",\"description\":\"environment test project\"}")
                .when()
                .post("/v2/domains/" + domainId + "/projects")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract().path("id");

        String environmentId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name":"alchemy-env-%s",
                          "description":"alchemy environment",
                          "projectIdentifier":"%s",
                          "environmentBlueprintIdentifier":"DefaultDataLake"
                        }
                        """.formatted(suffix, projectId))
                .when()
                .post("/v2/domains/" + domainId + "/environments")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("domainId", equalTo(domainId))
                .body("projectId", equalTo(projectId))
                .body("status", equalTo("ACTIVE"))
                .body("provider", equalTo("Amazon DataZone"))
                .body("name", equalTo("alchemy-env-" + suffix))
                .extract().path("id");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/environments/" + environmentId)
                .then()
                .statusCode(200)
                .body("id", equalTo(environmentId))
                .body("status", equalTo("ACTIVE"))
                .body("projectId", equalTo(projectId))
                .body("environmentBlueprintId", equalTo("DefaultDataLake"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/environments?projectIdentifier=" + projectId
                        + "&name=alchemy-env-" + suffix)
                .then()
                .statusCode(200)
                .body("items.find { it.id == '" + environmentId + "' }.status", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"description\":\"alchemy environment (updated)\"}")
                .when()
                .patch("/v2/domains/" + domainId + "/environments/" + environmentId)
                .then()
                .statusCode(200)
                .body("id", equalTo(environmentId))
                .body("description", equalTo("alchemy environment (updated)"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/v2/domains/" + domainId + "/environments/" + environmentId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/v2/domains/" + domainId + "/environments/" + environmentId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/v2/domains/" + domainId)
                .then()
                .statusCode(200);
    }

    private static String createDomain(String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy datazone environment test",
                          "domainExecutionRole":"%s"
                        }
                        """.formatted(name, ROLE))
                .when()
                .post("/v2/domains")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract().path("id");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/datazone/aws4_request";
    }
}
