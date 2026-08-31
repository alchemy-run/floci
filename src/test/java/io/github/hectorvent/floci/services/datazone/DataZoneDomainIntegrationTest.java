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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies DataZone restJson1 domain create/get/list/update/tag/delete
 * and AccessDeniedException for a missing domain.
 */
@QuarkusTest
class DataZoneDomainIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/datazone-domain-execution";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDomainOnANonexistentIdFailsWithAccessDeniedException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/v2/domains/dzd_doesnotexist01")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void createGetListUpdateTagsAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String name = "alchemy-dz-" + UUID.randomUUID().toString().substring(0, 8);

        String domainId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy datazone domain test",
                          "domainExecutionRole":"%s",
                          "tags":{"Environment":"test","alchemy::id":"TestDomain"}
                        }
                        """.formatted(name, ROLE))
                .when()
                .post("/v2/domains")
                .then()
                .statusCode(200)
                .body("id", startsWith("dzd"))
                .body("arn", startsWith("arn:aws:datazone:"))
                .body("status", equalTo("AVAILABLE"))
                .body("name", equalTo(name))
                .body("domainExecutionRole", equalTo(ROLE))
                .extract().path("id");

        String domainArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("id", equalTo(domainId))
                .body("status", equalTo("AVAILABLE"))
                .body("description", equalTo("alchemy datazone domain test"))
                .body("tags.Environment", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("TestDomain"))
                .body("arn", startsWith("arn:aws:datazone:"))
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains")
                .then()
                .statusCode(200)
                .body("items.id", hasItem(domainId))
                .body("items.find { it.name == '" + name + "' }.status", equalTo("AVAILABLE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(domainArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Update\":\"yes\"}}")
                .when()
                .post("/tags/" + encode(domainArn))
                .then()
                .statusCode(204);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"description\":\"alchemy datazone domain test (updated)\"}")
                .when()
                .put("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("id", equalTo(domainId))
                .body("description", equalTo("alchemy datazone domain test (updated)"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("description", equalTo("alchemy datazone domain test (updated)"))
                .body("tags.Update", equalTo("yes"))
                .body("tags.Environment", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId + "?skipDeletionCheck=true")
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/datazone/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
