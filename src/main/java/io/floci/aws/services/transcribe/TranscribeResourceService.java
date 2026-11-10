package io.floci.aws.services.transcribe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Transcribe families beyond standard transcription jobs and custom
 * vocabularies: call analytics, medical, and medical scribe jobs; medical
 * vocabularies; vocabulary filters; call analytics categories; custom
 * language models; and tags on all of them.
 * <p>
 * Floci runs no speech recognition. A job reads its media from S3 when it
 * starts and finishes at once: {@code COMPLETED} with a transcript (empty,
 * unless {@code AI_MOCK_CONFIG} names the media URI) written to the output
 * bucket, or {@code FAILED} with the reason when the media can't be read.
 * A vocabulary or language model whose input file can't be read is
 * {@code FAILED} the same way. Errors follow Transcribe: a missing job or
 * vocabulary is {@code BadRequestException}, a missing filter, category, or
 * language model {@code NotFoundException}, a value that breaks the model's
 * pattern {@code BadRequestException}.
 */
@ApplicationScoped
public class TranscribeResourceService {
    static final Pattern MEDIA_URI = Pattern.compile("^(s3://|http(s*)://).+$");
    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)/(.*)$");
    private static final Pattern NAME = Pattern.compile("^[0-9a-zA-Z._-]+$");
    private static final String JOB_NOT_FOUND =
            "The requested job couldn't be found. Check the job name and try your request again.";

    /** A stored kind: its storage file, ARN resource type, and how a missing one is reported. */
    enum Kind {
        CALL_ANALYTICS_JOB("call-analytics-job", "BadRequestException", JOB_NOT_FOUND),
        MEDICAL_JOB("medical-transcription-job", "BadRequestException", JOB_NOT_FOUND),
        SCRIBE_JOB("medical-scribe-job", "BadRequestException", JOB_NOT_FOUND),
        MEDICAL_VOCABULARY("medical-vocabulary", "BadRequestException",
                "The requested vocabulary couldn't be found. Check the vocabulary name and try your request again."),
        VOCABULARY_FILTER("vocabulary-filter", "NotFoundException",
                "The requested vocabulary filter couldn't be found. Check the name and try your request again."),
        CATEGORY("call-analytics-category", "NotFoundException",
                "The requested category couldn't be found. Check the name and try your request again."),
        LANGUAGE_MODEL("language-model", "NotFoundException",
                "The requested language model couldn't be found. Check the name and try your request again.");

        final String arnType;
        final String notFoundCode;
        final String notFoundMessage;

        Kind(String arnType, String notFoundCode, String notFoundMessage) {
            this.arnType = arnType;
            this.notFoundCode = notFoundCode;
            this.notFoundMessage = notFoundMessage;
        }
    }

    private final Map<Kind, StorageBackend<String, ObjectNode>> stores = new EnumMap<>(Kind.class);
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final EventBridgeService eventBridge;
    private final AiMockConfigLoader mocks;
    private final ObjectMapper objectMapper;

    @Inject
    public TranscribeResourceService(StorageFactory storageFactory, RegionResolver regionResolver,
                                     S3Service s3Service, EventBridgeService eventBridge,
                                     AiMockConfigLoader mocks, ObjectMapper objectMapper) {
        for (Kind kind : Kind.values()) {
            stores.put(kind, storageFactory.create("transcribe", "transcribe-" + kind.arnType + "s.json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
        }
        this.tags = storageFactory.create("transcribe", "transcribe-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.eventBridge = eventBridge;
        this.mocks = mocks;
        this.objectMapper = objectMapper;
    }

    // ── Jobs ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode startCallAnalyticsJob(JsonNode request, String region) {
        String name = requireName(request, "CallAnalyticsJobName");
        requireAbsent(Kind.CALL_ANALYTICS_JOB, region, name, "job name");
        String media = requireMediaUri(request);
        ObjectNode job = baseJob("CallAnalyticsJobName", name, request);
        job.put("CallAnalyticsJobStatus", "IN_PROGRESS");
        copy(request, job, "DataAccessRoleArn", "Settings", "ChannelDefinitions");
        finish(job, "CallAnalyticsJobStatus", media, text(request, "OutputLocation"), name,
                text(request, "LanguageCode"));
        put(Kind.CALL_ANALYTICS_JOB, region, name, job);
        putTags(arn(Kind.CALL_ANALYTICS_JOB, region, name), request.get("Tags"));
        return wrap("CallAnalyticsJob", job);
    }

    public synchronized ObjectNode startMedicalTranscriptionJob(JsonNode request, String region) {
        String name = requireName(request, "MedicalTranscriptionJobName");
        requireAbsent(Kind.MEDICAL_JOB, region, name, "job name");
        String media = requireMediaUri(request);
        String language = requireText(request, "LanguageCode");
        if (!"en-US".equals(language)) {
            throw badRequest("Medical transcription supports only en-US.");
        }
        String bucket = requireText(request, "OutputBucketName");
        requireText(request, "Specialty");
        requireText(request, "Type");
        ObjectNode job = baseJob("MedicalTranscriptionJobName", name, request);
        job.put("TranscriptionJobStatus", "IN_PROGRESS");
        job.put("LanguageCode", language);
        copy(request, job, "Specialty", "Type", "Settings", "ContentIdentificationType", "MediaFormat",
                "MediaSampleRateHertz");
        String key = text(request, "OutputKey");
        finish(job, "TranscriptionJobStatus", media, "s3://" + bucket + "/" + (key == null ? "" : key), name,
                language);
        put(Kind.MEDICAL_JOB, region, name, job);
        putTags(arn(Kind.MEDICAL_JOB, region, name), request.get("Tags"));
        return wrap("MedicalTranscriptionJob", job);
    }

    public synchronized ObjectNode startMedicalScribeJob(JsonNode request, String region) {
        String name = requireName(request, "MedicalScribeJobName");
        requireAbsent(Kind.SCRIBE_JOB, region, name, "job name");
        String media = requireMediaUri(request);
        String bucket = requireText(request, "OutputBucketName");
        if (!request.path("Settings").isObject()) {
            throw badRequest("Settings is a required field.");
        }
        ObjectNode job = baseJob("MedicalScribeJobName", name, request);
        job.put("MedicalScribeJobStatus", "IN_PROGRESS");
        job.put("LanguageCode", "en-US");
        copy(request, job, "Settings", "DataAccessRoleArn", "ChannelDefinitions");
        String failure = mediaFailure(media);
        job.put("CompletionTime", now());
        if (failure != null) {
            job.put("MedicalScribeJobStatus", "FAILED");
            job.put("FailureReason", failure);
        } else {
            job.put("MedicalScribeJobStatus", "COMPLETED");
            String prefix = "s3://" + bucket + "/" + name + "/";
            write(prefix + "transcript.json", transcriptJson(name, media));
            write(prefix + "summary.json", "{\"ClinicalDocumentation\":{\"Sections\":[]}}");
            job.putObject("MedicalScribeOutput")
                    .put("TranscriptFileUri", prefix + "transcript.json")
                    .put("ClinicalDocumentUri", prefix + "summary.json");
        }
        put(Kind.SCRIBE_JOB, region, name, job);
        putTags(arn(Kind.SCRIBE_JOB, region, name), request.get("Tags"));
        return wrap("MedicalScribeJob", job);
    }

    public ObjectNode getJob(Kind kind, String nameField, String wrapper, JsonNode request, String region) {
        return wrap(wrapper, require(kind, region, requireText(request, nameField)));
    }

    public synchronized ObjectNode deleteJob(Kind kind, String nameField, JsonNode request, String region) {
        String name = requireText(request, nameField);
        require(kind, region, name);
        stores.get(kind).delete(key(region, name));
        tags.delete(arn(kind, region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listJobs(Kind kind, String nameField, String statusField, String summariesKey,
                               JsonNode request, String region) {
        String status = text(request, "Status");
        String contains = text(request, "JobNameContains");
        List<ObjectNode> jobs = scan(kind, region).stream()
                .filter(job -> status == null || status.equals(job.path(statusField).asText()))
                .filter(job -> contains == null || job.path(nameField).asText().toLowerCase()
                        .contains(contains.toLowerCase()))
                .sorted(Comparator.comparingDouble((ObjectNode job) -> job.path("CreationTime").asDouble()).reversed())
                .toList();
        Page page = page(jobs.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        if (status != null) {
            response.put("Status", status);
        }
        ArrayNode list = response.putArray(summariesKey);
        for (ObjectNode job : jobs.subList(page.start(), page.end())) {
            ObjectNode summary = job.deepCopy();
            summary.remove(List.of("Media", "Settings", "Tags", "ChannelDefinitions", "DataAccessRoleArn",
                    "Transcript", "MedicalScribeOutput"));
            list.add(summary);
        }
        page.next(response);
        return response;
    }

    private ObjectNode baseJob(String nameField, String name, JsonNode request) {
        ObjectNode job = objectMapper.createObjectNode();
        job.put(nameField, name);
        job.set("Media", request.path("Media").deepCopy());
        double now = now();
        job.put("CreationTime", now);
        job.put("StartTime", now);
        return job;
    }

    /**
     * Finishes a job at once: {@code FAILED} with Transcribe's reason when the
     * media can't be read, otherwise {@code COMPLETED} with its transcript
     * written to {@code output} ({@code s3://bucket/prefix}).
     */
    private void finish(ObjectNode job, String statusField, String media, String output, String name,
                        String language) {
        job.put("CompletionTime", now());
        if (language != null) {
            job.put("LanguageCode", language);
        }
        String failure = mediaFailure(media);
        if (failure != null) {
            job.put(statusField, "FAILED");
            job.put("FailureReason", failure);
            return;
        }
        job.put(statusField, "COMPLETED");
        if (output != null && output.startsWith("s3://")) {
            String uri = output.endsWith("/") || !output.endsWith(".json") ? output + name + ".json" : output;
            write(uri, transcriptJson(name, media));
            job.putObject("Transcript").put("TranscriptFileUri", uri);
        }
    }

    /** Why Transcribe can't read the media, or null when it can (any http(s) URI is taken as readable). */
    String mediaFailure(String media) {
        if (!media.startsWith("s3://")) {
            return null;
        }
        Matcher matcher = S3_URI.matcher(media);
        if (!matcher.matches()) {
            return "The S3 URI that you provided can't be accessed. Make sure that you have read permission "
                    + "and try your request again.";
        }
        try {
            s3Service.getObject(matcher.group(1), matcher.group(2));
            return null;
        } catch (AwsException e) {
            return "The S3 URI that you provided can't be accessed. Make sure that you have read permission "
                    + "and try your request again.";
        }
    }

    /** The transcript Transcribe writes: the AI_MOCK_CONFIG entry for the media URI, or an empty one. */
    String transcriptJson(String name, String media) {
        String transcript = mocks.lookup("transcribe", media, "Transcript")
                .map(JsonNode::asText).orElse("");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jobName", name);
        root.put("accountId", regionResolver.getAccountId());
        root.put("status", "COMPLETED");
        ObjectNode results = root.putObject("results");
        results.putArray("transcripts").addObject().put("transcript", transcript);
        results.putArray("items");
        return root.toString();
    }

    void write(String uri, String content) {
        Matcher matcher = S3_URI.matcher(uri);
        if (!matcher.matches()) {
            return;
        }
        try {
            s3Service.putObject(matcher.group(1), matcher.group(2), content.getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());
        } catch (AwsException e) {
            // Transcribe reports an unwritable output location on the job; the transcript simply isn't there.
        }
    }

    /** The EventBridge event Transcribe sends when a transcription job finishes. */
    void publishTranscriptionJobEvent(String name, String status, String failureReason, String region) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("TranscriptionJobName", name);
        detail.put("TranscriptionJobStatus", status);
        if (failureReason != null) {
            detail.put("FailureReason", failureReason);
        }
        eventBridge.publishServiceEvent("aws.transcribe", "Transcribe Job State Change", detail.toString(),
                regionResolver.buildArn("transcribe", region, "transcription-job/" + name), region);
    }

    // ── Medical vocabularies ───────────────────────────────────────────────

    public synchronized ObjectNode createMedicalVocabulary(JsonNode request, String region) {
        String name = requireName(request, "VocabularyName");
        requireAbsent(Kind.MEDICAL_VOCABULARY, region, name, "vocabulary name");
        String language = requireText(request, "LanguageCode");
        String file = requireS3FileUri(request, "VocabularyFileUri");
        ObjectNode vocabulary = objectMapper.createObjectNode();
        vocabulary.put("VocabularyName", name);
        vocabulary.put("LanguageCode", language);
        readVocabularyFile(vocabulary, file);
        put(Kind.MEDICAL_VOCABULARY, region, name, vocabulary);
        putTags(arn(Kind.MEDICAL_VOCABULARY, region, name), request.get("Tags"));
        return vocabularyView(vocabulary, false);
    }

    public synchronized ObjectNode updateMedicalVocabulary(JsonNode request, String region) {
        String name = requireText(request, "VocabularyName");
        ObjectNode vocabulary = require(Kind.MEDICAL_VOCABULARY, region, name);
        String language = requireText(request, "LanguageCode");
        String file = requireS3FileUri(request, "VocabularyFileUri");
        vocabulary.put("LanguageCode", language);
        readVocabularyFile(vocabulary, file);
        put(Kind.MEDICAL_VOCABULARY, region, name, vocabulary);
        return vocabularyView(vocabulary, false);
    }

    public ObjectNode getMedicalVocabulary(JsonNode request, String region) {
        return vocabularyView(require(Kind.MEDICAL_VOCABULARY, region, requireText(request, "VocabularyName")), true);
    }

    public synchronized ObjectNode deleteMedicalVocabulary(JsonNode request, String region) {
        String name = requireText(request, "VocabularyName");
        require(Kind.MEDICAL_VOCABULARY, region, name);
        stores.get(Kind.MEDICAL_VOCABULARY).delete(key(region, name));
        tags.delete(arn(Kind.MEDICAL_VOCABULARY, region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listMedicalVocabularies(JsonNode request, String region) {
        String state = text(request, "StateEquals");
        String contains = text(request, "NameContains");
        List<ObjectNode> found = scan(Kind.MEDICAL_VOCABULARY, region).stream()
                .filter(v -> state == null || state.equals(v.path("VocabularyState").asText()))
                .filter(v -> contains == null || v.path("VocabularyName").asText().toLowerCase()
                        .contains(contains.toLowerCase()))
                .sorted(Comparator.comparing((ObjectNode v) -> v.path("VocabularyName").asText()))
                .toList();
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        if (state != null) {
            response.put("Status", state);
        }
        ArrayNode list = response.putArray("Vocabularies");
        found.subList(page.start(), page.end()).forEach(v -> list.add(vocabularyView(v, false)));
        page.next(response);
        return response;
    }

    private void readVocabularyFile(ObjectNode vocabulary, String file) {
        vocabulary.put("LastModifiedTime", now());
        vocabulary.put("fileUri", file);
        String failure = mediaFailure(file);
        if (failure != null) {
            vocabulary.put("VocabularyState", "FAILED");
            vocabulary.put("FailureReason", failure);
        } else {
            vocabulary.put("VocabularyState", "READY");
            vocabulary.remove("FailureReason");
        }
    }

    private ObjectNode vocabularyView(ObjectNode vocabulary, boolean withDownload) {
        ObjectNode view = objectMapper.createObjectNode();
        view.put("VocabularyName", vocabulary.path("VocabularyName").asText());
        view.put("LanguageCode", vocabulary.path("LanguageCode").asText());
        view.put("VocabularyState", vocabulary.path("VocabularyState").asText());
        view.put("LastModifiedTime", vocabulary.path("LastModifiedTime").asDouble());
        if (vocabulary.hasNonNull("FailureReason")) {
            view.put("FailureReason", vocabulary.path("FailureReason").asText());
        }
        if (withDownload) {
            view.put("DownloadUri", downloadUri("medical-vocabulary", vocabulary.path("VocabularyName").asText()));
        }
        return view;
    }

    // ── Vocabulary filters ─────────────────────────────────────────────────

    public synchronized ObjectNode createVocabularyFilter(JsonNode request, String region) {
        String name = requireName(request, "VocabularyFilterName");
        requireAbsent(Kind.VOCABULARY_FILTER, region, name, "vocabulary filter name");
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("VocabularyFilterName", name);
        filter.put("LanguageCode", requireText(request, "LanguageCode"));
        filterWords(filter, request);
        put(Kind.VOCABULARY_FILTER, region, name, filter);
        putTags(arn(Kind.VOCABULARY_FILTER, region, name), request.get("Tags"));
        return filterView(filter, false);
    }

    public synchronized ObjectNode updateVocabularyFilter(JsonNode request, String region) {
        String name = requireText(request, "VocabularyFilterName");
        ObjectNode filter = require(Kind.VOCABULARY_FILTER, region, name);
        filterWords(filter, request);
        put(Kind.VOCABULARY_FILTER, region, name, filter);
        return filterView(filter, false);
    }

    public ObjectNode getVocabularyFilter(JsonNode request, String region) {
        return filterView(require(Kind.VOCABULARY_FILTER, region, requireText(request, "VocabularyFilterName")), true);
    }

    public synchronized ObjectNode deleteVocabularyFilter(JsonNode request, String region) {
        String name = requireText(request, "VocabularyFilterName");
        require(Kind.VOCABULARY_FILTER, region, name);
        stores.get(Kind.VOCABULARY_FILTER).delete(key(region, name));
        tags.delete(arn(Kind.VOCABULARY_FILTER, region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listVocabularyFilters(JsonNode request, String region) {
        String contains = text(request, "NameContains");
        List<ObjectNode> found = scan(Kind.VOCABULARY_FILTER, region).stream()
                .filter(f -> contains == null || f.path("VocabularyFilterName").asText().toLowerCase()
                        .contains(contains.toLowerCase()))
                .sorted(Comparator.comparing((ObjectNode f) -> f.path("VocabularyFilterName").asText()))
                .toList();
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("VocabularyFilters");
        found.subList(page.start(), page.end()).forEach(f -> list.add(filterView(f, false)));
        page.next(response);
        return response;
    }

    /** Exactly one of Words or VocabularyFilterFileUri; the words are kept so a filter reads back. */
    private void filterWords(ObjectNode filter, JsonNode request) {
        JsonNode words = request.get("Words");
        String file = text(request, "VocabularyFilterFileUri");
        if ((words == null || !words.isArray() || words.isEmpty()) == (file == null)) {
            throw badRequest("Specify either Words or VocabularyFilterFileUri.");
        }
        if (file != null) {
            requireS3FileUri(request, "VocabularyFilterFileUri");
            if (mediaFailure(file) != null) {
                throw badRequest("The S3 URI that you provided can't be accessed.");
            }
            filter.put("fileUri", file);
            filter.remove("words");
        } else {
            filter.set("words", words.deepCopy());
            filter.remove("fileUri");
        }
        filter.put("LastModifiedTime", now());
    }

    private ObjectNode filterView(ObjectNode filter, boolean withDownload) {
        ObjectNode view = objectMapper.createObjectNode();
        view.put("VocabularyFilterName", filter.path("VocabularyFilterName").asText());
        view.put("LanguageCode", filter.path("LanguageCode").asText());
        view.put("LastModifiedTime", filter.path("LastModifiedTime").asDouble());
        if (withDownload) {
            view.put("DownloadUri", downloadUri("vocabulary-filter", filter.path("VocabularyFilterName").asText()));
        }
        return view;
    }

    // ── Call analytics categories ──────────────────────────────────────────

    public synchronized ObjectNode createCallAnalyticsCategory(JsonNode request, String region) {
        String name = requireName(request, "CategoryName");
        requireAbsent(Kind.CATEGORY, region, name, "category name");
        ObjectNode category = objectMapper.createObjectNode();
        category.put("CategoryName", name);
        category.set("Rules", requireRules(request));
        double now = now();
        category.put("CreateTime", now);
        category.put("LastUpdateTime", now);
        category.put("InputType", text(request, "InputType") == null ? "POST_CALL" : text(request, "InputType"));
        put(Kind.CATEGORY, region, name, category);
        putTags(arn(Kind.CATEGORY, region, name), request.get("Tags"));
        return wrap("CategoryProperties", category);
    }

    public synchronized ObjectNode updateCallAnalyticsCategory(JsonNode request, String region) {
        String name = requireText(request, "CategoryName");
        ObjectNode category = require(Kind.CATEGORY, region, name);
        category.set("Rules", requireRules(request));
        category.put("LastUpdateTime", now());
        if (text(request, "InputType") != null) {
            category.put("InputType", text(request, "InputType"));
        }
        put(Kind.CATEGORY, region, name, category);
        return wrap("CategoryProperties", category);
    }

    public ObjectNode getCallAnalyticsCategory(JsonNode request, String region) {
        return wrap("CategoryProperties", require(Kind.CATEGORY, region, requireText(request, "CategoryName")));
    }

    public synchronized ObjectNode deleteCallAnalyticsCategory(JsonNode request, String region) {
        String name = requireText(request, "CategoryName");
        require(Kind.CATEGORY, region, name);
        stores.get(Kind.CATEGORY).delete(key(region, name));
        tags.delete(arn(Kind.CATEGORY, region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listCallAnalyticsCategories(JsonNode request, String region) {
        List<ObjectNode> found = scan(Kind.CATEGORY, region).stream()
                .sorted(Comparator.comparing((ObjectNode c) -> c.path("CategoryName").asText()))
                .toList();
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Categories");
        found.subList(page.start(), page.end()).forEach(c -> list.add(c.deepCopy()));
        page.next(response);
        return response;
    }

    private JsonNode requireRules(JsonNode request) {
        JsonNode rules = request.get("Rules");
        if (rules == null || !rules.isArray() || rules.isEmpty() || rules.size() > 20) {
            throw badRequest("Rules must contain between 1 and 20 rules.");
        }
        return rules.deepCopy();
    }

    // ── Custom language models ─────────────────────────────────────────────

    private static final Set<String> BASE_MODELS = Set.of("NarrowBand", "WideBand");

    public synchronized ObjectNode createLanguageModel(JsonNode request, String region) {
        String name = requireName(request, "ModelName");
        requireAbsent(Kind.LANGUAGE_MODEL, region, name, "model name");
        String base = requireText(request, "BaseModelName");
        if (!BASE_MODELS.contains(base)) {
            throw badRequest("BaseModelName must be NarrowBand or WideBand.");
        }
        String language = requireText(request, "LanguageCode");
        JsonNode input = request.path("InputDataConfig");
        String s3Uri = text(input, "S3Uri");
        if (s3Uri == null || !S3_URI.matcher(s3Uri).matches()) {
            throw validation("inputDataConfig.s3Uri", s3Uri, "(s3://|http(s*)://).+");
        }
        if (text(input, "DataAccessRoleArn") == null) {
            throw badRequest("InputDataConfig.DataAccessRoleArn is a required field.");
        }
        ObjectNode model = objectMapper.createObjectNode();
        model.put("ModelName", name);
        double now = now();
        model.put("CreateTime", now);
        model.put("LastModifiedTime", now);
        model.put("LanguageCode", language);
        model.put("BaseModelName", base);
        model.put("UpgradeAvailability", false);
        model.set("InputDataConfig", input.deepCopy());
        boolean hasTrainingData = prefixHasObjects(s3Uri);
        model.put("ModelStatus", hasTrainingData ? "COMPLETED" : "FAILED");
        if (!hasTrainingData) {
            model.put("FailureReason", "The training data at " + s3Uri + " can't be accessed.");
        }
        put(Kind.LANGUAGE_MODEL, region, name, model);
        putTags(arn(Kind.LANGUAGE_MODEL, region, name), request.get("Tags"));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("LanguageCode", language);
        response.put("BaseModelName", base);
        response.put("ModelName", name);
        response.set("InputDataConfig", input.deepCopy());
        response.put("ModelStatus", "IN_PROGRESS");
        return response;
    }

    private boolean prefixHasObjects(String uri) {
        Matcher matcher = S3_URI.matcher(uri);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return !s3Service.listObjects(matcher.group(1), matcher.group(2), null, 1).isEmpty();
        } catch (AwsException e) {
            return false;
        }
    }

    public ObjectNode describeLanguageModel(JsonNode request, String region) {
        return wrap("LanguageModel", require(Kind.LANGUAGE_MODEL, region, requireText(request, "ModelName")));
    }

    /** DeleteLanguageModel models no NotFoundException: a missing model is a BadRequestException. */
    public synchronized ObjectNode deleteLanguageModel(JsonNode request, String region) {
        String name = requireText(request, "ModelName");
        if (stores.get(Kind.LANGUAGE_MODEL).get(key(region, name)).isEmpty()) {
            throw badRequest(Kind.LANGUAGE_MODEL.notFoundMessage);
        }
        stores.get(Kind.LANGUAGE_MODEL).delete(key(region, name));
        tags.delete(arn(Kind.LANGUAGE_MODEL, region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listLanguageModels(JsonNode request, String region) {
        String status = text(request, "StatusEquals");
        String contains = text(request, "NameContains");
        List<ObjectNode> found = scan(Kind.LANGUAGE_MODEL, region).stream()
                .filter(m -> status == null || status.equals(m.path("ModelStatus").asText()))
                .filter(m -> contains == null || m.path("ModelName").asText().toLowerCase()
                        .contains(contains.toLowerCase()))
                .sorted(Comparator.comparing((ObjectNode m) -> m.path("ModelName").asText()))
                .toList();
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Models");
        found.subList(page.start(), page.end()).forEach(m -> list.add(m.deepCopy()));
        page.next(response);
        return response;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** Tags a resource by ARN; {@code exists} answers for the kinds stored outside this class. */
    public synchronized ObjectNode tagResource(JsonNode request, Predicate<String> exists) {
        String arn = requireTaggable(text(request, "ResourceArn"), exists);
        putTags(arn, request.get("Tags"));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, Predicate<String> exists) {
        String arn = requireTaggable(text(request, "ResourceArn"), exists);
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        request.path("TagKeys").forEach(k -> current.remove(k.asText()));
        tags.put(arn, current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request, Predicate<String> exists) {
        String arn = requireTaggable(text(request, "ResourceArn"), exists);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", arn);
        ArrayNode list = response.putArray("Tags");
        Iterator<Map.Entry<String, JsonNode>> fields = tags.get(arn).orElse(objectMapper.createObjectNode()).fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> tag = fields.next();
            list.addObject().put("Key", tag.getKey()).put("Value", tag.getValue().asText());
        }
        return response;
    }

    void putTags(String arn, JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        list.forEach(tag -> current.put(tag.path("Key").asText(), tag.path("Value").asText()));
        tags.put(arn, current);
    }

    void deleteTags(String arn) {
        tags.delete(arn);
    }

    /**
     * The ARN, when it names a Transcribe resource that exists:
     * {@code arn:<partition>:transcribe:<region>:<account>:<type>/<name>}.
     */
    private String requireTaggable(String arn, Predicate<String> exists) {
        if (arn == null) {
            throw badRequest("ResourceArn is a required field.");
        }
        boolean known = false;
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed != null && "transcribe".equals(parsed.service()) && parsed.resource().contains("/")) {
            String type = parsed.resource().substring(0, parsed.resource().indexOf('/'));
            String name = parsed.resource().substring(parsed.resource().indexOf('/') + 1);
            for (Kind kind : Kind.values()) {
                if (kind.arnType.equals(type)) {
                    known = stores.get(kind).get(key(parsed.region(), name)).isPresent();
                }
            }
            known = known || exists.test(type + "/" + name);
        }
        if (!known) {
            throw new AwsException("NotFoundException",
                    "The requested resource couldn't be found. Check the ARN and try your request again.", 400);
        }
        return arn;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The ARN of any Transcribe resource, by its ARN type ({@code transcription-job}, {@code vocabulary}, ...). */
    String arnFor(String arnType, String region, String name) {
        return regionResolver.buildArn("transcribe", region, arnType + "/" + name);
    }

    String arn(Kind kind, String region, String name) {
        return regionResolver.buildArn("transcribe", region, kind.arnType + "/" + name);
    }

    private String downloadUri(String type, String name) {
        String region = regionResolver.getRegion();
        return "https://transcribe-" + region + ".s3." + region + "." + AwsRegions.dnsSuffixFor(region) + "/"
                + regionResolver.getAccountId() + "/" + type + "/" + name + ".txt";
    }

    private ObjectNode require(Kind kind, String region, String name) {
        return stores.get(kind).get(key(region, name))
                .orElseThrow(() -> new AwsException(kind.notFoundCode, kind.notFoundMessage, 400));
    }

    private void requireAbsent(Kind kind, String region, String name, String what) {
        if (stores.get(kind).get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException",
                    "The requested " + what + " already exists. Use a different " + what + ".", 400);
        }
    }

    private void put(Kind kind, String region, String name, ObjectNode value) {
        stores.get(kind).put(key(region, name), value);
    }

    private List<ObjectNode> scan(Kind kind, String region) {
        return stores.get(kind).scan(k -> k.startsWith(region + "/"));
    }

    private ObjectNode wrap(String key, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(key, value.deepCopy());
        return response;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    static String requireMediaUri(JsonNode request) {
        String uri = text(request.path("Media"), "MediaFileUri");
        if (uri == null || !MEDIA_URI.matcher(uri).matches()) {
            throw validation("media.mediaFileUri", uri, "(s3://|http(s*)://).+");
        }
        return uri;
    }

    static String requireS3FileUri(JsonNode request, String field) {
        String uri = text(request, field);
        if (uri == null || !MEDIA_URI.matcher(uri).matches()) {
            throw validation(Character.toLowerCase(field.charAt(0)) + field.substring(1), uri,
                    "(s3://|http(s*)://).+");
        }
        return uri;
    }

    private static String requireName(JsonNode request, String field) {
        String name = requireText(request, field);
        if (name.length() > 200 || !NAME.matcher(name).matches()) {
            throw validation(Character.toLowerCase(field.charAt(0)) + field.substring(1), name, "^[0-9a-zA-Z._-]+");
        }
        return name;
    }

    private static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw badRequest("1 validation error detected: Value null at '"
                    + Character.toLowerCase(field.charAt(0)) + field.substring(1)
                    + "' failed to satisfy constraint: Member must not be null");
        }
        return value;
    }

    static AwsException validation(String member, String value, String pattern) {
        return badRequest("1 validation error detected: Value '" + value + "' at '" + member
                + "' failed to satisfy constraint: Member must satisfy regular expression pattern: " + pattern);
    }

    static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record Page(int start, int end, String nextToken) {
        void next(ObjectNode response) {
            if (nextToken != null) {
                response.put("NextToken", nextToken);
            }
        }
    }

    private static Page page(int size, JsonNode request) {
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw badRequest("MaxResults must be between 1 and 100.");
        }
        int start = 0;
        String token = text(request, "NextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw badRequest("The NextToken is invalid.");
            }
        }
        start = Math.min(Math.max(start, 0), size);
        int end = Math.min(size, start + max);
        return new Page(start, end, end < size ? Integer.toString(end) : null);
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }
}
