package io.github.hectorvent.floci.services.mediaconvert;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * restJson1 coverage for the Alchemy MediaConvert bindings suite: list/search
 * return collections, missing jobs surface typed NotFoundException, Probe of a
 * missing S3 input is NotFoundException, CreateJob with an unassumable role is
 * BadRequestException, and StartJobsQuery completes immediately.
 */
@QuarkusTest
class MediaConvertBindingsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000812";
    private static final String MISSING_JOB = "0000000000000-aaaaaa";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listJobsReturnsCollection() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/2017-08-29/jobs?maxResults=5")
                .then()
                .statusCode(200)
                .body("jobs.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void searchJobsReturnsCollection() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/2017-08-29/search?status=COMPLETE&maxResults=5")
                .then()
                .statusCode(200)
                .body("jobs.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void getJobForMissingIdIsNotFoundException() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/2017-08-29/jobs/" + MISSING_JOB)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void cancelJobForMissingIdIsNotFoundException() {
        given()
                .header("Authorization", auth())
                .contentType("application/json")
                .when()
                .delete("/2017-08-29/jobs/" + MISSING_JOB)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void probeOfMissingS3InputIsNotFoundException() {
        given()
                .header("Authorization", auth())
                .contentType("application/json")
                .body("{\"inputFiles\":[{\"fileUrl\":\"s3://alchemy-nonexistent/in.mp4\"}]}")
                .when()
                .post("/2017-08-29/probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createJobWithMissingRoleIsBadRequestException() {
        given()
                .header("Authorization", auth())
                .contentType("application/json")
                .body("""
                        {
                          "role": "arn:aws:iam::000000000000:role/alchemy-does-not-exist",
                          "settings": {
                            "inputs": [{"fileInput": "s3://alchemy-nonexistent/in.mp4"}]
                          }
                        }
                        """)
                .when()
                .post("/2017-08-29/jobs")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void startJobsQueryCompletesImmediately() {
        Response started = given()
                .header("Authorization", auth())
                .contentType("application/json")
                .body("{\"maxResults\":5}")
                .when()
                .post("/2017-08-29/jobsQueries")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract()
                .response();
        String queryId = started.path("id");

        given()
                .header("Authorization", auth())
                .when()
                .get("/2017-08-29/jobsQueries/" + queryId)
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETE"))
                .body("jobs.size()", greaterThanOrEqualTo(0))
                .body("error", nullValue());
    }

    @Test
    void listQueuesIncludesDefaultSystemQueue() {
        given()
                .header("Authorization", auth())
                .when()
                .get("/2017-08-29/queues")
                .then()
                .statusCode(200)
                .body("queues.name", org.hamcrest.Matchers.hasItem("Default"));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + REGION + "/mediaconvert/aws4_request";
    }
}
