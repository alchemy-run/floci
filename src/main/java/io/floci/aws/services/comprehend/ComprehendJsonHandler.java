package io.floci.aws.services.comprehend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.services.comprehend.ComprehendJobService.Family;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Comprehend API operations.
 * Dispatches X-Amz-Target: Comprehend_20171127.* actions: real-time analysis
 * to {@link ComprehendService}, and the asynchronous jobs of every family and
 * their tags to {@link ComprehendJobService}. Custom models (document
 * classifiers, entity recognizers, flywheels, endpoints) are not emulated.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API Reference</a>
 */
@ApplicationScoped
public class ComprehendJsonHandler {
    private static final Logger LOG = Logger.getLogger(ComprehendJsonHandler.class);
    private final ComprehendService comprehendService;
    private final ComprehendJobService jobs;

    @Inject
    public ComprehendJsonHandler(ComprehendService comprehendService, ComprehendJobService jobs) {
        this.comprehendService = comprehendService;
        this.jobs = jobs;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Comprehend action: {0}", action);
        ObjectNode result = switch (action) {
            case "DetectDominantLanguage" -> comprehendService.detectDominantLanguage(request);
            case "DetectEntities" -> comprehendService.detectEntities(request);
            case "DetectKeyPhrases" -> comprehendService.detectKeyPhrases(request);
            case "DetectPiiEntities" -> comprehendService.detectPiiEntities(request);
            case "ContainsPiiEntities" -> comprehendService.containsPiiEntities(request);
            case "DetectSentiment" -> comprehendService.detectSentiment(request);
            case "DetectSyntax" -> comprehendService.detectSyntax(request);
            case "DetectTargetedSentiment" -> comprehendService.detectTargetedSentiment(request);
            case "DetectToxicContent" -> comprehendService.detectToxicContent(request);
            case "ClassifyDocument" -> comprehendService.classifyDocument(request);
            case "BatchDetectDominantLanguage" -> comprehendService.batchDetectDominantLanguage(request);
            case "BatchDetectEntities" -> comprehendService.batchDetectEntities(request);
            case "BatchDetectKeyPhrases" -> comprehendService.batchDetectKeyPhrases(request);
            case "BatchDetectSentiment" -> comprehendService.batchDetectSentiment(request);
            case "BatchDetectSyntax" -> comprehendService.batchDetectSyntax(request);
            case "BatchDetectTargetedSentiment" -> comprehendService.batchDetectTargetedSentiment(request);
            case "TagResource" -> jobs.tagResource(request);
            case "UntagResource" -> jobs.untagResource(request);
            case "ListTagsForResource" -> jobs.listTagsForResource(request);
            case "StartDocumentClassificationJob", "StartDominantLanguageDetectionJob",
                    "StartEntitiesDetectionJob", "StartEventsDetectionJob", "StartKeyPhrasesDetectionJob",
                    "StartPiiEntitiesDetectionJob", "StartSentimentDetectionJob",
                    "StartTargetedSentimentDetectionJob", "StartTopicsDetectionJob"
                    -> jobs.start(Family.of(action), request, region);
            case "DescribeDocumentClassificationJob", "DescribeDominantLanguageDetectionJob",
                    "DescribeEntitiesDetectionJob", "DescribeEventsDetectionJob",
                    "DescribeKeyPhrasesDetectionJob", "DescribePiiEntitiesDetectionJob",
                    "DescribeSentimentDetectionJob", "DescribeTargetedSentimentDetectionJob",
                    "DescribeTopicsDetectionJob"
                    -> jobs.describe(Family.of(action), request);
            case "ListDocumentClassificationJobs", "ListDominantLanguageDetectionJobs",
                    "ListEntitiesDetectionJobs", "ListEventsDetectionJobs", "ListKeyPhrasesDetectionJobs",
                    "ListPiiEntitiesDetectionJobs", "ListSentimentDetectionJobs",
                    "ListTargetedSentimentDetectionJobs", "ListTopicsDetectionJobs"
                    -> jobs.list(Family.of(action), request, region);
            case "StopDominantLanguageDetectionJob", "StopEntitiesDetectionJob", "StopEventsDetectionJob",
                    "StopKeyPhrasesDetectionJob", "StopPiiEntitiesDetectionJob", "StopSentimentDetectionJob",
                    "StopTargetedSentimentDetectionJob"
                    -> jobs.stop(Family.of(action), request);
            default -> null;
        };
        if (result == null) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Comprehend_20171127." + action))
                    .build();
        }
        return Response.ok(result).build();
    }
}
