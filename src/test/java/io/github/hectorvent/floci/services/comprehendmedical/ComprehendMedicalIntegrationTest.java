package io.github.hectorvent.floci.services.comprehendmedical;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Wire-format coverage for the Amazon Comprehend Medical stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: ComprehendMedical_20181030.&lt;Action&gt;
 */
@QuarkusTest
class ComprehendMedicalIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehendmedical/aws4_request";
    private static final String TARGET_PREFIX = "ComprehendMedical_20181030.";
    private static final String FAKE_JOB_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String BOGUS_ROLE =
            "arn:aws:iam::000000000000:role/alchemy-nonexistent-comprehendmedical-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void detectEntitiesV2_extractsMedicationAndCondition() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DetectEntitiesV2")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Patient takes 50 mg atenolol daily for hypertension.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()))
            .body("Entities.Category", hasItems("MEDICATION", "MEDICAL_CONDITION"))
            .body("ModelVersion", not(emptyString()));
    }

    @Test
    void detectPhi_extractsName() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DetectPHI")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"John Doe, age 47, was seen on 2024-01-03 at Seattle Clinic.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()))
            .body("Entities.Type", hasItem("NAME"));
    }

    @Test
    void inferIcd10cm_linksConditionsToCodes() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "InferICD10CM")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Patient presents with type 2 diabetes and hypertension.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()))
            .body("Entities.ICD10CMConcepts.Code.flatten()", not(empty()))
            .body("Entities[0].ICD10CMConcepts[0].Code", not(emptyString()));
    }

    @Test
    void inferRxNorm_linksMedicationsToConcepts() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "InferRxNorm")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Patient takes 50 mg atenolol daily and 500 mg metformin twice a day.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()))
            .body("Entities[0].RxNormConcepts[0].Code", not(emptyString()));
    }

    @Test
    void inferSnomedCt_linksConceptsToCodes() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "InferSNOMEDCT")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Patient presents with type 2 diabetes and hypertension.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()))
            .body("Entities[0].SNOMEDCTConcepts[0].Code", not(emptyString()));
    }

    @Test
    void listJobs_returnsEmptyListsForAllFamilies() {
        for (String action : new String[]{
                "ListEntitiesDetectionV2Jobs",
                "ListICD10CMInferenceJobs",
                "ListPHIDetectionJobs",
                "ListRxNormInferenceJobs",
                "ListSNOMEDCTInferenceJobs"
        }) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body("{}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ComprehendMedicalAsyncJobPropertiesList", notNullValue());
        }
    }

    @Test
    void describeUnknownJob_returnsResourceNotFound() {
        for (String action : new String[]{
                "DescribeEntitiesDetectionV2Job",
                "DescribeICD10CMInferenceJob",
                "DescribePHIDetectionJob",
                "DescribeRxNormInferenceJob",
                "DescribeSNOMEDCTInferenceJob"
        }) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body("{\"JobId\":\"" + FAKE_JOB_ID + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void stopUnknownJob_returnsResourceNotFound() {
        for (String action : new String[]{
                "StopEntitiesDetectionV2Job",
                "StopICD10CMInferenceJob",
                "StopPHIDetectionJob",
                "StopRxNormInferenceJob",
                "StopSNOMEDCTInferenceJob"
        }) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body("{\"JobId\":\"" + FAKE_JOB_ID + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void startPhiDetectionJob_bogusRole_returnsInvalidRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartPHIDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body("{"
                    + "\"InputDataConfig\":{\"S3Bucket\":\"alchemy-nonexistent-input-bucket\",\"S3Key\":\"notes/\"},"
                    + "\"OutputDataConfig\":{\"S3Bucket\":\"alchemy-nonexistent-output-bucket\"},"
                    + "\"DataAccessRoleArn\":\"" + BOGUS_ROLE + "\","
                    + "\"LanguageCode\":\"en\""
                    + "}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void detectEntitiesV2_missingText_returnsInvalidRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DetectEntitiesV2")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
