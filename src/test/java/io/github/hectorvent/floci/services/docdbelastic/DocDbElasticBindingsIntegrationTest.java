package io.github.hectorvent.floci.services.docdbelastic;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

/**
 * Binding-surface operations Alchemy DocDBElastic Bindings exercise: list
 * snapshots and pending maintenance, plus typed not-found/denied errors for
 * well-formed-but-nonexistent ARNs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocDbElasticBindingsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/docdb-elastic/aws4_request";
    private static final String MISSING =
            "arn:aws:docdb-elastic:us-east-1:000000000000:cluster-snapshot/00000000-0000-0000-0000-000000000000";
    private static final String MISSING_CLUSTER =
            "arn:aws:docdb-elastic:us-east-1:000000000000:cluster/00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(10)
    void listClusterSnapshotsReturnsArray() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/cluster-snapshots")
                .then()
                .statusCode(200)
                .body("snapshots", notNullValue())
                .body("snapshots.size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(11)
    void listPendingMaintenanceActionsReturnsArray() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/pending-actions")
                .then()
                .statusCode(200)
                .body("resourcePendingMaintenanceActions", notNullValue())
                .body("resourcePendingMaintenanceActions.size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(12)
    void getClusterSnapshotUnknownArnIsResourceNotFound() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/cluster-snapshot/" + encode(MISSING))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(13)
    void deleteClusterSnapshotUnknownArnIsResourceNotFound() {
        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/cluster-snapshot/" + encode(MISSING))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(14)
    void copyClusterSnapshotUnknownArnIsTypedRejection() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"targetSnapshotName\":\"alchemy-docdb-elastic-copy-probe\"}")
                .when()
                .post("/cluster-snapshot/" + encode(MISSING) + "/copy")
                .then()
                .statusCode(oneOf(400, 403, 404))
                .body("__type", oneOf(
                        "ResourceNotFoundException",
                        "ValidationException",
                        "AccessDeniedException"));
    }

    @Test
    @Order(15)
    void restoreClusterFromSnapshotUnknownArnIsTypedRejection() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"clusterName\":\"alchemy-docdb-elastic-restore-probe\"}")
                .when()
                .post("/cluster-snapshot/" + encode(MISSING) + "/restore")
                .then()
                .statusCode(oneOf(400, 403, 404))
                .body("__type", oneOf(
                        "ResourceNotFoundException",
                        "ValidationException",
                        "AccessDeniedException"));
    }

    @Test
    @Order(16)
    void getPendingMaintenanceActionUnknownClusterIsTypedRejection() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/pending-action/" + encode(MISSING_CLUSTER))
                .then()
                .statusCode(oneOf(400, 404))
                .body("__type", oneOf("ResourceNotFoundException", "ValidationException"));
    }

    @Test
    @Order(17)
    void applyPendingMaintenanceActionUnknownClusterIsTypedRejection() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"resourceArn\":\"" + MISSING_CLUSTER
                        + "\",\"applyAction\":\"ENGINE_UPDATE\",\"optInType\":\"NEXT_MAINTENANCE\"}")
                .when()
                .post("/pending-action")
                .then()
                .statusCode(oneOf(400, 404))
                .body("__type", oneOf("ResourceNotFoundException", "ValidationException"));
    }

    @Test
    @Order(20)
    void createClusterSnapshotGetAndDeleteRoundTrip() {
        String clusterArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {"clusterName":"alchemy-docdb-elastic-bindings",\
                        "authType":"PLAIN_TEXT","adminUserName":"admin",\
                        "adminUserPassword":"super-secret-password",\
                        "shardCapacity":2,"shardCount":1}
                        """)
                .when()
                .post("/cluster")
                .then()
                .statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"))
                .extract()
                .path("cluster.clusterArn");

        String snapshotArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"clusterArn\":\"" + clusterArn
                        + "\",\"snapshotName\":\"alchemy-docdb-elastic-bindings-snap\"}")
                .when()
                .post("/cluster-snapshot")
                .then()
                .statusCode(200)
                .body("snapshot.status", equalTo("AVAILABLE"))
                .body("snapshot.snapshotArn", notNullValue())
                .extract()
                .path("snapshot.snapshotArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/cluster-snapshot/" + encode(snapshotArn))
                .then()
                .statusCode(200)
                .body("snapshot.snapshotName", equalTo("alchemy-docdb-elastic-bindings-snap"));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/cluster-snapshot/" + encode(snapshotArn))
                .then()
                .statusCode(200)
                .body("snapshot.snapshotArn", equalTo(snapshotArn));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/cluster/" + encode(clusterArn))
                .then()
                .statusCode(200);
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
