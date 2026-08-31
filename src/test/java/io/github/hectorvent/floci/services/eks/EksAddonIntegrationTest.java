package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import io.restassured.specification.RequestSpecification;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * EKS managed add-on REST flow. Explicit routes must beat S3's path-style catch-all,
 * list/describe must hydrate well-formed add-on attributes, and pagination must omit
 * {@code nextToken} on the terminal page.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksAddonIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "addon-it-cluster";

    private static RequestSpecification eks() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/eks/aws4_request");
    }

    @Test
    @Order(1)
    void missingClusterReturnsResourceNotFound() {
        eks()
        .when()
            .get("/clusters/no-such-cluster/addons")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", containsString("no-such-cluster"));
    }

    @Test
    @Order(2)
    void createCluster() {
        eks().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(3)
    void existingClusterListsEmptyAddonsWithoutNextToken() {
        eks()
        .when()
            .get("/clusters/" + CLUSTER + "/addons")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("addons", hasSize(0))
            .body("nextToken", equalTo(null));
    }

    @Test
    @Order(4)
    void createAddonRoutesToEksNotS3() {
        eks().contentType(JSON)
                .body("{\"addonName\":\"metrics-server\",\"tags\":{\"env\":\"test\"}}")
                .when().post("/clusters/" + CLUSTER + "/addons")
                .then()
                .statusCode(200)
                .body("addon.addonName", equalTo("metrics-server"))
                .body("addon.clusterName", equalTo(CLUSTER))
                .body("addon.addonArn", containsString("addon/" + CLUSTER + "/metrics-server/"))
                .body("addon.status", equalTo("ACTIVE"))
                .body("addon.addonVersion", notNullValue())
                .body("addon.namespaceConfig.namespace", equalTo("kube-system"))
                .body("addon.tags.env", equalTo("test"));
    }

    @Test
    @Order(5)
    void listAddons() {
        eks()
        .when()
            .get("/clusters/" + CLUSTER + "/addons")
        .then().statusCode(200)
                .body("addons", hasItem("metrics-server"))
                .body("nextToken", equalTo(null));
    }

    @Test
    @Order(6)
    void describeAddon() {
        eks()
        .when()
            .get("/clusters/" + CLUSTER + "/addons/metrics-server")
        .then().statusCode(200)
                .body("addon.addonName", equalTo("metrics-server"))
                .body("addon.clusterName", equalTo(CLUSTER))
                .body("addon.status", equalTo("ACTIVE"))
                .body("addon.addonArn", containsString("addon/" + CLUSTER + "/metrics-server/"));
    }

    @Test
    @Order(7)
    void updateAddon() {
        eks().contentType(JSON)
                .body("{\"addonVersion\":\"v1.2.3-eksbuild.1\"}")
                .when().post("/clusters/" + CLUSTER + "/addons/metrics-server/update")
                .then().statusCode(200)
                .body("update.status", equalTo("Successful"))
                .body("update.type", equalTo("AddonUpdate"));

        eks()
        .when()
            .get("/clusters/" + CLUSTER + "/addons/metrics-server")
        .then().statusCode(200)
                .body("addon.addonVersion", equalTo("v1.2.3-eksbuild.1"));
    }

    @Test
    @Order(8)
    void listAddonsPaginatesWithoutLooping() {
        eks().contentType(JSON)
                .body("{\"addonName\":\"vpc-cni\"}")
                .when().post("/clusters/" + CLUSTER + "/addons")
                .then().statusCode(200);

        eks()
            .queryParam("maxResults", 1)
        .when()
            .get("/clusters/" + CLUSTER + "/addons")
        .then().statusCode(200)
                .body("addons", hasSize(1))
                .body("nextToken", equalTo("1"));

        eks()
            .queryParam("maxResults", 1)
            .queryParam("nextToken", "1")
        .when()
            .get("/clusters/" + CLUSTER + "/addons")
        .then().statusCode(200)
                .body("addons", hasSize(1))
                .body("nextToken", equalTo(null));
    }

    @Test
    @Order(9)
    void deleteAddon() {
        eks()
        .when()
            .delete("/clusters/" + CLUSTER + "/addons/metrics-server")
        .then().statusCode(200)
                .body("addon.status", equalTo("DELETING"));

        eks()
        .when()
            .get("/clusters/" + CLUSTER + "/addons/metrics-server")
        .then().statusCode(404);
    }

    @Test
    @Order(10)
    void deleteCluster() {
        eks().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }
}
