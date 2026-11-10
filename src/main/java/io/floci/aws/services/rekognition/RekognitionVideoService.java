package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rekognition's asynchronous analysis: the eight stored-video job families
 * and media analysis jobs.
 * <p>
 * Starting a job checks what Rekognition checks before accepting it: the
 * video (or manifest) object must be readable in S3, a face search needs its
 * collection, a segment detection its segment types. Floci decodes no
 * video, so a job finds nothing: it reports {@code IN_PROGRESS} for
 * {@link #RUNNING_MILLIS}, then {@code SUCCEEDED} with an empty result list.
 * The same {@code ClientRequestToken} returns the same job.
 */
@ApplicationScoped
public class RekognitionVideoService {
    static final long RUNNING_MILLIS = 2_000;
    private static final String UNREADABLE_OBJECT =
            "Unable to get object metadata from S3. Check object key, region and/or access permissions.";

    /** One stored-video job family: its action noun and the key of its result list. */
    enum Family {
        CELEBRITY_RECOGNITION("CelebrityRecognition", "Celebrities"),
        CONTENT_MODERATION("ContentModeration", "ModerationLabels"),
        FACE_DETECTION("FaceDetection", "Faces"),
        FACE_SEARCH("FaceSearch", "Persons"),
        LABEL_DETECTION("LabelDetection", "Labels"),
        PERSON_TRACKING("PersonTracking", "Persons"),
        SEGMENT_DETECTION("SegmentDetection", "Segments"),
        TEXT_DETECTION("TextDetection", "TextDetections");

        final String noun;
        final String resultsKey;

        Family(String noun, String resultsKey) {
            this.noun = noun;
            this.resultsKey = resultsKey;
        }

        static Family of(String action) {
            String noun = action.replaceFirst("^(Start|Get)", "");
            for (Family family : values()) {
                if (family.noun.equals(noun)) {
                    return family;
                }
            }
            throw new IllegalArgumentException(action);
        }
    }

    private final StorageBackend<String, ObjectNode> jobs;
    private final S3Service s3Service;
    private final RekognitionCollectionService collections;
    private final ObjectMapper objectMapper;

    @Inject
    public RekognitionVideoService(StorageFactory storageFactory, S3Service s3Service,
                                   RekognitionCollectionService collections, ObjectMapper objectMapper) {
        this.jobs = storageFactory.create("rekognition", "rekognition-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.s3Service = s3Service;
        this.collections = collections;
        this.objectMapper = objectMapper;
    }

    // ── Stored video ───────────────────────────────────────────────────────

    public synchronized ObjectNode start(Family family, JsonNode request, String region) {
        String token = RekognitionCollectionService.text(request, "ClientRequestToken");
        if (token != null) {
            for (ObjectNode job : jobs.scan(k -> true)) {
                if (token.equals(job.path("token").asText(null)) && family.name().equals(job.path("family").asText())) {
                    return objectMapper.createObjectNode().put("JobId", job.path("JobId").asText());
                }
            }
        }
        if (family == Family.FACE_SEARCH) {
            String collectionId = RekognitionCollectionService.text(request, "CollectionId");
            if (!collections.collectionExists(region, collectionId)) {
                throw new AwsException("ResourceNotFoundException",
                        "The collection id: " + collectionId + " does not exist", 400);
            }
        }
        if (family == Family.SEGMENT_DETECTION
                && RekognitionCollectionService.strings(request.get("SegmentTypes")).isEmpty()) {
            throw new AwsException("InvalidParameterException", "SegmentTypes is a required field.", 400);
        }
        requireReadable(request.path("Video").path("S3Object"));

        String jobId = jobId();
        ObjectNode job = objectMapper.createObjectNode();
        job.put("JobId", jobId);
        job.put("family", family.name());
        job.put("region", region);
        job.put("started", RekognitionCollectionService.now());
        if (token != null) {
            job.put("token", token);
        }
        job.set("Video", request.path("Video").deepCopy());
        if (request.hasNonNull("JobTag")) {
            job.put("JobTag", request.get("JobTag").asText());
        }
        if (family == Family.SEGMENT_DETECTION) {
            job.set("SegmentTypes", request.get("SegmentTypes").deepCopy());
        }
        jobs.put(jobId, job);
        return objectMapper.createObjectNode().put("JobId", jobId);
    }

    public ObjectNode get(Family family, JsonNode request) {
        String jobId = RekognitionCollectionService.text(request, "JobId");
        ObjectNode job = jobId == null ? null : jobs.get(jobId)
                .filter(j -> family.name().equals(j.path("family").asText())).orElse(null);
        if (job == null) {
            throw new AwsException("ResourceNotFoundException", "The job " + jobId + " was not found.", 400);
        }
        boolean done = (RekognitionCollectionService.now() - job.path("started").asDouble()) * 1000 >= RUNNING_MILLIS;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobStatus", done ? "SUCCEEDED" : "IN_PROGRESS");
        response.put("JobId", jobId);
        response.set("Video", job.path("Video").deepCopy());
        if (job.hasNonNull("JobTag")) {
            response.put("JobTag", job.path("JobTag").asText());
        }
        if (done) {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("Codec", "h264");
            metadata.put("DurationMillis", 0);
            metadata.put("Format", "QuickTime / MOV");
            metadata.put("FrameRate", 30.0);
            metadata.put("FrameHeight", 720);
            metadata.put("FrameWidth", 1280);
            metadata.put("ColorRange", "LIMITED");
            response.putArray("VideoMetadata").add(metadata);
            response.putArray(family.resultsKey);
            switch (family) {
                case CONTENT_MODERATION -> response.put("ModerationModelVersion", RekognitionService.MODEL_VERSION);
                case LABEL_DETECTION -> response.put("LabelModelVersion", RekognitionService.MODEL_VERSION);
                case TEXT_DETECTION -> response.put("TextModelVersion", RekognitionService.MODEL_VERSION);
                case FACE_SEARCH, FACE_DETECTION -> { }
                case SEGMENT_DETECTION -> {
                    ArrayNode selected = response.putArray("SelectedSegmentTypes");
                    job.path("SegmentTypes").forEach(type ->
                            selected.addObject().put("Type", type.asText()).put("ModelVersion", "1.0"));
                    response.putArray("AudioMetadata");
                }
                default -> { }
            }
        }
        return response;
    }

    // ── Media analysis ─────────────────────────────────────────────────────

    public synchronized ObjectNode startMediaAnalysisJob(JsonNode request, String region) {
        String token = RekognitionCollectionService.text(request, "ClientRequestToken");
        if (token != null) {
            for (ObjectNode job : jobs.scan(k -> true)) {
                if (token.equals(job.path("token").asText(null)) && "MEDIA".equals(job.path("family").asText())) {
                    return objectMapper.createObjectNode().put("JobId", job.path("JobId").asText());
                }
            }
        }
        if (!request.path("OperationsConfig").isObject() || request.path("OperationsConfig").isEmpty()) {
            throw new AwsException("InvalidParameterException", "OperationsConfig is a required field.", 400);
        }
        if (RekognitionCollectionService.text(request.path("OutputConfig"), "S3Bucket") == null) {
            throw new AwsException("InvalidParameterException", "OutputConfig.S3Bucket is a required field.", 400);
        }
        requireReadable(request.path("Input").path("S3Object"));

        String jobId = jobId();
        ObjectNode job = objectMapper.createObjectNode();
        job.put("JobId", jobId);
        job.put("family", "MEDIA");
        job.put("region", region);
        job.put("started", RekognitionCollectionService.now());
        if (token != null) {
            job.put("token", token);
        }
        if (request.hasNonNull("JobName")) {
            job.put("JobName", request.get("JobName").asText());
        }
        for (String field : List.of("OperationsConfig", "Input", "OutputConfig", "KmsKeyId")) {
            if (request.has(field)) {
                job.set(field, request.get(field).deepCopy());
            }
        }
        jobs.put(jobId, job);
        return objectMapper.createObjectNode().put("JobId", jobId);
    }

    public ObjectNode getMediaAnalysisJob(JsonNode request) {
        String jobId = RekognitionCollectionService.text(request, "JobId");
        ObjectNode job = jobId == null ? null : jobs.get(jobId)
                .filter(j -> "MEDIA".equals(j.path("family").asText())).orElse(null);
        if (job == null) {
            throw new AwsException("ResourceNotFoundException", "The job " + jobId + " was not found.", 400);
        }
        return mediaView(job);
    }

    public ObjectNode listMediaAnalysisJobs(JsonNode request, String region) {
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw new AwsException("InvalidParameterException", "MaxResults must be between 1 and 100.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("MediaAnalysisJobs");
        jobs.scan(k -> true).stream()
                .filter(job -> "MEDIA".equals(job.path("family").asText()) && region.equals(job.path("region").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode job) -> job.path("started").asDouble()).reversed())
                .limit(max)
                .forEach(job -> list.add(mediaView(job)));
        return response;
    }

    private ObjectNode mediaView(ObjectNode job) {
        double started = job.path("started").asDouble();
        boolean done = (RekognitionCollectionService.now() - started) * 1000 >= RUNNING_MILLIS;
        ObjectNode view = objectMapper.createObjectNode();
        view.put("JobId", job.path("JobId").asText());
        if (job.hasNonNull("JobName")) {
            view.put("JobName", job.path("JobName").asText());
        }
        view.set("OperationsConfig", job.path("OperationsConfig").deepCopy());
        view.put("Status", done ? "SUCCEEDED" : "IN_PROGRESS");
        view.put("CreationTimestamp", started);
        view.set("Input", job.path("Input").deepCopy());
        view.set("OutputConfig", job.path("OutputConfig").deepCopy());
        if (job.hasNonNull("KmsKeyId")) {
            view.set("KmsKeyId", job.get("KmsKeyId").deepCopy());
        }
        if (done) {
            view.put("CompletionTimestamp", started + RUNNING_MILLIS / 1000.0);
            String prefix = job.path("OutputConfig").path("S3KeyPrefix").asText("");
            view.putObject("Results").putObject("S3Object")
                    .put("Bucket", job.path("OutputConfig").path("S3Bucket").asText())
                    .put("Name", prefix + job.path("JobId").asText() + "/results.jsonl");
            view.putObject("ManifestSummary").putObject("S3Object")
                    .put("Bucket", job.path("OutputConfig").path("S3Bucket").asText())
                    .put("Name", prefix + job.path("JobId").asText() + "/manifest-summary.json");
        }
        return view;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The object must exist; an unreadable one is the InvalidS3ObjectException Rekognition reports. */
    private void requireReadable(JsonNode s3Object) {
        String bucket = RekognitionCollectionService.text(s3Object, "Bucket");
        String name = RekognitionCollectionService.text(s3Object, "Name");
        if (bucket == null || name == null) {
            throw new AwsException("InvalidParameterException", "An S3Object with a Bucket and Name is required.", 400);
        }
        try {
            s3Service.getObject(bucket, name);
        } catch (AwsException e) {
            throw new AwsException("InvalidS3ObjectException", UNREADABLE_OBJECT, 400);
        }
    }

    /** Rekognition job ids are 64 hex characters. */
    private static String jobId() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
