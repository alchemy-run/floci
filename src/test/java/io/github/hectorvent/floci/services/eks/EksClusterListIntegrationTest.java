package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import io.restassured.specification.RequestSpecification;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Alchemy {@code Cluster.list()} paginates {@code GET /clusters} then hydrates
 * each name via {@code describeCluster} and {@code listTagsForResource}. The
 * list body must be JSON under {@code clusters}, omit a terminal {@code nextToken}
 * (or the SDK paginator never completes), and describe/tag payloads must carry
 * {@code name}/{@code arn}/{@code roleArn} as strings.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksClusterListIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "cluster-list-it";
    private static final String ROLE = "arn:aws:iam::000000000000:role/eks-role";

    private static RequestSpecification eks() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/eks/aws4_request");
    }

    @Test
    @Order(1)
    void emptyListOmitsNextToken() {
        eks()
        .when()
            .get("/clusters")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("clusters", hasSize(0))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    @Order(2)
    void emptyListWithMaxResultsOmitsNextToken() {
        eks()
        .when()
            .get("/clusters?maxResults=100")
        .then()
            .statusCode(200)
            .body("clusters", hasSize(0))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    @Order(3)
    void createCluster() {
        eks().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"" + ROLE + "\","
                        + "\"version\":\"1.29\",\"tags\":{\"env\":\"list-it\"}}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER))
                .body("cluster.roleArn", equalTo(ROLE))
                .body("cluster.arn", startsWith("arn:aws:eks:"))
                .body("cluster.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void listClustersReturnsNamesWithoutNextToken() {
        eks()
        .when()
            .get("/clusters")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("clusters", hasItem(CLUSTER))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    @Order(5)
    void listClustersPagesThenOmitsTerminalNextToken() {
        eks()
        .when()
            .get("/clusters?maxResults=1")
        .then()
            .statusCode(200)
            .body("clusters", hasSize(1));

        String next = eks()
        .when()
            .get("/clusters?maxResults=1")
        .then()
            .statusCode(200)
            .extract()
            .path("nextToken");

        if (next != null) {
            eks()
            .when()
                .get("/clusters?maxResults=1&nextToken=" + next)
            .then()
                .statusCode(200)
                .body("$", not(hasKey("nextToken")));
        }
    }

    @Test
    @Order(6)
    void describeClusterHasAlchemyListFields() {
        eks()
        .when()
            .get("/clusters/" + CLUSTER)
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("cluster.name", equalTo(CLUSTER))
            .body("cluster.arn", startsWith("arn:aws:eks:"))
            .body("cluster.roleArn", equalTo(ROLE))
            .body("cluster.status", notNullValue());
    }

    @Test
    @Order(7)
    void listTagsForResourceReturnsTagMap() {
        String arn = eks()
                .when()
                    .get("/clusters/" + CLUSTER)
                .then()
                    .statusCode(200)
                    .extract()
                    .path("cluster.arn");

        eks()
        .when()
            .get("/tags/" + encode(arn))
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("tags.env", equalTo("list-it"));
    }

    @Test
    @Order(8)
    void deleteCluster() {
        eks().when().delete("/clusters/" + CLUSTER).then().statusCode(200);

        eks()
        .when()
            .get("/clusters/" + CLUSTER)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
