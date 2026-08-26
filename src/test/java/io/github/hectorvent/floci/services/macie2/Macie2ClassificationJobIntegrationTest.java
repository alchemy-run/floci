package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies Macie2 restJson1 session enablement and the classification-job
 * lifecycle Alchemy {@code ClassificationJob.test.ts} drives.
 */
@QuarkusTest
class Macie2ClassificationJobIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000901";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMacieSessionOnAFreshAccountIsAccessDenied() {
        given()
                .header("Authorization", auth("000000000902", EAST))
                .when()
                .get("/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Macie is not enabled"));
    }

    @Test
    void createClassificationJobWithoutMacieIsAccessDenied() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000903", EAST))
                .body("""
                        {"name":"Scan","jobType":"ONE_TIME",\
                        "s3JobDefinition":{"bucketDefinitions":[{"accountId":"000000000903","buckets":["b"]}]}}
                        """)
                .when()
                .post("/jobs")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Macie is not enabled"));
    }

    @Test
    void enableCreateDescribeCancelAndDisableClassificationJob() {
        String authorization = auth(ACCOUNT, EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"status\":\"ENABLED\"}")
                .when()
                .post("/macie")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/macie")
                .then()
                .statusCode(200)
                .body("status", equalTo("ENABLED"))
                .body("findingPublishingFrequency", equalTo("SIX_HOURS"))
                .body("serviceRole", containsString("AWSServiceRoleForAmazonMacie"));

        String jobId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"Scan","jobType":"ONE_TIME","samplingPercentage":100,\
                        "s3JobDefinition":{"bucketDefinitions":[{"accountId":"000000000901","buckets":["scan-target"]}]},\
                        "tags":{"env":"test","alchemy::id":"Scan"}}
                        """)
                .when()
                .post("/jobs")
                .then()
                .statusCode(200)
                .body("jobId", notNullValue())
                .body("jobArn", containsString(":classification-job/"))
                .extract()
                .path("jobId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("jobId", equalTo(jobId))
                .body("jobType", equalTo("ONE_TIME"))
                .body("jobStatus", equalTo("RUNNING"))
                .body("name", equalTo("Scan"))
                .body("tags.env", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("Scan"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"jobStatus\":\"CANCELLED\"}")
                .when()
                .patch("/jobs/" + jobId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("jobStatus", equalTo("CANCELLED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"jobStatus\":\"CANCELLED\"}")
                .when()
                .patch("/jobs/" + jobId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/macie")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/macie")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("jobStatus", equalTo("CANCELLED"));
    }

    @Test
    void tagAndUntagClassificationJob() {
        String authorization = auth("000000000904", EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/macie")
                .then()
                .statusCode(200);

        String jobArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"Tagged","jobType":"ONE_TIME",\
                        "s3JobDefinition":{"bucketDefinitions":[{"accountId":"000000000904","buckets":["b"]}]},\
                        "tags":{"env":"test"}}
                        """)
                .when()
                .post("/jobs")
                .then()
                .statusCode(200)
                .extract()
                .path("jobArn");

        String encoded = URLEncoder.encode(jobArn, StandardCharsets.UTF_8);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"env\":\"prod\",\"owner\":\"alchemy\"}}")
                .when()
                .post("/tags/" + encoded)
                .then()
                .statusCode(204);

        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("tags.owner", equalTo("alchemy"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/macie2/aws4_request";
    }
}
