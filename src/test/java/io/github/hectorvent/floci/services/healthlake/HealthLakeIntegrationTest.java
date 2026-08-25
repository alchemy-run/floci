package io.github.hectorvent.floci.services.healthlake;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 HealthLake coverage used by Alchemy Bindings.test.ts:
 * job ops on a missing datastore return ResourceNotFoundException; create +
 * import/export jobs round-trip.
 */
@QuarkusTest
class HealthLakeIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/healthlake/aws4_request";
    private static final String TARGET_PREFIX = "HealthLake.";
    private static final String MISSING = "0123456789abcdef0123456789abcdef";
    private static final String ROLE = "arn:aws:iam::000000000000:role/alchemy-probe-nonexistent";
    private static final String KMS =
            "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeFHIRImportJob_missingDatastore_returnsResourceNotFoundException() {
        healthlake("DescribeFHIRImportJob",
                "{\"DatastoreId\":\"" + MISSING + "\",\"JobId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeFHIRExportJob_missingDatastore_returnsResourceNotFoundException() {
        healthlake("DescribeFHIRExportJob",
                "{\"DatastoreId\":\"" + MISSING + "\",\"JobId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listFHIRImportJobs_missingDatastore_returnsResourceNotFoundException() {
        healthlake("ListFHIRImportJobs", "{\"DatastoreId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listFHIRExportJobs_missingDatastore_returnsResourceNotFoundException() {
        healthlake("ListFHIRExportJobs", "{\"DatastoreId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startFHIRImportJob_missingDatastore_returnsResourceNotFoundException() {
        healthlake("StartFHIRImportJob", startImportBody(MISSING))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startFHIRExportJob_missingDatastore_returnsResourceNotFoundException() {
        healthlake("StartFHIRExportJob", startExportBody(MISSING))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void importAndExportJobs_roundTripOnCreatedDatastore() {
        String datastoreId = healthlake("CreateFHIRDatastore",
                "{\"DatastoreName\":\"alchemy-hl-jobs\",\"DatastoreTypeVersion\":\"R4\"}")
                .then()
                .statusCode(200)
                .body("DatastoreId", notNullValue())
                .body("DatastoreStatus", equalTo("ACTIVE"))
                .extract().path("DatastoreId");

        String importJobId = healthlake("StartFHIRImportJob", startImportBody(datastoreId))
                .then()
                .statusCode(200)
                .body("JobStatus", equalTo("SUBMITTED"))
                .body("JobId", notNullValue())
                .extract().path("JobId");

        healthlake("DescribeFHIRImportJob",
                "{\"DatastoreId\":\"" + datastoreId + "\",\"JobId\":\"" + importJobId + "\"}")
                .then()
                .statusCode(200)
                .body("ImportJobProperties.JobId", equalTo(importJobId))
                .body("ImportJobProperties.JobStatus", equalTo("COMPLETED"));

        healthlake("ListFHIRImportJobs", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(200)
                .body("ImportJobPropertiesList.size()", greaterThanOrEqualTo(1));

        String exportJobId = healthlake("StartFHIRExportJob", startExportBody(datastoreId))
                .then()
                .statusCode(200)
                .body("JobStatus", equalTo("SUBMITTED"))
                .body("JobId", notNullValue())
                .extract().path("JobId");

        healthlake("DescribeFHIRExportJob",
                "{\"DatastoreId\":\"" + datastoreId + "\",\"JobId\":\"" + exportJobId + "\"}")
                .then()
                .statusCode(200)
                .body("ExportJobProperties.JobId", equalTo(exportJobId))
                .body("ExportJobProperties.JobStatus", equalTo("COMPLETED"));

        healthlake("ListFHIRExportJobs", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(200)
                .body("ExportJobPropertiesList.size()", greaterThanOrEqualTo(1));

        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(200)
                .body("DatastoreStatus", equalTo("DELETING"));

        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + datastoreId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String startImportBody(String datastoreId) {
        return "{"
                + "\"DatastoreId\":\"" + datastoreId + "\","
                + "\"DataAccessRoleArn\":\"" + ROLE + "\","
                + "\"InputDataConfig\":{\"S3Uri\":\"s3://alchemy-probe-nonexistent/in/\"},"
                + "\"JobOutputDataConfig\":{\"S3Configuration\":{"
                + "\"S3Uri\":\"s3://alchemy-probe-nonexistent/out/\","
                + "\"KmsKeyId\":\"" + KMS + "\"}}"
                + "}";
    }

    private static String startExportBody(String datastoreId) {
        return "{"
                + "\"DatastoreId\":\"" + datastoreId + "\","
                + "\"DataAccessRoleArn\":\"" + ROLE + "\","
                + "\"OutputDataConfig\":{\"S3Configuration\":{"
                + "\"S3Uri\":\"s3://alchemy-probe-nonexistent/export/\","
                + "\"KmsKeyId\":\"" + KMS + "\"}}"
                + "}";
    }

    private static Response healthlake(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
