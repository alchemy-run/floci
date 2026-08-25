package io.github.hectorvent.floci.services.neptunegraph;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies Neptune Analytics restJson1 Get* not-found tags and graph lifecycle. */
@QuarkusTest
class NeptuneGraphIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getGraphOnANonexistentGraphFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/graphs/g-0123456789")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getGraphSnapshotOnANonexistentSnapshotFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000402", EAST))
                .when()
                .get("/snapshots/gs-0123456789")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getImportTaskOnANonexistentTaskFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000403", EAST))
                .when()
                .get("/importtasks/t-0123456789")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getExportTaskOnANonexistentTaskFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000404", EAST))
                .when()
                .get("/exporttasks/t-0123456789")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void graphCreateGetUpdateTagsAndDeleteLifecycle() {
        String authorization = auth("000000000405", EAST);
        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "graphName":"lifecycle-graph",
                          "provisionedMemory":16,
                          "publicConnectivity":true,
                          "replicaCount":0,
                          "deletionProtection":false,
                          "tags":{"Environment":"test"}
                        }
                        """)
                .when()
                .post("/graphs")
                .then()
                .statusCode(200)
                .body("id", startsWith("g-"))
                .body("name", equalTo("lifecycle-graph"))
                .body("status", equalTo("AVAILABLE"))
                .body("provisionedMemory", equalTo(16))
                .body("publicConnectivity", equalTo(true))
                .body("replicaCount", equalTo(0))
                .body("deletionProtection", equalTo(false))
                .body("arn", notNullValue())
                .body("endpoint", notNullValue())
                .extract().path("id");

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/graphs/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo("lifecycle-graph"))
                .body("status", equalTo("AVAILABLE"))
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/graphs")
                .then()
                .statusCode(200)
                .body("graphs.find { it.id == '" + id + "' }.name", equalTo("lifecycle-graph"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"provisionedMemory\":32,\"publicConnectivity\":false}")
                .when()
                .patch("/graphs/" + id)
                .then()
                .statusCode(200)
                .body("provisionedMemory", equalTo(32))
                .body("publicConnectivity", equalTo(false));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"platform\"}}")
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
                .body("tags.Team", equalTo("platform"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/graphs/" + id + "?skipSnapshot=true")
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/graphs/" + id)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/neptune-graph/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
