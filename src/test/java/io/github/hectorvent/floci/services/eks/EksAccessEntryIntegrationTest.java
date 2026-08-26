package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * EKS access entry REST flow. {@code list()} in alchemy walks listClusters,
 * listAccessEntries, describeAccessEntry, and listAssociatedAccessPolicies;
 * those routes must not fall through to S3 and must return the documented JSON shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksAccessEntryIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "access-entry-it-cluster";
    private static final String PRINCIPAL = "arn:aws:iam::000000000000:role/eks-access-it";
    private static final String POLICY =
            "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";

    @Test
    @Order(1)
    void missingClusterReturnsResourceNotFound() {
        given()
        .when()
            .get("/clusters/no-such-cluster/access-entries")
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
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(3)
    void emptyListBeforeCreate() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries")
        .then()
            .statusCode(200)
            .body("accessEntries", hasSize(0));
    }

    @Test
    @Order(4)
    void createAccessEntry() {
        given().contentType(JSON)
                .body("{\"principalArn\":\"" + PRINCIPAL + "\",\"kubernetesGroups\":[\"viewers\"],"
                        + "\"tags\":{\"env\":\"test\"}}")
                .when().post("/clusters/" + CLUSTER + "/access-entries")
                .then()
                .statusCode(200)
                .body("accessEntry.clusterName", equalTo(CLUSTER))
                .body("accessEntry.principalArn", equalTo(PRINCIPAL))
                .body("accessEntry.accessEntryArn", containsString("access-entry/" + CLUSTER + "/"))
                .body("accessEntry.kubernetesGroups[0]", equalTo("viewers"))
                .body("accessEntry.username", equalTo(PRINCIPAL))
                .body("accessEntry.type", equalTo("STANDARD"))
                .body("accessEntry.tags.env", equalTo("test"))
                .body("accessEntry.createdAt", notNullValue());
    }

    @Test
    @Order(5)
    void duplicateCreateIsResourceInUse() {
        given().contentType(JSON)
                .body("{\"principalArn\":\"" + PRINCIPAL + "\"}")
                .when().post("/clusters/" + CLUSTER + "/access-entries")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    @Order(6)
    void listAccessEntriesReturnsPrincipalArns() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries")
        .then()
            .statusCode(200)
            .body("accessEntries", hasItem(PRINCIPAL));
    }

    @Test
    @Order(7)
    void describeAccessEntry() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
        .then()
            .statusCode(200)
            .body("accessEntry.principalArn", equalTo(PRINCIPAL))
            .body("accessEntry.clusterName", equalTo(CLUSTER))
            .body("accessEntry.tags.env", equalTo("test"));
    }

    @Test
    @Order(8)
    void updateAccessEntry() {
        given().contentType(JSON)
                .body("{\"kubernetesGroups\":[\"admins\"],\"username\":\"cluster-admin\"}")
                .when().post("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
                .then()
                .statusCode(200)
                .body("accessEntry.kubernetesGroups[0]", equalTo("admins"))
                .body("accessEntry.username", equalTo("cluster-admin"));
    }

    @Test
    @Order(9)
    void associateAndListAccessPolicies() {
        given().contentType(JSON)
                .body("{\"policyArn\":\"" + POLICY + "\",\"accessScope\":{\"type\":\"cluster\"}}")
                .when().post("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL)
                        + "/access-policies")
                .then()
                .statusCode(200)
                .body("clusterName", equalTo(CLUSTER))
                .body("principalArn", equalTo(PRINCIPAL))
                .body("associatedAccessPolicy.policyArn", equalTo(POLICY))
                .body("associatedAccessPolicy.accessScope.type", equalTo("cluster"));

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL) + "/access-policies")
        .then()
            .statusCode(200)
            .body("associatedAccessPolicies", hasSize(1))
            .body("associatedAccessPolicies[0].policyArn", equalTo(POLICY));
    }

    @Test
    @Order(10)
    void tagAccessEntry() {
        String arn = given()
                .when()
                    .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
                .then()
                    .statusCode(200)
                    .extract()
                    .path("accessEntry.accessEntryArn");

        given().contentType(JSON)
                .body("{\"tags\":{\"team\":\"platform\"}}")
                .when().post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
        .then()
            .statusCode(200)
            .body("accessEntry.tags.env", equalTo("test"))
            .body("accessEntry.tags.team", equalTo("platform"));
    }

    @Test
    @Order(11)
    void disassociateAccessPolicy() {
        given()
        .when()
            .delete("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL)
                    + "/access-policies/" + encode(POLICY))
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL) + "/access-policies")
        .then()
            .statusCode(200)
            .body("associatedAccessPolicies", hasSize(0));
    }

    @Test
    @Order(12)
    void deleteAccessEntry() {
        given()
        .when()
            .delete("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries/" + encode(PRINCIPAL))
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/access-entries")
        .then()
            .statusCode(200)
            .body("accessEntries", hasSize(0));
    }

    @Test
    @Order(13)
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
