package io.github.hectorvent.floci.services.polly;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Polly restJson1 coverage used by Alchemy Bindings.test.ts: lexicon
 * upsert/get/list/delete, DescribeVoices (Joanna), SynthesizeSpeech dummy
 * mp3, and Start/Get/ListSpeechSynthesisTask writing to S3.
 */
@QuarkusTest
class PollyIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String PLS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <lexicon version="1.0"
                  xmlns="http://www.w3.org/2005/01/pronunciation-lexicon"
                  alphabet="ipa" xml:lang="en-US">
              <lexeme><grapheme>IaE</grapheme><alias>infrastructure as effects</alias></lexeme>
            </lexicon>
            """;

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getLexiconOnANonexistentNameFailsWithLexiconNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/v1/lexicons/missingLexiconName")
                .then()
                .statusCode(404)
                .body("__type", equalTo("LexiconNotFoundException"));
    }

    @Test
    void putGetListAndDeleteLexiconRoundTrip() {
        String authorization = auth(EAST);
        String name = "alchemyPollyIt";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Content\":" + jsonString(PLS) + "}")
                .when()
                .put("/v1/lexicons/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/lexicons/" + name)
                .then()
                .statusCode(200)
                .body("Lexicon.Name", equalTo(name))
                .body("Lexicon.Content", containsString("infrastructure as effects"))
                .body("LexiconAttributes.LexemesCount", equalTo(1))
                .body("LexiconAttributes.Alphabet", equalTo("ipa"))
                .body("LexiconAttributes.LanguageCode", equalTo("en-US"))
                .body("LexiconAttributes.LexiconArn", containsString(":lexicon/" + name));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/lexicons")
                .then()
                .statusCode(200)
                .body("Lexicons.Name", hasItem(name));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/lexicons/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/lexicons/" + name)
                .then()
                .statusCode(404)
                .body("__type", equalTo("LexiconNotFoundException"));
    }

    @Test
    void describeVoicesListsEnUsJoanna() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("LanguageCode", "en-US")
                .when()
                .get("/v1/voices")
                .then()
                .statusCode(200)
                .body("Voices.Id", hasItem("Joanna"))
                .body("Voices.size()", greaterThan(0));
    }

    @Test
    void synthesizeSpeechReturnsMpegBytes() {
        String name = "synthLexIt";
        String authorization = auth(EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Content\":" + jsonString(PLS) + "}")
                .when()
                .put("/v1/lexicons/" + name)
                .then()
                .statusCode(200);

        byte[] audio = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Engine":"neural",
                          "OutputFormat":"mp3",
                          "VoiceId":"Joanna",
                          "Text":"Alchemy turns IaE into deployed infrastructure.",
                          "LexiconNames":["%s"]
                        }
                        """.formatted(name))
                .when()
                .post("/v1/speech")
                .then()
                .statusCode(200)
                .header("Content-Type", containsString("audio/mpeg"))
                .header("x-amzn-RequestCharacters", equalTo("47"))
                .extract()
                .asByteArray();

        org.junit.jupiter.api.Assertions.assertTrue(audio.length > 1000);
    }

    @Test
    void startGetAndListSpeechSynthesisTaskWritesToS3() {
        String authorization = auth(EAST);
        String bucket = "polly-it-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(bucket, EAST);

        String taskId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Engine":"neural",
                          "OutputFormat":"mp3",
                          "OutputS3BucketName":"%s",
                          "OutputS3KeyPrefix":"task-output/",
                          "VoiceId":"Joanna",
                          "Text":"Alchemy turns IaE into deployed infrastructure."
                        }
                        """.formatted(bucket))
                .when()
                .post("/v1/synthesisTasks")
                .then()
                .statusCode(200)
                .body("SynthesisTask.TaskId", notNullValue())
                .body("SynthesisTask.TaskStatus", equalTo("completed"))
                .body("SynthesisTask.OutputUri", containsString(bucket))
                .extract()
                .path("SynthesisTask.TaskId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/synthesisTasks/" + taskId)
                .then()
                .statusCode(200)
                .body("SynthesisTask.TaskStatus", equalTo("completed"))
                .body("SynthesisTask.OutputUri", containsString("task-output/"));

        given()
                .header("Authorization", authorization)
                .queryParam("MaxResults", 10)
                .when()
                .get("/v1/synthesisTasks")
                .then()
                .statusCode(200)
                .body("SynthesisTasks.TaskId", hasItem(taskId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/synthesisTasks/does-not-exist")
                .then()
                .statusCode(400)
                .body("__type", equalTo("SynthesisTaskNotFoundException"));
    }

    @Test
    void startSpeechSynthesisTaskOnMissingBucketFailsWithInvalidS3BucketException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("""
                        {
                          "OutputFormat":"mp3",
                          "OutputS3BucketName":"polly-missing-bucket",
                          "VoiceId":"Joanna",
                          "Text":"hello"
                        }
                        """)
                .when()
                .post("/v1/synthesisTasks")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidS3BucketException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/polly/aws4_request";
    }

    private static String jsonString(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}
