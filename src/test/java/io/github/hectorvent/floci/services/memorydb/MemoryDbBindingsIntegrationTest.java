package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 operations Alchemy {@code test/AWS/MemoryDB/Bindings.test.ts} exercises:
 * account-level describe lists plus typed not-found for snapshot and service-update probes.
 */
@QuarkusTest
class MemoryDbBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String MISSING = "alchemy-memorydb-nonexistent-probe";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }

    @Test
    void describeClusters_emptyFilter_returnsArray() {
        memorydb("DescribeClusters", "{}")
                .then()
                .statusCode(200)
                .body("Clusters", notNullValue())
                .body("Clusters.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void describeClusters_missing_clusterNotFound() {
        memorydb("DescribeClusters", "{\"ClusterName\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ClusterNotFoundFault"));
    }

    @Test
    void describeSnapshots_emptyAccount_returnsArray() {
        memorydb("DescribeSnapshots", "{}")
                .then()
                .statusCode(200)
                .body("Snapshots", notNullValue())
                .body("Snapshots.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void describeEvents_clusterSource_returnsArray() {
        memorydb("DescribeEvents", "{\"SourceType\":\"cluster\"}")
                .then()
                .statusCode(200)
                .body("Events", notNullValue())
                .body("Events.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void describeServiceUpdates_emptyAccount_returnsArray() {
        memorydb("DescribeServiceUpdates", "{}")
                .then()
                .statusCode(200)
                .body("ServiceUpdates", notNullValue())
                .body("ServiceUpdates.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void describeEngineVersions_valkey_returnsAtLeastOne() {
        memorydb("DescribeEngineVersions", "{\"Engine\":\"valkey\"}")
                .then()
                .statusCode(200)
                .body("EngineVersions", notNullValue())
                .body("EngineVersions.size()", greaterThanOrEqualTo(1))
                .body("EngineVersions[0].Engine", equalTo("valkey"));
    }

    @Test
    void batchUpdateCluster_unknownServiceUpdate_serviceUpdateNotFound() {
        memorydb("BatchUpdateCluster",
                "{\"ClusterNames\":[\"" + MISSING + "\"],"
                        + "\"ServiceUpdate\":{\"ServiceUpdateNameToApply\":\""
                        + MISSING + "-service-update\"}}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ServiceUpdateNotFoundFault"));
    }

    @Test
    void deleteSnapshot_missing_snapshotNotFound() {
        memorydb("DeleteSnapshot", "{\"SnapshotName\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SnapshotNotFoundFault"));
    }

    @Test
    void copySnapshot_missingSource_snapshotNotFound() {
        memorydb("CopySnapshot",
                "{\"SourceSnapshotName\":\"" + MISSING + "\",\"TargetSnapshotName\":\""
                        + MISSING + "-copy\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("SnapshotNotFoundFault"));
    }
}
