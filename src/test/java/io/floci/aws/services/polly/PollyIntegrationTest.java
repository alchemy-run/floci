package io.floci.aws.services.polly;

import io.floci.aws.core.common.AwsEventStreamDecoder;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Amazon Polly over its REST API. */
@QuarkusTest
class PollyIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/polly/aws4_request";
    private static final String LEXICON = """
            <?xml version="1.0" encoding="UTF-8"?>
            <lexicon version="1.0" xmlns="http://www.w3.org/2005/01/pronunciation-lexicon"
                     alphabet="ipa" xml:lang="en-US">
              <lexeme><grapheme>IaC</grapheme><alias>infrastructure as code</alias></lexeme>
            </lexicon>""";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification polly() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }

    @Test
    void aLexiconIsStoredReadListedAndDeleted() {
        String name = "lex" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        polly().body(Map.of("Content", LEXICON)).put("/v1/lexicons/" + name).then().statusCode(200);
        polly().get("/v1/lexicons/" + name).then().statusCode(200)
                .body("Lexicon.Name", equalTo(name))
                .body("Lexicon.Content", containsString("infrastructure as code"))
                .body("LexiconAttributes.Alphabet", equalTo("ipa"))
                .body("LexiconAttributes.LanguageCode", equalTo("en-US"))
                .body("LexiconAttributes.LexemesCount", equalTo(1))
                .body("LexiconAttributes.LexiconArn", containsString(":lexicon/" + name));
        polly().get("/v1/lexicons").then().statusCode(200).body("Lexicons.Name", hasItem(name));
        polly().delete("/v1/lexicons/" + name).then().statusCode(200);
        polly().get("/v1/lexicons/" + name).then().statusCode(404);
    }

    @Test
    void lexiconsAreChecked() {
        polly().body(Map.of("Content", "<not-xml")).put("/v1/lexicons/badxml").then().statusCode(400)
                .body("__type", containsString("InvalidLexiconException"));
        polly().body(Map.of("Content", LEXICON.replace("ipa", "klingon"))).put("/v1/lexicons/badalpha")
                .then().statusCode(400).body("__type", containsString("UnsupportedPlsAlphabetException"));
        polly().body(Map.of("Content", LEXICON.replace("en-US", "xx-XX"))).put("/v1/lexicons/badlang")
                .then().statusCode(400).body("__type", containsString("UnsupportedPlsLanguageException"));
        polly().body(Map.of("Content", LEXICON.replace("<alias>infrastructure as code</alias>", "")))
                .put("/v1/lexicons/noalias").then().statusCode(400)
                .body("__type", containsString("InvalidLexiconException"));
    }

    @Test
    void voicesAreFilteredByLanguageAndEngine() {
        polly().get("/v1/voices?LanguageCode=en-US").then().statusCode(200)
                .body("Voices.Id", hasItem("Joanna"))
                .body("Voices.Id", not(hasItem("Hans")));
        polly().get("/v1/voices?Engine=generative").then().statusCode(200)
                .body("Voices.Id", hasItem("Matthew"))
                .body("Voices.Id", not(hasItem("Aditi")));
        polly().get("/v1/voices?LanguageCode=hi-IN&IncludeAdditionalLanguageCodes=true").then().statusCode(200)
                .body("Voices.Id", hasItem("Kajal"));
    }

    @Test
    void synthesizeSpeechReturnsAudioOfTheSpokenLength() {
        byte[] mp3 = polly().body(("{\"Engine\":\"neural\",\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\","
                        + "\"Text\":\"Hello from Alchemy.\"}"))
                .post("/v1/speech").then().statusCode(200)
                .header("Content-Type", containsString("audio/mpeg"))
                .header("x-amzn-RequestCharacters", "19")
                .extract().asByteArray();
        assertTrue(mp3.length > 1000, "mp3 length " + mp3.length);
        assertEquals((byte) 0xFF, mp3[0]);
        assertEquals((byte) 0xF3, mp3[1]);

        byte[] pcm = polly().body("{\"OutputFormat\":\"pcm\",\"VoiceId\":\"Joanna\",\"SampleRate\":\"8000\","
                        + "\"Text\":\"Hello.\"}")
                .post("/v1/speech").then().statusCode(200).extract().asByteArray();
        assertEquals(8000 * 390 / 1000 * 2, pcm.length);

        byte[] ogg = polly().body("{\"OutputFormat\":\"ogg_vorbis\",\"VoiceId\":\"Joanna\",\"Text\":\"Hello.\"}")
                .post("/v1/speech").then().statusCode(200).header("Content-Type", containsString("audio/ogg"))
                .extract().asByteArray();
        assertEquals("OggS", new String(ogg, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void speechMarksTimeEachWordAndSentence() {
        String marks = polly().body("{\"OutputFormat\":\"json\",\"VoiceId\":\"Joanna\",\"Text\":\"Hello there. Bye.\","
                        + "\"SpeechMarkTypes\":[\"word\",\"sentence\"]}")
                .post("/v1/speech").then().statusCode(200)
                .header("Content-Type", containsString("application/x-json-stream"))
                .extract().asString();
        List<String> lines = marks.lines().toList();
        assertTrue(lines.contains("{\"time\":0,\"type\":\"sentence\",\"start\":0,\"end\":12,\"value\":\"Hello there.\"}"),
                marks);
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"word\"") && line.contains("\"Bye\"")),
                marks);
        polly().body("{\"OutputFormat\":\"json\",\"VoiceId\":\"Joanna\",\"Text\":\"<speak>Hi <mark name=\\\"m1\\\"/>"
                        + " there</speak>\",\"TextType\":\"ssml\",\"SpeechMarkTypes\":[\"ssml\"]}")
                .post("/v1/speech").then().statusCode(200).body(containsString("\"value\":\"m1\""));
    }

    @Test
    void synthesisRequestsAreChecked() {
        polly().body("{\"Engine\":\"generative\",\"OutputFormat\":\"mp3\",\"VoiceId\":\"Aditi\",\"Text\":\"Hi\"}")
                .post("/v1/speech").then().statusCode(400).body("__type", containsString("EngineNotSupportedException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"LanguageCode\":\"de-DE\",\"Text\":\"Hi\"}")
                .post("/v1/speech").then().statusCode(400)
                .body("__type", containsString("LanguageNotSupportedException"));
        polly().body("{\"OutputFormat\":\"pcm\",\"VoiceId\":\"Joanna\",\"SampleRate\":\"22050\",\"Text\":\"Hi\"}")
                .post("/v1/speech").then().statusCode(400).body("__type", containsString("InvalidSampleRateException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"SpeechMarkTypes\":[\"word\"],\"Text\":\"Hi\"}")
                .post("/v1/speech").then().statusCode(400)
                .body("__type", containsString("MarksNotSupportedForFormatException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"TextType\":\"ssml\",\"Text\":\"<speak>Hi\"}")
                .post("/v1/speech").then().statusCode(400).body("__type", containsString("InvalidSsmlException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"LexiconNames\":[\"missing\"],\"Text\":\"Hi\"}")
                .post("/v1/speech").then().statusCode(404).body("__type", containsString("LexiconNotFoundException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"Text\":\"" + "a".repeat(3001) + "\"}")
                .post("/v1/speech").then().statusCode(400).body("__type", containsString("TextLengthExceededException"));
    }

    @Test
    void aSynthesisTaskWritesItsAudioToS3() {
        String bucket = "polly-it-" + UUID.randomUUID().toString().substring(0, 8);
        String s3Auth = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";
        given().header("Authorization", s3Auth).put("/" + bucket).then().statusCode(200);
        ValidatableResponse started = polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"Text\":\"Hello.\","
                        + "\"OutputS3BucketName\":\"" + bucket + "\",\"OutputS3KeyPrefix\":\"out/\"}")
                .post("/v1/synthesisTasks").then().statusCode(200)
                .body("SynthesisTask.TaskStatus", equalTo("scheduled"))
                .body("SynthesisTask.OutputUri", containsString(bucket + "/out/"));
        String taskId = started.extract().path("SynthesisTask.TaskId");
        polly().get("/v1/synthesisTasks/" + taskId).then().statusCode(200)
                .body("SynthesisTask.VoiceId", equalTo("Joanna"));
        polly().get("/v1/synthesisTasks?MaxResults=10").then().statusCode(200)
                .body("SynthesisTasks.TaskId", hasItem(taskId));
        given().header("Authorization", s3Auth).get("/" + bucket + "/out/" + taskId + ".mp3").then().statusCode(200);
        polly().get("/v1/synthesisTasks/" + UUID.randomUUID()).then().statusCode(400)
                .body("__type", containsString("SynthesisTaskNotFoundException"));
        polly().body("{\"OutputFormat\":\"mp3\",\"VoiceId\":\"Joanna\",\"Text\":\"Hi\",\"OutputS3BucketName\":\""
                        + bucket + "-missing\"}")
                .post("/v1/synthesisTasks").then().statusCode(400).body("__type", containsString("InvalidS3BucketException"));
    }

    @Test
    void aSynthesisStreamAnswersTextEventsWithAudioThenCloses() throws Exception {
        ByteArrayOutputStream actions = new ByteArrayOutputStream();
        actions.write(event("TextEvent", "{\"Text\":\"Hello from Alchemy.\"}"));
        actions.write(event("CloseStreamEvent", "{}"));
        byte[] answer = given().header("Authorization", AUTH).contentType("application/vnd.amazon.eventstream")
                .header("x-amzn-Engine", "generative").header("x-amzn-OutputFormat", "mp3")
                .header("x-amzn-VoiceId", "Matthew").body(actions.toByteArray())
                .post("/v1/synthesisStream").then().statusCode(200).extract().asByteArray();
        AwsEventStreamDecoder decoder = new AwsEventStreamDecoder(new ByteArrayInputStream(answer));
        List<String> types = new ArrayList<>();
        int audio = 0;
        AwsEventStreamDecoder.Message message;
        String closed = null;
        while ((message = decoder.next()) != null) {
            types.add(message.header(":event-type"));
            if ("AudioEvent".equals(message.header(":event-type"))) {
                audio += message.payload().length;
            } else {
                closed = new String(message.payload(), StandardCharsets.UTF_8);
            }
        }
        assertTrue(audio > 1000, "audio " + audio);
        assertEquals("StreamClosedEvent", types.getLast());
        assertEquals("{\"RequestCharacters\":19}", closed);
    }

    private static byte[] event(String type, String json) throws Exception {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(":message-type", "event");
        headers.put(":event-type", type);
        headers.put(":content-type", "application/json");
        return AwsEventStreamEncoder.encodeMessage(headers, json.getBytes(StandardCharsets.UTF_8));
    }
}
