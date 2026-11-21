package io.floci.aws.services.comprehend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Comprehend's asynchronous analysis jobs (nine families) and the tags on
 * them.
 * <p>
 * A job analyzes its input when it starts, with the same rules as the
 * real-time actions, and writes {@code output/output.tar.gz} (one JSON line
 * per document) under {@code <OutputDataConfig.S3Uri>/<account>-<TYPE>-<JobId>/},
 * as Comprehend does; a PII job in {@code ONLY_REDACTION} mode writes each
 * document redacted instead. Its status then moves on with time:
 * {@code SUBMITTED} for {@link #SUBMITTED_MILLIS}, {@code IN_PROGRESS} until
 * {@link #COMPLETED_MILLIS}, then {@code COMPLETED} ({@code FAILED} with a
 * message when the input couldn't be read). Stopping a job that hasn't
 * finished answers {@code STOP_REQUESTED} and leaves it {@code STOPPED}.
 */
@ApplicationScoped
public class ComprehendJobService {
    static final long SUBMITTED_MILLIS = 2_000;
    static final long COMPLETED_MILLIS = 4_000;
    private static final Pattern S3_URI = Pattern.compile("^s3://([a-z0-9][.\\-a-z0-9]{1,61}[a-z0-9])(/.*)?$");
    private static final Pattern ROLE_ARN = Pattern.compile("^arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:role/.+$");
    private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED", "STOPPED");

    /** One job family: its action noun, ARN resource type, and output type label. */
    enum Family {
        DOCUMENT_CLASSIFICATION("DocumentClassificationJob", "document-classification-job", "CLS", false),
        DOMINANT_LANGUAGE("DominantLanguageDetectionJob", "dominant-language-detection-job", "LANGUAGE", false),
        ENTITIES("EntitiesDetectionJob", "entities-detection-job", "NER", true),
        EVENTS("EventsDetectionJob", "events-detection-job", "EVENTS", true),
        KEY_PHRASES("KeyPhrasesDetectionJob", "key-phrases-detection-job", "KP", true),
        PII_ENTITIES("PiiEntitiesDetectionJob", "pii-entities-detection-job", "PII", true),
        SENTIMENT("SentimentDetectionJob", "sentiment-detection-job", "SENTIMENT", true),
        TARGETED_SENTIMENT("TargetedSentimentDetectionJob", "targeted-sentiment-detection-job", "TS", true),
        TOPICS("TopicsDetectionJob", "topics-detection-job", "TOPICS", false);

        final String noun;
        final String arnType;
        final String outputType;
        final boolean needsLanguage;

        Family(String noun, String arnType, String outputType, boolean needsLanguage) {
            this.noun = noun;
            this.arnType = arnType;
            this.outputType = outputType;
            this.needsLanguage = needsLanguage;
        }

        String propertiesKey() {
            return noun + "Properties";
        }

        /** The family an action ({@code StartSentimentDetectionJob}, {@code ListTopicsDetectionJobs}, …) acts on. */
        static Family of(String action) {
            String noun = action.replaceFirst("^(Start|Stop|Describe|List)", "").replaceFirst("s$", "");
            for (Family family : values()) {
                if (family.noun.equals(noun)) {
                    return family;
                }
            }
            return null;
        }
    }

    private final StorageBackend<String, ObjectNode> jobs;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ComprehendService analysis;
    private final ObjectMapper objectMapper;

    @Inject
    public ComprehendJobService(StorageFactory storageFactory, RegionResolver regionResolver, S3Service s3Service,
                                ComprehendService analysis, ObjectMapper objectMapper) {
        this.jobs = storageFactory.create("comprehend", "comprehend-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tags = storageFactory.create("comprehend", "comprehend-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.analysis = analysis;
        this.objectMapper = objectMapper;
    }

    // ── Jobs ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode start(Family family, JsonNode request, String region) {
        JsonNode input = request.path("InputDataConfig");
        JsonNode output = request.path("OutputDataConfig");
        String inputUri = requireS3Uri(input, "InputDataConfig.S3Uri");
        String outputUri = requireS3Uri(output, "OutputDataConfig.S3Uri");
        String role = ComprehendService.text(request, "DataAccessRoleArn");
        if (role == null || !ROLE_ARN.matcher(role).matches()) {
            throw validation("DataAccessRoleArn must be an IAM role ARN.");
        }
        String language = null;
        if (family.needsLanguage) {
            language = ComprehendService.requireLanguage(request, family == Family.PII_ENTITIES
                    ? ComprehendService.PII_SUPPORTED_LANGUAGE_CODES
                    : family == Family.TARGETED_SENTIMENT ? Set.of("en") : ComprehendService.SUPPORTED_LANGUAGE_CODES);
        }
        switch (family) {
            case DOCUMENT_CLASSIFICATION -> {
                String classifier = ComprehendService.text(request, "DocumentClassifierArn");
                String flywheel = ComprehendService.text(request, "FlywheelArn");
                if (classifier == null && flywheel == null) {
                    throw ComprehendService.invalid("DocumentClassifierArn or FlywheelArn is required.");
                }
                throw new AwsException("ResourceNotFoundException", "The specified resource ARN was not found: "
                        + (classifier != null ? classifier : flywheel), 400);
            }
            case ENTITIES -> {
                String recognizer = ComprehendService.text(request, "EntityRecognizerArn");
                if (recognizer != null) {
                    throw new AwsException("ResourceNotFoundException",
                            "The specified resource ARN was not found: " + recognizer, 400);
                }
            }
            case EVENTS -> {
                JsonNode types = request.get("TargetEventTypes");
                if (types == null || !types.isArray() || types.isEmpty()) {
                    throw ComprehendService.invalid("TargetEventTypes is a required field.");
                }
            }
            case PII_ENTITIES -> {
                String mode = ComprehendService.text(request, "Mode");
                if (!"ONLY_OFFSETS".equals(mode) && !"ONLY_REDACTION".equals(mode)) {
                    throw validation("Mode must be ONLY_OFFSETS or ONLY_REDACTION.");
                }
            }
            default -> { }
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        String arn = regionResolver.buildArn("comprehend", region, family.arnType + "/" + jobId);
        String outputPrefix = regionResolver.getAccountId() + "-" + family.outputType + "-" + jobId + "/";
        ObjectNode job = objectMapper.createObjectNode();
        job.put("JobId", jobId);
        job.put("JobArn", arn);
        putIfPresent(job, "JobName", ComprehendService.text(request, "JobName"));
        job.put("JobStatus", "SUBMITTED");
        job.put("SubmitTime", now());
        job.set("InputDataConfig", input.deepCopy());
        ObjectNode outputConfig = output.deepCopy();
        outputConfig.put("S3Uri", trimSlash(outputUri) + "/" + outputPrefix + "output/output.tar.gz");
        job.set("OutputDataConfig", outputConfig);
        putIfPresent(job, "LanguageCode", language);
        job.put("DataAccessRoleArn", role);
        for (String field : List.of("VolumeKmsKeyId", "Mode", "NumberOfTopics", "VpcConfig", "RedactionConfig",
                "TargetEventTypes")) {
            if (request.has(field)) {
                job.set(field, request.get(field).deepCopy());
            }
        }
        job.put("family", family.name());
        job.put("region", region);

        try {
            run(family, job, inputUri, outputUri + (outputUri.endsWith("/") ? "" : "/") + outputPrefix,
                    input.path("InputFormat").asText("ONE_DOC_PER_FILE"));
        } catch (AwsException e) {
            job.put("failure", e.getMessage());
        }
        jobs.put(jobId, job);
        putTags(arn, request.get("Tags"));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobId", jobId);
        response.put("JobArn", arn);
        response.put("JobStatus", "SUBMITTED");
        return response;
    }

    public ObjectNode describe(Family family, JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(family.propertiesKey(), view(require(family, ComprehendService.text(request, "JobId"))));
        return response;
    }

    public ObjectNode list(Family family, JsonNode request, String region) {
        JsonNode filter = request.path("Filter");
        String name = ComprehendService.text(filter, "JobName");
        String status = ComprehendService.text(filter, "JobStatus");
        double before = filter.path("SubmitTimeBefore").asDouble(Double.MAX_VALUE);
        double after = filter.path("SubmitTimeAfter").asDouble(0);
        int max = Math.max(1, Math.min(500, request.path("MaxResults").asInt(100)));
        int offset = parseToken(ComprehendService.text(request, "NextToken"));
        List<ObjectNode> matching = jobs.scan(key -> true).stream()
                .filter(job -> family.name().equals(job.path("family").asText())
                        && region.equals(job.path("region").asText()))
                .map(this::view)
                .filter(job -> name == null || name.equals(job.path("JobName").asText(null)))
                .filter(job -> status == null || status.equals(job.path("JobStatus").asText()))
                .filter(job -> job.path("SubmitTime").asDouble() < before && job.path("SubmitTime").asDouble() > after)
                .sorted(Comparator.comparingDouble((ObjectNode job) -> job.path("SubmitTime").asDouble()).reversed())
                .toList();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray(family.propertiesKey() + "List");
        matching.stream().skip(offset).limit(max).forEach(list::add);
        if (offset + max < matching.size()) {
            response.put("NextToken", Integer.toString(offset + max));
        }
        return response;
    }

    public synchronized ObjectNode stop(Family family, JsonNode request) {
        String jobId = ComprehendService.text(request, "JobId");
        ObjectNode job = require(family, jobId);
        String status = view(job).path("JobStatus").asText();
        if (!FINISHED.contains(status)) {
            job.put("stoppedAt", now());
            jobs.put(jobId, job);
            status = "STOP_REQUESTED";
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobId", jobId);
        response.put("JobStatus", status);
        return response;
    }

    /** The job as Comprehend reports it now, without the bookkeeping fields. */
    private ObjectNode view(ObjectNode job) {
        ObjectNode copy = job.deepCopy();
        copy.remove(List.of("family", "region", "failure", "stoppedAt"));
        double submitted = job.path("SubmitTime").asDouble();
        double elapsed = (now() - submitted) * 1000;
        if (job.has("stoppedAt")) {
            copy.put("JobStatus", "STOPPED");
            copy.put("EndTime", job.path("stoppedAt").asDouble());
        } else if (elapsed >= COMPLETED_MILLIS) {
            boolean failed = job.has("failure");
            copy.put("JobStatus", failed ? "FAILED" : "COMPLETED");
            copy.put("EndTime", submitted + COMPLETED_MILLIS / 1000.0);
            if (failed) {
                copy.put("Message", job.path("failure").asText());
            }
        } else if (elapsed >= SUBMITTED_MILLIS) {
            copy.put("JobStatus", "IN_PROGRESS");
        }
        return copy;
    }

    private ObjectNode require(Family family, String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            throw ComprehendService.invalid("JobId is a required field.");
        }
        return jobs.get(jobId)
                .filter(job -> family.name().equals(job.path("family").asText()))
                .orElseThrow(() -> new AwsException("JobNotFoundException",
                        "Could not find job with the specified ID: " + jobId, 400));
    }

    // ── Running ────────────────────────────────────────────────────────────

    private void run(Family family, ObjectNode job, String inputUri, String outputPrefixUri, String format) {
        S3Location in = location(inputUri);
        S3Location out = location(outputPrefixUri);
        List<String[]> documents = new ArrayList<>();
        List<S3Object> objects;
        try {
            objects = s3Service.listObjects(in.bucket(), in.key(), null, 1000);
        } catch (AwsException e) {
            throw new AwsException("InvalidRequestException", "Unable to read input " + inputUri + ": "
                    + e.getMessage(), 400);
        }
        for (S3Object object : objects) {
            if (object.getKey().endsWith("/")) {
                continue;
            }
            String file = object.getKey().substring(object.getKey().lastIndexOf('/') + 1);
            String body = new String(s3Service.getObject(in.bucket(), object.getKey()).getData(),
                    StandardCharsets.UTF_8);
            if ("ONE_DOC_PER_LINE".equals(format)) {
                List<String> lines = body.lines().toList();
                for (int line = 0; line < lines.size(); line++) {
                    if (!lines.get(line).isBlank()) {
                        documents.add(new String[]{file, Integer.toString(line), lines.get(line)});
                    }
                }
            } else {
                documents.add(new String[]{file, null, body});
            }
        }
        if (documents.isEmpty()) {
            throw new AwsException("InvalidRequestException", "No input documents were found under "
                    + inputUri, 400);
        }

        if (family == Family.PII_ENTITIES && "ONLY_REDACTION".equals(job.path("Mode").asText())) {
            for (String[] document : documents) {
                String key = out.key() + "output/" + document[0] + (document[1] == null ? "" : "." + document[1])
                        + ".out";
                s3Service.putObject(out.bucket(), key, redact(document[2], job.path("RedactionConfig"))
                        .getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
            }
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (String[] document : documents) {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("File", document[0]);
            if (document[1] != null) {
                line.put("Line", Integer.parseInt(document[1]));
            }
            line.setAll(analyze(family, document[2]));
            lines.append(line).append('\n');
        }
        s3Service.putObject(out.bucket(), out.key() + "output/output.tar.gz",
                tarGz("output", lines.toString().getBytes(StandardCharsets.UTF_8)), "application/gzip", Map.of());
    }

    private ObjectNode analyze(Family family, String text) {
        return switch (family) {
            case DOMINANT_LANGUAGE -> analysis.dominantLanguage(text);
            case ENTITIES, EVENTS -> analysis.entities(text);
            case KEY_PHRASES, TOPICS -> analysis.keyPhrases(text);
            case PII_ENTITIES -> {
                ObjectNode result = objectMapper.createObjectNode();
                ArrayNode entities = result.putArray("Entities");
                ComprehendAnalyzer.pii(text).forEach(pii -> entities.addObject()
                        .put("Score", pii.score()).put("Type", pii.type())
                        .put("BeginOffset", pii.begin()).put("EndOffset", pii.end()));
                yield result;
            }
            case SENTIMENT -> analysis.sentiment(text);
            case TARGETED_SENTIMENT -> analysis.targetedSentiment(text);
            case DOCUMENT_CLASSIFICATION -> objectMapper.createObjectNode();
        };
    }

    /** PII replaced per the redaction config: by its type ({@code [NAME]}) or a mask character. */
    private static String redact(String text, JsonNode config) {
        String mode = config.path("MaskMode").asText("REPLACE_WITH_PII_ENTITY_TYPE");
        String mask = config.path("MaskCharacter").asText("*");
        StringBuilder redacted = new StringBuilder(text);
        List<ComprehendAnalyzer.Entity> found = new ArrayList<>(ComprehendAnalyzer.pii(text));
        found.sort((a, b) -> Integer.compare(b.begin(), a.begin()));
        for (ComprehendAnalyzer.Entity pii : found) {
            String replacement = "MASK".equals(mode)
                    ? mask.repeat(pii.end() - pii.begin())
                    : "[" + pii.type() + "]";
            redacted.replace(pii.begin(), pii.end(), replacement);
        }
        return redacted.toString();
    }

    /** A gzipped ustar archive holding one file. */
    static byte[] tarGz(String name, byte[] content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            byte[] header = new byte[512];
            writeField(header, 0, 100, name);
            writeField(header, 100, 8, "0000644");
            writeField(header, 108, 8, "0000000");
            writeField(header, 116, 8, "0000000");
            writeField(header, 124, 12, String.format("%011o", content.length));
            writeField(header, 136, 12, String.format("%011o", Instant.now().getEpochSecond()));
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            writeField(header, 257, 6, "ustar");
            writeField(header, 263, 2, "00");
            int checksum = 0;
            for (byte b : header) {
                checksum += b & 0xff;
            }
            writeField(header, 148, 7, String.format("%06o", checksum));
            gzip.write(header);
            gzip.write(content);
            gzip.write(new byte[(512 - content.length % 512) % 512]);
            gzip.write(new byte[1024]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeField(byte[] header, int offset, int length, String value) {
        byte[] data = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(data, 0, header, offset, Math.min(data.length, length));
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode tagResource(JsonNode request) {
        String arn = requireTaggable(ComprehendService.text(request, "ResourceArn"));
        putTags(arn, request.get("Tags"));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        String arn = requireTaggable(ComprehendService.text(request, "ResourceArn"));
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        request.path("TagKeys").forEach(key -> current.remove(key.asText()));
        tags.put(arn, current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        String arn = requireTaggable(ComprehendService.text(request, "ResourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", arn);
        ArrayNode list = response.putArray("Tags");
        Iterator<Map.Entry<String, JsonNode>> fields =
                tags.get(arn).orElse(objectMapper.createObjectNode()).fields();
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
        tagList.forEach(tag -> current.put(tag.path("Key").asText(), tag.path("Value").asText()));
        tags.put(arn, current);
    }

    private String requireTaggable(String arn) {
        if (arn == null) {
            throw ComprehendService.invalid("ResourceArn is a required field.");
        }
        boolean known = jobs.scan(key -> true).stream().anyMatch(job -> arn.equals(job.path("JobArn").asText()));
        if (!known) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified resource ARN was not found: " + arn, 400);
        }
        return arn;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private record S3Location(String bucket, String key) {}

    private static S3Location location(String uri) {
        Matcher matcher = S3_URI.matcher(uri);
        if (!matcher.matches()) {
            throw validation("Invalid S3 URI: " + uri);
        }
        String key = matcher.group(2) == null ? "" : matcher.group(2).substring(1);
        return new S3Location(matcher.group(1), key);
    }

    private static String requireS3Uri(JsonNode config, String field) {
        String uri = config.path("S3Uri").isTextual() ? config.path("S3Uri").asText() : null;
        if (uri == null || !S3_URI.matcher(uri).matches()) {
            throw validation("1 validation error detected: Value '" + uri + "' at '" + field
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + "s3://[a-z0-9][\\.\\-a-z0-9]{1,61}[a-z0-9](/.*)?");
        }
        return uri;
    }

    private static String trimSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static int parseToken(String token) {
        if (token == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            throw ComprehendService.invalid("Invalid NextToken.");
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
