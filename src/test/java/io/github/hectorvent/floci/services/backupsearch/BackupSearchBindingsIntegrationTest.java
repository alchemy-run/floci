package io.github.hectorvent.floci.services.backupsearch;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-surface operations Alchemy BackupSearch Bindings exercise:
 * Start/Get/List/Stop search jobs, empty result/backup pages, and typed
 * not-found errors for UUID-shaped identifiers.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupSearchBindingsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup-search/aws4_request";
    private static final String MISSING = "00000000-0000-0000-0000-000000000000";
    private static final String SCOPE = """
            {"SearchScope":{"BackupResourceTypes":["S3"]},"Name":"alchemy-test-backupsearch-bindings",\
            "ItemFilters":{"S3ItemFilters":[{"ObjectKeys":[{"Value":"alchemy-bindings-","Operator":"BEGINS_WITH"}]}]},\
            "Tags":{"fixture":"backup-search-bindings"}}
            """;

    private static String searchJobId;
    private static String searchJobArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(10)
    void listSearchJobsReturnsArray() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs")
                .then()
                .statusCode(200)
                .body("SearchJobs", notNullValue());
    }

    @Test
    @Order(11)
    void getSearchJobUnknownIdIsResourceNotFound() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void listSearchJobResultsUnknownIdIsResourceNotFound() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING + "/search-results")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(13)
    void listSearchJobBackupsUnknownIdIsResourceNotFound() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING + "/backups")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(20)
    void startSearchJob() {
        searchJobId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body(SCOPE)
                .when()
                .put("/search-jobs")
                .then()
                .statusCode(200)
                .body("SearchJobIdentifier", notNullValue())
                .body("SearchJobArn", org.hamcrest.Matchers.containsString("search-job"))
                .extract()
                .path("SearchJobIdentifier");
        searchJobArn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + searchJobId)
                .then()
                .statusCode(200)
                .extract()
                .path("SearchJobArn");
    }

    @Test
    @Order(21)
    void getSearchJobReturnsStatusAndName() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + searchJobId)
                .then()
                .statusCode(200)
                .body("Name", equalTo("alchemy-test-backupsearch-bindings"))
                .body("Status", org.hamcrest.Matchers.oneOf(
                        "RUNNING", "COMPLETED", "STOPPING", "STOPPED", "FAILED"))
                .body("SearchJobIdentifier", equalTo(searchJobId))
                .body("SearchScope.BackupResourceTypes[0]", equalTo("S3"));
    }

    @Test
    @Order(22)
    void listSearchJobResultsReturnsWellFormedPage() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + searchJobId + "/search-results?maxResults=25")
                .then()
                .statusCode(200)
                .body("Results", notNullValue())
                .body("Results", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(23)
    void listSearchJobBackupsReturnsWellFormedPage() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + searchJobId + "/backups?maxResults=25")
                .then()
                .statusCode(200)
                .body("Results", notNullValue())
                .body("Results", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(24)
    void listSearchJobsIncludesStartedJob() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs?MaxResults=25")
                .then()
                .statusCode(200)
                .body("SearchJobs", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(25)
    void listTagsForResourceRoundTrip() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + searchJobArn)
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("backup-search-bindings"));
    }

    @Test
    @Order(30)
    void stopCompletedSearchJobIsConflict() {
        given()
                .header("Authorization", AUTH)
                .when()
                .put("/search-jobs/" + searchJobId + "/actions/cancel")
                .then()
                .statusCode(is(409))
                .body("__type", equalTo("ConflictException"));
    }
}
