package io.github.hectorvent.floci.services.comprehend;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Amazon Comprehend stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: Comprehend_20171127.&lt;Action&gt;
 */
@QuarkusTest
class ComprehendIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehend/aws4_request";
    private static final String BOGUS_JOB_ID = "00000000000000000000000000000000";
    private static final String VALID_START = """
            {"InputDataConfig":{"S3Uri":"s3://alchemy-test-comprehend-bindings/comprehend-input/",
             "InputFormat":"ONE_DOC_PER_LINE"},
             "OutputDataConfig":{"S3Uri":"s3://alchemy-test-comprehend-bindings/comprehend-output/"},
             "DataAccessRoleArn":"arn:aws:iam::000000000000:role/ComprehendDataAccessRole",
             "LanguageCode":"en","JobName":"alchemy-comprehend-bindings-lifecycle"}""";
    private static final String INVALID_START = """
            {"InputDataConfig":{"S3Uri":"invalid-uri"},
             "OutputDataConfig":{"S3Uri":"invalid-uri"},
             "DataAccessRoleArn":"arn:aws:iam::000000000000:role/ComprehendDataAccessRole",
             "LanguageCode":"en"}""";

    @Test
    void detectDominantLanguage_returnsEnglish() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectDominantLanguage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Bob ordered two sandwiches yesterday.\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Languages[0].LanguageCode", equalTo("en"));
    }

    @Test
    void detectEntities_returnsPersonAndLocation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Bob moved to Seattle in 2017.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities.Type", hasItems("PERSON", "LOCATION"));
    }

    @Test
    void detectKeyPhrases_returnsPhrases() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectKeyPhrases")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"The quarterly earnings report exceeded analyst expectations.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyPhrases", not(empty()));
    }

    @Test
    void detectPiiEntities_returnsNameAndEmail() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My name is Jane Doe and my email address is jane@example.com.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities.Type", hasItems("NAME", "EMAIL"));
    }

    @Test
    void detectSentiment_positiveReview() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"I love this product, it works wonderfully!\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Sentiment", equalTo("POSITIVE"));
    }

    @Test
    void detectSyntax_tagsNouns() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectSyntax")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"The cat sat on the mat.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SyntaxTokens.PartOfSpeech.Tag", hasItem("NOUN"));
    }

    @Test
    void detectTargetedSentiment_returnsEntities() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectTargetedSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"The screen is gorgeous but the battery is disappointing.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Entities", not(empty()));
    }

    @Test
    void detectToxicContent_lowScoreForCompliment() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DetectToxicContent")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TextSegments\":[{\"Text\":\"You are a wonderful person.\"}],\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultList[0].Toxicity", lessThan(0.5f));
    }

    @Test
    void containsPiiEntities_labelsName() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.ContainsPiiEntities")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"My name is Jane Doe and my email address is jane@example.com.\",\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Labels.Name", hasItem("NAME"));
    }

    @Test
    void batchDetect_indexAlignedResults() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.BatchDetectDominantLanguage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TextList\":[\"I love this product, it works wonderfully!\",\"The delivery was late and the box arrived damaged.\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultList", hasSize(2))
            .body("ResultList[0].Languages[0].LanguageCode", equalTo("en"))
            .body("ErrorList", empty());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.BatchDetectSentiment")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TextList\":[\"I love this product, it works wonderfully!\",\"The delivery was late and the box arrived damaged.\"],\"LanguageCode\":\"en\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultList", hasSize(2))
            .body("ResultList[0].Sentiment", equalTo("POSITIVE"));
    }

    @Test
    void classifyDocument_missingEndpoint_resourceUnavailable() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.ClassifyDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Text\":\"Subject: your invoice for March is attached\",\"EndpointArn\":\"arn:aws:comprehend:us-east-1:000000000000:document-classifier-endpoint/alchemy-nonexistent\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceUnavailableException"));
    }

    @Test
    void listJobs_returnsEmptyPage() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.ListSentimentDetectionJobs")
            .header("Authorization", AUTH_HEADER)
            .body("{\"MaxResults\":5}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SentimentDetectionJobPropertiesList", notNullValue());
    }

    @Test
    void describeJob_unknownId_jobNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DescribeSentimentDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + BOGUS_JOB_ID + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("JobNotFoundException"));
    }

    @Test
    void stopJob_unknownId_jobNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.StopSentimentDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + BOGUS_JOB_ID + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("JobNotFoundException"));
    }

    @Test
    void startJob_invalidS3Uri_invalidRequest() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.StartEntitiesDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body(INVALID_START)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void sentimentJob_startDescribeStopLifecycle() {
        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.StartSentimentDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body(VALID_START)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", not(emptyString()))
            .body("JobStatus", is(oneOf("SUBMITTED", "IN_PROGRESS")))
            .extract()
            .path("JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.DescribeSentimentDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SentimentDetectionJobProperties.JobStatus", is(oneOf("SUBMITTED", "IN_PROGRESS")));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.StopSentimentDetectionJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", is(oneOf("STOP_REQUESTED", "STOPPED")));
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Comprehend_20171127.NotARealAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
