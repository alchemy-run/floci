package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Query-protocol coverage for Alchemy {@code test/AWS/Neptune/Bindings.test.ts}:
 * account-level lists (empty-ok) and typed not-found tags for identifier probes.
 *
 * Neptune's SDK signs with the {@code rds} credential scope; tests cover both
 * the {@code neptune} scope (direct handler) and the {@code rds} scope (the
 * path the distilled client takes).
 */
@QuarkusTest
class NeptuneBindingsIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String MISSING_CLUSTER = "alchemy-nonexistent-neptune-probe";
    private static final String MISSING_INSTANCE = "alchemy-nonexistent-neptune-instance-probe";
    private static final String MISSING_SNAPSHOT = "alchemy-nonexistent-neptune-snapshot-probe";
    private static final String MISSING_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster:" + MISSING_CLUSTER;

    private static final String AUTH_NEPTUNE =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    private static final String AUTH_RDS =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/rds/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    void describeClusters_missing_dbClusterNotFoundFault_neptuneScope() {
        neptune("DescribeDBClusters")
            .formParam("DBClusterIdentifier", MISSING_CLUSTER)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    void describeClusters_missing_dbClusterNotFoundFault_rdsScope() {
        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", MISSING_CLUSTER)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    void describeInstances_missing_dbInstanceNotFound_neptuneScope() {
        neptune("DescribeDBInstances")
            .formParam("DBInstanceIdentifier", MISSING_INSTANCE)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBInstanceNotFound"));
    }

    @Test
    void describeInstances_missing_dbInstanceNotFound_rdsScope() {
        rds("DescribeDBInstances")
            .formParam("DBInstanceIdentifier", MISSING_INSTANCE)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBInstanceNotFound"));
    }

    @Test
    void describeEvents_returnsEmptyList() {
        neptune("DescribeEvents")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeEventsResult"))
            .body(containsString("<Events"));
    }

    @Test
    void describeEvents_rdsScope_returnsList() {
        rds("DescribeEvents")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeEventsResult"))
            .body(containsString("<Events"));
    }

    @Test
    void describeClusterSnapshots_returnsEmptyList() {
        neptune("DescribeDBClusterSnapshots")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeDBClusterSnapshotsResult"))
            .body(containsString("DBClusterSnapshots"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void describeClusterEndpoints_returnsEmptyList() {
        neptune("DescribeDBClusterEndpoints")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeDBClusterEndpointsResult"))
            .body(containsString("DBClusterEndpoints"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void deleteClusterSnapshot_missing_notFoundFault() {
        neptune("DeleteDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", MISSING_SNAPSHOT)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    @Test
    void deleteClusterSnapshot_missing_rdsScope_notFoundFault() {
        rds("DeleteDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", MISSING_SNAPSHOT)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    @Test
    void copyClusterSnapshot_missingSource_notFoundFault() {
        neptune("CopyDBClusterSnapshot")
            .formParam("SourceDBClusterSnapshotIdentifier", MISSING_SNAPSHOT)
            .formParam("TargetDBClusterSnapshotIdentifier", MISSING_SNAPSHOT + "-copy")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    @Test
    void copyClusterSnapshot_missingSource_rdsScope_notFoundFault() {
        rds("CopyDBClusterSnapshot")
            .formParam("SourceDBClusterSnapshotIdentifier", MISSING_SNAPSHOT)
            .formParam("TargetDBClusterSnapshotIdentifier", MISSING_SNAPSHOT + "-copy")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    @Test
    void describePendingMaintenanceActions_returnsEmptyList() {
        neptune("DescribePendingMaintenanceActions")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribePendingMaintenanceActionsResult"))
            .body(containsString("PendingMaintenanceActions"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void applyPendingMaintenanceAction_missingArn_resourceNotFoundFault() {
        neptune("ApplyPendingMaintenanceAction")
            .formParam("ResourceIdentifier", MISSING_ARN)
            .formParam("ApplyAction", "system-update")
            .formParam("OptInType", "next-maintenance")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ResourceNotFoundFault"));
    }

    @Test
    void applyPendingMaintenanceAction_missingArn_rdsScope_resourceNotFoundFault() {
        rds("ApplyPendingMaintenanceAction")
            .formParam("ResourceIdentifier", MISSING_ARN)
            .formParam("ApplyAction", "system-update")
            .formParam("OptInType", "next-maintenance")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ResourceNotFoundFault"));
    }

    private static io.restassured.specification.RequestSpecification neptune(String action) {
        return given()
            .header("Authorization", AUTH_NEPTUNE)
            .contentType(FORM)
            .formParam("Action", action);
    }

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given()
            .header("Authorization", AUTH_RDS)
            .contentType(FORM)
            .formParam("Action", action);
    }
}
