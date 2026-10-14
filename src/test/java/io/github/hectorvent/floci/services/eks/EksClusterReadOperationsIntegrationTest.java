package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Cluster-scoped read operations the Alchemy EKS cluster bindings call: ListUpdates, the insights
 * operations, capabilities, DescribeIdentityProviderConfig and ListAssociatedAccessPolicies. Each
 * must be routed to EKS (not S3's path-style catch-all) and return the model's result keys or
 * typed errors.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksClusterReadOperationsIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "read-ops-it-cluster";

    @Test
    @Order(1)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\",\"accessConfig\":{\"authenticationMode\":\"API\","
                        + "\"bootstrapClusterCreatorAdminPermissions\":false}}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(2)
    void listUpdatesReturnsUpdateIdsAndResolvesFilters() {
        given().when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("updateIds", hasSize(0));
        given().when().get("/clusters/" + CLUSTER + "/updates?nodegroupName=missing")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        given().when().get("/clusters/" + CLUSTER + "/updates?addonName=vpc-cni&nodegroupName=ng")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        given().when().get("/clusters/no-such-cluster/updates")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(3)
    void capabilitiesAndIdentityProviderConfigsAreEmpty() {
        given().when().get("/clusters/" + CLUSTER + "/capabilities")
                .then().statusCode(200).body("capabilities", hasSize(0));
        given().when().get("/clusters/" + CLUSTER + "/capabilities/missing")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        given().contentType(JSON).body("{\"identityProviderConfig\":{\"type\":\"oidc\",\"name\":\"missing\"}}")
                .when().post("/clusters/" + CLUSTER + "/identity-provider-configs/describe")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void associatedAccessPoliciesRequireAnExistingEntry() {
        String principal = URLEncoder.encode("arn:aws:iam::000000000000:role/missing", StandardCharsets.UTF_8);
        given().urlEncodingEnabled(false)
                .when().get("/clusters/" + CLUSTER + "/access-entries/" + principal + "/access-policies")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(5)
    void insightsAreListedDescribedAndRefreshed() {
        String id = given().contentType(JSON).body("{}")
                .when().post("/clusters/" + CLUSTER + "/insights")
                .then().statusCode(200)
                .body("insights", hasSize(3))
                .body("insights.name", hasItems("Deprecated APIs removed in Kubernetes v1.30",
                        "Amazon EKS add-on version compatibility", "Kubelet version skew"))
                .body("insights.kubernetesVersion", hasItems("1.30"))
                .extract().path("insights[0].id");

        given().when().get("/clusters/" + CLUSTER + "/insights/" + id)
                .then().statusCode(200)
                .body("insight.id", equalTo(id))
                .body("insight.recommendation", notNullValue());
        given().when().get("/clusters/" + CLUSTER + "/insights/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().contentType(JSON).body("{\"filter\":{\"statuses\":[\"UNKNOWN\"]}}")
                .when().post("/clusters/" + CLUSTER + "/insights")
                .then().statusCode(200)
                .body("insights.name", hasItems("Deprecated APIs removed in Kubernetes v1.30"));

        given().when().get("/clusters/" + CLUSTER + "/insights-refresh")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        given().when().post("/clusters/" + CLUSTER + "/insights-refresh")
                .then().statusCode(200).body("status", equalTo("IN_PROGRESS"));
        given().when().get("/clusters/" + CLUSTER + "/insights-refresh")
                .then().statusCode(200)
                .body("status", anyOf(equalTo("IN_PROGRESS"), equalTo("COMPLETED")))
                .body("startedAt", notNullValue());
    }

    @Test
    @Order(6)
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }
}
