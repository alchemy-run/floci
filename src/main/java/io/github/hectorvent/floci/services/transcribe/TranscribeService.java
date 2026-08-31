package io.github.hectorvent.floci.services.transcribe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJob;
import io.github.hectorvent.floci.services.transcribe.model.TranscriptionJobSummary;
import io.github.hectorvent.floci.services.transcribe.model.VocabularyInfo;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Stub for Amazon Transcribe. Jobs transition to COMPLETED and vocabularies
 * to READY immediately. No real transcription is performed.
 *
 * @see <a href="https://docs.aws.amazon.com/transcribe/latest/APIReference/Welcome.html">Transcribe API</a>
 */
@ApplicationScoped
public class TranscribeService implements Resettable {

    private static final Pattern MEDIA_URI = Pattern.compile("^(s3://|https?://).+", Pattern.CASE_INSENSITIVE);

    private final StorageFactory storageFactory;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, TranscriptionJob> transcriptionJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> callAnalyticsJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> medicalJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> scribeJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VocabularyInfo> medicalVocabularies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> filters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> categories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectNode> languageModels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedHashMap<String, String>> tagsByArn = new ConcurrentHashMap<>();
    private Map<String, VocabularyInfo> vocabularies = new ConcurrentHashMap<>();

    public TranscribeService(StorageFactory storageFactory) {
        this(storageFactory, new ObjectMapper());
    }

    @Inject
    public TranscribeService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.storageFactory = storageFactory;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.vocabularies = new StorageBackedMap<>(storageFactory.create("transcribe",
                "transcribe-vocabularies.json", new TypeReference<Map<String, VocabularyInfo>>() {}));
    }

    public void clear() {
        transcriptionJobs.clear();
        callAnalyticsJobs.clear();
        medicalJobs.clear();
        scribeJobs.clear();
        medicalVocabularies.clear();
        filters.clear();
        categories.clear();
        languageModels.clear();
        tagsByArn.clear();
        vocabularies.clear();
    }

    public TranscriptionJob startTranscriptionJob(String jobName, String mediaFileUri,
                                                  String languageCode, String mediaFormat) {
        requireNonBlank(jobName, "TranscriptionJobName");
        requireNonBlank(mediaFileUri, "Media.MediaFileUri");
        requireMediaUri(mediaFileUri, "Media.MediaFileUri");
        if (transcriptionJobs.containsKey(jobName)) {
            throw conflict("The requested job name already exists. Use a different job name.");
        }

        long now = Instant.now().getEpochSecond();
        TranscriptionJob job = new TranscriptionJob(
                jobName,
                "COMPLETED",
                languageCode != null ? languageCode : "en-US",
                mediaFormat != null ? mediaFormat : "mp4",
                48000,
                new TranscriptionJob.Media(mediaFileUri),
                new TranscriptionJob.Transcript("s3://floci-transcribe-output/" + jobName + ".json"),
                now, now, now);

        transcriptionJobs.put(jobName, job);
        return job;
    }

    public TranscriptionJob getTranscriptionJob(String jobName) {
        requireNonBlank(jobName, "TranscriptionJobName");
        TranscriptionJob job = transcriptionJobs.get(jobName);
        if (job == null) {
            throw jobNotFound();
        }
        return job;
    }

    public ListTranscriptionJobsResult listTranscriptionJobs(String statusFilter, String jobNameContains,
                                                             Integer maxResults) {
        List<TranscriptionJobSummary> filtered = transcriptionJobs.values().stream()
                .filter(j -> statusFilter == null || statusFilter.equals(j.transcriptionJobStatus()))
                .filter(j -> jobNameContains == null || j.transcriptionJobName().contains(jobNameContains))
                .sorted(Comparator.comparing(TranscriptionJob::transcriptionJobName))
                .map(TranscriptionJobSummary::from)
                .toList();

        List<TranscriptionJobSummary> page = pageOf(filtered, maxResults);
        String nextToken = page.size() < filtered.size()
                ? page.get(page.size() - 1).transcriptionJobName() : null;

        return new ListTranscriptionJobsResult(page, statusFilter, nextToken);
    }

    public void deleteTranscriptionJob(String jobName) {
        requireNonBlank(jobName, "TranscriptionJobName");
        if (transcriptionJobs.remove(jobName) == null) {
            throw jobNotFound();
        }
    }

    public VocabularyInfo createVocabulary(String vocabularyName, String languageCode) {
        return createVocabulary(vocabularyName, languageCode, null);
    }

    public VocabularyInfo createVocabulary(String vocabularyName, String languageCode, String vocabularyFileUri) {
        requireNonBlank(vocabularyName, "VocabularyName");
        requireNonBlank(languageCode, "LanguageCode");
        if (vocabularyFileUri != null && !vocabularyFileUri.isBlank()) {
            requireMediaUri(vocabularyFileUri, "VocabularyFileUri");
        }
        if (vocabularies.containsKey(vocabularyName)) {
            throw conflict("The requested vocabulary name already exists. Use a different vocabulary name.");
        }

        VocabularyInfo vocab = new VocabularyInfo(
                vocabularyName, languageCode, "READY", Instant.now().getEpochSecond());
        vocabularies.put(vocabularyName, vocab);
        return vocab;
    }

    public VocabularyInfo getVocabulary(String vocabularyName) {
        requireNonBlank(vocabularyName, "VocabularyName");
        VocabularyInfo vocab = vocabularies.get(vocabularyName);
        if (vocab == null) {
            throw vocabularyNotFound();
        }
        return vocab;
    }

    public VocabularyInfo updateVocabulary(String vocabularyName, String languageCode, String vocabularyFileUri) {
        requireNonBlank(vocabularyName, "VocabularyName");
        VocabularyInfo existing = vocabularies.get(vocabularyName);
        if (existing == null) {
            throw vocabularyNotFound();
        }
        if (vocabularyFileUri != null && !vocabularyFileUri.isBlank()) {
            requireMediaUri(vocabularyFileUri, "VocabularyFileUri");
        }
        requireNonBlank(languageCode, "LanguageCode");
        VocabularyInfo updated = new VocabularyInfo(
                vocabularyName, languageCode, "READY", Instant.now().getEpochSecond());
        vocabularies.put(vocabularyName, updated);
        return updated;
    }

    public ListVocabulariesResult listVocabularies(String stateEquals, String nameContains,
                                                   Integer maxResults) {
        List<VocabularyInfo> filtered = vocabularies.values().stream()
                .filter(v -> stateEquals == null || stateEquals.equals(v.vocabularyState()))
                .filter(v -> nameContains == null || v.vocabularyName().contains(nameContains))
                .sorted(Comparator.comparing(VocabularyInfo::vocabularyName))
                .toList();

        List<VocabularyInfo> page = pageOf(filtered, maxResults);
        String nextToken = page.size() < filtered.size()
                ? page.get(page.size() - 1).vocabularyName() : null;

        return new ListVocabulariesResult(page, stateEquals, nextToken);
    }

    public void deleteVocabulary(String vocabularyName) {
        requireNonBlank(vocabularyName, "VocabularyName");
        if (vocabularies.remove(vocabularyName) == null) {
            throw vocabularyNotFound();
        }
    }

    public ObjectNode startCallAnalyticsJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CallAnalyticsJobName"), "CallAnalyticsJobName");
        String uri = mediaFileUri(request);
        requireNonBlank(uri, "Media.MediaFileUri");
        requireMediaUri(uri, "Media.MediaFileUri");
        if (callAnalyticsJobs.containsKey(name)) {
            throw conflict("The requested job name already exists. Use a different job name.");
        }
        ObjectNode job = jobNode("CallAnalyticsJobName", name, "CallAnalyticsJobStatus", uri,
                stringField(request, "LanguageCode"));
        callAnalyticsJobs.put(name, job);
        return wrap("CallAnalyticsJob", job);
    }

    public ObjectNode getCallAnalyticsJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CallAnalyticsJobName"), "CallAnalyticsJobName");
        ObjectNode job = callAnalyticsJobs.get(name);
        if (job == null) {
            throw jobNotFound();
        }
        return wrap("CallAnalyticsJob", job);
    }

    public ObjectNode listCallAnalyticsJobs(JsonNode request) {
        return listJobs(callAnalyticsJobs, request, "CallAnalyticsJobSummaries",
                "CallAnalyticsJobName", "CallAnalyticsJobStatus");
    }

    public ObjectNode deleteCallAnalyticsJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CallAnalyticsJobName"), "CallAnalyticsJobName");
        if (callAnalyticsJobs.remove(name) == null) {
            throw jobNotFound();
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode startMedicalTranscriptionJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalTranscriptionJobName"),
                "MedicalTranscriptionJobName");
        String uri = mediaFileUri(request);
        requireNonBlank(uri, "Media.MediaFileUri");
        requireMediaUri(uri, "Media.MediaFileUri");
        if (medicalJobs.containsKey(name)) {
            throw conflict("The requested job name already exists. Use a different job name.");
        }
        ObjectNode job = jobNode("MedicalTranscriptionJobName", name, "TranscriptionJobStatus", uri,
                stringField(request, "LanguageCode"));
        putIfPresent(job, "Specialty", stringField(request, "Specialty"));
        putIfPresent(job, "Type", stringField(request, "Type"));
        medicalJobs.put(name, job);
        return wrap("MedicalTranscriptionJob", job);
    }

    public ObjectNode getMedicalTranscriptionJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalTranscriptionJobName"),
                "MedicalTranscriptionJobName");
        ObjectNode job = medicalJobs.get(name);
        if (job == null) {
            throw jobNotFound();
        }
        return wrap("MedicalTranscriptionJob", job);
    }

    public ObjectNode listMedicalTranscriptionJobs(JsonNode request) {
        return listJobs(medicalJobs, request, "MedicalTranscriptionJobSummaries",
                "MedicalTranscriptionJobName", "TranscriptionJobStatus");
    }

    public ObjectNode deleteMedicalTranscriptionJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalTranscriptionJobName"),
                "MedicalTranscriptionJobName");
        if (medicalJobs.remove(name) == null) {
            throw jobNotFound();
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode startMedicalScribeJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalScribeJobName"), "MedicalScribeJobName");
        String uri = mediaFileUri(request);
        requireNonBlank(uri, "Media.MediaFileUri");
        requireMediaUri(uri, "Media.MediaFileUri");
        if (scribeJobs.containsKey(name)) {
            throw conflict("The requested job name already exists. Use a different job name.");
        }
        ObjectNode job = jobNode("MedicalScribeJobName", name, "MedicalScribeJobStatus", uri, "en-US");
        if (request != null && request.has("Settings") && !request.get("Settings").isNull()) {
            job.set("Settings", request.get("Settings").deepCopy());
        }
        scribeJobs.put(name, job);
        return wrap("MedicalScribeJob", job);
    }

    public ObjectNode getMedicalScribeJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalScribeJobName"), "MedicalScribeJobName");
        ObjectNode job = scribeJobs.get(name);
        if (job == null) {
            throw jobNotFound();
        }
        return wrap("MedicalScribeJob", job);
    }

    public ObjectNode listMedicalScribeJobs(JsonNode request) {
        return listJobs(scribeJobs, request, "MedicalScribeJobSummaries",
                "MedicalScribeJobName", "MedicalScribeJobStatus");
    }

    public ObjectNode deleteMedicalScribeJob(JsonNode request) {
        String name = requireNonBlank(stringField(request, "MedicalScribeJobName"), "MedicalScribeJobName");
        if (scribeJobs.remove(name) == null) {
            throw jobNotFound();
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createMedicalVocabulary(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyName"), "VocabularyName");
        String languageCode = requireNonBlank(stringField(request, "LanguageCode"), "LanguageCode");
        requireMediaUri(requireNonBlank(stringField(request, "VocabularyFileUri"), "VocabularyFileUri"),
                "VocabularyFileUri");
        if (medicalVocabularies.containsKey(name)) {
            throw conflict("The requested vocabulary name already exists. Use a different vocabulary name.");
        }
        VocabularyInfo vocab = new VocabularyInfo(name, languageCode, "READY", Instant.now().getEpochSecond());
        medicalVocabularies.put(name, vocab);
        return vocabularyNode(vocab, false);
    }

    public ObjectNode getMedicalVocabulary(JsonNode request) {
        return vocabularyNode(requireMedicalVocabulary(stringField(request, "VocabularyName")), true);
    }

    public ObjectNode updateMedicalVocabulary(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyName"), "VocabularyName");
        VocabularyInfo existing = medicalVocabularies.get(name);
        if (existing == null) {
            throw vocabularyNotFound();
        }
        String languageCode = requireNonBlank(stringField(request, "LanguageCode"), "LanguageCode");
        String uri = stringField(request, "VocabularyFileUri");
        if (uri != null && !uri.isBlank()) {
            requireMediaUri(uri, "VocabularyFileUri");
        }
        VocabularyInfo updated = new VocabularyInfo(name, languageCode, "READY", Instant.now().getEpochSecond());
        medicalVocabularies.put(name, updated);
        return vocabularyNode(updated, false);
    }

    public ObjectNode listMedicalVocabularies(JsonNode request) {
        String stateEquals = stringField(request, "StateEquals");
        String nameContains = stringField(request, "NameContains");
        List<VocabularyInfo> filtered = medicalVocabularies.values().stream()
                .filter(v -> stateEquals == null || stateEquals.equals(v.vocabularyState()))
                .filter(v -> nameContains == null || v.vocabularyName().contains(nameContains))
                .sorted(Comparator.comparing(VocabularyInfo::vocabularyName))
                .toList();
        List<VocabularyInfo> page = pageOf(filtered, intField(request, "MaxResults"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("Vocabularies");
        for (VocabularyInfo vocab : page) {
            list.add(vocabularyNode(vocab, false));
        }
        return root;
    }

    public ObjectNode deleteMedicalVocabulary(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyName"), "VocabularyName");
        if (medicalVocabularies.remove(name) == null) {
            throw vocabularyNotFound();
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createVocabularyFilter(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyFilterName"), "VocabularyFilterName");
        String languageCode = requireNonBlank(stringField(request, "LanguageCode"), "LanguageCode");
        JsonNode words = request == null ? null : request.get("Words");
        String fileUri = stringField(request, "VocabularyFilterFileUri");
        if ((words == null || !words.isArray() || words.isEmpty())
                && (fileUri == null || fileUri.isBlank())) {
            throw badRequest("Words or VocabularyFilterFileUri is required.");
        }
        if (fileUri != null && !fileUri.isBlank()) {
            requireMediaUri(fileUri, "VocabularyFilterFileUri");
        }
        if (filters.containsKey(name)) {
            throw conflict("The requested vocabulary filter name already exists. Use a different name.");
        }
        ObjectNode filter = filterNode(name, languageCode, words);
        filters.put(name, filter);
        return filterSummary(filter);
    }

    public ObjectNode getVocabularyFilter(JsonNode request) {
        ObjectNode filter = requireFilter(stringField(request, "VocabularyFilterName"));
        ObjectNode root = filterSummary(filter);
        root.put("DownloadUri", "https://s3.amazonaws.com/floci-transcribe-filters/"
                + filter.path("VocabularyFilterName").asText() + ".txt");
        return root;
    }

    public ObjectNode updateVocabularyFilter(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyFilterName"), "VocabularyFilterName");
        ObjectNode existing = filters.get(name);
        if (existing == null) {
            throw notFound("The requested vocabulary filter couldn't be found.");
        }
        JsonNode words = request == null ? null : request.get("Words");
        String fileUri = stringField(request, "VocabularyFilterFileUri");
        if (fileUri != null && !fileUri.isBlank()) {
            requireMediaUri(fileUri, "VocabularyFilterFileUri");
        }
        if (words != null && words.isArray()) {
            existing.set("Words", words.deepCopy());
        }
        existing.put("LastModifiedTime", Instant.now().getEpochSecond());
        return filterSummary(existing);
    }

    public ObjectNode deleteVocabularyFilter(JsonNode request) {
        String name = requireNonBlank(stringField(request, "VocabularyFilterName"), "VocabularyFilterName");
        if (filters.remove(name) == null) {
            throw notFound("The requested vocabulary filter couldn't be found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listVocabularyFilters(JsonNode request) {
        String nameContains = stringField(request, "NameContains");
        List<ObjectNode> filtered = filters.values().stream()
                .filter(f -> nameContains == null || f.path("VocabularyFilterName").asText().contains(nameContains))
                .sorted(Comparator.comparing(f -> f.path("VocabularyFilterName").asText()))
                .toList();
        List<ObjectNode> page = pageOf(filtered, intField(request, "MaxResults"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("VocabularyFilters");
        for (ObjectNode filter : page) {
            list.add(filterSummary(filter));
        }
        return root;
    }

    public ObjectNode createCallAnalyticsCategory(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CategoryName"), "CategoryName");
        JsonNode rules = request == null ? null : request.get("Rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            throw badRequest("Rules is required.");
        }
        if (categories.containsKey(name)) {
            throw conflict("The requested category name already exists. Use a different name.");
        }
        ObjectNode category = categoryNode(name, rules);
        categories.put(name, category);
        return wrap("CategoryProperties", category);
    }

    public ObjectNode getCallAnalyticsCategory(JsonNode request) {
        return wrap("CategoryProperties", requireCategory(stringField(request, "CategoryName")));
    }

    public ObjectNode updateCallAnalyticsCategory(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CategoryName"), "CategoryName");
        ObjectNode existing = categories.get(name);
        if (existing == null) {
            throw notFound("The requested category couldn't be found.");
        }
        JsonNode rules = request == null ? null : request.get("Rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            throw badRequest("Rules is required.");
        }
        existing.set("Rules", rules.deepCopy());
        existing.put("LastUpdateTime", Instant.now().getEpochSecond());
        return wrap("CategoryProperties", existing);
    }

    public ObjectNode deleteCallAnalyticsCategory(JsonNode request) {
        String name = requireNonBlank(stringField(request, "CategoryName"), "CategoryName");
        if (categories.remove(name) == null) {
            throw notFound("The requested category couldn't be found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listCallAnalyticsCategories(JsonNode request) {
        List<ObjectNode> filtered = categories.values().stream()
                .sorted(Comparator.comparing(c -> c.path("CategoryName").asText()))
                .toList();
        List<ObjectNode> page = pageOf(filtered, intField(request, "MaxResults"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("Categories");
        for (ObjectNode category : page) {
            list.add(category);
        }
        return root;
    }

    public ObjectNode createLanguageModel(JsonNode request) {
        String name = requireNonBlank(stringField(request, "ModelName"), "ModelName");
        String s3Uri = request == null ? null : stringField(request.path("InputDataConfig"), "S3Uri");
        requireNonBlank(s3Uri, "InputDataConfig.S3Uri");
        requireMediaUri(s3Uri, "InputDataConfig.S3Uri");
        if (languageModels.containsKey(name)) {
            throw conflict("The requested language model name already exists. Use a different name.");
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode model = objectMapper.createObjectNode();
        model.put("ModelName", name);
        putIfPresent(model, "LanguageCode", stringField(request, "LanguageCode"));
        putIfPresent(model, "BaseModelName", stringField(request, "BaseModelName"));
        model.put("ModelStatus", "COMPLETED");
        model.put("CreateTime", now);
        model.put("LastModifiedTime", now);
        if (request != null && request.has("InputDataConfig")) {
            model.set("InputDataConfig", request.get("InputDataConfig").deepCopy());
        }
        languageModels.put(name, model);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("ModelName", name);
        putIfPresent(root, "LanguageCode", stringField(request, "LanguageCode"));
        putIfPresent(root, "BaseModelName", stringField(request, "BaseModelName"));
        root.put("ModelStatus", "COMPLETED");
        if (request != null && request.has("InputDataConfig")) {
            root.set("InputDataConfig", request.get("InputDataConfig").deepCopy());
        }
        return root;
    }

    public ObjectNode describeLanguageModel(JsonNode request) {
        String name = requireNonBlank(stringField(request, "ModelName"), "ModelName");
        ObjectNode model = languageModels.get(name);
        if (model == null) {
            throw notFound("The requested language model couldn't be found.");
        }
        return wrap("LanguageModel", model);
    }

    public ObjectNode listLanguageModels(JsonNode request) {
        String nameContains = stringField(request, "NameContains");
        String statusEquals = stringField(request, "StatusEquals");
        List<ObjectNode> filtered = languageModels.values().stream()
                .filter(m -> nameContains == null || m.path("ModelName").asText().contains(nameContains))
                .filter(m -> statusEquals == null || statusEquals.equals(m.path("ModelStatus").asText()))
                .sorted(Comparator.comparing(m -> m.path("ModelName").asText()))
                .toList();
        List<ObjectNode> page = pageOf(filtered, intField(request, "MaxResults"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("Models");
        for (ObjectNode model : page) {
            list.add(model);
        }
        return root;
    }

    public ObjectNode deleteLanguageModel(JsonNode request) {
        String name = requireNonBlank(stringField(request, "ModelName"), "ModelName");
        if (languageModels.remove(name) == null) {
            throw badRequest("The requested language model couldn't be found. Check the model name and try your request again.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode tagResource(JsonNode request) {
        String arn = requireNonBlank(stringField(request, "ResourceArn"), "ResourceArn");
        JsonNode tags = request == null ? null : request.get("Tags");
        if (tags == null || !tags.isArray()) {
            throw badRequest("Tags is required.");
        }
        LinkedHashMap<String, String> map = tagsByArn.computeIfAbsent(arn, ignored -> new LinkedHashMap<>());
        for (JsonNode tag : tags) {
            if (tag != null && tag.has("Key")) {
                map.put(tag.path("Key").asText(), tag.path("Value").asText(""));
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        String arn = requireNonBlank(stringField(request, "ResourceArn"), "ResourceArn");
        JsonNode keys = request == null ? null : request.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw badRequest("TagKeys is required.");
        }
        Map<String, String> map = tagsByArn.get(arn);
        if (map != null) {
            for (JsonNode key : keys) {
                if (key != null && !key.isNull()) {
                    map.remove(key.asText());
                }
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requireNonBlank(stringField(request, "ResourceArn"), "ResourceArn");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("ResourceArn", arn);
        ArrayNode tags = root.putArray("Tags");
        Map<String, String> map = tagsByArn.get(arn);
        if (map != null) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                ObjectNode tag = tags.addObject();
                tag.put("Key", entry.getKey());
                tag.put("Value", entry.getValue());
            }
        }
        return root;
    }

    private ObjectNode listJobs(Map<String, ObjectNode> store, JsonNode request, String listField,
                                String nameField, String statusField) {
        String statusFilter = stringField(request, "Status");
        String nameContains = stringField(request, "JobNameContains");
        List<ObjectNode> filtered = store.values().stream()
                .filter(j -> statusFilter == null || statusFilter.equals(j.path(statusField).asText()))
                .filter(j -> nameContains == null || j.path(nameField).asText().contains(nameContains))
                .sorted(Comparator.comparing(j -> j.path(nameField).asText()))
                .toList();
        List<ObjectNode> page = pageOf(filtered, intField(request, "MaxResults"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray(listField);
        for (ObjectNode job : page) {
            list.add(job);
        }
        return root;
    }

    private ObjectNode jobNode(String nameField, String name, String statusField, String mediaUri,
                               String languageCode) {
        long now = Instant.now().getEpochSecond();
        ObjectNode job = objectMapper.createObjectNode();
        job.put(nameField, name);
        job.put(statusField, "COMPLETED");
        job.put("LanguageCode", languageCode != null ? languageCode : "en-US");
        job.put("CreationTime", now);
        job.put("StartTime", now);
        job.put("CompletionTime", now);
        ObjectNode media = job.putObject("Media");
        media.put("MediaFileUri", mediaUri);
        return job;
    }

    private ObjectNode filterNode(String name, String languageCode, JsonNode words) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("VocabularyFilterName", name);
        filter.put("LanguageCode", languageCode);
        filter.put("LastModifiedTime", Instant.now().getEpochSecond());
        if (words != null && words.isArray()) {
            filter.set("Words", words.deepCopy());
        }
        return filter;
    }

    private ObjectNode filterSummary(ObjectNode filter) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("VocabularyFilterName", filter.path("VocabularyFilterName").asText());
        root.put("LanguageCode", filter.path("LanguageCode").asText());
        root.put("LastModifiedTime", filter.path("LastModifiedTime").asLong());
        return root;
    }

    private ObjectNode categoryNode(String name, JsonNode rules) {
        long now = Instant.now().getEpochSecond();
        ObjectNode category = objectMapper.createObjectNode();
        category.put("CategoryName", name);
        category.set("Rules", rules.deepCopy());
        category.put("CreateTime", now);
        category.put("LastUpdateTime", now);
        return category;
    }

    private ObjectNode vocabularyNode(VocabularyInfo vocab, boolean includeDownloadUri) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("VocabularyName", vocab.vocabularyName());
        root.put("LanguageCode", vocab.languageCode());
        root.put("VocabularyState", vocab.vocabularyState());
        root.put("LastModifiedTime", vocab.lastModifiedTime());
        if (includeDownloadUri) {
            root.put("DownloadUri", "https://s3.amazonaws.com/floci-transcribe-vocabularies/"
                    + vocab.vocabularyName() + ".txt");
        }
        return root;
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set(field, value);
        return root;
    }

    private ObjectNode requireFilter(String name) {
        requireNonBlank(name, "VocabularyFilterName");
        ObjectNode filter = filters.get(name);
        if (filter == null) {
            throw notFound("The requested vocabulary filter couldn't be found.");
        }
        return filter;
    }

    private ObjectNode requireCategory(String name) {
        requireNonBlank(name, "CategoryName");
        ObjectNode category = categories.get(name);
        if (category == null) {
            throw notFound("The requested category couldn't be found.");
        }
        return category;
    }

    private VocabularyInfo requireMedicalVocabulary(String name) {
        requireNonBlank(name, "VocabularyName");
        VocabularyInfo vocab = medicalVocabularies.get(name);
        if (vocab == null) {
            throw vocabularyNotFound();
        }
        return vocab;
    }

    private static <T> List<T> pageOf(List<T> filtered, Integer maxResults) {
        int limit = maxResults != null ? Math.min(maxResults, 100) : 100;
        if (filtered.size() <= limit) {
            return filtered;
        }
        return new ArrayList<>(filtered.subList(0, limit));
    }

    private void requireMediaUri(String uri, String fieldName) {
        if (uri == null || !MEDIA_URI.matcher(uri).matches()) {
            throw badRequest("1 validation error detected: Value at '" + fieldName
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: (s3://|http(s)*://).+");
        }
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw badRequest("1 validation error detected: Value null at '" + fieldName
                    + "' failed to satisfy constraint: Member must not be null");
        }
        return value;
    }

    private static String stringField(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static Integer intField(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asInt() : null;
    }

    private static String mediaFileUri(JsonNode request) {
        if (request == null) {
            return null;
        }
        JsonNode media = request.path("Media");
        if (media.isMissingNode() || media.isNull()) {
            return null;
        }
        JsonNode uri = media.get("MediaFileUri");
        return (uri != null && !uri.isNull()) ? uri.asText() : null;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 400);
    }

    private static AwsException jobNotFound() {
        return new AwsException("BadRequestException",
                "The requested job couldn't be found. Check the job name and try your request again.", 400);
    }

    private static AwsException vocabularyNotFound() {
        return new AwsException("NotFoundException",
                "The requested vocabulary couldn't be found. Check the vocabulary name and try your request again.",
                400);
    }

    public record ListTranscriptionJobsResult(
            List<TranscriptionJobSummary> summaries, String status, String nextToken) {}

    public record ListVocabulariesResult(
            List<VocabularyInfo> vocabularies, String status, String nextToken) {}
}
