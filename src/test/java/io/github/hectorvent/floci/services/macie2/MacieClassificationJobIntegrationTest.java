package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class MacieClassificationJobIntegrationTest {
    private static final String ACCOUNT = "720000000001";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "macie-classification-it-data";

    @Inject
    MacieService service;

    @Inject
    S3Service s3;

    @BeforeEach
    void setUp() {
        service.clear();
        RequestScopes.runAs(ACCOUNT, () -> {
            s3.createBucket(BUCKET, REGION);
            s3.putObject(BUCKET, "people.csv", "name,ssn\nJane,SSN 123-45-6789\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", Map.of());
            s3.putObject(BUCKET, "readme.txt", "no sensitive data".getBytes(StandardCharsets.UTF_8),
                    "text/plain", Map.of());
        });
    }

    @AfterEach
    void tearDown() {
        service.clear();
        RequestScopes.runAs(ACCOUNT, () -> {
            for (String key : List.of("people.csv", "readme.txt")) {
                s3.deleteObject(BUCKET, key);
            }
            s3.deleteBucket(BUCKET);
        });
    }

    @Test
    void oneTimeJobReadsS3AndPublishesFindings() throws Exception {
        request().body("{}").post("/macie").then().statusCode(200);
        Map<String, Object> job = Map.of(
                "clientToken", "it-token",
                "name", "it-scan",
                "jobType", "ONE_TIME",
                "s3JobDefinition", Map.of("bucketDefinitions",
                        List.of(Map.of("accountId", ACCOUNT, "buckets", List.of(BUCKET)))),
                "tags", Map.of("env", "test"));
        String jobId = request().body(job).post("/jobs").then().statusCode(200)
                .body("jobArn", org.hamcrest.Matchers.containsString(":classification-job/"))
                .extract().path("jobId");

        String status = null;
        for (int i = 0; i < 100; i++) {
            status = request().get("/jobs/" + jobId).then().statusCode(200).extract().path("jobStatus");
            if ("COMPLETE".equals(status)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("COMPLETE", status);
        request().get("/jobs/" + jobId).then().statusCode(200)
                .body("jobId", equalTo(jobId))
                .body("jobType", equalTo("ONE_TIME"))
                .body("tags.env", equalTo("test"))
                .body("statistics.numberOfRuns", equalTo(1))
                .body("lastRunErrorStatus.code", equalTo("NONE"));

        String findingId = request().body(Map.of("findingCriteria", Map.of("criterion",
                        Map.of("classificationDetails.jobId", Map.of("eq", List.of(jobId))))))
                .post("/findings").then().statusCode(200)
                .body("findingIds", hasSize(1)).extract().path("findingIds[0]");
        request().body(Map.of("findingIds", List.of(findingId))).post("/findings/describe").then().statusCode(200)
                .body("findings[0].type", equalTo("SensitiveData:S3Object/Personal"))
                .body("findings[0].resourcesAffected.s3Bucket.name", equalTo(BUCKET))
                .body("findings[0].resourcesAffected.s3Object.key", equalTo("people.csv"))
                .body("findings[0].classificationDetails.result.sensitiveData[0].detections[0].type",
                        equalTo("USA_SOCIAL_SECURITY_NUMBER"));

        request().body("{}").post("/jobs/list").then().statusCode(200)
                .body("items", hasSize(1)).body("items[0].jobStatus", equalTo("COMPLETE"));
        request().body(Map.of("jobStatus", "CANCELLED")).patch("/jobs/" + jobId).then().statusCode(409)
                .body("__type", equalTo("ConflictException"));

        request().delete("/macie").then().statusCode(200);
        request().get("/jobs/" + jobId).then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    private static RequestSpecification request() {
        return given().contentType("application/json").header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260921/" + REGION
                        + "/macie2/aws4_request, SignedHeaders=host, Signature=abc");
    }
}
