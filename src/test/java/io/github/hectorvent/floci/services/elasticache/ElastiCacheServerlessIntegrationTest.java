package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ElastiCacheServerlessIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";
    private static final String CACHE_NAME = "it-serverless-cache";
    private static final String SNAPSHOT_NAME = "it-serverless-snap";
    private static final String MISSING = "alchemy-nonexistent-cache-probe";

    @Test
    void describeServerlessCachesEmptyAndNotFound() {
        given()
            .formParam("Action", "DescribeServerlessCaches")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(containsString("DescribeServerlessCachesResult"));

        given()
            .formParam("Action", "DescribeServerlessCaches")
            .formParam("ServerlessCacheName", MISSING)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ServerlessCacheNotFoundFault"));
    }

    @Test
    void describeServerlessCacheSnapshotsEmpty() {
        given()
            .formParam("Action", "DescribeServerlessCacheSnapshots")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeServerlessCacheSnapshotsResult"));
    }

    @Test
    void describeEventsEmpty() {
        given()
            .formParam("Action", "DescribeEvents")
            .formParam("SourceType", "serverless-cache")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Events>"));
    }

    @Test
    void snapshotMutationsOnMissingNameReturnNotFound() {
        given()
            .formParam("Action", "DeleteServerlessCacheSnapshot")
            .formParam("ServerlessCacheSnapshotName", MISSING)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ServerlessCacheSnapshotNotFoundFault"));

        given()
            .formParam("Action", "CopyServerlessCacheSnapshot")
            .formParam("SourceServerlessCacheSnapshotName", MISSING)
            .formParam("TargetServerlessCacheSnapshotName", MISSING + "-copy")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ServerlessCacheSnapshotNotFoundFault"));

        given()
            .formParam("Action", "ExportServerlessCacheSnapshot")
            .formParam("ServerlessCacheSnapshotName", MISSING)
            .formParam("S3BucketName", "alchemy-elasticache-export-probe")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ServerlessCacheSnapshotNotFoundFault"));
    }

    @Test
    void createDescribeSnapshotCopyExportDeleteRoundTrip() {
        given()
            .formParam("Action", "CreateServerlessCache")
            .formParam("ServerlessCacheName", CACHE_NAME)
            .formParam("Engine", "valkey")
            .formParam("CacheUsageLimits.DataStorage.Maximum", "1")
            .formParam("CacheUsageLimits.DataStorage.Unit", "GB")
            .formParam("CacheUsageLimits.ECPUPerSecond.Maximum", "1000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServerlessCacheResponse.CreateServerlessCacheResult.ServerlessCache.ServerlessCacheName",
                    equalTo(CACHE_NAME))
            .body("CreateServerlessCacheResponse.CreateServerlessCacheResult.ServerlessCache.Status",
                    equalTo("available"))
            .body("CreateServerlessCacheResponse.CreateServerlessCacheResult.ServerlessCache.Endpoint.Port",
                    equalTo("6379"));

        given()
            .formParam("Action", "DescribeServerlessCaches")
            .formParam("ServerlessCacheName", CACHE_NAME)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(CACHE_NAME));

        given()
            .formParam("Action", "CreateServerlessCacheSnapshot")
            .formParam("ServerlessCacheName", CACHE_NAME)
            .formParam("ServerlessCacheSnapshotName", SNAPSHOT_NAME)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateServerlessCacheSnapshotResponse.CreateServerlessCacheSnapshotResult.ServerlessCacheSnapshot.ServerlessCacheSnapshotName",
                    equalTo(SNAPSHOT_NAME))
            .body("CreateServerlessCacheSnapshotResponse.CreateServerlessCacheSnapshotResult.ServerlessCacheSnapshot.Status",
                    equalTo("available"));

        given()
            .formParam("Action", "CopyServerlessCacheSnapshot")
            .formParam("SourceServerlessCacheSnapshotName", SNAPSHOT_NAME)
            .formParam("TargetServerlessCacheSnapshotName", SNAPSHOT_NAME + "-copy")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(SNAPSHOT_NAME + "-copy"));

        given()
            .formParam("Action", "ExportServerlessCacheSnapshot")
            .formParam("ServerlessCacheSnapshotName", SNAPSHOT_NAME)
            .formParam("S3BucketName", "alchemy-elasticache-export-probe")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(SNAPSHOT_NAME));

        given()
            .formParam("Action", "DeleteServerlessCacheSnapshot")
            .formParam("ServerlessCacheSnapshotName", SNAPSHOT_NAME + "-copy")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteServerlessCacheSnapshot")
            .formParam("ServerlessCacheSnapshotName", SNAPSHOT_NAME)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteServerlessCache")
            .formParam("ServerlessCacheName", CACHE_NAME)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
