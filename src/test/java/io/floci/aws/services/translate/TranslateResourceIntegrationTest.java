package io.floci.aws.services.translate;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Translate's stateful surface: terminologies (and applying them in
 * TranslateText), parallel data read from S3, batch jobs, and tags.
 */
@QuarkusTest
class TranslateResourceIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/translate/aws4_request";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSShineFrontendService_20170701." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    private static String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anImportedTerminologyIsReadListedAppliedTaggedAndDeleted() {
        String file = base64("en,es\nAlchemy,Alquimia\nworld,mundo\n");
        String arn = call("ImportTerminology", """
                {"Name":"glossary-1","MergeStrategy":"OVERWRITE","Description":"terms",
                 "TerminologyData":{"File":"%s","Format":"CSV","Directionality":"UNI"},
                 "Tags":[{"Key":"team","Value":"i18n"}]}""".formatted(file))
            .statusCode(200)
            .body("TerminologyProperties.Name", equalTo("glossary-1"))
            .body("TerminologyProperties.TermCount", equalTo(2))
            .body("TerminologyProperties.SourceLanguageCode", equalTo("en"))
            .body("TerminologyProperties.TargetLanguageCodes", contains("es"))
            .extract().path("TerminologyProperties.Arn");
        assertEquals(true, arn.endsWith(":terminology/glossary-1/LATEST"));

        call("GetTerminology", "{\"Name\":\"glossary-1\"}")
            .statusCode(200)
            .body("TerminologyProperties.Description", equalTo("terms"))
            .body("TerminologyDataLocation.RepositoryType", equalTo("S3"));
        call("ListTerminologies", "{}")
            .statusCode(200)
            .body("TerminologyPropertiesList.Name", hasItem("glossary-1"));

        call("TranslateText", """
                {"Text":"I love Alchemy so much.","SourceLanguageCode":"en","TargetLanguageCode":"es",
                 "TerminologyNames":["glossary-1"]}""")
            .statusCode(200)
            .body("TranslatedText", equalTo("I love Alquimia so much."))
            .body("AppliedTerminologies[0].Name", equalTo("glossary-1"))
            .body("AppliedTerminologies[0].Terms[0].TargetText", equalTo("Alquimia"));

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("i18n"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"dev\"}]}")
            .statusCode(200);
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\"]}")
            .statusCode(200);
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("Tags.Key", contains("stage"));

        call("DeleteTerminology", "{\"Name\":\"glossary-1\"}").statusCode(200);
        call("GetTerminology", "{\"Name\":\"glossary-1\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        call("TranslateText", """
                {"Text":"hi","SourceLanguageCode":"en","TargetLanguageCode":"es",
                 "TerminologyNames":["glossary-1"]}""")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void parallelDataImportsItsS3FileAndFailsWhenTheFileIsMissing() {
        s3Service.createBucket("translate-parallel-data-test", "us-east-1");
        s3Service.putObject("translate-parallel-data-test", "pairs.csv",
                "en,es\nHello,Hola\nGood morning,Buenos dias\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", Map.of());

        call("CreateParallelData", """
                {"Name":"pairs-1","ClientToken":"t1",
                 "ParallelDataConfig":{"S3Uri":"s3://translate-parallel-data-test/pairs.csv","Format":"CSV"}}""")
            .statusCode(200)
            .body("Status", equalTo("CREATING"));
        call("GetParallelData", "{\"Name\":\"pairs-1\"}")
            .statusCode(200)
            .body("ParallelDataProperties.Status", equalTo("ACTIVE"))
            .body("ParallelDataProperties.ImportedRecordCount", equalTo(2))
            .body("ParallelDataProperties.SourceLanguageCode", equalTo("en"))
            .body("ParallelDataProperties.Arn", endsWith(":parallel-data/pairs-1"));
        call("CreateParallelData", """
                {"Name":"pairs-1","ClientToken":"t2",
                 "ParallelDataConfig":{"S3Uri":"s3://translate-parallel-data-test/pairs.csv","Format":"CSV"}}""")
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));

        call("UpdateParallelData", """
                {"Name":"pairs-1","ClientToken":"t3","Description":"updated",
                 "ParallelDataConfig":{"S3Uri":"s3://translate-parallel-data-test/missing.csv","Format":"CSV"}}""")
            .statusCode(200);
        call("GetParallelData", "{\"Name\":\"pairs-1\"}")
            .statusCode(200)
            .body("ParallelDataProperties.Status", equalTo("FAILED"))
            .body("ParallelDataProperties.Description", equalTo("updated"))
            .body("ParallelDataProperties.Message", containsString("missing.csv"));

        call("ListParallelData", "{}")
            .statusCode(200)
            .body("ParallelDataPropertiesList.Name", hasItem("pairs-1"));
        call("DeleteParallelData", "{\"Name\":\"pairs-1\"}")
            .statusCode(200)
            .body("Status", equalTo("DELETING"));
        call("GetParallelData", "{\"Name\":\"pairs-1\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aBatchJobTranslatesItsInputPrefixAndSettles() {
        s3Service.createBucket("translate-job-test", "us-east-1");
        s3Service.putObject("translate-job-test", "in/greeting.txt",
                "Hello, world!\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        String jobId = call("StartTextTranslationJob", """
                {"JobName":"job-1","SourceLanguageCode":"en","TargetLanguageCodes":["es"],
                 "DataAccessRoleArn":"arn:aws:iam::000000000000:role/translate",
                 "InputDataConfig":{"S3Uri":"s3://translate-job-test/in/","ContentType":"text/plain"},
                 "OutputDataConfig":{"S3Uri":"s3://translate-job-test/out/"}}""")
            .statusCode(200)
            .body("JobStatus", equalTo("SUBMITTED"))
            .body("JobId", matchesPattern("^[0-9a-f]{32}$"))
            .extract().path("JobId");

        assertEquals(1, s3Service.listObjects("translate-job-test", "out/", null, 100).size());
        call("DescribeTextTranslationJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(200)
            .body("TextTranslationJobProperties.JobName", equalTo("job-1"))
            .body("TextTranslationJobProperties.JobDetails.InputDocumentsCount", equalTo(1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                call("DescribeTextTranslationJob", "{\"JobId\":\"" + jobId + "\"}")
                    .statusCode(200)
                    .body("TextTranslationJobProperties.JobStatus", equalTo("COMPLETED")));
        call("ListTextTranslationJobs", "{\"Filter\":{\"JobName\":\"job-1\"}}")
            .statusCode(200)
            .body("TextTranslationJobPropertiesList.JobId", contains(jobId));

        String second = call("StartTextTranslationJob", """
                {"JobName":"job-2","SourceLanguageCode":"en","TargetLanguageCodes":["fr"],
                 "InputDataConfig":{"S3Uri":"s3://translate-job-test/in/","ContentType":"text/plain"},
                 "OutputDataConfig":{"S3Uri":"s3://translate-job-test/out/"}}""")
            .statusCode(200)
            .extract().path("JobId");
        call("StopTextTranslationJob", "{\"JobId\":\"" + second + "\"}")
            .statusCode(200)
            .body("JobStatus", equalTo("STOP_REQUESTED"));
        call("DescribeTextTranslationJob", "{\"JobId\":\"" + second + "\"}")
            .statusCode(200)
            .body("TextTranslationJobProperties.JobStatus", equalTo("STOPPED"));

        call("DescribeTextTranslationJob", "{\"JobId\":\"00000000000000000000000000000000\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        call("StopTextTranslationJob", "{\"JobId\":\"00000000000000000000000000000000\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
