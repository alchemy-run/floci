package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Account-scoped EKS catalogs used by Alchemy's EKS bindings fixture:
 * ListAccessPolicies, DescribeClusterVersions, DescribeAddonVersions,
 * DescribeAddonConfiguration. These routes must be handled by EKS — S3's
 * path-style catch-all would otherwise return XML NoSuchBucket.
 */
@QuarkusTest
class EksCatalogIntegrationTest {

    private static RequestSpecification eks() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/eks/aws4_request");
    }

    @Test
    void functionUrlBindingsPathIsNotClaimedByEks() {
        given()
                .header("Host", "deadbeefdeadbeefdeadbeefdeadbeef.lambda-url.us-east-1.localhost:4566")
        .when()
                .get("/bindings")
        .then()
                .statusCode(404)
                .body("message", containsString("URL ID"));
    }

    @Test
    void listClustersReturnsJsonNames() {
        eks()
        .when()
            .get("/clusters")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("clusters", notNullValue());
    }

    @Test
    void listAccessPoliciesReturnsAwsManagedCatalog() {
        eks()
        .when()
            .get("/access-policies")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("accessPolicies.size()", greaterThan(0))
            .body("accessPolicies.name", hasItem("AmazonEKSViewPolicy"))
            .body("accessPolicies.find { it.name == 'AmazonEKSViewPolicy' }.arn",
                    equalTo("arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"));
    }

    @Test
    void describeClusterVersionsReportsDefaultKubernetesVersion() {
        eks()
            .queryParam("defaultOnly", true)
        .when()
            .get("/cluster-versions")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("clusterVersions.size()", greaterThan(0))
            .body("clusterVersions[0].clusterVersion", matchesPattern("^\\d+\\.\\d+$"))
            .body("clusterVersions[0].defaultVersion", equalTo(true));
    }

    @Test
    void describeAddonVersionsFindsVpcCni() {
        eks()
            .queryParam("addonName", "vpc-cni")
            .queryParam("maxResults", 1)
        .when()
            .get("/addons/supported-versions")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("addons.size()", equalTo(1))
            .body("addons[0].addonName", equalTo("vpc-cni"))
            .body("addons[0].addonVersions.size()", greaterThan(0));
    }

    @Test
    void describeAddonConfigurationReturnsSchemaForLiveVpcCniVersion() {
        String version = eks()
            .queryParam("addonName", "vpc-cni")
            .queryParam("maxResults", 1)
        .when()
            .get("/addons/supported-versions")
        .then()
            .statusCode(200)
            .extract()
            .path("addons[0].addonVersions[0].addonVersion");

        eks()
            .queryParam("addonName", "vpc-cni")
            .queryParam("addonVersion", version)
        .when()
            .get("/addons/configuration-schemas")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("addonName", equalTo("vpc-cni"))
            .body("addonVersion", equalTo(version))
            .body("configurationSchema", not(emptyOrNullString()));
    }

    @Test
    void unknownAddonConfigurationIsResourceNotFound() {
        eks()
            .queryParam("addonName", "vpc-cni")
            .queryParam("addonVersion", "v0.0.0-not-a-version")
        .when()
            .get("/addons/configuration-schemas")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void catalogAlsoAnswersOnEksHostWithoutCredentialScope() {
        given()
            .header("Host", "eks.us-east-1.amazonaws.com")
        .when()
            .get("/access-policies")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("accessPolicies.name", hasItem("AmazonEKSViewPolicy"));
    }
}
