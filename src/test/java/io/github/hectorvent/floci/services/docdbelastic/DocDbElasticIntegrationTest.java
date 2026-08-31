package io.github.hectorvent.floci.services.docdbelastic;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies DocumentDB Elastic restJson1 cluster CRUD, tags, and not-found. */
@QuarkusTest
class DocDbElasticIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING_ARN =
            "arn:aws:docdb-elastic:us-east-1:000000000000:cluster/00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getClusterOnANonexistentClusterArnFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/cluster/" + encode(MISSING_ARN))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("cluster"));
    }

    @Test
    void createGetTagUpdateDeleteClusterLifecycle() {
        String authorization = auth(EAST);
        String name = "elastic-" + UUID.randomUUID().toString().substring(0, 8);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clusterName": "%s",
                          "authType": "PLAIN_TEXT",
                          "adminUserName": "alchemyadmin",
                          "adminUserPassword": "AlchemyTestPassw0rd",
                          "shardCapacity": 2,
                          "shardCount": 1,
                          "subnetIds": ["subnet-aaa", "subnet-bbb"],
                          "vpcSecurityGroupIds": ["sg-aaa"],
                          "backupRetentionPeriod": 1,
                          "tags": {"fixture": "docdb-elastic-cluster"}
                        }
                        """.formatted(name))
                .when()
                .post("/cluster")
                .then()
                .statusCode(200)
                .body("cluster.clusterName", equalTo(name))
                .body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.clusterArn", startsWith("arn:aws:docdb-elastic:"))
                .body("cluster.clusterEndpoint", notNullValue())
                .body("cluster.adminUserName", equalTo("alchemyadmin"))
                .body("cluster.authType", equalTo("PLAIN_TEXT"))
                .body("cluster.shardCapacity", equalTo(2))
                .body("cluster.shardCount", equalTo(1))
                .extract()
                .path("cluster.clusterArn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + encode(arn))
                .then()
                .statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.clusterName", equalTo(name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/clusters")
                .then()
                .statusCode(200)
                .body("clusters.find { it.clusterName == '%s' }.status".formatted(name), equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("docdb-elastic-cluster"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"1\"}}")
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
                .body("tags.fixture", equalTo("docdb-elastic-cluster"))
                .body("tags.extra", equalTo("1"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"shardCount\": 2}")
                .when()
                .put("/cluster/" + encode(arn))
                .then()
                .statusCode(200)
                .body("cluster.shardCount", equalTo(2))
                .body("cluster.status", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/cluster/" + encode(arn))
                .then()
                .statusCode(200)
                .body("cluster.status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/cluster/" + encode(arn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/docdb-elastic/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
