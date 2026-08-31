package io.github.hectorvent.floci.services.transcribe;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Binding-coverage operations used by Alchemy's Transcribe Bindings suite:
 * extra job families, vocabulary filters, call analytics categories, tagging,
 * and invalid S3 URI validation.
 */
@QuarkusTest
class TranscribeBindingsIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/transcribe/aws4_request";

    private static void transcribeIgnoreError(String action, String body) {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe." + action)
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/");
    }

    @Test
    void listFamilies_returnEmptyCollections() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListCallAnalyticsJobs")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CallAnalyticsJobSummaries", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListMedicalTranscriptionJobs")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MedicalTranscriptionJobSummaries", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListMedicalScribeJobs")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MedicalScribeJobSummaries", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListVocabularyFilters")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VocabularyFilters", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListCallAnalyticsCategories")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Categories", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListLanguageModels")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Models", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListMedicalVocabularies")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Vocabularies", notNullValue());
    }

    @Test
    void invalidMediaUri_returnsBadRequest() {
        String[][] starts = {
            {"StartTranscriptionJob",
                    "{\"TranscriptionJobName\":\"bogus-start\",\"Media\":{\"MediaFileUri\":\"invalid-uri\"},\"LanguageCode\":\"en-US\"}"},
            {"StartCallAnalyticsJob",
                    "{\"CallAnalyticsJobName\":\"bogus-call\",\"Media\":{\"MediaFileUri\":\"invalid-uri\"}}"},
            {"StartMedicalTranscriptionJob",
                    "{\"MedicalTranscriptionJobName\":\"bogus-med\",\"LanguageCode\":\"en-US\",\"Media\":{\"MediaFileUri\":\"invalid-uri\"},\"OutputBucketName\":\"out\",\"Specialty\":\"PRIMARYCARE\",\"Type\":\"DICTATION\"}"},
            {"StartMedicalScribeJob",
                    "{\"MedicalScribeJobName\":\"bogus-scribe\",\"Media\":{\"MediaFileUri\":\"invalid-uri\"},\"OutputBucketName\":\"out\",\"Settings\":{\"ShowSpeakerLabels\":true,\"MaxSpeakerLabels\":2}}"},
            {"CreateVocabulary",
                    "{\"VocabularyName\":\"bogus-vocab\",\"LanguageCode\":\"en-US\",\"VocabularyFileUri\":\"invalid-uri\"}"},
            {"CreateMedicalVocabulary",
                    "{\"VocabularyName\":\"bogus-med-vocab\",\"LanguageCode\":\"en-US\",\"VocabularyFileUri\":\"invalid-uri\"}"},
            {"CreateLanguageModel",
                    "{\"ModelName\":\"bogus-model\",\"BaseModelName\":\"NarrowBand\",\"LanguageCode\":\"en-US\",\"InputDataConfig\":{\"S3Uri\":\"invalid-uri\",\"DataAccessRoleArn\":\"arn:aws:iam::000000000000:role/x\"}}"}
        };
        for (String[] start : starts) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe." + start[0])
                .header("Authorization", AUTH_HEADER)
                .body(start[1])
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        }
    }

    @Test
    void missingJobs_returnBadRequest() {
        String[][] gets = {
            {"GetTranscriptionJob", "{\"TranscriptionJobName\":\"missing\"}"},
            {"GetCallAnalyticsJob", "{\"CallAnalyticsJobName\":\"missing\"}"},
            {"GetMedicalTranscriptionJob", "{\"MedicalTranscriptionJobName\":\"missing\"}"},
            {"GetMedicalScribeJob", "{\"MedicalScribeJobName\":\"missing\"}"}
        };
        for (String[] get : gets) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe." + get[0])
                .header("Authorization", AUTH_HEADER)
                .body(get[1])
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        }
    }

    @Test
    void missingVocabulariesAndModels_returnNotFound() {
        String[][] gets = {
            {"GetVocabulary", "{\"VocabularyName\":\"missing\"}"},
            {"GetMedicalVocabulary", "{\"VocabularyName\":\"missing\"}"},
            {"DescribeLanguageModel", "{\"ModelName\":\"missing\"}"},
            {"UpdateVocabulary",
                    "{\"VocabularyName\":\"missing\",\"LanguageCode\":\"en-US\",\"Phrases\":[\"alchemy\"]}"},
            {"UpdateMedicalVocabulary",
                    "{\"VocabularyName\":\"missing\",\"LanguageCode\":\"en-US\",\"VocabularyFileUri\":\"invalid-uri\"}"}
        };
        for (String[] get : gets) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe." + get[0])
                .header("Authorization", AUTH_HEADER)
                .body(get[1])
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", anyOf(equalTo("NotFoundException"), equalTo("BadRequestException")));
        }
    }

    @Test
    void vocabularyFilter_lifecycleAndTags() {
        transcribeIgnoreError("DeleteVocabularyFilter", "{\"VocabularyFilterName\":\"floci-filter\"}");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateVocabularyFilter")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyFilterName":"floci-filter","LanguageCode":"en-US",
                 "Words":["alchemyzz","distilledzz"]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VocabularyFilterName", equalTo("floci-filter"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetVocabularyFilter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"VocabularyFilterName\":\"floci-filter\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VocabularyFilterName", equalTo("floci-filter"))
            .body("DownloadUri", notNullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.UpdateVocabularyFilter")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"VocabularyFilterName":"floci-filter",
                 "Words":["alchemyzz","distilledzz","workerdzz"]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String arn = "arn:aws:transcribe:us-east-1:000000000000:vocabulary-filter/floci-filter";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.TagResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn
                    + "\",\"Tags\":[{\"Key\":\"alchemy-test\",\"Value\":\"1\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.ListTagsForResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(greaterThanOrEqualTo(1)));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.UntagResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"alchemy-test\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteVocabularyFilter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"VocabularyFilterName\":\"floci-filter\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetVocabularyFilter")
            .header("Authorization", AUTH_HEADER)
            .body("{\"VocabularyFilterName\":\"floci-filter\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void callAnalyticsCategory_lifecycle() {
        transcribeIgnoreError("DeleteCallAnalyticsCategory", "{\"CategoryName\":\"floci-category\"}");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.CreateCallAnalyticsCategory")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"CategoryName":"floci-category",
                 "Rules":[{"NonTalkTimeFilter":{"Threshold":30000}}]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CategoryProperties.CategoryName", equalTo("floci-category"))
            .body("CategoryProperties.Rules", hasSize(1));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetCallAnalyticsCategory")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CategoryName\":\"floci-category\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CategoryProperties.Rules", hasSize(1));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.UpdateCallAnalyticsCategory")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"CategoryName":"floci-category",
                 "Rules":[{"NonTalkTimeFilter":{"Threshold":60000}}]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.DeleteCallAnalyticsCategory")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CategoryName\":\"floci-category\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Transcribe.GetCallAnalyticsCategory")
            .header("Authorization", AUTH_HEADER)
            .body("{\"CategoryName\":\"floci-category\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void deleteMissing_returnsTypedTag() {
        String[][] deletes = {
            {"DeleteTranscriptionJob", "{\"TranscriptionJobName\":\"missing\"}"},
            {"DeleteCallAnalyticsJob", "{\"CallAnalyticsJobName\":\"missing\"}"},
            {"DeleteMedicalTranscriptionJob", "{\"MedicalTranscriptionJobName\":\"missing\"}"},
            {"DeleteMedicalScribeJob", "{\"MedicalScribeJobName\":\"missing\"}"},
            {"DeleteVocabulary", "{\"VocabularyName\":\"missing\"}"},
            {"DeleteMedicalVocabulary", "{\"VocabularyName\":\"missing\"}"},
            {"DeleteLanguageModel", "{\"ModelName\":\"missing\"}"}
        };
        for (String[] delete : deletes) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Transcribe." + delete[0])
                .header("Authorization", AUTH_HEADER)
                .body(delete[1])
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", anyOf(equalTo("BadRequestException"), equalTo("NotFoundException")));
        }
    }
}
