package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Cluster-scoped EKS bindings exercised by alchemy's ClusterBindings suite:
 * list/describe of insights, updates, capabilities, identity-provider configs,
 * pod-identity associations, and insights refresh. Routes must beat S3's
 * path-style catch-all; missing sub-resources must surface ResourceNotFoundException.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksClusterBindingsIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "cluster-bindings-it";
    private static final String BOGUS = "alchemy-nonexistent-probe";
    private static final String BOGUS_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String BOGUS_PRINCIPAL =
            "arn:aws:iam::000000000000:role/alchemy-nonexistent-probe";

    @ParameterizedTest
    @Order(1)
    @CsvSource({
            "GET, /clusters/no-such-cluster/updates",
            "GET, /clusters/no-such-cluster/capabilities",
            "GET, /clusters/no-such-cluster/insights-refresh",
            "POST, /clusters/no-such-cluster/insights",
            "POST, /clusters/no-such-cluster/insights-refresh"
    })
    void missingClusterReturnsResourceNotFound(String method, String path) {
        var spec = given().contentType(JSON).body("{}");
        (method.equals("POST") ? spec.when().post(path) : spec.when().get(path))
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(2)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER))
                .body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.endpoint", notNullValue())
                .body("cluster.certificateAuthority.data", notNullValue())
                .body("cluster.certificateAuthority.data", not(equalTo("")));
    }

    @Test
    @Order(3)
    void listUpdatesEmpty() {
        given()
        .when().get("/clusters/" + CLUSTER + "/updates")
        .then()
            .statusCode(200)
            .body("updateIds", hasSize(0));
    }

    @Test
    @Order(4)
    void listCapabilitiesEmpty() {
        given()
        .when().get("/clusters/" + CLUSTER + "/capabilities")
        .then()
            .statusCode(200)
            .body("capabilities", hasSize(0));
    }

    @Test
    @Order(5)
    void listInsightsEmpty() {
        given().contentType(JSON).body("{}")
        .when().post("/clusters/" + CLUSTER + "/insights")
        .then()
            .statusCode(200)
            .body("insights", hasSize(0));
    }

    @ParameterizedTest
    @Order(6)
    @ValueSource(strings = {
            "/clusters/" + CLUSTER + "/insights/" + BOGUS_UUID,
            "/clusters/" + CLUSTER + "/updates/" + BOGUS_UUID,
            "/clusters/" + CLUSTER + "/capabilities/" + BOGUS,
            "/clusters/" + CLUSTER + "/pod-identity-associations/a-" + BOGUS
    })
    void describeMissingSubresourceReturnsResourceNotFound(String path) {
        given()
        .when().get(path)
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(7)
    void describeMissingIdentityProviderConfigReturnsResourceNotFound() {
        given().contentType(JSON)
                .body("{\"identityProviderConfig\":{\"type\":\"oidc\",\"name\":\"" + BOGUS + "\"}}")
                .when().post("/clusters/" + CLUSTER + "/identity-provider-configs/describe")
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(8)
    void listAssociatedAccessPoliciesForMissingPrincipalReturnsResourceNotFound() {
        given()
        .when().get("/clusters/" + CLUSTER + "/access-entries/" + BOGUS_PRINCIPAL + "/access-policies")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(9)
    void insightsRefreshStartAndDescribe() {
        given().contentType(JSON).body("{}")
        .when().post("/clusters/" + CLUSTER + "/insights-refresh")
        .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"));

        given()
        .when().get("/clusters/" + CLUSTER + "/insights-refresh")
        .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"));
    }

    @Test
    @Order(10)
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }
}
