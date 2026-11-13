package io.floci.aws.services.comprehend;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehend's rule-based analysis, batches, ClassifyDocument, and the
 * asynchronous jobs of every family with their output and tags.
 */
@QuarkusTest
class ComprehendAnalysisIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehend/aws4_request";
    private static final String ROLE = "arn:aws:iam::000000000000:role/comprehend";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "Comprehend_20171127." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    @Test
    void entitiesFollowTheText() {
        call("DetectEntities", "{\"Text\":\"Bob moved to Seattle in 2017.\",\"LanguageCode\":\"en\"}")
            .statusCode(200)
            .body("Entities.find { it.Text == 'Bob' }.Type", equalTo("PERSON"))
            .body("Entities.find { it.Text == 'Seattle' }.Type", equalTo("LOCATION"))
            .body("Entities.find { it.Text == 'Seattle' }.BeginOffset", equalTo(13))
            .body("Entities.find { it.Text == '2017' }.Type", equalTo("DATE"));
        call("DetectEntities", "{\"Text\":\"Jane works at Amazon Inc.\",\"LanguageCode\":\"en\"}")
            .statusCode(200)
            .body("Entities.find { it.Text.startsWith('Amazon') }.Type", equalTo("ORGANIZATION"));
        call("DetectEntities", """
                {"Text":"hi","EndpointArn":"arn:aws:comprehend:us-east-1:000000000000:entity-recognizer-endpoint/x"}""")
            .statusCode(400)
            .body("__type", equalTo("ResourceUnavailableException"));
    }

    @Test
    void sentimentReadsPolarityNegationAndMixture() {
        call("DetectSentiment", "{\"Text\":\"I love this product, it works wonderfully!\",\"LanguageCode\":\"en\"}")
            .statusCode(200).body("Sentiment", equalTo("POSITIVE"));
        call("DetectSentiment", "{\"Text\":\"The box arrived damaged and late.\",\"LanguageCode\":\"en\"}")
            .statusCode(200).body("Sentiment", equalTo("NEGATIVE"));
        call("DetectSentiment", "{\"Text\":\"This is not good.\",\"LanguageCode\":\"en\"}")
            .statusCode(200).body("Sentiment", equalTo("NEGATIVE"));
        call("DetectSentiment", "{\"Text\":\"The screen is great but the battery is terrible.\",\"LanguageCode\":\"en\"}")
            .statusCode(200).body("Sentiment", equalTo("MIXED"));
    }

    @Test
    void syntaxPiiTargetedSentimentAndToxicity() {
        call("DetectSyntax", "{\"Text\":\"The cat sat on the mat.\",\"LanguageCode\":\"en\"}")
            .statusCode(200)
            .body("SyntaxTokens.PartOfSpeech.Tag", contains("DET", "NOUN", "VERB", "ADP", "DET", "NOUN", "PUNCT"))
            .body("SyntaxTokens[1].Text", equalTo("cat"))
            .body("SyntaxTokens[1].TokenId", equalTo(2));
        call("DetectPiiEntities", """
                {"Text":"My name is Jane Doe and my email address is jane@example.com.","LanguageCode":"en"}""")
            .statusCode(200)
            .body("Entities.Type", hasItems("NAME", "EMAIL"))
            .body("Entities.find { it.Type == 'EMAIL' }.BeginOffset", equalTo(44));
        call("DetectTargetedSentiment", """
                {"Text":"The screen is gorgeous but the battery is disappointing.","LanguageCode":"en"}""")
            .statusCode(200)
            .body("Entities.collect { it.Mentions[0] }.find { it.Text == 'screen' }.MentionSentiment.Sentiment",
                    equalTo("POSITIVE"))
            .body("Entities.collect { it.Mentions[0] }.find { it.Text == 'battery' }.MentionSentiment.Sentiment",
                    equalTo("NEGATIVE"));
        call("DetectToxicContent", """
                {"TextSegments":[{"Text":"You are a wonderful person."},{"Text":"You stupid idiot."}],"LanguageCode":"en"}""")
            .statusCode(200)
            .body("ResultList[0].Toxicity", lessThan(0.5f))
            .body("ResultList[1].Toxicity", greaterThan(0.5f))
            .body("ResultList[1].Labels.find { it.Name == 'INSULT' }.Score", greaterThan(0.5f));
        call("DetectDominantLanguage", "{\"Text\":\"El gato está en la casa y es muy bonito.\"}")
            .statusCode(200).body("Languages[0].LanguageCode", equalTo("es"));
    }

    @Test
    void batchesAreIndexAlignedAndReportBadDocuments() {
        call("BatchDetectSentiment", """
                {"TextList":["I love it!","","The delivery was late and the box arrived damaged."],"LanguageCode":"en"}""")
            .statusCode(200)
            .body("ResultList.Index", contains(0, 2))
            .body("ResultList[0].Sentiment", equalTo("POSITIVE"))
            .body("ResultList[1].Sentiment", equalTo("NEGATIVE"))
            .body("ErrorList[0].Index", equalTo(1));
        call("BatchDetectDominantLanguage", "{\"TextList\":[\"Hello there\",\"Hola amigo\"]}")
            .statusCode(200)
            .body("ResultList", hasSize(2));
        String tooMany = "[" + String.join(",", Collections.nCopies(26, "\"x\"")) + "]";
        call("BatchDetectKeyPhrases", "{\"TextList\":" + tooMany + ",\"LanguageCode\":\"en\"}")
            .statusCode(400)
            .body("__type", equalTo("BatchSizeLimitExceededException"));
    }

    @Test
    void classifyDocumentWithoutATrainedEndpointIsUnavailable() {
        call("ClassifyDocument", """
                {"Text":"invoice","EndpointArn":"arn:aws:comprehend:us-east-1:000000000000:document-classifier-endpoint/x"}""")
            .statusCode(400)
            .body("__type", equalTo("ResourceUnavailableException"));
    }

    @Test
    void aSentimentJobWritesItsOutputAndSettles() throws IOException {
        s3Service.createBucket("comprehend-job-test", "us-east-1");
        s3Service.putObject("comprehend-job-test", "in/reviews.txt",
                "I love this product!\nThe box arrived damaged.\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        String jobId = call("StartSentimentDetectionJob", """
                {"JobName":"reviews","LanguageCode":"en","DataAccessRoleArn":"%s",
                 "InputDataConfig":{"S3Uri":"s3://comprehend-job-test/in/","InputFormat":"ONE_DOC_PER_LINE"},
                 "OutputDataConfig":{"S3Uri":"s3://comprehend-job-test/out/"},
                 "Tags":[{"Key":"team","Value":"nlp"}]}""".formatted(ROLE))
            .statusCode(200)
            .body("JobStatus", equalTo("SUBMITTED"))
            .body("JobArn", containsString(":sentiment-detection-job/"))
            .extract().path("JobId");

        call("DescribeSentimentDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(200)
            .body("SentimentDetectionJobProperties.JobStatus", equalTo("SUBMITTED"))
            .body("SentimentDetectionJobProperties.JobName", equalTo("reviews"))
            .body("SentimentDetectionJobProperties.OutputDataConfig.S3Uri",
                    endsWith("-SENTIMENT-" + jobId + "/output/output.tar.gz"));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                call("DescribeSentimentDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
                    .statusCode(200)
                    .body("SentimentDetectionJobProperties.JobStatus", equalTo("COMPLETED")));

        List<S3Object> output = s3Service.listObjects("comprehend-job-test", "out/", null, 10);
        assertEquals(1, output.size());
        String lines = untar(s3Service.getObject("comprehend-job-test", output.getFirst().getKey()).getData());
        assertTrue(lines.contains("\"Line\":0") && lines.contains("\"Sentiment\":\"POSITIVE\""), lines);
        assertTrue(lines.contains("\"Line\":1") && lines.contains("\"Sentiment\":\"NEGATIVE\""), lines);

        call("ListSentimentDetectionJobs", "{\"Filter\":{\"JobName\":\"reviews\"}}")
            .statusCode(200)
            .body("SentimentDetectionJobPropertiesList.JobId", contains(jobId));
        String arn = call("DescribeSentimentDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .extract().path("SentimentDetectionJobProperties.JobArn");
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(200)
            .body("Tags.find { it.Key == 'team' }.Value", equalTo("nlp"));
        call("StopSentimentDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(200)
            .body("JobStatus", equalTo("COMPLETED"));
    }

    @Test
    void stoppingARunningJobAndEveryFamilysNotFoundAndValidation() {
        s3Service.createBucket("comprehend-stop-test", "us-east-1");
        s3Service.putObject("comprehend-stop-test", "in/a.txt", "Hello world".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        String jobId = call("StartEntitiesDetectionJob", """
                {"LanguageCode":"en","DataAccessRoleArn":"%s",
                 "InputDataConfig":{"S3Uri":"s3://comprehend-stop-test/in/"},
                 "OutputDataConfig":{"S3Uri":"s3://comprehend-stop-test/out/"}}""".formatted(ROLE))
            .statusCode(200).extract().path("JobId");
        call("StopEntitiesDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(200).body("JobStatus", equalTo("STOP_REQUESTED"));
        call("DescribeEntitiesDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(200).body("EntitiesDetectionJobProperties.JobStatus", equalTo("STOPPED"));
        // A job of one family isn't found through another's actions.
        call("DescribeSentimentDetectionJob", "{\"JobId\":\"" + jobId + "\"}")
            .statusCode(400).body("__type", equalTo("JobNotFoundException"));

        for (String family : Arrays.asList("DocumentClassificationJob", "DominantLanguageDetectionJob",
                "EntitiesDetectionJob", "EventsDetectionJob", "KeyPhrasesDetectionJob", "PiiEntitiesDetectionJob",
                "SentimentDetectionJob", "TargetedSentimentDetectionJob", "TopicsDetectionJob")) {
            call("Describe" + family, "{\"JobId\":\"00000000000000000000000000000000\"}")
                .statusCode(400).body("__type", equalTo("JobNotFoundException"));
            call("List" + family + "s", "{}").statusCode(200);
            call("Start" + family, """
                    {"LanguageCode":"en","DataAccessRoleArn":"%s","InputDataConfig":{"S3Uri":"invalid-uri"},
                     "OutputDataConfig":{"S3Uri":"invalid-uri"}}""".formatted(ROLE))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        }
        call("StopTopicsDetectionJob", "{\"JobId\":\"x\"}")
            .statusCode(400).body("__type", equalTo("UnknownOperationException"));
        call("StartDocumentClassificationJob", """
                {"DataAccessRoleArn":"%s","DocumentClassifierArn":"arn:aws:comprehend:us-east-1:000000000000:document-classifier/x",
                 "InputDataConfig":{"S3Uri":"s3://comprehend-stop-test/in/"},
                 "OutputDataConfig":{"S3Uri":"s3://comprehend-stop-test/out/"}}""".formatted(ROLE))
            .statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aPiiRedactionJobWritesRedactedDocuments() {
        s3Service.createBucket("comprehend-pii-test", "us-east-1");
        s3Service.putObject("comprehend-pii-test", "in/note.txt",
                "Email jane@example.com today.".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        call("StartPiiEntitiesDetectionJob", """
                {"LanguageCode":"en","DataAccessRoleArn":"%s","Mode":"ONLY_REDACTION",
                 "RedactionConfig":{"MaskMode":"REPLACE_WITH_PII_ENTITY_TYPE"},
                 "InputDataConfig":{"S3Uri":"s3://comprehend-pii-test/in/"},
                 "OutputDataConfig":{"S3Uri":"s3://comprehend-pii-test/out/"}}""".formatted(ROLE))
            .statusCode(200);
        List<S3Object> output = s3Service.listObjects("comprehend-pii-test", "out/", null, 10);
        assertEquals(1, output.size());
        assertEquals("Email [EMAIL] today.", new String(s3Service.getObject("comprehend-pii-test",
                output.getFirst().getKey()).getData(), StandardCharsets.UTF_8));
    }

    /** The content of the single file in a gzipped tar. */
    private static String untar(byte[] archive) throws IOException {
        byte[] tar = new GZIPInputStream(new ByteArrayInputStream(archive)).readAllBytes();
        String sizeField = new String(tar, 124, 11, StandardCharsets.US_ASCII).trim();
        int size = Integer.parseInt(sizeField, 8);
        return new String(tar, 512, size, StandardCharsets.UTF_8);
    }
}
