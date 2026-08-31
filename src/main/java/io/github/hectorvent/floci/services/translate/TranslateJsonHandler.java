package io.github.hectorvent.floci.services.translate;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Translate.
 * Dispatches X-Amz-Target: AWSShineFrontendService_20170701.* actions to {@link TranslateService}.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/API_Operations.html">Translate API</a>
 */
@ApplicationScoped
public class TranslateJsonHandler {

    private static final Logger LOG = Logger.getLogger(TranslateJsonHandler.class);

    private final TranslateService translateService;

    @Inject
    public TranslateJsonHandler(TranslateService translateService) {
        this.translateService = translateService;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Translate action: {0}", action);
        JsonNode body = request;
        return switch (action) {
            case "TranslateText" -> ok(translateService.translateText(body, region));
            case "TranslateDocument" -> ok(translateService.translateDocument(body, region));
            case "ListLanguages" -> ok(translateService.listLanguages(body));
            case "ImportTerminology" -> ok(translateService.importTerminology(body, region));
            case "GetTerminology" -> ok(translateService.getTerminology(body, region));
            case "ListTerminologies" -> ok(translateService.listTerminologies(body));
            case "DeleteTerminology" -> ok(translateService.deleteTerminology(body));
            case "CreateParallelData" -> ok(translateService.createParallelData(body, region));
            case "UpdateParallelData" -> ok(translateService.updateParallelData(body, region));
            case "GetParallelData" -> ok(translateService.getParallelData(body));
            case "ListParallelData" -> ok(translateService.listParallelData(body));
            case "DeleteParallelData" -> ok(translateService.deleteParallelData(body));
            case "StartTextTranslationJob" -> ok(translateService.startTextTranslationJob(body, region));
            case "DescribeTextTranslationJob" -> ok(translateService.describeTextTranslationJob(body));
            case "ListTextTranslationJobs" -> ok(translateService.listTextTranslationJobs(body));
            case "StopTextTranslationJob" -> ok(translateService.stopTextTranslationJob(body));
            case "TagResource" -> ok(translateService.tagResource(body));
            case "UntagResource" -> ok(translateService.untagResource(body));
            case "ListTagsForResource" -> ok(translateService.listTagsForResource(body));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSShineFrontendService_20170701." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
