package io.floci.aws.services.comprehendmedical;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** Comprehend Medical's real-time analyses and batch jobs over JSON 1.1. */
@QuarkusTest
class ComprehendMedicalIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehendmedical/aws4_request";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", AUTH)
                .header("X-Amz-Target", "ComprehendMedical_20181030." + action).body(body)
                .when().post("/").then();
    }

    @Test
    void detectEntitiesFindsMedicationsWithAttributesAndConditions() {
        call("DetectEntitiesV2", "{\"Text\":\"Patient takes 50 mg atenolol daily for hypertension.\"}")
                .statusCode(200)
                .body("Entities.Category", hasItems("MEDICATION", "MEDICAL_CONDITION"))
                .body("Entities.find { it.Text == 'atenolol' }.Type", equalTo("GENERIC_NAME"))
                .body("Entities.find { it.Text == 'atenolol' }.Attributes.Type", hasItems("DOSAGE", "FREQUENCY"))
                .body("Entities.find { it.Text == 'hypertension' }.Traits.Name", hasItem("DIAGNOSIS"))
                .body("ModelVersion", notNullValue());
    }

    @Test
    void aNegatedConditionCarriesTheNegationTrait() {
        call("DetectEntitiesV2", "{\"Text\":\"The patient denies chest pain.\"}").statusCode(200)
                .body("Entities.find { it.Text == 'chest pain' }.Traits.Name", hasItems("SYMPTOM", "NEGATION"));
    }

    @Test
    void detectPhiFindsNameAgeDateAndAddress() {
        call("DetectPHI", "{\"Text\":\"John Doe, age 47, was seen on 2024-01-03 at Seattle Clinic.\"}")
                .statusCode(200)
                .body("Entities.Type", hasItems("NAME", "AGE", "DATE", "ADDRESS"))
                .body("Entities.find { it.Type == 'NAME' }.Text", equalTo("John Doe"))
                .body("Entities.find { it.Type == 'AGE' }.Text", equalTo("47"))
                .body("Entities.Category", not(hasItem("MEDICATION")));
    }

    @Test
    void theInferActionsLinkCodes() {
        String note = "{\"Text\":\"Patient presents with type 2 diabetes and hypertension.\"}";
        call("InferICD10CM", note).statusCode(200)
                .body("Entities.find { it.Text == 'type 2 diabetes' }.ICD10CMConcepts[0].Code", equalTo("E11.9"))
                .body("Entities.find { it.Text == 'hypertension' }.ICD10CMConcepts[0].Code", equalTo("I10"));
        call("InferSNOMEDCT", note).statusCode(200)
                .body("Entities.find { it.Text == 'type 2 diabetes' }.SNOMEDCTConcepts[0].Code", equalTo("44054006"))
                .body("SNOMEDCTDetails.Edition", equalTo("US"))
                .body("Characters.OriginalTextCharacters", equalTo(55));
        call("InferRxNorm", "{\"Text\":\"Patient takes 50 mg atenolol daily and 500 mg metformin twice a day.\"}")
                .statusCode(200)
                .body("Entities.find { it.Text == 'metformin' }.RxNormConcepts[0].Code", equalTo("6809"))
                .body("Entities.find { it.Text == 'metformin' }.Attributes.find { it.Type == 'FREQUENCY' }.Text",
                        equalTo("twice a day"));
    }

    @Test
    void aMockedResponseReplacesTheAnalysis() {
        call("DetectPHI", "{\"Text\":\"Mocked clinical note.\"}").statusCode(200)
                .body("ModelVersion", equalTo("mock"));
    }

    @Test
    void textIsRequiredAndBounded() {
        call("DetectEntitiesV2", "{}").statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("InferSNOMEDCT", "{\"Text\":\"" + "a".repeat(10_001) + "\"}").statusCode(400)
                .body("__type", equalTo("TextSizeLimitExceededException"));
    }

    @Test
    void aBatchJobAnalyzesTheInputPrefixAndWritesTheOutput() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "cm-it-" + suffix;
        String role = "cm-it-role-" + suffix;
        String s3Auth = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";
        given().header("Authorization", s3Auth).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", s3Auth).body("Patient takes 50 mg atenolol daily for hypertension.")
                .put("/" + bucket + "/notes/a.txt").then().statusCode(200);
        String roleArn = given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/iam/aws4_request")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":{\"Service\":\"comprehendmedical.amazonaws.com\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");

        String body = """
                {"InputDataConfig":{"S3Bucket":"%s","S3Key":"notes/"},
                 "OutputDataConfig":{"S3Bucket":"%s","S3Key":"out"},
                 "DataAccessRoleArn":"%s","LanguageCode":"en","JobName":"icd","ClientRequestToken":"t-%s"}"""
                .formatted(bucket, bucket, roleArn, suffix);
        String jobId = call("StartICD10CMInferenceJob", body).statusCode(200).extract().path("JobId");
        call("StartICD10CMInferenceJob", body).statusCode(200).body("JobId", equalTo(jobId));

        String prefix = call("DescribeICD10CMInferenceJob", "{\"JobId\":\"" + jobId + "\"}").statusCode(200)
                .body("ComprehendMedicalAsyncJobProperties.JobName", equalTo("icd"))
                .body("ComprehendMedicalAsyncJobProperties.JobStatus", equalTo("SUBMITTED"))
                .extract().path("ComprehendMedicalAsyncJobProperties.OutputDataConfig.S3Key");
        given().header("Authorization", s3Auth).get("/" + bucket + "/" + prefix + "a.txt.out").then().statusCode(200)
                .body(containsString("I10"));
        given().header("Authorization", s3Auth).get("/" + bucket + "/" + prefix + "Manifest").then().statusCode(200);
        call("ListICD10CMInferenceJobs", "{\"Filter\":{\"JobName\":\"icd\"}}").statusCode(200)
                .body("ComprehendMedicalAsyncJobPropertiesList.JobId", hasItem(jobId));
        call("ListPHIDetectionJobs", "{}").statusCode(200)
                .body("ComprehendMedicalAsyncJobPropertiesList.JobId", not(hasItem(jobId)));
        call("StopICD10CMInferenceJob", "{\"JobId\":\"" + jobId + "\"}").statusCode(200).body("JobId", equalTo(jobId));
        call("DescribeICD10CMInferenceJob", "{\"JobId\":\"" + jobId + "\"}").statusCode(200)
                .body("ComprehendMedicalAsyncJobProperties.JobStatus", equalTo("STOPPED"));

        call("StartPHIDetectionJob", body.replace(roleArn, roleArn + "-missing")).statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString("DATA_ACCESS_ROLE_ARN_INVALID"));
        call("DescribePHIDetectionJob", "{\"JobId\":\"" + jobId + "\"}").statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
