package io.github.hectorvent.floci.services.comprehend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.comprehend.ComprehendService.JobFamily;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Comprehend.
 * Dispatches X-Amz-Target: Comprehend_20171127.* actions to {@link ComprehendService}.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API</a>
 */
@ApplicationScoped
public class ComprehendJsonHandler {

    private static final Logger LOG = Logger.getLogger(ComprehendJsonHandler.class);

    private final ComprehendService comprehendService;

    @Inject
    public ComprehendJsonHandler(ComprehendService comprehendService) {
        this.comprehendService = comprehendService;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Comprehend action: {0}", action);
        return switch (action) {
            case "DetectDominantLanguage" -> ok(comprehendService.detectDominantLanguage(request));
            case "DetectEntities" -> ok(comprehendService.detectEntities(request));
            case "DetectKeyPhrases" -> ok(comprehendService.detectKeyPhrases(request));
            case "DetectPiiEntities" -> ok(comprehendService.detectPiiEntities(request));
            case "DetectSentiment" -> ok(comprehendService.detectSentiment(request));
            case "DetectSyntax" -> ok(comprehendService.detectSyntax(request));
            case "DetectTargetedSentiment" -> ok(comprehendService.detectTargetedSentiment(request));
            case "DetectToxicContent" -> ok(comprehendService.detectToxicContent(request));
            case "ContainsPiiEntities" -> ok(comprehendService.containsPiiEntities(request));
            case "ClassifyDocument" -> ok(comprehendService.classifyDocument(request));
            case "BatchDetectDominantLanguage" -> ok(comprehendService.batchDetectDominantLanguage(request));
            case "BatchDetectEntities" -> ok(comprehendService.batchDetectEntities(request));
            case "BatchDetectKeyPhrases" -> ok(comprehendService.batchDetectKeyPhrases(request));
            case "BatchDetectSentiment" -> ok(comprehendService.batchDetectSentiment(request));
            case "BatchDetectSyntax" -> ok(comprehendService.batchDetectSyntax(request));
            case "BatchDetectTargetedSentiment" -> ok(comprehendService.batchDetectTargetedSentiment(request));
            case "StartDocumentClassificationJob" ->
                    ok(comprehendService.startJob(JobFamily.DOCUMENT_CLASSIFICATION, request));
            case "StartDominantLanguageDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.DOMINANT_LANGUAGE, request));
            case "StartEntitiesDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.ENTITIES, request));
            case "StartEventsDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.EVENTS, request));
            case "StartKeyPhrasesDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.KEY_PHRASES, request));
            case "StartPiiEntitiesDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.PII_ENTITIES, request));
            case "StartSentimentDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.SENTIMENT, request));
            case "StartTargetedSentimentDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.TARGETED_SENTIMENT, request));
            case "StartTopicsDetectionJob" ->
                    ok(comprehendService.startJob(JobFamily.TOPICS, request));
            case "DescribeDocumentClassificationJob" ->
                    ok(comprehendService.describeJob(JobFamily.DOCUMENT_CLASSIFICATION, request));
            case "DescribeDominantLanguageDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.DOMINANT_LANGUAGE, request));
            case "DescribeEntitiesDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.ENTITIES, request));
            case "DescribeEventsDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.EVENTS, request));
            case "DescribeKeyPhrasesDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.KEY_PHRASES, request));
            case "DescribePiiEntitiesDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.PII_ENTITIES, request));
            case "DescribeSentimentDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.SENTIMENT, request));
            case "DescribeTargetedSentimentDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.TARGETED_SENTIMENT, request));
            case "DescribeTopicsDetectionJob" ->
                    ok(comprehendService.describeJob(JobFamily.TOPICS, request));
            case "ListDocumentClassificationJobs" ->
                    ok(comprehendService.listJobs(JobFamily.DOCUMENT_CLASSIFICATION));
            case "ListDominantLanguageDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.DOMINANT_LANGUAGE));
            case "ListEntitiesDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.ENTITIES));
            case "ListEventsDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.EVENTS));
            case "ListKeyPhrasesDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.KEY_PHRASES));
            case "ListPiiEntitiesDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.PII_ENTITIES));
            case "ListSentimentDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.SENTIMENT));
            case "ListTargetedSentimentDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.TARGETED_SENTIMENT));
            case "ListTopicsDetectionJobs" ->
                    ok(comprehendService.listJobs(JobFamily.TOPICS));
            case "StopDominantLanguageDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.DOMINANT_LANGUAGE, request));
            case "StopEntitiesDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.ENTITIES, request));
            case "StopEventsDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.EVENTS, request));
            case "StopKeyPhrasesDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.KEY_PHRASES, request));
            case "StopPiiEntitiesDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.PII_ENTITIES, request));
            case "StopSentimentDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.SENTIMENT, request));
            case "StopTargetedSentimentDetectionJob" ->
                    ok(comprehendService.stopJob(JobFamily.TARGETED_SENTIMENT, request));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Comprehend_20171127." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
