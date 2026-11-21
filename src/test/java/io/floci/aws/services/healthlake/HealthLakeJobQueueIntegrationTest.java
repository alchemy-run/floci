package io.floci.aws.services.healthlake;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.awaitDatastore;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.awaitJob;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.healthlake;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.role;
import static io.floci.aws.services.healthlake.HealthLakeIntegrationTest.s3Put;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** A data store runs one import or export at a time; a job started while another runs is refused. */
@QuarkusTest
@TestProfile(HealthLakeJobQueueIntegrationTest.SlowJobs.class)
class HealthLakeJobQueueIntegrationTest {

    public static class SlowJobs implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.healthlake.transition-millis", "1500");
        }
    }

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aDatastoreRunsOneJobAtATime() {
        String id = healthlake("CreateFHIRDatastore", "{\"DatastoreTypeVersion\":\"R4\"}").statusCode(200)
                .extract().path("DatastoreId");
        healthlake("DescribeFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200)
                .body("DatastoreProperties.DatastoreStatus", equalTo("CREATING"));
        healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\"arn:aws:iam::"
                + "000000000000:role/r\",\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-queue/e\","
                + "\"KmsKeyId\":\"k\"}}}").statusCode(400).body("message", containsString("not ACTIVE"));
        awaitDatastore(id, "ACTIVE");
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3/"
                + "aws4_request").put("/healthlake-queue").then().statusCode(200);
        s3Put("healthlake-queue", "in/p.ndjson", "{\"resourceType\":\"Patient\",\"id\":\"q\"}\n");
        String role = role("healthlake-queue");
        String output = "\"JobOutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-queue/out\","
                + "\"KmsKeyId\":\"k\"}}";
        String first = healthlake("StartFHIRImportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\""
                + role + "\",\"InputDataConfig\":{\"S3Uri\":\"s3://healthlake-queue/in/\"}," + output + "}")
                .statusCode(200).extract().path("JobId");
        healthlake("StartFHIRExportJob", "{\"DatastoreId\":\"" + id + "\",\"DataAccessRoleArn\":\"" + role + "\","
                + "\"OutputDataConfig\":{\"S3Configuration\":{\"S3Uri\":\"s3://healthlake-queue/e\",\"KmsKeyId\":"
                + "\"k\"}}}").statusCode(400).body("message", containsString("one import or export at a time"));
        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        awaitJob("DescribeFHIRImportJob", id, first, "ImportJobProperties");
        healthlake("DeleteFHIRDatastore", "{\"DatastoreId\":\"" + id + "\"}").statusCode(200);
    }
}
