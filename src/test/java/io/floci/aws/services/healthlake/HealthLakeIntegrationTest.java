package io.floci.aws.services.healthlake;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AWS HealthLake over JSON 1.0, its data stores keeping their resources in floci: the data store lifecycle, NDJSON
 * imports from S3 with their outcomes, exports by resource type, one job at a time, a Synthea preload, restores from
 * continuous backups and tags.
 */
@QuarkusTest
class HealthLakeIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    static ValidatableResponse healthlake(String action, String body) {
        return given().header("Authorization", auth("healthlake")).header("X-Amz-Target", "HealthLake." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }

    static String role(String name) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"healthlake.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    static void s3Put(String bucket, String key, String body) {
        given().header("Authorization", auth("s3")).body(body).put("/" + bucket + "/" + key).then().statusCode(200);
    }

    static String s3Get(String bucket, String key) {
        return given().header("Authorization", auth("s3")).get("/" + bucket + "/" + key).then().statusCode(200)
                .extract().asString();
    }

    static List<String> keys(String bucket, String prefix) {
        return given().header("Authorization", auth("s3")).queryParam("list-type", "2").queryParam("prefix", prefix)
                .get("/" + bucket).then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
    }

    static String awaitDatastore(String id, String status) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String current = healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200)
                    .extract().path("DatastoreProperties.DatastoreStatus");
            if (status.equals(current)) {
                return current;
            }
            sleep();
        }
        fail("Data store " + id + " did not become " + status);
        return null;
    }

    static String awaitJob(String action, String datastoreId, String jobId, String field) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = healthlake(action, "{\"DatastoreId\":\"" + datastoreId + "\",\"JobId\":\"" + jobId + "\"}")
                    .statusCode(200).extract().path(field + ".JobStatus");
            if (!List.of("SUBMITTED", "QUEUED", "IN_PROGRESS").contains(status)) {
                return status;
            }
            sleep();
        }
        fail("Job " + jobId + " did not finish");
        return null;
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void datastoresImportExportAndRestore() {
        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + "0".repeat(32) + "\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R5\"}").statusCode(400);
        healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R4\",\"IdentityProviderConfiguration\":"
                + "{\"AuthorizationStrategy\":\"SMART_ON_FHIR\"}}").statusCode(400)
                .body("message", containsString("IdpLambdaArn"));
        String id = healthlake("CreateFHIRDatastore", "{\"DatastoreName\":\"clinic\",\"DatastoreTypeVersion\":\"R4\","
                + "\"ClientToken\":\"once\",\"BackupConfiguration\":{\"Status\":\"ENABLED\"},\"Tags\":[{\"Key\":"
                + "\"team\",\"Value\":\"care\"}]}").statusCode(200).body("DatastoreStatus", equalTo("CREATING"))
                .body("DatastoreArn", containsString(":datastore/fhir/"))
                .body("DatastoreEndpoint", containsString("/r4/")).extract().path("DatastoreId");
        healthlake("CreateFHIRDatastore", "{\"DatastoreName\":\"clinic\",\"DatastoreTypeVersion\":\"R4\","
                + "\"ClientToken\":\"once\"}").statusCode(200).body("DatastoreId", equalTo(id));
        awaitDatastore(id, "ACTIVE");
        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200)
                .body("DatastoreProperties.DatastoreTypeVersion", equalTo("R4"))
                .body("DatastoreProperties.DatastoreEndpoint", containsString("healthlake.us-east-1."))
                .body("DatastoreProperties.SseConfiguration.KmsEncryptionConfig.CmkType",
                        equalTo("AWS_OWNED_KMS_KEY"))
                .body("DatastoreProperties.BackupStatusInfo.Configuration.Status", equalTo("ENABLED"));
        healthlake("ListFHIRDatastores", "{\"Filter\":{\"DatastoreName\":\"clinic\"}}").statusCode(200)
                .body("DatastorePropertiesList.DatastoreId", hasItem(id));
        healthlake("UpdateFHIRDatastore", "{\"DatastoreId\":\"" + id + "\",\"DatastoreName\":\"clinic-2\","
                + "\"AnalyticsConfiguration\":{\"Status\":\"ENABLED\"}}").statusCode(200)
                .body("DatastoreProperties.DatastoreName", equalTo("clinic-2"))
                .body("DatastoreProperties.AnalyticsConfiguration.Status", equalTo("ENABLED"));

        given().header("Authorization", auth("s3")).put("/healthlake-data").then().statusCode(200);
        s3Put("healthlake-data", "in/patients.ndjson", "{\"resourceType\":\"Patient\",\"id\":\"p-1\",\"name\":"
                + "[{\"family\":\"Doe\"}]}\n{\"resourceType\":\"Patient\",\"id\":\"p-2\"}\nnot json\n"
                + "{\"resourceType\":\"Observation\",\"id\":\"o-1\",\"status\":\"final\",\"subject\":{\"reference\":"
                + "\"Patient/p-1\"}}\n");
        String role = role("healthlake-data");
        String outside = given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", "not-healthlake").formParam("AssumeRolePolicyDocument", "{\"Version\":"
                        + "\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                        + "\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}").post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String output = "\"JobOutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-data/out\","
                + "\"KmsKeyId\":\"arn:aws:kms:us-east-1:" + ACCOUNT + ":key/k\"}}";
        String denied = healthlake("StartFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\""
                + outside + "\",\"InputDataConfig\":{\"S3Uri\":\"s3://healthlake-data/in/\"}," + output + "}")
                .statusCode(200).body("JobStatus", equalTo("SUBMITTED")).extract().path("JobId");
        assertEquals("FAILED", awaitJob("DescribeFHIRImportJob", id, denied, "ImportJobProperties"));
        healthlake("DescribeFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"JobId\":\"" + denied + "\"}")
                .body("ImportJobProperties.Message", containsString("healthlake.amazonaws.com"));
        String imported = healthlake("StartFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"JobName\":\"first\","
                + "\"DataAccessRoleArn\":\"" + role + "\",\"InputDataConfig\":{\"S3Uri\":\"s3://healthlake-data/in/\"},"
                + output + "}").statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED_WITH_ERRORS", awaitJob("DescribeFHIRImportJob", id, imported, "ImportJobProperties"));
        healthlake("DescribeFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"JobId\":\"" + imported + "\"}")
                .body("ImportJobProperties.JobProgressReport.TotalNumberOfResourcesScanned", equalTo(4))
                .body("ImportJobProperties.JobProgressReport.TotalNumberOfResourcesImported", equalTo(3))
                .body("ImportJobProperties.JobProgressReport.TotalNumberOfResourcesWithCustomerError", equalTo(1));
        String failures = s3Get("healthlake-data", "out/" + id + "-FHIR_IMPORT-" + imported
                + "/FAILURE/FAILURE_OUTPUT.ndjson");
        assertTrue(failures.contains("\"line\":3"), failures);
        healthlake("ListFHIRImportJobs", "{\"DatastoreId\":\"" + id + "\",\"JobStatus\":\"COMPLETED_WITH_ERRORS\"}")
                .statusCode(200).body("ImportJobPropertiesList", hasSize(1))
                .body("ImportJobPropertiesList[0].JobName", equalTo("first"));

        String exported = healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\""
                + role + "\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-data/export\","
                + "\"KmsKeyId\":\"k\"}}}").statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED", awaitJob("DescribeFHIRExportJob", id, exported, "ExportJobProperties"));
        String folder = "export/" + id + "-FHIR_EXPORT-" + exported + "/";
        assertEquals(List.of(folder + "Observation/Observation-0.ndjson", folder + "Patient/Patient-0.ndjson"),
                keys("healthlake-data", folder));
        assertEquals(2, s3Get("healthlake-data", folder + "Patient/Patient-0.ndjson").lines().count());

        // Without Docker the data store keeps its resources in floci and its endpoint has no FHIR server to serve.
        given().header("Authorization", auth("healthlake")).get("/datastore/" + id + "/r4/Patient/p-1").then()
                .statusCode(503).body("resourceType", equalTo("OperationOutcome"));
        given().get("/datastore/" + id + "/r4/Patient/p-1").then().statusCode(403);

        String restored = healthlake("RestoreFHIRDatastore", "{\"SourceDatastoreId\":\"" + id + "\","
                + "\"RestoreConfiguration\":{\"ContinuousBackupRestoreConfiguration\":{}}}").statusCode(200)
                .extract().path("DatastoreId");
        awaitDatastore(restored, "ACTIVE");
        String copy = healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + restored + "\",\"DataAccessRoleArn\":\""
                + role + "\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-data/restored\","
                + "\"KmsKeyId\":\"k\"}}}").statusCode(200).extract().path("JobId");
        awaitJob("DescribeFHIRExportJob", restored, copy, "ExportJobProperties");
        assertEquals(2, s3Get("healthlake-data", "restored/" + restored + "-FHIR_EXPORT-" + copy
                + "/Patient/Patient-0.ndjson").lines().count());

        String arn = "arn:aws:healthlake:us-east-1:" + ACCOUNT + ":datastore/fhir/" + id;
        healthlake("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"one\"}]}")
                .statusCode(200);
        healthlake("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").statusCode(200);
        healthlake("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags", hasSize(1)).body("Tags[0].Key", equalTo("stage"));

        for (String datastore : List.of(id, restored)) {
            healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + datastore + "\"}").statusCode(200)
                    .body("DatastoreStatus", equalTo("DELETING"));
            awaitDatastore(datastore, "DELETED");
        }
        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
    }

    @Test
    void aSyntheaPreloadWritesASyntheticPopulation() {
        String id = healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R4\",\"PreloadDataConfig\":"
                + "{\"PreloadDataType\":\"SYNTHEA\"}}").statusCode(200).extract().path("DatastoreId");
        awaitDatastore(id, "ACTIVE");
        given().header("Authorization", auth("s3")).put("/healthlake-synthea").then().statusCode(200);
        String role = role("healthlake-synthea");
        String job = healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\"" + role
                + "\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-synthea/\",\"KmsKeyId\":"
                + "\"k\"}}}").statusCode(200).body("JobId", matchesPattern("[0-9a-f]{32}")).extract().path("JobId");
        awaitJob("DescribeFHIRExportJob", id, job, "ExportJobProperties");
        String folder = id + "-FHIR_EXPORT-" + job + "/";
        assertEquals(10, s3Get("healthlake-synthea", folder + "Patient/Patient-0.ndjson").lines().count());
        assertEquals(20, s3Get("healthlake-synthea", folder + "Encounter/Encounter-0.ndjson").lines().count());
        assertEquals(40, s3Get("healthlake-synthea", folder + "Observation/Observation-0.ndjson").lines().count());
        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200);
        awaitDatastore(id, "DELETED");
    }
}
