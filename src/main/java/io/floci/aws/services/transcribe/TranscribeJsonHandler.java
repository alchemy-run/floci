package io.floci.aws.services.transcribe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.services.transcribe.TranscribeResourceService.Kind;
import io.floci.aws.services.transcribe.model.TranscriptionJob;
import io.floci.aws.services.transcribe.model.VocabularyInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * JSON 1.1 handler for Amazon Transcribe API operations.
 * Dispatches X-Amz-Target: Transcribe.* actions: standard transcription jobs and custom
 * vocabularies to {@link TranscribeService}, every other family and tags to
 * {@link TranscribeResourceService}.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeJsonHandler {

    private static final Logger LOG = Logger.getLogger(TranscribeJsonHandler.class);

    private final TranscribeService transcribeService;
    private final TranscribeResourceService resources;
    private final ObjectMapper objectMapper;

    @Inject
    public TranscribeJsonHandler(TranscribeService transcribeService, TranscribeResourceService resources,
                                 ObjectMapper objectMapper) {
        this.transcribeService = transcribeService;
        this.resources = resources;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Transcribe action: {0}", action);
        return switch (action) {
            case "StartTranscriptionJob" -> {
                String name = getStringField(request, "TranscriptionJobName");
                String media = TranscribeResourceService.requireMediaUri(request);
                String failure = resources.mediaFailure(media);
                String bucket = getStringField(request, "OutputBucketName");
                String outputKey = getStringField(request, "OutputKey");
                String transcript = bucket == null
                        ? "s3://floci-transcribe-output/" + name + ".json"
                        : "s3://" + bucket + "/" + (outputKey == null ? name + ".json"
                                : outputKey.endsWith("/") ? outputKey + name + ".json" : outputKey);
                TranscriptionJob job = transcribeService.startTranscriptionJob(name, media,
                        getStringField(request, "LanguageCode"), getStringField(request, "MediaFormat"),
                        failure, transcript);
                if (failure == null && bucket != null) {
                    resources.write(transcript, resources.transcriptJson(name, media));
                }
                resources.putTags(resources.arnFor("transcription-job", region, name), request.get("Tags"));
                resources.publishTranscriptionJobEvent(name, job.transcriptionJobStatus(), failure, region);
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "GetTranscriptionJob" -> {
                TranscriptionJob job = transcribeService.getTranscriptionJob(
                        getStringField(request, "TranscriptionJobName"));
                yield Response.ok(Map.of("TranscriptionJob", job)).build();
            }
            case "ListTranscriptionJobs" -> {
                TranscribeService.ListTranscriptionJobsResult result = transcribeService.listTranscriptionJobs(
                        getStringField(request, "Status"),
                        getStringField(request, "JobNameContains"),
                        getStringField(request, "NextToken"),
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
                resources.deleteTags(resources.arnFor("transcription-job", region,
                        getStringField(request, "TranscriptionJobName")));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "CreateVocabulary" -> {
                VocabularyInfo vocab = transcribeService.createVocabulary(
                        getStringField(request, "VocabularyName"),
                        getStringField(request, "LanguageCode"),
                        vocabularyFailure(request));
                resources.putTags(resources.arnFor("vocabulary", region, vocab.vocabularyName()), request.get("Tags"));
                yield Response.ok(vocab).build();
            }
            case "UpdateVocabulary" -> {
                String failure = vocabularyFailure(request);
                yield Response.ok(transcribeService.updateVocabulary(getStringField(request, "VocabularyName"),
                        getStringField(request, "LanguageCode"), failure)).build();
            }
            case "GetVocabulary" -> {
                VocabularyInfo vocab = transcribeService.getVocabulary(
                        getStringField(request, "VocabularyName"));
                yield Response.ok(vocab).build();
            }
            case "ListVocabularies" -> {
                TranscribeService.ListVocabulariesResult result = transcribeService.listVocabularies(
                        getStringField(request, "StateEquals"),
                        getStringField(request, "NameContains"),
                        getStringField(request, "NextToken"),
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
                resources.deleteTags(resources.arnFor("vocabulary", region, getStringField(request, "VocabularyName")));
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "StartCallAnalyticsJob" -> ok(resources.startCallAnalyticsJob(request, region));
            case "GetCallAnalyticsJob" -> ok(resources.getJob(Kind.CALL_ANALYTICS_JOB, "CallAnalyticsJobName",
                    "CallAnalyticsJob", request, region));
            case "ListCallAnalyticsJobs" -> ok(resources.listJobs(Kind.CALL_ANALYTICS_JOB, "CallAnalyticsJobName",
                    "CallAnalyticsJobStatus", "CallAnalyticsJobSummaries", request, region));
            case "DeleteCallAnalyticsJob" -> ok(resources.deleteJob(Kind.CALL_ANALYTICS_JOB, "CallAnalyticsJobName",
                    request, region));
            case "StartMedicalTranscriptionJob" -> ok(resources.startMedicalTranscriptionJob(request, region));
            case "GetMedicalTranscriptionJob" -> ok(resources.getJob(Kind.MEDICAL_JOB, "MedicalTranscriptionJobName",
                    "MedicalTranscriptionJob", request, region));
            case "ListMedicalTranscriptionJobs" -> ok(resources.listJobs(Kind.MEDICAL_JOB,
                    "MedicalTranscriptionJobName", "TranscriptionJobStatus", "MedicalTranscriptionJobSummaries",
                    request, region));
            case "DeleteMedicalTranscriptionJob" -> ok(resources.deleteJob(Kind.MEDICAL_JOB,
                    "MedicalTranscriptionJobName", request, region));
            case "StartMedicalScribeJob" -> ok(resources.startMedicalScribeJob(request, region));
            case "GetMedicalScribeJob" -> ok(resources.getJob(Kind.SCRIBE_JOB, "MedicalScribeJobName",
                    "MedicalScribeJob", request, region));
            case "ListMedicalScribeJobs" -> ok(resources.listJobs(Kind.SCRIBE_JOB, "MedicalScribeJobName",
                    "MedicalScribeJobStatus", "MedicalScribeJobSummaries", request, region));
            case "DeleteMedicalScribeJob" -> ok(resources.deleteJob(Kind.SCRIBE_JOB, "MedicalScribeJobName",
                    request, region));
            case "CreateMedicalVocabulary" -> ok(resources.createMedicalVocabulary(request, region));
            case "GetMedicalVocabulary" -> ok(resources.getMedicalVocabulary(request, region));
            case "UpdateMedicalVocabulary" -> ok(resources.updateMedicalVocabulary(request, region));
            case "DeleteMedicalVocabulary" -> ok(resources.deleteMedicalVocabulary(request, region));
            case "ListMedicalVocabularies" -> ok(resources.listMedicalVocabularies(request, region));
            case "CreateVocabularyFilter" -> ok(resources.createVocabularyFilter(request, region));
            case "GetVocabularyFilter" -> ok(resources.getVocabularyFilter(request, region));
            case "UpdateVocabularyFilter" -> ok(resources.updateVocabularyFilter(request, region));
            case "DeleteVocabularyFilter" -> ok(resources.deleteVocabularyFilter(request, region));
            case "ListVocabularyFilters" -> ok(resources.listVocabularyFilters(request, region));
            case "CreateCallAnalyticsCategory" -> ok(resources.createCallAnalyticsCategory(request, region));
            case "GetCallAnalyticsCategory" -> ok(resources.getCallAnalyticsCategory(request, region));
            case "UpdateCallAnalyticsCategory" -> ok(resources.updateCallAnalyticsCategory(request, region));
            case "DeleteCallAnalyticsCategory" -> ok(resources.deleteCallAnalyticsCategory(request, region));
            case "ListCallAnalyticsCategories" -> ok(resources.listCallAnalyticsCategories(request, region));
            case "CreateLanguageModel" -> ok(resources.createLanguageModel(request, region));
            case "DescribeLanguageModel" -> ok(resources.describeLanguageModel(request, region));
            case "DeleteLanguageModel" -> ok(resources.deleteLanguageModel(request, region));
            case "ListLanguageModels" -> ok(resources.listLanguageModels(request, region));
            case "TagResource" -> ok(resources.tagResource(request, transcribeService::exists));
            case "UntagResource" -> ok(resources.untagResource(request, transcribeService::exists));
            case "ListTagsForResource" -> ok(resources.listTagsForResource(request, transcribeService::exists));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Transcribe." + action))
                    .build();
        };
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    /**
     * Why a custom vocabulary's terms can't be read, or null: Phrases are taken as given,
     * and a VocabularyFileUri must match Transcribe's pattern and name a readable object.
     */
    private String vocabularyFailure(JsonNode request) {
        if (request.hasNonNull("VocabularyFileUri")) {
            return resources.mediaFailure(TranscribeResourceService.requireS3FileUri(request, "VocabularyFileUri"));
        }
        JsonNode phrases = request.get("Phrases");
        if (phrases == null || !phrases.isArray() || phrases.isEmpty()) {
            throw TranscribeResourceService.badRequest("Specify either Phrases or VocabularyFileUri.");
        }
        return null;
    }

    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private Integer getIntField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asInt() : null;
    }
}
