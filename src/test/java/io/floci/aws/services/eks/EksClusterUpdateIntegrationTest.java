package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * UpdateClusterConfig and UpdateClusterVersion: routed to EKS (not S3's path-style catch-all), one
 * category per request, each change applied and reported as an update DescribeUpdate returns.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksClusterUpdateIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "update-it-cluster";

    @Test
    @Order(1)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.30\",\"deletionProtection\":true,"
                        + "\"logging\":{\"clusterLogging\":[{\"types\":[\"api\"],\"enabled\":true}]}}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.deletionProtection", equalTo(true));
    }

    @Test
    @Order(2)
    void aLoggingUpdateChangesOnlyTheTypesItNamesAndIsReported() {
        String updateId = given().contentType(JSON)
                .body("{\"logging\":{\"clusterLogging\":[{\"types\":[\"audit\"],\"enabled\":true}]}}")
                .when().post("/clusters/" + CLUSTER + "/update-config")
                .then().statusCode(200)
                .body("update.type", equalTo("LoggingUpdate"))
                .body("update.status", equalTo("Successful"))
                .extract().path("update.id");

        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.logging.clusterLogging.find { it.enabled }.types", hasItems("api", "audit"));
        given().when().get("/clusters/" + CLUSTER + "/updates/" + updateId)
                .then().statusCode(200)
                .body("update.id", equalTo(updateId))
                .body("update.status", equalTo("Successful"));
        given().when().get("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("updateIds", hasItem(updateId));
    }

    @Test
    @Order(3)
    void aRequestCarriesExactlyOneCategory() {
        given().contentType(JSON)
                .body("{\"deletionProtection\":false,\"upgradePolicy\":{\"supportType\":\"STANDARD\"}}")
                .when().post("/clusters/" + CLUSTER + "/update-config")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(4)
    void endpointAccessAndUpgradePolicyUpdate() {
        given().contentType(JSON)
                .body("{\"resourcesVpcConfig\":{\"endpointPublicAccess\":false,\"endpointPrivateAccess\":true}}")
                .when().post("/clusters/" + CLUSTER + "/update-config")
                .then().statusCode(200).body("update.type", equalTo("EndpointAccessUpdate"));
        given().contentType(JSON)
                .body("{\"upgradePolicy\":{\"supportType\":\"STANDARD\"}}")
                .when().post("/clusters/" + CLUSTER + "/update-config")
                .then().statusCode(200).body("update.type", equalTo("UpgradePolicyUpdate"));
        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.resourcesVpcConfig.endpointPublicAccess", equalTo(false))
                .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(true))
                .body("cluster.upgradePolicy.supportType", equalTo("STANDARD"));
    }

    @Test
    @Order(5)
    void aVersionUpdateMovesTheControlPlane() {
        given().contentType(JSON).body("{\"version\":\"1.31\"}")
                .when().post("/clusters/" + CLUSTER + "/updates")
                .then().statusCode(200).body("update.type", equalTo("VersionUpdate"));
        given().when().get("/clusters/" + CLUSTER)
                .then().statusCode(200).body("cluster.version", equalTo("1.31"));
    }

    @Test
    @Order(6)
    void deletionProtectionBlocksDeleteUntilItIsTurnedOff() {
        given().when().delete("/clusters/" + CLUSTER)
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        given().contentType(JSON).body("{\"deletionProtection\":false}")
                .when().post("/clusters/" + CLUSTER + "/update-config")
                .then().statusCode(200).body("update.type", equalTo("DeletionProtectionUpdate"));
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
        given().when().get("/clusters")
                .then().statusCode(200).body("clusters", not(hasItem(CLUSTER)));
    }
}
