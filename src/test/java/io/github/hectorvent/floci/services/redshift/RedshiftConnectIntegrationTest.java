package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Query-protocol coverage for Alchemy {@code test/AWS/Redshift/Connect.test.ts}:
 * typed not-found on both credential strategies, plus minting IAM-mapped and
 * AutoCreate named-user temporary credentials.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftConnectIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String MISSING_CLUSTER = "alchemy-nonexistent-redshift-connect-probe";
    private static final String CLUSTER_ID = "alchemy-redshift-connect-it";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/redshift/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void getClusterCredentialsWithIAM_missing_clusterNotFound() {
        redshift("GetClusterCredentialsWithIAM")
            .formParam("ClusterIdentifier", MISSING_CLUSTER)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterNotFound"));
    }

    @Test
    @Order(2)
    void getClusterCredentials_missing_clusterNotFound() {
        redshift("GetClusterCredentials")
            .formParam("ClusterIdentifier", MISSING_CLUSTER)
            .formParam("DbUser", "probe")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterNotFound"));
    }

    @Test
    @Order(3)
    void createClusterForCredentials() {
        redshift("CreateCluster")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("NodeType", "ra3.large")
            .formParam("MasterUsername", "awsuser")
            .formParam("MasterUserPassword", "Secret99")
            .formParam("DBName", "dev")
            .formParam("ManageMasterPassword", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"));
    }

    @Test
    @Order(4)
    void getClusterCredentialsWithIAM_mintsIamMappedUser() {
        redshift("GetClusterCredentialsWithIAM")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("DbName", "dev")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("GetClusterCredentialsWithIAMResult"))
            .body(containsString("<DbUser>IAM"))
            .body(containsString("<DbPassword>"))
            .body(containsString("<Expiration>"))
            .body(containsString("<NextRefreshTime>"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    @Order(5)
    void getClusterCredentials_autoCreatePrefixesIama() {
        redshift("GetClusterCredentials")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("DbUser", "alchemy_etl")
            .formParam("DbName", "dev")
            .formParam("AutoCreate", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("GetClusterCredentialsResult"))
            .body(containsString("<DbUser>IAMA:alchemy_etl</DbUser>"))
            .body(containsString("<DbPassword>"))
            .body(containsString("<Expiration>"));
    }

    @Test
    @Order(6)
    void getClusterCredentials_withoutAutoCreatePrefixesIam() {
        redshift("GetClusterCredentials")
            .formParam("ClusterIdentifier", CLUSTER_ID)
            .formParam("DbUser", "alchemy_etl")
            .formParam("AutoCreate", "false")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DbUser>IAM:alchemy_etl</DbUser>"));
    }

    private static io.restassured.specification.RequestSpecification redshift(String action) {
        return given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", action);
    }
}
