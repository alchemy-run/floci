package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The serverless cache actions over the Query protocol, through the real ElastiCache dispatch.
 * Only paths that need no engine container are exercised here; provisioning and the data plane
 * are covered by {@link ElastiCacheServerlessServiceTest}.
 */
@QuarkusTest
class ElastiCacheServerlessIntegrationTest {

    private static final String EC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";

    private static RequestSpecification elasticache(String action) {
        return given().header("Authorization", EC_AUTH)
                .formParam("Action", action)
                .formParam("Version", "2015-02-02");
    }

    @Test
    void describeOfAMissingCacheIsTheTypedNotFoundFault() {
        elasticache("DescribeServerlessCaches")
                .formParam("ServerlessCacheName", "alchemy-nonexistent-cache-probe")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>ServerlessCacheNotFoundFault</Code>"));
    }

    @Test
    void describeWithoutFiltersAnswersTheListShape() {
        elasticache("DescribeServerlessCaches")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DescribeServerlessCachesResult>"))
            .body(containsString("<ServerlessCaches>"));
    }

    @Test
    void createRejectsAnUnknownEngineBeforeProvisioningAnything() {
        elasticache("CreateServerlessCache")
                .formParam("ServerlessCacheName", "bad-engine")
                .formParam("Engine", "mongodb")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void createRejectsSubnetsThatDoNotExistInEc2() {
        elasticache("CreateServerlessCache")
                .formParam("ServerlessCacheName", "bad-subnet")
                .formParam("Engine", "valkey")
                .formParam("SubnetIds.SubnetId.1", "subnet-0doesnotexist")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void createRejectsUsageLimitsBelowTheServiceMinimum() {
        elasticache("CreateServerlessCache")
                .formParam("ServerlessCacheName", "low-ecpu")
                .formParam("Engine", "valkey")
                .formParam("CacheUsageLimits.ECPUPerSecond.Maximum", "10")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void snapshotLookupsAreTypedNotFoundFaults() {
        for (String action : new String[] {"DeleteServerlessCacheSnapshot", "ExportServerlessCacheSnapshot"}) {
            elasticache(action)
                    .formParam("ServerlessCacheSnapshotName", "nope")
                    .formParam("S3BucketName", "bucket")
            .when().post("/")
            .then()
                .statusCode(404)
                .body(containsString("<Code>ServerlessCacheSnapshotNotFoundFault</Code>"));
        }
        elasticache("CopyServerlessCacheSnapshot")
                .formParam("SourceServerlessCacheSnapshotName", "nope")
                .formParam("TargetServerlessCacheSnapshotName", "nope-copy")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>ServerlessCacheSnapshotNotFoundFault</Code>"));
    }

    @Test
    void tagsOfAMissingServerlessCacheAreItsNotFoundFault() {
        elasticache("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:serverlesscache:nope")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>ServerlessCacheNotFoundFault</Code>"));
    }
}
