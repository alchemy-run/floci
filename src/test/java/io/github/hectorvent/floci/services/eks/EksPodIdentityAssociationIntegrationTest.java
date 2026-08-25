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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * EKS pod identity association REST flow. Alchemy {@code list()} walks
 * listClusters → listPodIdentityAssociations → describePodIdentityAssociation;
 * reconcile also create/update/tag/delete. Routes must beat S3's catch-all.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksPodIdentityAssociationIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "pia-it-cluster";
    private static final String ROLE = "arn:aws:iam::000000000000:role/eks-pod-it";
    private static final String ROLE_UPDATED = "arn:aws:iam::000000000000:role/eks-pod-it-updated";
    private static final String NAMESPACE = "default";
    private static final String SERVICE_ACCOUNT = "alchemy-test-list-sa";

    private static String associationId;

    @Test
    @Order(1)
    void missingClusterReturnsResourceNotFound() {
        given()
        .when()
            .get("/clusters/no-such-cluster/pod-identity-associations")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", containsString("no-such-cluster"));
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
            .get("/clusters/" + CLUSTER + "/pod-identity-associations")
        .then()
            .statusCode(200)
            .body("associations", hasSize(0));
    }

    @Test
    @Order(4)
    void createAssociation() {
        associationId = given().contentType(JSON)
                .body("{\"namespace\":\"" + NAMESPACE + "\",\"serviceAccount\":\"" + SERVICE_ACCOUNT + "\","
                        + "\"roleArn\":\"" + ROLE + "\",\"tags\":{\"env\":\"test\"}}")
                .when().post("/clusters/" + CLUSTER + "/pod-identity-associations")
                .then()
                .statusCode(200)
                .body("association.clusterName", equalTo(CLUSTER))
                .body("association.namespace", equalTo(NAMESPACE))
                .body("association.serviceAccount", equalTo(SERVICE_ACCOUNT))
                .body("association.roleArn", equalTo(ROLE))
                .body("association.associationId", notNullValue())
                .body("association.associationArn", containsString("podidentityassociation/" + CLUSTER + "/"))
                .body("association.disableSessionTags", equalTo(false))
                .body("association.tags.env", equalTo("test"))
                .body("association.createdAt", notNullValue())
                .extract()
                .path("association.associationId");
    }

    @Test
    @Order(5)
    void duplicateCreateIsResourceInUse() {
        given().contentType(JSON)
                .body("{\"namespace\":\"" + NAMESPACE + "\",\"serviceAccount\":\"" + SERVICE_ACCOUNT + "\","
                        + "\"roleArn\":\"" + ROLE + "\"}")
                .when().post("/clusters/" + CLUSTER + "/pod-identity-associations")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    @Order(6)
    void listAssociationsReturnsSummary() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations")
        .then()
            .statusCode(200)
            .body("associations", hasSize(1))
            .body("associations[0].associationId", equalTo(associationId))
            .body("associations[0].clusterName", equalTo(CLUSTER))
            .body("associations[0].namespace", equalTo(NAMESPACE))
            .body("associations[0].serviceAccount", equalTo(SERVICE_ACCOUNT))
            .body("associations[0].associationArn", containsString("podidentityassociation/" + CLUSTER + "/"));
    }

    @Test
    @Order(7)
    void listFiltersByNamespaceAndServiceAccount() {
        given()
            .queryParam("namespace", NAMESPACE)
            .queryParam("serviceAccount", SERVICE_ACCOUNT)
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations")
        .then()
            .statusCode(200)
            .body("associations", hasSize(1))
            .body("associations[0].associationId", equalTo(associationId));

        given()
            .queryParam("namespace", "other")
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations")
        .then()
            .statusCode(200)
            .body("associations", hasSize(0));
    }

    @Test
    @Order(8)
    void describeAssociation() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
        .then()
            .statusCode(200)
            .body("association.associationId", equalTo(associationId))
            .body("association.clusterName", equalTo(CLUSTER))
            .body("association.roleArn", equalTo(ROLE))
            .body("association.tags.env", equalTo("test"));
    }

    @Test
    @Order(9)
    void describeMissingReturnsResourceNotFound() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations/a-missing")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(10)
    void updateAssociation() {
        given().contentType(JSON)
                .body("{\"roleArn\":\"" + ROLE_UPDATED + "\",\"disableSessionTags\":true}")
                .when().post("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
                .then()
                .statusCode(200)
                .body("association.roleArn", equalTo(ROLE_UPDATED))
                .body("association.disableSessionTags", equalTo(true));
    }

    @Test
    @Order(11)
    void tagAssociation() {
        String arn = given()
                .when()
                    .get("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
                .then()
                    .statusCode(200)
                    .extract()
                    .path("association.associationArn");

        given().contentType(JSON)
                .body("{\"tags\":{\"team\":\"platform\"}}")
                .when().post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
        .then()
            .statusCode(200)
            .body("association.tags.env", equalTo("test"))
            .body("association.tags.team", equalTo("platform"));
    }

    @Test
    @Order(12)
    void deleteAssociation() {
        given()
        .when()
            .delete("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
        .then()
            .statusCode(200)
            .body("association.associationId", equalTo(associationId));

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations/" + associationId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/pod-identity-associations")
        .then()
            .statusCode(200)
            .body("associations", hasSize(0));
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
