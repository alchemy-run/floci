package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

/**
 * EKS managed node group REST flow (issue #1137). The key regression: {@code
 * POST /clusters/{name}/node-groups} must route to EKS and return a {@code nodegroup}
 * envelope, not fall through to S3's path-style catch-all (which returned a 400 "POST
 * requires ?uploads parameter").
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksNodegroupIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "ng-it-cluster";
    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-node-role";

    @Test
    @Order(1)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(2)
    void createNodeGroupRoutesToEksNotS3() {
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"ng1\",\"subnets\":[\"subnet-abc\"],\"nodeRole\":\"" + NODE_ROLE + "\","
                        + "\"scalingConfig\":{\"minSize\":1,\"maxSize\":3,\"desiredSize\":2}}")
                .when().post("/clusters/" + CLUSTER + "/node-groups")
                .then()
                .statusCode(200)
                // Regression: a nodegroup envelope proves EKS handled it, not S3.
                .body("nodegroup.nodegroupName", equalTo("ng1"))
                .body("nodegroup.clusterName", equalTo(CLUSTER))
                .body("nodegroup.nodegroupArn", containsString("nodegroup/" + CLUSTER + "/ng1/"))
                .body("nodegroup.status", equalTo("ACTIVE"))
                .body("nodegroup.scalingConfig.desiredSize", equalTo(2))
                .body("nodegroup.amiType", notNullValue());
    }

    @Test
    @Order(3)
    void listNodeGroups() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups")
                .then().statusCode(200)
                .body("nodegroups", hasItem("ng1"));
    }

    @Test
    @Order(4)
    void describeNodeGroup() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200)
                .body("nodegroup.nodegroupName", equalTo("ng1"))
                .body("nodegroup.subnets[0]", equalTo("subnet-abc"))
                .body("nodegroup.nodeRole", equalTo(NODE_ROLE));
    }

    @Test
    @Order(5)
    void updateNodegroupConfigRoutesToEksAndSettles() {
        String updateId = given().contentType(JSON)
                .body("{\"scalingConfig\":{\"maxSize\":4,\"desiredSize\":3},"
                        + "\"labels\":{\"addOrUpdateLabels\":{\"tier\":\"web\"}},"
                        + "\"clientRequestToken\":\"scale-1\"}")
                .when().post("/clusters/" + CLUSTER + "/node-groups/ng1/update-config")
                .then().statusCode(200)
                .body("update.type", equalTo("ConfigUpdate"))
                .body("update.status", equalTo("InProgress"))
                .body("update.params.find { it.type == 'DesiredSize' }.value", equalTo("3"))
                .body("update.params.find { it.type == 'LabelsToAdd' }.value", equalTo("{\"tier\":\"web\"}"))
                .body("update.createdAt", instanceOf(Number.class))
                .extract().path("update.id");

        given().when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200)
                .body("nodegroup.status", equalTo("UPDATING"))
                .body("nodegroup.scalingConfig.minSize", equalTo(1))
                .body("nodegroup.scalingConfig.maxSize", equalTo(4))
                .body("nodegroup.scalingConfig.desiredSize", equalTo(3))
                .body("nodegroup.labels.tier", equalTo("web"));
        given().queryParam("nodegroupName", "ng1")
                .when().get("/clusters/" + CLUSTER + "/updates/" + updateId)
                .then().statusCode(200).body("update.id", equalTo(updateId));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                given().queryParam("nodegroupName", "ng1")
                        .when().get("/clusters/" + CLUSTER + "/updates/" + updateId)
                        .then().statusCode(200).body("update.status", equalTo("Successful")));
        given().when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200).body("nodegroup.status", equalTo("ACTIVE"));
        given().queryParam("nodegroupName", "ng1")
                .when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("updateIds", hasItem(updateId));
    }

    @Test
    @Order(6)
    void updateNodegroupVersionRoutesToEks() {
        String updateId = given().contentType(JSON).body("{}")
                .when().post("/clusters/" + CLUSTER + "/node-groups/ng1/update-version")
                .then().statusCode(200)
                .body("update.type", equalTo("VersionUpdate"))
                .body("update.status", equalTo("InProgress"))
                .body("update.params.find { it.type == 'Version' }.value", equalTo("1.29"))
                .extract().path("update.id");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                given().queryParam("nodegroupName", "ng1")
                        .when().get("/clusters/" + CLUSTER + "/updates/" + updateId)
                        .then().statusCode(200).body("update.status", equalTo("Successful")));
        given().contentType(JSON).body("{\"version\":\"1.30\"}")
                .when().post("/clusters/" + CLUSTER + "/node-groups/ng1/update-version")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(7)
    void deleteNodeGroup() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200)
                .body("nodegroup.status", equalTo("DELETING"));

        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(404);
    }
}
