package io.floci.aws.services.backupsearch;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Backup Search over AWS Backup's indexes: an S3 backup started with {@code Index: ENABLED} is indexed with
 * the bucket's objects, a search job finds the objects its filters match, and an export writes the results to S3.
 */
@QuarkusTest
class BackupSearchIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification signed(String service) {
        return given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/" + service
                        + "/aws4_request");
    }

    private static String s3(String method, String path, String body) {
        RequestSpecification request = signed("s3");
        if (body != null) {
            request = request.body(body);
        }
        return request.request(method, path).then().statusCode(200).extract().asString();
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    /** Backs a bucket up into a vault with indexing on, and returns the recovery point once it completes. */
    private static String indexedBackup(String bucket, String vault) {
        signed("backup").body("{}").put("/backup-vaults/" + vault).then().statusCode(200);
        String job = signed("backup").body("{\"BackupVaultName\":\"" + vault + "\",\"ResourceArn\":\"arn:aws:s3:::"
                + bucket + "\",\"IamRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/backup\",\"Index\":\"ENABLED\","
                + "\"RecoveryPointTags\":{\"team\":\"data\"}}").put("/backup-jobs").then().statusCode(200).extract()
                .path("BackupJobId");
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> "COMPLETED".equals(signed("backup")
                .get("/backup-jobs/" + job).then().extract().path("State")));
        return signed("backup").get("/backup-jobs/" + job).then().extract().path("RecoveryPointArn");
    }

    private static String awaitSearch(String id) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !List.of("RUNNING", "STOPPING").contains(
                signed("backup-search").get("/search-jobs/" + id).then().extract().<String>path("Status")));
        return signed("backup-search").get("/search-jobs/" + id).then().extract().path("Status");
    }

    @Test
    void searchesAnIndexedS3BackupAndExportsTheResults() {
        String bucket = "search-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + bucket, null);
        s3("PUT", "/" + bucket + "/reports/2026/q1.csv", "a,b\n1,2\n");
        s3("PUT", "/" + bucket + "/reports/2026/q2.csv", "a,b\n3,4\n5,6\n");
        s3("PUT", "/" + bucket + "/images/logo.png", "png");
        String vault = "search-vault-" + UUID.randomUUID().toString().substring(0, 6);
        String point = indexedBackup(bucket, vault);

        signed("backup").urlEncodingEnabled(false).get("/backup-vaults/" + vault + "/recovery-points/" + encode(point)
                + "/index").then()
                .statusCode(200).body("IndexStatus", equalTo("ACTIVE")).body("TotalItemsIndexed", equalTo(3))
                .body("SourceResourceArn", equalTo("arn:aws:s3:::" + bucket));
        signed("backup").queryParam("resourceType", "S3").get("/indexes/recovery-point/").then().statusCode(200)
                .body("IndexedRecoveryPoints.RecoveryPointArn", hasItem(point));

        String id = signed("backup-search").body("{\"Name\":\"reports\",\"SearchScope\":{\"BackupResourceTypes\":"
                + "[\"S3\"],\"SourceResourceArns\":[\"arn:aws:s3:::" + bucket + "\"],\"BackupResourceTags\":{\"team\":"
                + "\"data\"}},\"ItemFilters\":{\"S3ItemFilters\":[{\"ObjectKeys\":[{\"Value\":\"reports/\","
                + "\"Operator\":\"BEGINS_WITH\"}],\"Sizes\":[{\"Value\":9,\"Operator\":\"GREATER_THAN_EQUAL_TO\"}]}]},"
                + "\"Tags\":{\"fixture\":\"search\"}}").put("/search-jobs").then().statusCode(200)
                .body("SearchJobArn", containsString(":backup:us-east-1:" + ACCOUNT + ":search-job/"))
                .extract().path("SearchJobIdentifier");
        assertEquals("COMPLETED", awaitSearch(id));
        signed("backup-search").get("/search-jobs/" + id).then().statusCode(200)
                .body("SearchScopeSummary.TotalRecoveryPointsToScanCount", equalTo(1))
                .body("CurrentSearchProgress.ItemsScannedCount", equalTo(3))
                .body("CurrentSearchProgress.ItemsMatchedCount", equalTo(1));
        signed("backup-search").get("/search-jobs/" + id + "/search-results").then().statusCode(200)
                .body("Results", hasSize(1)).body("Results[0].S3ResultItem.ObjectKey", equalTo("reports/2026/q2.csv"))
                .body("Results[0].S3ResultItem.BackupVaultName", equalTo(vault))
                .body("Results[0].S3ResultItem.BackupResourceArn", equalTo(point));
        signed("backup-search").get("/search-jobs/" + id + "/backups").then().statusCode(200)
                .body("Results[0].Status", equalTo("COMPLETED")).body("Results[0].BackupResourceArn", equalTo(point));
        signed("backup-search").put("/search-jobs/" + id + "/actions/cancel").then().statusCode(409)
                .body("__type", containsString("ConflictException"));

        String arn = signed("backup-search").get("/search-jobs/" + id).then().extract().path("SearchJobArn");
        signed("backup-search").get("/tags/" + arn).then().statusCode(200).body("Tags.fixture", equalTo("search"));
        signed("backup-search").body("{\"Tags\":{\"stage\":\"dev\"}}").post("/tags/" + arn).then().statusCode(200);
        signed("backup-search").queryParam("tagKeys", "fixture").delete("/tags/" + arn).then().statusCode(200);
        signed("backup-search").get("/tags/" + arn).then().statusCode(200).body("Tags.stage", equalTo("dev"))
                .body("Tags.fixture", equalTo(null));

        String exportBucket = "exports-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + exportBucket, null);
        String export = signed("backup-search").body("{\"SearchJobIdentifier\":\"" + id + "\",\"ExportSpecification\":"
                + "{\"s3ExportSpecification\":{\"DestinationBucket\":\"" + exportBucket + "\",\"DestinationPrefix\":"
                + "\"out\"}}}").put("/export-search-jobs").then().statusCode(200).extract().path("ExportJobIdentifier");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(signed("backup-search")
                .get("/export-search-jobs/" + export).then().extract().path("Status")));
        String csv = s3("GET", "/" + exportBucket + "/out/" + export + "/results.csv", null);
        assertTrue(csv.startsWith("BackupResourceArn,SourceResourceArn,BackupVaultName,ObjectKey") && csv.contains(
                ",reports/2026/q2.csv,12,"), csv);
        signed("backup-search").queryParam("SearchJobIdentifier", id).get("/export-search-jobs").then()
                .statusCode(200).body("ExportJobs.ExportJobIdentifier", hasItem(export));
    }

    @Test
    void jobsAndIndexesFollowAwsRules() {
        String missing = "00000000-0000-0000-0000-000000000000";
        signed("backup-search").get("/search-jobs/" + missing).then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
        signed("backup-search").get("/search-jobs/" + missing + "/search-results").then().statusCode(404);
        signed("backup-search").get("/search-jobs/" + missing + "/backups").then().statusCode(404);
        signed("backup-search").get("/export-search-jobs/" + missing).then().statusCode(404);
        signed("backup-search").get("/search-jobs").then().statusCode(200);

        String bucket = "noindex-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + bucket, null);
        signed("backup").body("{}").put("/backup-vaults/" + bucket).then().statusCode(200);
        signed("backup").body("{\"BackupVaultName\":\"" + bucket + "\",\"ResourceArn\":\"arn:aws:dynamodb:us-east-1:"
                + ACCOUNT + ":table/t\",\"IamRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/backup\",\"Index\":"
                + "\"ENABLED\"}").put("/backup-jobs").then().statusCode(400);

        String empty = signed("backup-search").body("{\"SearchScope\":{\"BackupResourceTypes\":[\"EBS\"]}}")
                .put("/search-jobs").then().statusCode(200).extract().path("SearchJobIdentifier");
        String status = awaitSearch(empty);
        assertTrue(List.of("COMPLETED").contains(status), status);
        signed("backup-search").body("{\"SearchJobIdentifier\":\"" + empty + "\",\"ExportSpecification\":"
                + "{\"s3ExportSpecification\":{\"DestinationBucket\":\"no-such-bucket-search\"}}}")
                .put("/export-search-jobs").then().statusCode(404);

        String running = signed("backup-search").body("{\"SearchScope\":{\"BackupResourceTypes\":[\"S3\"]}}")
                .put("/search-jobs").then().statusCode(200).extract().path("SearchJobIdentifier");
        signed("backup-search").put("/search-jobs/" + running + "/actions/cancel").then().statusCode(200);
        assertEquals("STOPPED", awaitSearch(running));
        signed("backup-search").body("{\"SearchJobIdentifier\":\"" + running + "\",\"ExportSpecification\":"
                + "{\"s3ExportSpecification\":{\"DestinationBucket\":\"" + bucket + "\"}}}").put("/export-search-jobs")
                .then().statusCode(200);
        signed("backup-search").body("{\"SearchScope\":{\"BackupResourceTypes\":[\"S3\"]},\"ItemFilters\":"
                + "{\"EBSItemFilters\":[{\"FilePaths\":[{\"Value\":\"/etc\"}]}]}}").put("/search-jobs").then()
                .statusCode(400);
    }
}
