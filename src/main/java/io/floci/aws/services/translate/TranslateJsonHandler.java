package io.floci.aws.services.translate;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
/**
 * JSON 1.1 handler for Amazon Translate API operations.
 * Dispatches X-Amz-Target: AWSShineFrontendService_20170701.* actions to {@link TranslateService}.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html">Translate API Reference</a>
 */
@ApplicationScoped
public class TranslateJsonHandler {
    private static final Logger LOG = Logger.getLogger(TranslateJsonHandler.class);
    private final TranslateService translateService;
    private final TranslateResourceService resources;
    @Inject
    public TranslateJsonHandler(TranslateService translateService, TranslateResourceService resources) {
        this.translateService = translateService;
        this.resources = resources;
    }
    /**
     * Dispatches Translate actions received via the AwsJson11Controller: the stateless
     * translation actions to {@link TranslateService}, and terminologies, parallel data,
     * batch jobs, and tags to {@link TranslateResourceService}.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Translate action: {0}", action);
        return switch (action) {
            case "TranslateText" -> translateService.translateText(
                    getStringField(request, "Text"),
                    getStringField(request, "SourceLanguageCode"),
                    getStringField(request, "TargetLanguageCode"),
                    getStringList(request, "TerminologyNames"), resources, region);
            case "TranslateDocument" -> translateService.translateDocument(
                    getNestedStringField(request, "Document", "Content"),
                    getNestedStringField(request, "Document", "ContentType"),
                    getStringField(request, "SourceLanguageCode"),
                    getStringField(request, "TargetLanguageCode"));
            case "ListLanguages" -> translateService.listLanguages(
                    getStringField(request, "DisplayLanguageCode"));
            case "ImportTerminology" -> ok(resources.importTerminology(request, region));
            case "GetTerminology" -> ok(resources.getTerminology(request, region));
            case "ListTerminologies" -> ok(resources.listTerminologies(region));
            case "DeleteTerminology" -> ok(resources.deleteTerminology(request, region));
            case "CreateParallelData" -> ok(resources.createParallelData(request, region));
            case "GetParallelData" -> ok(resources.getParallelData(request, region));
            case "UpdateParallelData" -> ok(resources.updateParallelData(request, region));
            case "DeleteParallelData" -> ok(resources.deleteParallelData(request, region));
            case "ListParallelData" -> ok(resources.listParallelData(region));
            case "StartTextTranslationJob" -> ok(resources.startTextTranslationJob(request, region, translateService));
            case "DescribeTextTranslationJob" -> ok(resources.describeTextTranslationJob(request, region));
            case "ListTextTranslationJobs" -> ok(resources.listTextTranslationJobs(request, region));
            case "StopTextTranslationJob" -> ok(resources.stopTextTranslationJob(request, region));
            case "TagResource" -> ok(resources.tagResource(request));
            case "UntagResource" -> ok(resources.untagResource(request));
            case "ListTagsForResource" -> ok(resources.listTagsForResource(request));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSShineFrontendService_20170701." + action))
                    .build();
        };
    }
    /**
     * Extracts a string-typed field. A present-but-wrong-typed value is a shape/marshalling
     * mismatch caught at the protocol layer before operation-specific validation, matching
     * {@link ComprehendJsonHandler}. It must not be silently coerced, nor conflated with a
     * missing field (a modeled business error handled in {@link TranslateService}).
     */
    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a string.", 400);
        }
        return value.asText();
    }
    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    /** A list-of-strings field, or an empty list when it is absent. */
    private List<String> getStringList(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        List<String> values = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    /** {@link #getStringField} for a field nested one level under {@code parent} (e.g. Document.Content). */
    private String getNestedStringField(JsonNode node, String parent, String field) {
        JsonNode parentNode = node == null ? null : node.get(parent);
        if (parentNode == null || parentNode.isNull()) {
            return null;
        }
        if (!parentNode.isObject()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + parent + "' is not a structure.", 400);
        }
        return getStringField(parentNode, field);
    }
}
