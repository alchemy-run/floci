package io.floci.aws.services.translate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stateful half of Amazon Translate: custom terminologies, parallel data,
 * batch translation jobs, and their tags.
 * <p>
 * Imports and updates finish at once: parallel data reads its file from S3
 * when it is created or updated, so a {@code GetParallelData} right after
 * {@code CreateParallelData} already reports {@code ACTIVE} (or {@code FAILED}
 * with the reason). A batch job translates its input prefix when it starts
 * (the same echo translation {@code TranslateText} uses, with its terminologies
 * applied), writes the output where Translate does, and reports
 * {@code SUBMITTED} until {@link #JOB_SETTLE_MILLIS} pass, then {@code COMPLETED}.
 *
 * @see <a href="https://docs.aws.amazon.com/translate/latest/APIReference/welcome.html">Translate API Reference</a>
 */
@ApplicationScoped
public class TranslateResourceService {
    /** How long a batch job reports {@code SUBMITTED} before {@code COMPLETED}. */
    static final long JOB_SETTLE_MILLIS = 2_000;
    private static final Pattern NAME = Pattern.compile("^([A-Za-z0-9-]_?)+$");
    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)/?(.*)$");
    private static final Pattern TMX_TU = Pattern.compile("<tu[\\s>].*?</tu>", Pattern.DOTALL);
    private static final Pattern TMX_TUV = Pattern.compile(
            "<tuv[^>]*xml:lang=\"([^\"]+)\"[^>]*>\\s*<seg>(.*?)</seg>", Pattern.DOTALL);
    private static final Set<String> FORMATS = Set.of("CSV", "TSV", "TMX");

    private final StorageBackend<String, ObjectNode> terminologies;
    private final StorageBackend<String, ObjectNode> parallelData;
    private final StorageBackend<String, ObjectNode> jobs;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public TranslateResourceService(StorageFactory storageFactory, RegionResolver regionResolver,
                                    S3Service s3Service, ObjectMapper objectMapper) {
        this.terminologies = storageFactory.create("translate", "translate-terminologies.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.parallelData = storageFactory.create("translate", "translate-parallel-data.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.jobs = storageFactory.create("translate", "translate-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tags = storageFactory.create("translate", "translate-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    // ── Terminologies ──────────────────────────────────────────────────────

    public synchronized ObjectNode importTerminology(JsonNode request, String region) {
        String name = requireName(text(request, "Name"));
        String mergeStrategy = text(request, "MergeStrategy");
        if (mergeStrategy != null && !"OVERWRITE".equals(mergeStrategy)) {
            throw invalid("MergeStrategy must be OVERWRITE.");
        }
        JsonNode data = request.path("TerminologyData");
        String format = requireFormat(text(data, "Format"));
        String file = text(data, "File");
        if (file == null || file.isEmpty()) {
            throw invalid("TerminologyData.File is a required field.");
        }
        byte[] bytes = decodeBase64(file, "TerminologyData.File");
        List<Map<String, String>> rows = parseRecords(new String(bytes, StandardCharsets.UTF_8), format);
        if (rows.isEmpty()) {
            throw invalid("The terminology file has no terms.");
        }
        List<String> languages = new ArrayList<>(rows.getFirst().keySet());
        String directionality = text(data, "Directionality");
        String arn = regionResolver.buildArn("translate", region, "terminology/" + name + "/LATEST");
        double now = now();
        ObjectNode existing = terminologies.get(key(region, name)).orElse(null);

        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("Name", name);
        putIfPresent(properties, "Description", text(request, "Description"));
        properties.put("Arn", arn);
        properties.put("SourceLanguageCode", languages.getFirst());
        ArrayNode targets = properties.putArray("TargetLanguageCodes");
        languages.subList(1, languages.size()).forEach(targets::add);
        JsonNode encryptionKey = request.get("EncryptionKey");
        if (encryptionKey != null && encryptionKey.isObject()) {
            properties.set("EncryptionKey", encryptionKey);
        }
        properties.put("SizeBytes", bytes.length);
        properties.put("TermCount", rows.size());
        properties.put("CreatedAt", existing == null ? now : existing.path("properties").path("CreatedAt").asDouble(now));
        properties.put("LastUpdatedAt", now);
        properties.put("Directionality", directionality == null ? "UNI" : directionality);
        properties.put("SkippedTermCount", 0);
        properties.put("Format", format);

        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("properties", properties);
        stored.set("terms", objectMapper.valueToTree(rows));
        terminologies.put(key(region, name), stored);
        putTags(arn, request.get("Tags"));

        ObjectNode response = objectMapper.createObjectNode();
        response.set("TerminologyProperties", properties.deepCopy());
        return response;
    }

    public ObjectNode getTerminology(JsonNode request, String region) {
        ObjectNode stored = requireTerminology(region, text(request, "Name"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TerminologyProperties", stored.get("properties").deepCopy());
        response.set("TerminologyDataLocation", dataLocation(region, "terminology", text(request, "Name")));
        return response;
    }

    public ObjectNode listTerminologies(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("TerminologyPropertiesList");
        inRegion(terminologies, region).forEach(stored -> list.add(stored.get("properties").deepCopy()));
        return response;
    }

    public synchronized ObjectNode deleteTerminology(JsonNode request, String region) {
        ObjectNode stored = requireTerminology(region, text(request, "Name"));
        terminologies.delete(key(region, text(request, "Name")));
        tags.delete(stored.path("properties").path("Arn").asText());
        return objectMapper.createObjectNode();
    }

    /**
     * The terms of {@code names} that occur in {@code text}, applied: each
     * source term found (whole word, any case) becomes its target term.
     * Returns the rewritten text and the {@code AppliedTerminologies} list,
     * which names each terminology that matched with the terms it applied.
     */
    public Applied applyTerminologies(List<String> names, String text, String source, String target,
                                      String region) {
        ArrayNode applied = objectMapper.createArrayNode();
        String result = text;
        for (String name : names) {
            ObjectNode stored = requireTerminology(region, name);
            ArrayNode matched = objectMapper.createArrayNode();
            for (JsonNode row : stored.path("terms")) {
                String sourceTerm = row.path(source).asText(null);
                String targetTerm = row.path(target).asText(null);
                if (sourceTerm == null || targetTerm == null || sourceTerm.isEmpty()) {
                    continue;
                }
                Pattern term = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(sourceTerm)
                        + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                Matcher matcher = term.matcher(result);
                if (matcher.find()) {
                    result = matcher.replaceAll(Matcher.quoteReplacement(targetTerm));
                    matched.addObject().put("SourceText", sourceTerm).put("TargetText", targetTerm);
                }
            }
            if (!matched.isEmpty()) {
                ObjectNode entry = applied.addObject();
                entry.put("Name", name);
                entry.set("Terms", matched);
            }
        }
        return new Applied(result, applied);
    }

    public record Applied(String text, ArrayNode terminologies) {}

    // ── Parallel data ──────────────────────────────────────────────────────

    public synchronized ObjectNode createParallelData(JsonNode request, String region) {
        String name = requireName(text(request, "Name"));
        if (parallelData.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException",
                    "Parallel data " + name + " already exists.", 400);
        }
        JsonNode config = requireParallelDataConfig(request);
        double now = now();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("Name", name);
        String arn = regionResolver.buildArn("translate", region, "parallel-data/" + name);
        properties.put("Arn", arn);
        putIfPresent(properties, "Description", text(request, "Description"));
        JsonNode encryptionKey = request.get("EncryptionKey");
        if (encryptionKey != null && encryptionKey.isObject()) {
            properties.set("EncryptionKey", encryptionKey);
        }
        properties.put("CreatedAt", now);
        importParallelData(properties, config, now);
        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("properties", properties);
        parallelData.put(key(region, name), stored);
        putTags(arn, request.get("Tags"));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", name);
        response.put("Status", "CREATING");
        return response;
    }

    public ObjectNode getParallelData(JsonNode request, String region) {
        String name = text(request, "Name");
        ObjectNode stored = requireParallelData(region, name);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ParallelDataProperties", stored.get("properties").deepCopy());
        response.set("DataLocation", dataLocation(region, "parallel-data", name));
        return response;
    }

    public synchronized ObjectNode updateParallelData(JsonNode request, String region) {
        String name = text(request, "Name");
        ObjectNode stored = requireParallelData(region, name);
        JsonNode config = requireParallelDataConfig(request);
        ObjectNode properties = (ObjectNode) stored.get("properties");
        String description = text(request, "Description");
        if (description != null) {
            properties.put("Description", description);
        }
        double now = now();
        importParallelData(properties, config, now);
        properties.put("LatestUpdateAttemptStatus", properties.path("Status").asText());
        properties.put("LatestUpdateAttemptAt", now);
        parallelData.put(key(region, name), stored);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", name);
        response.put("Status", properties.path("Status").asText());
        response.put("LatestUpdateAttemptStatus", "UPDATING");
        response.put("LatestUpdateAttemptAt", now);
        return response;
    }

    public synchronized ObjectNode deleteParallelData(JsonNode request, String region) {
        String name = text(request, "Name");
        ObjectNode stored = requireParallelData(region, name);
        parallelData.delete(key(region, name));
        tags.delete(stored.path("properties").path("Arn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", name);
        response.put("Status", "DELETING");
        return response;
    }

    public ObjectNode listParallelData(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ParallelDataPropertiesList");
        inRegion(parallelData, region).forEach(stored -> list.add(stored.get("properties").deepCopy()));
        return response;
    }

    /**
     * Reads the parallel data file named by {@code config} and records what
     * Translate reports for it: languages, record count, size, and status
     * ({@code FAILED} with the reason when the file can't be read or parsed).
     */
    private void importParallelData(ObjectNode properties, JsonNode config, double now) {
        properties.set("ParallelDataConfig", config.deepCopy());
        properties.put("LastUpdatedAt", now);
        properties.put("FailedRecordCount", 0);
        properties.put("SkippedRecordCount", 0);
        properties.remove("Message");
        String format = config.path("Format").asText();
        try {
            byte[] bytes = readS3(config.path("S3Uri").asText());
            List<Map<String, String>> rows = parseRecords(new String(bytes, StandardCharsets.UTF_8), format);
            if (rows.isEmpty()) {
                throw invalid("The parallel data file has no records.");
            }
            List<String> languages = new ArrayList<>(rows.getFirst().keySet());
            properties.put("SourceLanguageCode", languages.getFirst());
            ArrayNode targets = properties.putArray("TargetLanguageCodes");
            languages.subList(1, languages.size()).forEach(targets::add);
            properties.put("ImportedDataSize", bytes.length);
            properties.put("ImportedRecordCount", rows.size());
            properties.put("Status", "ACTIVE");
        } catch (AwsException e) {
            properties.put("Status", "FAILED");
            properties.put("Message", e.getMessage());
            properties.put("ImportedDataSize", 0);
            properties.put("ImportedRecordCount", 0);
        }
    }

    private JsonNode requireParallelDataConfig(JsonNode request) {
        JsonNode config = request.get("ParallelDataConfig");
        if (config == null || !config.isObject() || text(config, "S3Uri") == null) {
            throw invalid("ParallelDataConfig.S3Uri is a required field.");
        }
        requireFormat(text(config, "Format"));
        return config;
    }

    // ── Batch translation jobs ─────────────────────────────────────────────

    public synchronized ObjectNode startTextTranslationJob(JsonNode request, String region,
                                                           TranslateService translate) {
        JsonNode input = request.path("InputDataConfig");
        JsonNode output = request.path("OutputDataConfig");
        String inputUri = text(input, "S3Uri");
        String outputUri = text(output, "S3Uri");
        if (inputUri == null || text(input, "ContentType") == null) {
            throw invalid("InputDataConfig.S3Uri and InputDataConfig.ContentType are required.");
        }
        if (outputUri == null) {
            throw invalid("OutputDataConfig.S3Uri is a required field.");
        }
        String source = text(request, "SourceLanguageCode");
        List<String> targetCodes = strings(request.get("TargetLanguageCodes"));
        if (source == null || targetCodes.isEmpty()) {
            throw invalid("SourceLanguageCode and TargetLanguageCodes are required.");
        }
        List<String> terminologyNames = strings(request.get("TerminologyNames"));
        terminologyNames.forEach(name -> requireTerminology(region, name));
        strings(request.get("ParallelDataNames")).forEach(name -> requireParallelData(region, name));

        String jobId = UUID.randomUUID().toString().replace("-", "");
        double now = now();
        ObjectNode job = objectMapper.createObjectNode();
        job.put("JobId", jobId);
        putIfPresent(job, "JobName", text(request, "JobName"));
        job.put("JobStatus", "SUBMITTED");
        job.put("SourceLanguageCode", source);
        job.set("TargetLanguageCodes", request.get("TargetLanguageCodes").deepCopy());
        job.set("TerminologyNames", objectMapper.valueToTree(terminologyNames));
        job.set("ParallelDataNames", objectMapper.valueToTree(strings(request.get("ParallelDataNames"))));
        job.put("SubmittedTime", now);
        job.set("InputDataConfig", input.deepCopy());
        job.set("OutputDataConfig", output.deepCopy());
        putIfPresent(job, "DataAccessRoleArn", text(request, "DataAccessRoleArn"));
        if (request.get("Settings") != null) {
            job.set("Settings", request.get("Settings").deepCopy());
        }

        int documents = 0;
        int failed = 0;
        String outputPrefix = regionResolver.getAccountId() + "-TranslateText-" + jobId + "/";
        try {
            S3Location in = s3Location(inputUri);
            S3Location out = s3Location(outputUri);
            for (S3Object object : s3Service.listObjects(in.bucket(), in.key(), null, 1000)) {
                if (object.getKey().endsWith("/")) {
                    continue;
                }
                byte[] body = s3Service.getObject(in.bucket(), object.getKey()).getData();
                String file = object.getKey().substring(object.getKey().lastIndexOf('/') + 1);
                for (String target : targetCodes) {
                    String translated = translate.echoTranslation(new String(body, StandardCharsets.UTF_8));
                    if (!terminologyNames.isEmpty()) {
                        translated = applyTerminologies(terminologyNames, translated, source, target, region).text();
                    }
                    s3Service.putObject(out.bucket(), out.key() + outputPrefix + target + "." + file,
                            translated.getBytes(StandardCharsets.UTF_8), text(input, "ContentType"), Map.of());
                }
                documents++;
            }
        } catch (AwsException e) {
            failed++;
            job.put("Message", e.getMessage());
        }
        ObjectNode details = job.putObject("JobDetails");
        details.put("TranslatedDocumentsCount", documents * targetCodes.size());
        details.put("DocumentsWithErrorsCount", failed);
        details.put("InputDocumentsCount", documents);
        jobs.put(key(region, jobId), job);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobId", jobId);
        response.put("JobStatus", "SUBMITTED");
        return response;
    }

    public ObjectNode describeTextTranslationJob(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TextTranslationJobProperties", settled(requireJob(region, text(request, "JobId"))));
        return response;
    }

    public ObjectNode listTextTranslationJobs(JsonNode request, String region) {
        JsonNode filter = request.path("Filter");
        String jobName = text(filter, "JobName");
        String status = text(filter, "JobStatus");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("TextTranslationJobPropertiesList");
        inRegion(jobs, region).stream()
                .map(this::settled)
                .filter(job -> jobName == null || jobName.equals(job.path("JobName").asText(null)))
                .filter(job -> status == null || status.equals(job.path("JobStatus").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode job) -> job.path("SubmittedTime").asDouble()).reversed())
                .forEach(list::add);
        return response;
    }

    public synchronized ObjectNode stopTextTranslationJob(JsonNode request, String region) {
        String jobId = text(request, "JobId");
        ObjectNode job = settled(requireJob(region, jobId));
        String status = job.path("JobStatus").asText();
        if ("SUBMITTED".equals(status) || "IN_PROGRESS".equals(status)) {
            job.put("JobStatus", "STOPPED");
            job.put("EndTime", now());
            jobs.put(key(region, jobId), job);
            status = "STOP_REQUESTED";
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobId", jobId);
        response.put("JobStatus", status);
        return response;
    }

    /** A copy of {@code job} with the status it has now: {@code COMPLETED} once it has settled. */
    private ObjectNode settled(ObjectNode job) {
        ObjectNode copy = job.deepCopy();
        double submitted = job.path("SubmittedTime").asDouble();
        if ("SUBMITTED".equals(job.path("JobStatus").asText())
                && now() - submitted >= JOB_SETTLE_MILLIS / 1000.0) {
            boolean failed = job.path("JobDetails").path("DocumentsWithErrorsCount").asInt() > 0;
            copy.put("JobStatus", failed ? "COMPLETED_WITH_ERROR" : "COMPLETED");
            copy.put("EndTime", submitted + JOB_SETTLE_MILLIS / 1000.0);
        }
        return copy;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode tagResource(JsonNode request) {
        String arn = requireTaggable(text(request, "ResourceArn"));
        putTags(arn, request.get("Tags"));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        String arn = requireTaggable(text(request, "ResourceArn"));
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        strings(request.get("TagKeys")).forEach(current::remove);
        tags.put(arn, current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requireTaggable(text(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Tags");
        Iterator<Map.Entry<String, JsonNode>> fields = tags.get(arn).orElse(objectMapper.createObjectNode()).fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> tag = fields.next();
            list.addObject().put("Key", tag.getKey()).put("Value", tag.getValue().asText());
        }
        return response;
    }

    private void putTags(String arn, JsonNode tagList) {
        if (tagList == null || !tagList.isArray() || tagList.isEmpty()) {
            return;
        }
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        for (JsonNode tag : tagList) {
            current.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        tags.put(arn, current);
    }

    /** The ARN, once it names a terminology or parallel data that exists. */
    private String requireTaggable(String arn) {
        if (arn == null) {
            throw invalid("ResourceArn is a required field.");
        }
        boolean exists = terminologies.scan(key -> true).stream()
                .anyMatch(stored -> arn.equals(stored.path("properties").path("Arn").asText()))
                || parallelData.scan(key -> true).stream()
                .anyMatch(stored -> arn.equals(stored.path("properties").path("Arn").asText()));
        if (!exists) {
            throw new AwsException("ResourceNotFoundException",
                    "The resource " + arn + " does not exist.", 400);
        }
        return arn;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode requireTerminology(String region, String name) {
        if (name == null) {
            throw invalid("Name is a required field.");
        }
        return terminologies.get(key(region, name)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The terminology " + name + " does not exist.", 400));
    }

    private ObjectNode requireParallelData(String region, String name) {
        if (name == null) {
            throw invalid("Name is a required field.");
        }
        return parallelData.get(key(region, name)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The parallel data " + name + " does not exist.", 400));
    }

    private ObjectNode requireJob(String region, String jobId) {
        if (jobId == null) {
            throw invalid("JobId is a required field.");
        }
        return jobs.get(key(region, jobId)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The job " + jobId + " does not exist.", 400));
    }

    private static List<ObjectNode> inRegion(StorageBackend<String, ObjectNode> store, String region) {
        String prefix = region + "/";
        return store.scan(key -> key.startsWith(prefix));
    }

    private ObjectNode dataLocation(String region, String kind, String name) {
        ObjectNode location = objectMapper.createObjectNode();
        location.put("RepositoryType", "S3");
        location.put("Location", "https://translate-" + region + ".s3." + region + "." + AwsRegions.dnsSuffixFor(region) + "/"
                + regionResolver.getAccountId() + "/" + kind + "/" + name);
        return location;
    }

    /**
     * Records of a CSV, TSV, or TMX file, each a map of language code to text
     * in the file's column order. The first CSV/TSV row names the languages.
     */
    static List<Map<String, String>> parseRecords(String content, String format) {
        List<Map<String, String>> rows = new ArrayList<>();
        String body = content.startsWith("﻿") ? content.substring(1) : content;
        if ("TMX".equals(format)) {
            Matcher unit = TMX_TU.matcher(body);
            while (unit.find()) {
                Map<String, String> row = new LinkedHashMap<>();
                Matcher variant = TMX_TUV.matcher(unit.group());
                while (variant.find()) {
                    row.put(variant.group(1).toLowerCase(Locale.ROOT), variant.group(2).trim());
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            return rows;
        }
        char separator = "TSV".equals(format) ? '\t' : ',';
        List<String> lines = body.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return rows;
        }
        List<String> languages = splitRecord(lines.getFirst(), separator);
        for (String line : lines.subList(1, lines.size())) {
            List<String> cells = splitRecord(line, separator);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < languages.size() && i < cells.size(); i++) {
                row.put(languages.get(i).trim(), cells.get(i).trim());
            }
            rows.add(row);
        }
        return rows;
    }

    /** Splits one CSV/TSV record, honoring double-quoted cells. */
    private static List<String> splitRecord(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == separator && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private byte[] readS3(String uri) {
        S3Location location = s3Location(uri);
        try {
            return s3Service.getObject(location.bucket(), location.key()).getData();
        } catch (AwsException e) {
            throw invalid("Unable to read " + uri + ": " + e.getMessage());
        }
    }

    private record S3Location(String bucket, String key) {}

    private static S3Location s3Location(String uri) {
        Matcher matcher = S3_URI.matcher(uri == null ? "" : uri);
        if (!matcher.matches()) {
            throw invalid("Invalid S3 URI: " + uri);
        }
        return new S3Location(matcher.group(1), matcher.group(2));
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 256 || !NAME.matcher(name).matches()) {
            throw invalid("Name must match ^([A-Za-z0-9-]_?)+$ and be at most 256 characters.");
        }
        return name;
    }

    private static String requireFormat(String format) {
        if (format == null || !FORMATS.contains(format)) {
            throw invalid("Format must be one of CSV, TSV, or TMX.");
        }
        return format;
    }

    private static byte[] decodeBase64(String value, String field) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not valid base64-encoded data.");
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
