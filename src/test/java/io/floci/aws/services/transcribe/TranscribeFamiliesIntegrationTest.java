package io.floci.aws.services.transcribe;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Transcribe families beyond standard jobs and vocabularies, the jobs'
 * S3 media and output, and tags.
 */
@QuarkusTest
class TranscribeFamiliesIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/transcribe/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/transcribe";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    private void media(String bucket, String key) {
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, key, "RIFF".getBytes(StandardCharsets.UTF_8), "audio/wav", Map.of());
    }

    @Test
    void transcriptionJobsReadTheirMediaAndWriteTheirTranscript() {
        media("tr-media", "call.wav");
        s3Service.createBucket("tr-output", "us-east-1");
        call("StartTranscriptionJob", """
                {"TranscriptionJobName":"with-output","LanguageCode":"en-US",
                 "Media":{"MediaFileUri":"s3://tr-media/call.wav"},"OutputBucketName":"tr-output",
                 "Tags":[{"Key":"team","Value":"voice"}]}""")
            .statusCode(200)
            .body("TranscriptionJob.TranscriptionJobStatus", equalTo("COMPLETED"))
            .body("TranscriptionJob.Transcript.TranscriptFileUri", equalTo("s3://tr-output/with-output.json"));
        String transcript = new String(s3Service.getObject("tr-output", "with-output.json").getData(),
                StandardCharsets.UTF_8);
        assertTrue(transcript.contains("\"jobName\":\"with-output\""), transcript);

        call("StartTranscriptionJob", """
                {"TranscriptionJobName":"missing-media","LanguageCode":"en-US",
                 "Media":{"MediaFileUri":"s3://tr-media/nope.wav"}}""")
            .statusCode(200)
            .body("TranscriptionJob.TranscriptionJobStatus", equalTo("FAILED"))
            .body("TranscriptionJob.FailureReason", containsString("can't be accessed"));
        call("StartTranscriptionJob", """
                {"TranscriptionJobName":"bad-uri","LanguageCode":"en-US","Media":{"MediaFileUri":"invalid-uri"}}""")
            .statusCode(400).body("__type", equalTo("BadRequestException"));

        String arn = "arn:aws:transcribe:us-east-1:000000000000:transcription-job/with-output";
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(200).body("Tags.find { it.Key == 'team' }.Value", equalTo("voice"));
    }

    @Test
    void vocabulariesFromPhrasesOrFilesAndUpdates() {
        call("CreateVocabulary", """
                {"VocabularyName":"from-file","LanguageCode":"en-US","VocabularyFileUri":"s3://tr-media/none.txt"}""")
            .statusCode(200).body("VocabularyState", equalTo("FAILED"));
        call("CreateVocabulary", """
                {"VocabularyName":"bad-file","LanguageCode":"en-US","VocabularyFileUri":"invalid-uri"}""")
            .statusCode(400).body("__type", equalTo("BadRequestException"));
        call("CreateVocabulary", "{\"VocabularyName\":\"no-terms\",\"LanguageCode\":\"en-US\"}")
            .statusCode(400).body("__type", equalTo("BadRequestException"));
        call("UpdateVocabulary", "{\"VocabularyName\":\"from-file\",\"LanguageCode\":\"en-US\",\"Phrases\":[\"floci\"]}")
            .statusCode(200).body("VocabularyState", equalTo("READY"));
        call("UpdateVocabulary", "{\"VocabularyName\":\"nope\",\"LanguageCode\":\"en-US\",\"Phrases\":[\"x\"]}")
            .statusCode(400).body("__type", equalTo("NotFoundException"));
    }

    @Test
    void callAnalyticsMedicalAndScribeJobs() {
        media("tr-calls", "call.wav");
        s3Service.createBucket("tr-medical-out", "us-east-1");
        call("StartCallAnalyticsJob", """
                {"CallAnalyticsJobName":"call-1","DataAccessRoleArn":"%s","Media":{"MediaFileUri":"s3://tr-calls/call.wav"}}"""
                .formatted(ROLE))
            .statusCode(200).body("CallAnalyticsJob.CallAnalyticsJobStatus", equalTo("COMPLETED"));
        call("StartCallAnalyticsJob", """
                {"CallAnalyticsJobName":"call-1","Media":{"MediaFileUri":"s3://tr-calls/call.wav"}}""")
            .statusCode(400).body("__type", equalTo("ConflictException"));
        call("ListCallAnalyticsJobs", "{\"JobNameContains\":\"CALL\"}")
            .statusCode(200).body("CallAnalyticsJobSummaries.CallAnalyticsJobName", contains("call-1"));
        call("DeleteCallAnalyticsJob", "{\"CallAnalyticsJobName\":\"call-1\"}").statusCode(200);
        call("GetCallAnalyticsJob", "{\"CallAnalyticsJobName\":\"call-1\"}")
            .statusCode(400).body("__type", equalTo("BadRequestException"));

        call("StartMedicalTranscriptionJob", """
                {"MedicalTranscriptionJobName":"med-1","LanguageCode":"en-US","Specialty":"PRIMARYCARE",
                 "Type":"DICTATION","OutputBucketName":"tr-medical-out","Media":{"MediaFileUri":"s3://tr-calls/call.wav"}}""")
            .statusCode(200)
            .body("MedicalTranscriptionJob.TranscriptionJobStatus", equalTo("COMPLETED"))
            .body("MedicalTranscriptionJob.Transcript.TranscriptFileUri", equalTo("s3://tr-medical-out/med-1.json"));
        call("StartMedicalTranscriptionJob", """
                {"MedicalTranscriptionJobName":"med-2","LanguageCode":"fr-FR","Specialty":"PRIMARYCARE",
                 "Type":"DICTATION","OutputBucketName":"tr-medical-out","Media":{"MediaFileUri":"s3://tr-calls/call.wav"}}""")
            .statusCode(400).body("__type", equalTo("BadRequestException"));

        call("StartMedicalScribeJob", """
                {"MedicalScribeJobName":"scribe-1","OutputBucketName":"tr-medical-out","DataAccessRoleArn":"%s",
                 "Settings":{"ShowSpeakerLabels":true,"MaxSpeakerLabels":2},
                 "Media":{"MediaFileUri":"s3://tr-calls/call.wav"}}""".formatted(ROLE))
            .statusCode(200)
            .body("MedicalScribeJob.MedicalScribeJobStatus", equalTo("COMPLETED"))
            .body("MedicalScribeJob.MedicalScribeOutput.ClinicalDocumentUri",
                    equalTo("s3://tr-medical-out/scribe-1/summary.json"));
        call("ListMedicalScribeJobs", "{}")
            .statusCode(200).body("MedicalScribeJobSummaries.MedicalScribeJobName", hasItem("scribe-1"));
        for (String start : List.of("StartCallAnalyticsJob|CallAnalyticsJobName", "StartMedicalScribeJob|MedicalScribeJobName")) {
            String[] parts = start.split("\\|");
            call(parts[0], "{\"" + parts[1] + "\":\"x\",\"Media\":{\"MediaFileUri\":\"invalid-uri\"}}")
                .statusCode(400).body("__type", equalTo("BadRequestException"));
        }
    }

    @Test
    void filtersCategoriesMedicalVocabulariesAndLanguageModels() {
        call("CreateVocabularyFilter", "{\"VocabularyFilterName\":\"profanity\",\"LanguageCode\":\"en-US\",\"Words\":[\"darn\"]}")
            .statusCode(200).body("VocabularyFilterName", equalTo("profanity"));
        call("GetVocabularyFilter", "{\"VocabularyFilterName\":\"profanity\"}")
            .statusCode(200).body("DownloadUri", startsWith("https://"));
        call("UpdateVocabularyFilter", "{\"VocabularyFilterName\":\"profanity\",\"Words\":[\"darn\",\"heck\"]}")
            .statusCode(200);
        String filterArn = "arn:aws:transcribe:us-east-1:000000000000:vocabulary-filter/profanity";
        call("TagResource", "{\"ResourceArn\":\"" + filterArn + "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"1\"}]}")
            .statusCode(200);
        call("ListTagsForResource", "{\"ResourceArn\":\"" + filterArn + "\"}").statusCode(200).body("Tags", hasSize(1));
        call("UntagResource", "{\"ResourceArn\":\"" + filterArn + "\",\"TagKeys\":[\"a\"]}").statusCode(200);
        call("DeleteVocabularyFilter", "{\"VocabularyFilterName\":\"profanity\"}").statusCode(200);
        call("GetVocabularyFilter", "{\"VocabularyFilterName\":\"profanity\"}")
            .statusCode(400).body("__type", equalTo("NotFoundException"));
        call("TagResource", "{\"ResourceArn\":\"" + filterArn + "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"1\"}]}")
            .statusCode(400).body("__type", equalTo("NotFoundException"));

        call("CreateCallAnalyticsCategory", """
                {"CategoryName":"silence","Rules":[{"NonTalkTimeFilter":{"Threshold":30000}}]}""")
            .statusCode(200).body("CategoryProperties.CategoryName", equalTo("silence"));
        call("UpdateCallAnalyticsCategory", """
                {"CategoryName":"silence","Rules":[{"NonTalkTimeFilter":{"Threshold":60000}}]}""")
            .statusCode(200).body("CategoryProperties.Rules[0].NonTalkTimeFilter.Threshold", equalTo(60000));
        call("ListCallAnalyticsCategories", "{}").statusCode(200).body("Categories.CategoryName", hasItem("silence"));
        call("DeleteCallAnalyticsCategory", "{\"CategoryName\":\"silence\"}").statusCode(200);
        call("GetCallAnalyticsCategory", "{\"CategoryName\":\"silence\"}")
            .statusCode(400).body("__type", equalTo("NotFoundException"));

        call("CreateMedicalVocabulary", """
                {"VocabularyName":"med-terms","LanguageCode":"en-US","VocabularyFileUri":"invalid-uri"}""")
            .statusCode(400).body("__type", equalTo("BadRequestException"));
        call("GetMedicalVocabulary", "{\"VocabularyName\":\"med-terms\"}")
            .statusCode(400).body("__type", equalTo("BadRequestException"));

        media("tr-training", "data/corpus.txt");
        call("CreateLanguageModel", """
                {"ModelName":"domain","BaseModelName":"NarrowBand","LanguageCode":"en-US",
                 "InputDataConfig":{"S3Uri":"s3://tr-training/data/","DataAccessRoleArn":"%s"}}""".formatted(ROLE))
            .statusCode(200).body("ModelStatus", equalTo("IN_PROGRESS"));
        call("DescribeLanguageModel", "{\"ModelName\":\"domain\"}")
            .statusCode(200).body("LanguageModel.ModelStatus", equalTo("COMPLETED"));
        call("CreateLanguageModel", """
                {"ModelName":"bad","BaseModelName":"NarrowBand","LanguageCode":"en-US",
                 "InputDataConfig":{"S3Uri":"invalid-uri","DataAccessRoleArn":"%s"}}""".formatted(ROLE))
            .statusCode(400).body("__type", equalTo("BadRequestException"));
        call("ListLanguageModels", "{}").statusCode(200).body("Models.ModelName", hasItem("domain"));
        call("DeleteLanguageModel", "{\"ModelName\":\"domain\"}").statusCode(200);
        call("DescribeLanguageModel", "{\"ModelName\":\"domain\"}")
            .statusCode(400).body("__type", equalTo("NotFoundException"));
        call("DeleteLanguageModel", "{\"ModelName\":\"domain\"}")
            .statusCode(400).body("__type", equalTo("BadRequestException"));
    }
}
