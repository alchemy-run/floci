package io.github.hectorvent.floci.services.translate;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the Amazon Translate stub.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSShineFrontendService_20170701.&lt;Action&gt;
 */
@QuarkusTest
class TranslateIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/translate/aws4_request";
    private static final String TARGET_PREFIX = "AWSShineFrontendService_20170701.";

    @Test
    void translateText_helloWorld_containsHola() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Text":"Hello, world!","SourceLanguageCode":"en","TargetLanguageCode":"es"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", containsString("Hola"))
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("es"));
    }

    @Test
    void translateText_appliesImportedTerminology() {
        String csv = Base64.getEncoder().encodeToString("en,es\nAlchemy,Alquimia".getBytes(StandardCharsets.UTF_8));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ImportTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"it-glossary\",\"MergeStrategy\":\"OVERWRITE\","
                    + "\"TerminologyData\":{\"File\":\"" + csv + "\",\"Format\":\"CSV\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminologyProperties.TermCount", equalTo(1))
            .body("TerminologyProperties.SourceLanguageCode", equalTo("en"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "TranslateText")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Text":"I love Alchemy so much.","SourceLanguageCode":"en",
                 "TargetLanguageCode":"es","TerminologyNames":["it-glossary"]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedText", containsString("Alquimia"))
            .body("AppliedTerminologies.Name", hasItem("it-glossary"));
    }

    @Test
    void translateDocument_plainText_containsBuenos() {
        String content = Base64.getEncoder().encodeToString(
                "Good morning, friend.".getBytes(StandardCharsets.UTF_8));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "TranslateDocument")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Document\":{\"Content\":\"" + content
                    + "\",\"ContentType\":\"text/plain\"},"
                    + "\"SourceLanguageCode\":\"en\",\"TargetLanguageCode\":\"es\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TranslatedDocument.Content", notNullValue())
            .body("SourceLanguageCode", equalTo("en"))
            .body("TargetLanguageCode", equalTo("es"));
    }

    @Test
    void listLanguages_includesSpanishAndMoreThanTen() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListLanguages")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MaxResults":500}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Languages.size()", greaterThan(10))
            .body("Languages.LanguageCode", hasItem("es"));
    }

    @Test
    void getAndListTerminologies_roundTrip() {
        String csv = Base64.getEncoder().encodeToString("en,es\nAlchemy,Alquimia".getBytes(StandardCharsets.UTF_8));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ImportTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"list-glossary\",\"MergeStrategy\":\"OVERWRITE\","
                    + "\"TerminologyData\":{\"File\":\"" + csv + "\",\"Format\":\"CSV\"},"
                    + "\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"test\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Name":"list-glossary"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminologyProperties.Name", equalTo("list-glossary"))
            .body("TerminologyProperties.TermCount", equalTo(1))
            .body("TerminologyProperties.SourceLanguageCode", equalTo("en"))
            .body("TerminologyDataLocation.RepositoryType", equalTo("S3"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListTerminologies")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MaxResults":100}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TerminologyPropertiesList.Name", hasItem("list-glossary"));
    }

    @Test
    void getTerminology_missing_returnsResourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Name":"does-not-exist"}""")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getParallelData_missing_returnsResourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListParallelData")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"MaxResults":100}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "GetParallelData")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Name":"alchemy-nonexistent-parallel-data"}""")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeAndStop_missingJob_returnsResourceNotFound() {
        String bogus = "00000000000000000000000000000000";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + bogus + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StopTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + bogus + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void jobLifecycle_startDescribeStop() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StartTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"JobName":"lifecycle",
                 "InputDataConfig":{"S3Uri":"s3://bucket/translate-input/","ContentType":"text/plain"},
                 "OutputDataConfig":{"S3Uri":"s3://bucket/translate-output/"},
                 "DataAccessRoleArn":"arn:aws:iam::123456789012:role/translate",
                 "SourceLanguageCode":"en",
                 "TargetLanguageCodes":["es"],
                 "ClientToken":"lifecycle-token"}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobId", matchesPattern("^[0-9a-f]{32}$"))
            .body("JobStatus", equalTo("SUBMITTED"));

        String jobId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListTextTranslationJobs")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"Filter":{"JobName":"lifecycle"}}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("TextTranslationJobPropertiesList[0].JobId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TextTranslationJobProperties.JobStatus", equalTo("SUBMITTED"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "StopTextTranslationJob")
            .header("Authorization", AUTH_HEADER)
            .body("{\"JobId\":\"" + jobId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("JobStatus", equalTo("STOP_REQUESTED"));
    }

    @Test
    void tagResource_roundTrip() {
        String csv = Base64.getEncoder().encodeToString("en,es\nStack,Pila".getBytes(StandardCharsets.UTF_8));

        String arn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ImportTerminology")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"tag-glossary\",\"MergeStrategy\":\"OVERWRITE\","
                    + "\"TerminologyData\":{\"File\":\"" + csv + "\",\"Format\":\"CSV\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("TerminologyProperties.Arn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn
                    + "\",\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-test\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("purpose"))
            .body("Tags.Value", hasItem("alchemy-test"));
    }

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "FakeAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
