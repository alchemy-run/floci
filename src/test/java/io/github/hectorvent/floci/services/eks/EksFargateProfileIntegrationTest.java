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
 * EKS Fargate profile REST flow. {@code describeFargateProfile} against a missing
 * cluster must return the model-declared {@code ResourceNotFoundException} (the
 * alchemy typed-error probe). List pagination must omit {@code nextToken} on the
 * terminal page so {@code listFargateProfiles.pages()} cannot loop.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksFargateProfileIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "fp-it-cluster";
    private static final String ROLE = "arn:aws:iam::000000000000:role/eks-fargate-role";
    private static final String PROFILE = "alchemy-fp";
    private static final String PROFILE_B = "alchemy-fp-b";

    @Test
    @Order(1)
    void describeFargateProfileOnMissingClusterReturnsResourceNotFound() {
        given()
        .when()
            .get("/clusters/alchemy-nonexistent-cluster-probe/fargate-profiles/alchemy-nonexistent-profile-probe")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", containsString("alchemy-nonexistent-cluster-probe"));
    }

    @Test
    @Order(2)
    void listFargateProfilesOnMissingClusterReturnsResourceNotFound() {
        given()
        .when()
            .get("/clusters/alchemy-nonexistent-cluster-probe/fargate-profiles")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(3)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(4)
    void existingClusterListsEmptyProfilesWithoutNextToken() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("fargateProfileNames", hasSize(0))
            .body("nextToken", equalTo(null));
    }

    @Test
    @Order(5)
    void createFargateProfileRoutesToEksNotS3() {
        given().contentType(JSON)
                .body("{\"fargateProfileName\":\"" + PROFILE + "\",\"podExecutionRoleArn\":\"" + ROLE + "\","
                        + "\"subnets\":[\"subnet-aaa\",\"subnet-bbb\"],"
                        + "\"selectors\":[{\"namespace\":\"alchemy-fargate-test\"}],"
                        + "\"tags\":{\"env\":\"test\"}}")
                .when().post("/clusters/" + CLUSTER + "/fargate-profiles")
                .then()
                .statusCode(200)
                .body("fargateProfile.fargateProfileName", equalTo(PROFILE))
                .body("fargateProfile.clusterName", equalTo(CLUSTER))
                .body("fargateProfile.fargateProfileArn",
                        containsString("fargateprofile/" + CLUSTER + "/" + PROFILE + "/"))
                .body("fargateProfile.status", equalTo("ACTIVE"))
                .body("fargateProfile.podExecutionRoleArn", equalTo(ROLE))
                .body("fargateProfile.subnets[0]", equalTo("subnet-aaa"))
                .body("fargateProfile.selectors[0].namespace", equalTo("alchemy-fargate-test"))
                .body("fargateProfile.health.issues", hasSize(0))
                .body("fargateProfile.tags.env", equalTo("test"))
                .body("fargateProfile.createdAt", notNullValue());
    }

    @Test
    @Order(6)
    void listFargateProfiles() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles")
        .then().statusCode(200)
                .body("fargateProfileNames", hasItem(PROFILE))
                .body("nextToken", equalTo(null));
    }

    @Test
    @Order(7)
    void describeFargateProfile() {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
        .then().statusCode(200)
                .body("fargateProfile.fargateProfileName", equalTo(PROFILE))
                .body("fargateProfile.clusterName", equalTo(CLUSTER))
                .body("fargateProfile.status", equalTo("ACTIVE"))
                .body("fargateProfile.podExecutionRoleArn", equalTo(ROLE));
    }

    @Test
    @Order(8)
    void listFargateProfilesPaginatesWithoutLooping() {
        given().contentType(JSON)
                .body("{\"fargateProfileName\":\"" + PROFILE_B + "\",\"podExecutionRoleArn\":\"" + ROLE + "\","
                        + "\"selectors\":[{\"namespace\":\"default\"}]}")
                .when().post("/clusters/" + CLUSTER + "/fargate-profiles")
                .then().statusCode(200);

        given()
            .queryParam("maxResults", 1)
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles")
        .then().statusCode(200)
                .body("fargateProfileNames", hasSize(1))
                .body("nextToken", equalTo("1"));

        given()
            .queryParam("maxResults", 1)
            .queryParam("nextToken", "1")
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles")
        .then().statusCode(200)
                .body("fargateProfileNames", hasSize(1))
                .body("nextToken", equalTo(null));
    }

    @Test
    @Order(9)
    void tagFargateProfile() {
        String arn = given()
                .when()
                    .get("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
                .then()
                    .statusCode(200)
                    .extract()
                    .path("fargateProfile.fargateProfileArn");

        given().contentType(JSON)
                .body("{\"tags\":{\"team\":\"platform\"}}")
                .when().post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
        .then()
            .statusCode(200)
            .body("fargateProfile.tags.env", equalTo("test"))
            .body("fargateProfile.tags.team", equalTo("platform"));

        given()
        .when()
            .delete("/tags/" + encode(arn) + "?tagKeys=env")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
        .then()
            .statusCode(200)
            .body("fargateProfile.tags.env", equalTo(null))
            .body("fargateProfile.tags.team", equalTo("platform"));
    }

    @Test
    @Order(10)
    void deleteFargateProfile() {
        given()
        .when()
            .delete("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
        .then()
            .statusCode(200)
            .body("fargateProfile.status", equalTo("DELETING"));

        given()
        .when()
            .get("/clusters/" + CLUSTER + "/fargate-profiles/" + PROFILE)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
