package io.github.hectorvent.floci.services.schemas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class SchemasDiscovererIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String SOURCE =
            "arn:aws:events:us-east-1:000000000701:event-bus/discoverer-bus";
    private static final String OTHER_SOURCE =
            "arn:aws:events:us-east-1:000000000701:event-bus/other-bus";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingDiscovererReturnsNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000700", EAST))
                .when()
                .get("/v1/discoverers/id/does-not-exist")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("NotFoundException"))
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void discovererCreateUpdateStopStartTagsDeleteLifecycle() {
        String authorization = auth("000000000701", EAST);
        Response created = create(authorization, SOURCE, "alchemy schemas discoverer test");
        created.then()
                .statusCode(200)
                .body("State", equalTo("STARTED"))
                .body("SourceArn", equalTo(SOURCE))
                .body("Description", equalTo("alchemy schemas discoverer test"))
                .body("CrossAccount", equalTo(true))
                .body("DiscovererId", notNullValue())
                .body("DiscovererArn", notNullValue())
                .body("tags.purpose", equalTo("alchemy-test"));

        String discovererId = created.path("DiscovererId");
        String arn = created.path("DiscovererArn");
        assertNotNull(discovererId);
        assertEquals("arn:aws:schemas:" + EAST + ":000000000701:discoverer/" + discovererId, arn);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/v1/discoverers/id/" + discovererId)
                .then()
                .statusCode(200)
                .body("DiscovererArn", equalTo(arn))
                .body("SourceArn", equalTo(SOURCE))
                .body("State", equalTo("STARTED"))
                .body("Description", equalTo("alchemy schemas discoverer test"))
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/discoverers")
                .then()
                .statusCode(200)
                .body("Discoverers.size()", equalTo(1))
                .body("Discoverers[0].DiscovererId", equalTo(discovererId))
                .body("Discoverers[0].tags.purpose", equalTo("alchemy-test"));

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
                        {"Description":"paused discoverer","CrossAccount":true}
                        """)
                .when()
                .put("/v1/discoverers/id/" + discovererId)
                .then()
                .statusCode(200)
                .body("Description", equalTo("paused discoverer"))
                .body("State", equalTo("STARTED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/v1/discoverers/id/" + discovererId + "/stop")
                .then()
                .statusCode(200)
                .body("DiscovererId", equalTo(discovererId))
                .body("State", equalTo("STOPPED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/discoverers/id/" + discovererId)
                .then()
                .statusCode(200)
                .body("State", equalTo("STOPPED"))
                .body("Description", equalTo("paused discoverer"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/v1/discoverers/id/" + discovererId + "/start")
                .then()
                .statusCode(200)
                .body("State", equalTo("STARTED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"phase\":\"two\"}}")
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
                .body("tags.phase", equalTo("two"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "phase")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags.phase", nullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/v1/discoverers/id/" + discovererId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/v1/discoverers/id/" + discovererId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createDuplicateSourceArnReturnsConflictException() {
        String authorization = auth("000000000702", EAST);
        create(authorization, OTHER_SOURCE, "first").then().statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SourceArn":"%s",
                          "Description":"second"
                        }
                        """.formatted(OTHER_SOURCE))
                .when()
                .post("/v1/discoverers")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    void createWithoutSourceArnReturnsBadRequestException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000703", EAST))
                .body("{}")
                .when()
                .post("/v1/discoverers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void deleteMissingDiscovererReturnsNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000704", EAST))
                .when()
                .delete("/v1/discoverers/id/ghost")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static Response create(String authorization, String sourceArn, String description) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "SourceArn":"%s",
                          "Description":"%s",
                          "tags":{"purpose":"alchemy-test"}
                        }
                        """.formatted(sourceArn, description))
                .when()
                .post("/v1/discoverers");
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/schemas/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
