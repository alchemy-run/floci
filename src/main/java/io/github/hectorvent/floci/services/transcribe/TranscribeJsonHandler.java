package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJob;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * JSON 1.1 handler for Amazon Transcribe API operations.
 * Dispatches X-Amz-Target: Transcribe.* actions to {@link TranscribeService}.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeJsonHandler {

    private static final Logger LOG = Logger.getLogger(TranscribeJsonHandler.class);

    private final TranscribeService transcribeService;
    private final ObjectMapper objectMapper;

    @Inject
    public TranscribeJsonHandler(TranscribeService transcribeService, ObjectMapper objectMapper) {
        this.transcribeService = transcribeService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Transcribe action: {0}", action);
        return switch (action) {
            case "StartTranscriptionJob" -> {
                TranscriptionJob job = transcribeService.startTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"),
                        getMediaFileUri(request),
                        getStringField(request, "LanguageCode"),
                        getStringField(request, "MediaFormat"));
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "GetTranscriptionJob" -> {
                TranscriptionJob job = transcribeService.getTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"));
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "ListTranscriptionJobs" -> {
                var result = transcribeService.listTranscriptionJobs(
                        getStringField(request, "Status"),
                        getStringField(request, "JobNameContains"),
                        getIntField(request, "MaxResults"));
                ObjectNode root = objectMapper.createObjectNode();
                if (result.status() != null) {
                    root.put("Status", result.status());
                }
                root.set("TranscriptionJobSummaries",
                        objectMapper.valueToTree(result.summaries()));
                if (result.nextToken() != null) {
                    root.put("NextToken", result.nextToken());
                }
                yield Response.ok(root).build();
            }
            case "DeleteTranscriptionJob" -> {
                transcribeService.deleteTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "CreateVocabulary" -> {
                VocabularyInfo vocab = transcribeService.createVocabulary(
                        getStringField(request, "VocabularyName"),
                        getStringField(request, "LanguageCode"),
                        getStringField(request, "VocabularyFileUri"));
                yield Response.ok(vocab).build();
            }
            case "GetVocabulary" -> {
                VocabularyInfo vocab = transcribeService.getVocabulary(
                        getStringField(request, "VocabularyName"));
                yield Response.ok(vocab).build();
            }
            case "UpdateVocabulary" -> Response.ok(transcribeService.updateVocabulary(
                    getStringField(request, "VocabularyName"),
                    getStringField(request, "LanguageCode"),
                    getStringField(request, "VocabularyFileUri"))).build();
            case "ListVocabularies" -> {
                var result = transcribeService.listVocabularies(
                        getStringField(request, "StateEquals"),
                        getStringField(request, "NameContains"),
                        getIntField(request, "MaxResults"));
                ObjectNode root = objectMapper.createObjectNode();
                if (result.status() != null) {
                    root.put("Status", result.status());
                }
                root.set("Vocabularies", objectMapper.valueToTree(result.vocabularies()));
                if (result.nextToken() != null) {
                    root.put("NextToken", result.nextToken());
                }
                yield Response.ok(root).build();
            }
            case "DeleteVocabulary" -> {
                transcribeService.deleteVocabulary(
                        getStringField(request, "VocabularyName"));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "StartCallAnalyticsJob" -> Response.ok(transcribeService.startCallAnalyticsJob(request)).build();
            case "GetCallAnalyticsJob" -> Response.ok(transcribeService.getCallAnalyticsJob(request)).build();
            case "ListCallAnalyticsJobs" -> Response.ok(transcribeService.listCallAnalyticsJobs(request)).build();
            case "DeleteCallAnalyticsJob" -> Response.ok(transcribeService.deleteCallAnalyticsJob(request)).build();
            case "StartMedicalTranscriptionJob" ->
                    Response.ok(transcribeService.startMedicalTranscriptionJob(request)).build();
            case "GetMedicalTranscriptionJob" ->
                    Response.ok(transcribeService.getMedicalTranscriptionJob(request)).build();
            case "ListMedicalTranscriptionJobs" ->
                    Response.ok(transcribeService.listMedicalTranscriptionJobs(request)).build();
            case "DeleteMedicalTranscriptionJob" ->
                    Response.ok(transcribeService.deleteMedicalTranscriptionJob(request)).build();
            case "StartMedicalScribeJob" -> Response.ok(transcribeService.startMedicalScribeJob(request)).build();
            case "GetMedicalScribeJob" -> Response.ok(transcribeService.getMedicalScribeJob(request)).build();
            case "ListMedicalScribeJobs" -> Response.ok(transcribeService.listMedicalScribeJobs(request)).build();
            case "DeleteMedicalScribeJob" -> Response.ok(transcribeService.deleteMedicalScribeJob(request)).build();
            case "CreateMedicalVocabulary" -> Response.ok(transcribeService.createMedicalVocabulary(request)).build();
            case "GetMedicalVocabulary" -> Response.ok(transcribeService.getMedicalVocabulary(request)).build();
            case "UpdateMedicalVocabulary" -> Response.ok(transcribeService.updateMedicalVocabulary(request)).build();
            case "ListMedicalVocabularies" -> Response.ok(transcribeService.listMedicalVocabularies(request)).build();
            case "DeleteMedicalVocabulary" -> Response.ok(transcribeService.deleteMedicalVocabulary(request)).build();
            case "CreateVocabularyFilter" -> Response.ok(transcribeService.createVocabularyFilter(request)).build();
            case "GetVocabularyFilter" -> Response.ok(transcribeService.getVocabularyFilter(request)).build();
            case "UpdateVocabularyFilter" -> Response.ok(transcribeService.updateVocabularyFilter(request)).build();
            case "DeleteVocabularyFilter" -> Response.ok(transcribeService.deleteVocabularyFilter(request)).build();
            case "ListVocabularyFilters" -> Response.ok(transcribeService.listVocabularyFilters(request)).build();
            case "CreateCallAnalyticsCategory" ->
                    Response.ok(transcribeService.createCallAnalyticsCategory(request)).build();
            case "GetCallAnalyticsCategory" ->
                    Response.ok(transcribeService.getCallAnalyticsCategory(request)).build();
            case "UpdateCallAnalyticsCategory" ->
                    Response.ok(transcribeService.updateCallAnalyticsCategory(request)).build();
            case "DeleteCallAnalyticsCategory" ->
                    Response.ok(transcribeService.deleteCallAnalyticsCategory(request)).build();
            case "ListCallAnalyticsCategories" ->
                    Response.ok(transcribeService.listCallAnalyticsCategories(request)).build();
            case "CreateLanguageModel" -> Response.ok(transcribeService.createLanguageModel(request)).build();
            case "DescribeLanguageModel" -> Response.ok(transcribeService.describeLanguageModel(request)).build();
            case "ListLanguageModels" -> Response.ok(transcribeService.listLanguageModels(request)).build();
            case "DeleteLanguageModel" -> Response.ok(transcribeService.deleteLanguageModel(request)).build();
            case "TagResource" -> Response.ok(transcribeService.tagResource(request)).build();
            case "UntagResource" -> Response.ok(transcribeService.untagResource(request)).build();
            case "ListTagsForResource" -> Response.ok(transcribeService.listTagsForResource(request)).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Transcribe." + action))
                    .build();
        };
    }

    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private String getMediaFileUri(JsonNode request) {
        if (request == null) return null;
        JsonNode media = request.path("Media");
        if (media.isMissingNode()) return null;
        JsonNode uri = media.get("MediaFileUri");
        return (uri != null && !uri.isNull()) ? uri.asText() : null;
    }

    private Integer getIntField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asInt() : null;
    }
}
