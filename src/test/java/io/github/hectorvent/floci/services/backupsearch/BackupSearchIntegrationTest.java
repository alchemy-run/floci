package io.github.hectorvent.floci.services.backupsearch;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies Backup Search restJson1 get/list/start/stop and tag APIs. */
@QuarkusTest
class BackupSearchIntegrationTest {

    private static final String MISSING = "00000000-0000-0000-0000-000000000000";
    private static final String AUTH = auth("000000000401", "us-east-1");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSearchJobOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING))
                .body("resourceType", equalTo("SEARCH_JOB"));
    }

    @Test
    void getSearchJobOnANonUuidIdentifierFailsWithValidationException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/not-a-uuid")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getSearchResultExportJobOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/export-search-jobs/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING))
                .body("resourceType", equalTo("EXPORT_JOB"));
    }

    @Test
    void listSearchJobResultsOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING + "/search-results")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listSearchJobBackupsOnANonexistentIdentifierFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + MISSING + "/backups")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listSearchJobsSucceedsOnAnAccountLevelScan() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs?MaxResults=10")
                .then()
                .statusCode(200)
                .body("SearchJobs", notNullValue());
    }

    @Test
    void startGetListStopAndTagSearchJob() {
        Response created = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "Name": "floci-search",
                          "SearchScope": {"BackupResourceTypes": ["S3"]},
                          "ItemFilters": {
                            "S3ItemFilters": [
                              {"ObjectKeys": [{"Value": "alchemy-", "Operator": "BEGINS_WITH"}]}
                            ]
                          },
                          "Tags": {"fixture": "backup-search"}
                        }
                        """)
                .when()
                .put("/search-jobs")
                .then()
                .statusCode(200)
                .body("SearchJobIdentifier", notNullValue())
                .body("SearchJobArn", notNullValue())
                .extract()
                .response();

        String identifier = created.path("SearchJobIdentifier");
        String arn = created.path("SearchJobArn");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + identifier)
                .then()
                .statusCode(200)
                .body("SearchJobIdentifier", equalTo(identifier))
                .body("SearchJobArn", equalTo(arn))
                .body("Name", equalTo("floci-search"))
                .body("Status", equalTo("COMPLETED"))
                .body("SearchScope.BackupResourceTypes[0]", equalTo("S3"))
                .body("ItemFilters.S3ItemFilters[0].ObjectKeys[0].Value", equalTo("alchemy-"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs?Status=COMPLETED")
                .then()
                .statusCode(200)
                .body("SearchJobs.find { it.SearchJobIdentifier == '" + identifier + "' }.Status",
                        equalTo("COMPLETED"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + identifier + "/search-results")
                .then()
                .statusCode(200)
                .body("Results", notNullValue());

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/search-jobs/" + identifier + "/backups")
                .then()
                .statusCode(200)
                .body("Results", notNullValue());

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("backup-search"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Tags\":{\"env\":\"test\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.fixture", equalTo("backup-search"))
                .body("Tags.env", equalTo("test"));

        given()
                .header("Authorization", AUTH)
                .when()
                .put("/search-jobs/" + identifier + "/actions/cancel")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        Response export = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "SearchJobIdentifier": "%s",
                          "ExportSpecification": {
                            "s3ExportSpecification": {
                              "DestinationBucket": "export-bucket",
                              "DestinationPrefix": "results/"
                            }
                          }
                        }
                        """.formatted(identifier))
                .when()
                .put("/export-search-jobs")
                .then()
                .statusCode(200)
                .body("ExportJobIdentifier", notNullValue())
                .extract()
                .response();

        String exportId = export.path("ExportJobIdentifier");
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/export-search-jobs/" + exportId)
                .then()
                .statusCode(200)
                .body("Status", equalTo("COMPLETED"))
                .body("SearchJobArn", equalTo(arn));

        Response replaced = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                        {
                          "SearchScope": {"BackupResourceTypes": ["S3"]},
                          "ItemFilters": {
                            "S3ItemFilters": [
                              {"ObjectKeys": [{"Value": "replaced-", "Operator": "BEGINS_WITH"}]}
                            ]
                          }
                        }
                        """)
                .when()
                .put("/search-jobs")
                .then()
                .statusCode(200)
                .extract()
                .response();
        assertNotEquals(identifier, replaced.path("SearchJobIdentifier"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/backup-search/aws4_request";
    }
}
