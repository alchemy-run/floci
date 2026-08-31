package io.github.hectorvent.floci.services.finspace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies FinSpace restJson1 kdb environment-scoped binding operations. */
@QuarkusTest
class FinSpaceIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String AUTH = auth("000000000501", EAST);
    private static final String MISSING = "zzzzzzzzzzzzzzzzzzzzzzzzzz";
    private static final String USER_ARN =
            "arn:aws:finspace:us-east-1:123456789012:kxEnvironment/" + MISSING + "/kxUser/nouser";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getKxConnectionStringOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/kx/environments/" + MISSING
                        + "/connectionString?userArn=" + USER_ARN + "&clusterName=nocluster")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createKxChangesetOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "changeRequests":[{
                            "changeType":"PUT",
                            "s3Path":"s3://nonexistent-bucket/nonexistent/",
                            "dbPath":"/2024.01.02/"
                          }],
                          "clientToken":"alchemy-finspace-probe-changeset"
                        }
                        """)
                .when()
                .post("/kx/environments/" + MISSING + "/databases/nodb/changesets")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listKxChangesetsOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/kx/environments/" + MISSING + "/databases/nodb/changesets")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listKxClusterNodesOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/kx/environments/" + MISSING + "/clusters/nocluster/nodes")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxDataviewOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/kx/environments/" + MISSING + "/databases/nodb/dataviews/noview")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getKxUserOnANonexistentEnvironmentFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/kx/environments/" + MISSING + "/users/nouser")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createEnvironmentThenConnectionStringChangesetsNodesDataviewAndUserRoundTrip() {
        String authorization = auth("000000000502", EAST);
        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"bindings-kdb",
                          "kmsKeyId":"arn:aws:kms:us-east-1:000000000502:key/abc",
                          "description":"bindings fixture"
                        }
                        """)
                .when()
                .post("/kx/environments")
                .then()
                .statusCode(200)
                .body("environmentId", notNullValue())
                .body("status", equalTo("CREATED"))
                .body("name", equalTo("bindings-kdb"))
                .extract()
                .response();
        String environmentId = created.path("environmentId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/users/nouser")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "userName":"trader",
                          "iamRole":"arn:aws:iam::000000000502:role/kdb-user"
                        }
                        """)
                .when()
                .post("/kx/environments/" + environmentId + "/users")
                .then()
                .statusCode(200)
                .body("userName", equalTo("trader"))
                .body("environmentId", equalTo(environmentId))
                .body("userArn", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/users/trader")
                .then()
                .statusCode(200)
                .body("userName", equalTo("trader"))
                .body("iamRole", equalTo("arn:aws:iam::000000000502:role/kdb-user"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clusterName":"hdb1",
                          "clusterType":"HDB",
                          "releaseLabel":"1.0",
                          "azMode":"SINGLE",
                          "vpcConfiguration":{"vpcId":"vpc-1","subnetIds":["subnet-1"],"securityGroupIds":["sg-1"]}
                        }
                        """)
                .when()
                .post("/kx/environments/" + environmentId + "/clusters")
                .then()
                .statusCode(200)
                .body("clusterName", equalTo("hdb1"));

        String userArn = "arn:aws:finspace:" + EAST + ":000000000502:kxEnvironment/"
                + environmentId + "/kxUser/trader";
        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId
                        + "/connectionString?userArn=" + userArn + "&clusterName=hdb1")
                .then()
                .statusCode(200)
                .body("signedConnectionString", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/clusters/hdb1/nodes")
                .then()
                .statusCode(200)
                .body("nodes", hasSize(1))
                .body("nodes[0].status", equalTo("RUNNING"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "databaseName":"market",
                          "clientToken":"create-db-market"
                        }
                        """)
                .when()
                .post("/kx/environments/" + environmentId + "/databases")
                .then()
                .statusCode(200)
                .body("databaseName", equalTo("market"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "changeRequests":[{
                            "changeType":"PUT",
                            "s3Path":"s3://bucket/data/",
                            "dbPath":"/2024.01.02/"
                          }],
                          "clientToken":"create-changeset-1"
                        }
                        """)
                .when()
                .post("/kx/environments/" + environmentId + "/databases/market/changesets")
                .then()
                .statusCode(200)
                .body("changesetId", notNullValue())
                .body("status", equalTo("COMPLETED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/databases/market/changesets")
                .then()
                .statusCode(200)
                .body("kxChangesets", hasSize(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "dataviewName":"eod",
                          "azMode":"SINGLE",
                          "clientToken":"create-view-eod"
                        }
                        """)
                .when()
                .post("/kx/environments/" + environmentId + "/databases/market/dataviews")
                .then()
                .statusCode(200)
                .body("dataviewName", equalTo("eod"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/kx/environments/" + environmentId + "/databases/market/dataviews/eod")
                .then()
                .statusCode(200)
                .body("dataviewName", equalTo("eod"))
                .body("status", equalTo("ACTIVE"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/kx/environments/" + environmentId)
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/finspace/aws4_request";
    }
}
