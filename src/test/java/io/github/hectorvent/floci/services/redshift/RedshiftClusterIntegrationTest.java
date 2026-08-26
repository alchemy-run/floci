package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftClusterIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "alchemy-redshift-cluster-it";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/redshift/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void describeClustersNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusters")
            .formParam("ClusterIdentifier", "alchemy-nonexistent-redshift-probe")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterNotFound"));
    }

    @Test
    @Order(2)
    void createCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("NodeType", "ra3.large")
            .formParam("ClusterType", "single-node")
            .formParam("MasterUsername", "alchemyadmin")
            .formParam("MasterUserPassword", "AlchemyRedshiftTest1")
            .formParam("DBName", "analytics")
            .formParam("PubliclyAccessible", "false")
            .formParam("Encrypted", "true")
            .formParam("Tags.member.1.Key", "fixture")
            .formParam("Tags.member.1.Value", "redshift-cluster")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("ra3.large"))
            .body(containsString("analytics"))
            .body(containsString("alchemyadmin"))
            .body(containsString("redshift"))
            .body(containsString("5439"))
            .body(containsString("fixture"))
            .body(not(containsString("AlchemyRedshiftTest1")));
    }

    @Test
    @Order(3)
    void createClusterDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateCluster")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("NodeType", "ra3.large")
            .formParam("MasterUsername", "alchemyadmin")
            .formParam("MasterUserPassword", "AlchemyRedshiftTest1")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("ClusterAlreadyExists"));
    }

    @Test
    @Order(4)
    void describeClusterById() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusters")
            .formParam("ClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("ra3.large"))
            .body(containsString("namespace:"))
            .body(containsString("fixture"));
    }

    @Test
    @Order(5)
    void createTagsUpserts() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateTags")
            .formParam("ResourceName",
                    "arn:aws:redshift:us-east-1:000000000000:cluster:" + CLUSTER_ID)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
        .when().post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusters")
            .formParam("ClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("env"))
            .body(containsString("test"));
    }

    @Test
    @Order(6)
    void deleteCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteCluster")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("SkipFinalClusterSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(7)
    void describeAfterDeleteIsNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeClusters")
            .formParam("ClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterNotFound"));
    }
}
