package io.github.hectorvent.floci.services.amplify;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Amplify restJson1 job/deployment APIs used by Alchemy Bindings tests. */
@QuarkusTest
class AmplifyJobIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final byte[] SITE_ZIP = "hello amplify".getBytes();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void manualDeploymentLifecycleCreateStartListDelete() {
        String authorization = auth("000000000601", EAST);
        String appId = createAppAndBranch(authorization);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/apps/" + appId + "/branches/main/deployments")
                .then()
                .statusCode(200)
                .body("jobId", notNullValue())
                .body("zipUploadUrl", containsString("https://"))
                .extract()
                .response();

        String jobId = created.path("jobId");
        String zipUploadUrl = created.path("zipUploadUrl");
        putZip(zipUploadUrl);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"jobId\":\"" + jobId + "\"}")
                .when()
                .post("/apps/" + appId + "/branches/main/deployments/start")
                .then()
                .statusCode(200)
                .body("jobSummary.jobId", equalTo(jobId))
                .body("jobSummary.status", equalTo("SUCCEED"))
                .body("jobSummary.jobType", equalTo("MANUAL"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("job.summary.status", equalTo("SUCCEED"))
                .body("job.steps.size()", equalTo(1));

        List<Map<String, Object>> jobs = given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main/jobs")
                .then()
                .statusCode(200)
                .extract()
                .path("jobSummaries");
        assertTrue(jobs.stream().anyMatch(job -> jobId.equals(job.get("jobId"))));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main/jobs/" + jobId + "/artifacts")
                .then()
                .statusCode(200)
                .body("artifacts", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId + "/branches/main/jobs/" + jobId + "/stop")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId + "/branches/main/jobs/" + jobId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("active job"));

        Response successor = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/apps/" + appId + "/branches/main/deployments")
                .then()
                .statusCode(200)
                .extract()
                .response();
        String successorId = successor.path("jobId");
        assertFalse(jobId.equals(successorId));
        putZip(successor.path("zipUploadUrl"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"jobId\":\"" + successorId + "\"}")
                .when()
                .post("/apps/" + appId + "/branches/main/deployments/start")
                .then()
                .statusCode(200)
                .body("jobSummary.status", equalTo("SUCCEED"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId + "/branches/main/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("jobSummary.jobId", equalTo(jobId));

        List<Map<String, Object>> remaining = given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main/jobs")
                .then()
                .statusCode(200)
                .extract()
                .path("jobSummaries");
        assertEquals(1, remaining.size());
        assertEquals(successorId, remaining.getFirst().get("jobId"));
    }

    @Test
    void startJobReleaseOnRepoLessBranchIsBadRequestException() {
        String authorization = auth("000000000602", EAST);
        String appId = createAppAndBranch(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"jobType\":\"RELEASE\"}")
                .when()
                .post("/apps/" + appId + "/branches/main/jobs")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void generateAccessLogsReturnsHttpsLogUrl() {
        String authorization = auth("000000000603", EAST);
        String appId = createAppAndBranch(authorization);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"domainName\":\"" + appId + ".amplifyapp.com\"}")
                .when()
                .post("/apps/" + appId + "/accesslogs")
                .then()
                .statusCode(200)
                .body("logUrl", containsString("https://"));
    }

    @Test
    void getJobOnANonexistentJobFailsWithNotFoundException() {
        String authorization = auth("000000000604", EAST);
        String appId = createAppAndBranch(authorization);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main/jobs/999")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String createAppAndBranch(String authorization) {
        String appId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"job-app\",\"platform\":\"WEB\"}")
                .when()
                .post("/apps")
                .then()
                .statusCode(200)
                .extract()
                .path("app.appId");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"branchName\":\"main\",\"enableAutoBuild\":false}")
                .when()
                .post("/apps/" + appId + "/branches")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("main"));
        return appId;
    }

    private static void putZip(String zipUploadUrl) {
        URI uri = URI.create(zipUploadUrl);
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        given()
                .urlEncodingEnabled(false)
                .contentType("application/zip")
                .body(SITE_ZIP)
                .when()
                .put(path)
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/amplify/aws4_request";
    }
}
