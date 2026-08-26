package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Query-protocol coverage for Alchemy {@code test/AWS/Redshift/Bindings.test.ts}:
 * account-level lists (empty-ok) and typed not-found tags for identifier probes.
 */
@QuarkusTest
class RedshiftBindingsIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String MISSING_CLUSTER = "alchemy-nonexistent-redshift-probe";
    private static final String MISSING_SNAPSHOT = "alchemy-nonexistent-redshift-snapshot-probe";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/redshift/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    void describeClusters_missing_clusterNotFound() {
        redshift("DescribeClusters")
            .formParam("ClusterIdentifier", MISSING_CLUSTER)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterNotFound"));
    }

    @Test
    void describeClusterSnapshots_returnsEmptyList() {
        redshift("DescribeClusterSnapshots")
            .formParam("SnapshotType", "manual")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeClusterSnapshotsResult"))
            .body(containsString("Snapshots"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void describeEvents_returnsEmptyList() {
        redshift("DescribeEvents")
            .formParam("Duration", "60")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeEventsResult"))
            .body(containsString("<Events"))
            .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void deleteClusterSnapshot_missing_clusterSnapshotNotFound() {
        redshift("DeleteClusterSnapshot")
            .formParam("SnapshotIdentifier", MISSING_SNAPSHOT)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterSnapshotNotFound"));
    }

    @Test
    void copyClusterSnapshot_missingSource_clusterSnapshotNotFound() {
        redshift("CopyClusterSnapshot")
            .formParam("SourceSnapshotIdentifier", MISSING_SNAPSHOT)
            .formParam("TargetSnapshotIdentifier", MISSING_SNAPSHOT + "-copy")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("ClusterSnapshotNotFound"));
    }

    @Test
    void createCluster_describe_createSnapshot_copy_deleteRoundTrip() {
        String clusterId = "alchemy-redshift-bindings";
        String snapshotId = "alchemy-redshift-bindings-snap";
        String copyId = snapshotId + "-copy";

        redshift("CreateCluster")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("NodeType", "ra3.large")
            .formParam("MasterUsername", "awsuser")
            .formParam("MasterUserPassword", "Secret99")
            .formParam("DBName", "dev")
            .formParam("NumberOfNodes", "1")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(clusterId))
            .body(containsString("available"));

        redshift("DescribeClusters")
            .formParam("ClusterIdentifier", clusterId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(clusterId));

        redshift("CreateClusterSnapshot")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("SnapshotIdentifier", snapshotId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(snapshotId))
            .body(containsString("available"));

        redshift("CopyClusterSnapshot")
            .formParam("SourceSnapshotIdentifier", snapshotId)
            .formParam("TargetSnapshotIdentifier", copyId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(copyId));

        redshift("DeleteClusterSnapshot")
            .formParam("SnapshotIdentifier", copyId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(copyId));

        redshift("DeleteClusterSnapshot")
            .formParam("SnapshotIdentifier", snapshotId)
        .when().post("/")
        .then()
            .statusCode(200);

        redshift("DeleteCluster")
            .formParam("ClusterIdentifier", clusterId)
            .formParam("SkipFinalClusterSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(200);
    }

    private static io.restassured.specification.RequestSpecification redshift(String action) {
        return given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", action);
    }
}
