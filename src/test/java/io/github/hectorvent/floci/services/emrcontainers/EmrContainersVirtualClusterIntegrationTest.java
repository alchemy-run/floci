package io.github.hectorvent.floci.services.emrcontainers;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Virtual-cluster restJson1 semantics the Alchemy EMRContainers suite pins:
 * missing describe is ResourceNotFoundException (HTTP 400), missing delete is
 * ValidationException, job-run ops on a missing cluster are ValidationException
 * except ListJobRuns which returns an empty page.
 */
@QuarkusTest
class EmrContainersVirtualClusterIntegrationTest {

    private static final String MISSING_ID = "abcdefabcdefabcdefabcdef01";
    private static final String MISSING_JOB = "abcdefabcdefabcdefa";
    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeMissingVirtualClusterIsResourceNotFoundAt400() {
        given()
                .header("Authorization", auth("000000000201", EAST))
                .when()
                .get("/virtualclusters/" + MISSING_ID)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteMissingVirtualClusterIsValidationException() {
        given()
                .header("Authorization", auth("000000000202", EAST))
                .when()
                .delete("/virtualclusters/" + MISSING_ID)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void jobRunOpsOnMissingVirtualClusterMatchLiveTags() {
        String authorization = auth("000000000203", EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + MISSING_ID + "/jobruns/" + MISSING_JOB)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/virtualclusters/" + MISSING_ID + "/jobruns/" + MISSING_JOB)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + MISSING_ID + "/jobruns")
                .then()
                .statusCode(200)
                .body("jobRuns.size()", equalTo(0));
    }

    @Test
    void listVirtualClustersReturnsWellFormedArray() {
        String authorization = auth("000000000204", EAST);
        Response response = given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters")
                .then()
                .statusCode(200)
                .extract().response();
        List<Map<String, Object>> clusters = response.path("virtualClusters");
        assertTrue(clusters != null);
        for (Map<String, Object> cluster : clusters) {
            assertTrue(cluster.get("id") instanceof String);
            assertTrue(cluster.get("name") instanceof String);
            assertTrue(String.valueOf(cluster.get("arn")).contains(":/virtualclusters/"));
        }
    }

    @Test
    void virtualClusterCreateDescribeListTagDeleteLifecycle() {
        String authorization = auth("000000000205", EAST);
        String name = "vc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "clientToken":"%s",
                          "containerProvider":{"id":"eks-cluster","type":"EKS","info":{"eksInfo":{"namespace":"default"}}},
                          "tags":{"Owner":"floci"}
                        }
                        """.formatted(name, name))
                .when()
                .post("/virtualclusters")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("name", equalTo(name))
                .body("arn", notNullValue())
                .extract().path("id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + id)
                .then()
                .statusCode(200)
                .body("virtualCluster.id", equalTo(id))
                .body("virtualCluster.name", equalTo(name))
                .body("virtualCluster.state", equalTo("RUNNING"))
                .body("virtualCluster.containerProvider.id", equalTo("eks-cluster"))
                .body("virtualCluster.tags.Owner", equalTo("floci"));

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + id)
                .then()
                .extract().path("virtualCluster.arn");
        assertTrue(arn.contains(":/virtualclusters/" + id));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .queryParam("states", "RUNNING")
                .when()
                .get("/virtualclusters")
                .then()
                .statusCode(200)
                .extract().path("virtualClusters");
        assertEquals(1, listed.size());
        assertEquals(id, listed.getFirst().get("id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Env\":\"test\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + id)
                .then()
                .statusCode(200)
                .body("virtualCluster.tags.Owner", equalTo("floci"))
                .body("virtualCluster.tags.Env", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/virtualclusters/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/virtualclusters/" + id)
                .then()
                .statusCode(200)
                .body("virtualCluster.state", equalTo("TERMINATED"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/virtualclusters/" + id)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/emr-containers/aws4_request";
    }
}
