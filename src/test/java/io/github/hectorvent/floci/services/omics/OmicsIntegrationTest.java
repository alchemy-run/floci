package io.github.hectorvent.floci.services.omics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * HealthOmics restJson1 coverage used by Alchemy Omics.test.ts and
 * Bindings.test.ts: typed not-found on missing stores/runs, empty listings,
 * and the run-group create/update/delete lifecycle.
 */
@QuarkusTest
class OmicsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "0000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listReadSetsOnANonexistentSequenceStoreFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/sequencestore/" + MISSING + "/readsets")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getRunOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/run/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listReferencesOnANonexistentReferenceStoreFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/referencestore/" + MISSING + "/references")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listReadSetsOnAnEmptySequenceStoreReturnsEmpty() {
        String authorization = auth(EAST);
        String sequenceStoreId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-omics-reads",
                          "clientToken":"alchemy-omics-sequence-1"
                        }
                        """)
                .when()
                .post("/sequencestore")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("arn", startsWith("arn:aws:omics:"))
                .body("status", equalTo("ACTIVE"))
                .extract()
                .path("id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/sequencestore/" + sequenceStoreId + "/readsets")
                .then()
                .statusCode(200)
                .body("readSets", empty());
    }

    @Test
    void listReferencesOnAnEmptyReferenceStoreReturnsEmpty() {
        String authorization = auth(EAST);
        String referenceStoreId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-omics-refs",
                          "clientToken":"alchemy-omics-reference-1"
                        }
                        """)
                .when()
                .post("/referencestore")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("arn", startsWith("arn:aws:omics:"))
                .extract()
                .path("id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/referencestore/" + referenceStoreId + "/references")
                .then()
                .statusCode(200)
                .body("references", empty());
    }

    @Test
    void getRunGroupOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/runGroup/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateAndDeleteRunGroupLifecycle() {
        String authorization = auth(EAST);

        String runGroupId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-omics-batch",
                          "maxCpus":4,
                          "maxRuns":2,
                          "requestId":"alchemy-omics-run-group-1",
                          "tags":{"fixture":"omics-run-group"}
                        }
                        """)
                .when()
                .post("/runGroup")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("arn", startsWith("arn:aws:omics:"))
                .body("tags.fixture", equalTo("omics-run-group"))
                .extract()
                .path("id");

        String runGroupArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/runGroup/" + runGroupId)
                .then()
                .statusCode(200)
                .body("id", equalTo(runGroupId))
                .body("name", equalTo("alchemy-omics-batch"))
                .body("maxCpus", equalTo(4))
                .body("maxRuns", equalTo(2))
                .extract()
                .path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runGroup")
                .then()
                .statusCode(200)
                .body("items.find { it.id == '" + runGroupId + "' }.maxCpus", equalTo(4));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(runGroupArn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("omics-run-group"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"maxCpus\":8}")
                .when()
                .post("/runGroup/" + runGroupId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runGroup/" + runGroupId)
                .then()
                .statusCode(200)
                .body("id", equalTo(runGroupId))
                .body("maxCpus", equalTo(8))
                .body("maxRuns", equalTo(2));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/runGroup/" + runGroupId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/runGroup/" + runGroupId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/omics/aws4_request";
    }
}
