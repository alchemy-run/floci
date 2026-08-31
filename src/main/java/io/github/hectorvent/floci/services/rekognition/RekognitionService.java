package io.github.hectorvent.floci.services.rekognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local Amazon Rekognition stub. Sync image APIs return deterministic empty
 * detections plus a Sky label (no ML). Collections, users, liveness sessions,
 * and async jobs are stored in memory. Video Start* validates S3 access and
 * surfaces {@code InvalidS3ObjectException} for a missing bucket/object.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/Welcome.html">Rekognition API</a>
 */
@ApplicationScoped
public class RekognitionService implements Resettable {

    static final String FACE_MODEL_VERSION = "7.0";
    static final String LABEL_MODEL_VERSION = "3.0";

    private record Collection(
            String collectionId,
            String collectionArn,
            long creationTimestamp,
            ConcurrentHashMap<String, UserRecord> users
    ) {}

    private record UserRecord(String userId, String userStatus) {}

    private record LivenessSession(String sessionId, String status) {}

    private record VideoJob(String jobId, String family, String status) {}

    private record MediaJob(String jobId, String status) {}

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ConcurrentHashMap<String, Collection> collections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LivenessSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VideoJob> videoJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MediaJob> mediaJobs = new ConcurrentHashMap<>();

    @Inject
    public RekognitionService(ObjectMapper objectMapper, RegionResolver regionResolver, S3Service s3Service) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
    }

    public void clear() {
        collections.clear();
        sessions.clear();
        videoJobs.clear();
        mediaJobs.clear();
    }

    public ObjectNode detectLabels(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode labels = root.putArray("Labels");
        ObjectNode sky = labels.addObject();
        sky.put("Name", "Sky");
        sky.put("Confidence", 99.0);
        sky.putArray("Instances");
        sky.putArray("Parents");
        root.put("LabelModelVersion", LABEL_MODEL_VERSION);
        return root;
    }

    public ObjectNode detectFaces(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceDetails");
        return root;
    }

    public ObjectNode detectModerationLabels(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("ModerationLabels");
        root.put("ModerationModelVersion", "7.0");
        return root;
    }

    public ObjectNode detectText(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("TextDetections");
        root.put("TextModelVersion", "3.0");
        return root;
    }

    public ObjectNode detectProtectiveEquipment(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("Persons");
        root.put("ProtectiveEquipmentModelVersion", "1.0");
        return root;
    }

    public ObjectNode recognizeCelebrities(JsonNode request) {
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("CelebrityFaces");
        root.putArray("UnrecognizedFaces");
        return root;
    }

    public ObjectNode compareFaces(JsonNode request) {
        requireImage(request, "SourceImage");
        requireImage(request, "TargetImage");
        throw noFaces();
    }

    public ObjectNode getCelebrityInfo(JsonNode request) {
        String id = stringField(request, "Id");
        if (id == null || id.isBlank()) {
            throw invalid("Id is required.");
        }
        throw notFound("The requested celebrity could not be found.");
    }

    public ObjectNode createCollection(JsonNode request, String region) {
        String collectionId = requireField(request, "CollectionId");
        if (collections.containsKey(collectionId)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The collection " + collectionId + " already exists.", 400);
        }
        String resolvedRegion = region != null ? region : regionResolver.getDefaultRegion();
        String arn = "arn:aws:rekognition:" + resolvedRegion + ":" + regionResolver.getAccountId()
                + ":collection/" + collectionId;
        collections.put(collectionId, new Collection(
                collectionId, arn, Instant.now().getEpochSecond(), new ConcurrentHashMap<>()));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("StatusCode", 200);
        root.put("CollectionArn", arn);
        root.put("FaceModelVersion", FACE_MODEL_VERSION);
        return root;
    }

    public ObjectNode deleteCollection(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        collections.remove(collection.collectionId);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("StatusCode", 200);
        return root;
    }

    public ObjectNode describeCollection(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("FaceCount", 0);
        root.put("FaceModelVersion", FACE_MODEL_VERSION);
        root.put("CollectionARN", collection.collectionArn);
        root.put("CreationTimestamp", collection.creationTimestamp);
        root.put("UserCount", collection.users.size());
        return root;
    }

    public ObjectNode listCollections() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode ids = root.putArray("CollectionIds");
        ArrayNode versions = root.putArray("FaceModelVersions");
        List<String> keys = new ArrayList<>(collections.keySet());
        keys.sort(String::compareTo);
        for (String id : keys) {
            ids.add(id);
            versions.add(FACE_MODEL_VERSION);
        }
        return root;
    }

    public ObjectNode indexFaces(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        requireImage(request, "Image");
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FaceRecords");
        root.put("FaceModelVersion", FACE_MODEL_VERSION);
        root.putArray("UnindexedFaces");
        return root;
    }

    public ObjectNode listFaces(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("Faces");
        root.put("FaceModelVersion", FACE_MODEL_VERSION);
        return root;
    }

    public ObjectNode deleteFaces(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        JsonNode faceIds = request == null ? null : request.get("FaceIds");
        if (faceIds == null || !faceIds.isArray() || faceIds.isEmpty()) {
            throw invalid("FaceIds is required.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("DeletedFaces");
        ArrayNode unsuccessful = root.putArray("UnsuccessfulFaceDeletions");
        for (JsonNode faceId : faceIds) {
            ObjectNode entry = unsuccessful.addObject();
            entry.put("FaceId", faceId.asText());
            entry.put("Reasons", objectMapper.createArrayNode().add("ASSOCIATED_TO_USER"));
        }
        return root;
    }

    public ObjectNode searchFaces(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        String faceId = stringField(request, "FaceId");
        if (faceId == null || faceId.isBlank()) {
            throw invalid("FaceId is required.");
        }
        throw notFound("The face id " + faceId + " is not found.");
    }

    public ObjectNode searchFacesByImage(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        requireImage(request, "Image");
        throw noFaces();
    }

    public ObjectNode createUser(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        String userId = requireField(request, "UserId");
        if (collection.users.putIfAbsent(userId, new UserRecord(userId, "CREATED")) != null) {
            throw new AwsException("ConflictException",
                    "The user " + userId + " already exists.", 400);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteUser(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        String userId = requireField(request, "UserId");
        if (collection.users.remove(userId) == null) {
            throw notFound("The user " + userId + " is not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listUsers(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode users = root.putArray("Users");
        collection.users.values().stream()
                .sorted((a, b) -> a.userId.compareTo(b.userId))
                .forEach(user -> {
                    ObjectNode node = users.addObject();
                    node.put("UserId", user.userId);
                    node.put("UserStatus", user.userStatus);
                });
        return root;
    }

    public ObjectNode associateFaces(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        requireUser(collection, stringField(request, "UserId"));
        JsonNode faceIds = request == null ? null : request.get("FaceIds");
        if (faceIds == null || !faceIds.isArray() || faceIds.isEmpty()) {
            throw invalid("FaceIds is required.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("AssociatedFaces");
        ArrayNode unsuccessful = root.putArray("UnsuccessfulFaceAssociations");
        for (JsonNode faceId : faceIds) {
            ObjectNode entry = unsuccessful.addObject();
            entry.put("FaceId", faceId.asText());
            entry.put("Reasons", objectMapper.createArrayNode().add("FACE_NOT_FOUND"));
            entry.put("Confidence", 0.0);
        }
        root.put("UserStatus", "CREATED");
        return root;
    }

    public ObjectNode disassociateFaces(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        requireUser(collection, stringField(request, "UserId"));
        JsonNode faceIds = request == null ? null : request.get("FaceIds");
        if (faceIds == null || !faceIds.isArray() || faceIds.isEmpty()) {
            throw invalid("FaceIds is required.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("DisassociatedFaces");
        ArrayNode unsuccessful = root.putArray("UnsuccessfulFaceDisassociations");
        for (JsonNode faceId : faceIds) {
            ObjectNode entry = unsuccessful.addObject();
            entry.put("FaceId", faceId.asText());
            entry.put("Reasons", objectMapper.createArrayNode().add("FACE_NOT_FOUND"));
            entry.put("UserId", stringField(request, "UserId"));
        }
        root.put("UserStatus", "CREATED");
        return root;
    }

    public ObjectNode searchUsers(JsonNode request) {
        Collection collection = requireCollection(stringField(request, "CollectionId"));
        String userId = stringField(request, "UserId");
        String faceId = stringField(request, "FaceId");
        if ((userId == null || userId.isBlank()) && (faceId == null || faceId.isBlank())) {
            throw invalid("UserId or FaceId is required.");
        }
        if (userId != null && !userId.isBlank()) {
            requireUser(collection, userId);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("UserMatches");
        root.put("FaceModelVersion", FACE_MODEL_VERSION);
        return root;
    }

    public ObjectNode searchUsersByImage(JsonNode request) {
        requireCollection(stringField(request, "CollectionId"));
        requireImage(request, "Image");
        throw noFaces();
    }

    public ObjectNode createFaceLivenessSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new LivenessSession(sessionId, "CREATED"));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("SessionId", sessionId);
        return root;
    }

    public ObjectNode getFaceLivenessSessionResults(JsonNode request) {
        String sessionId = requireField(request, "SessionId");
        LivenessSession session = sessions.get(sessionId);
        if (session == null) {
            throw new AwsException("SessionNotFoundException",
                    "The requested session was not found.", 400);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("SessionId", session.sessionId);
        root.put("Status", session.status);
        return root;
    }

    public ObjectNode startVideoJob(String family, JsonNode request) {
        requireS3Video(request);
        String jobId = newJobId();
        videoJobs.put(jobKey(family, jobId), new VideoJob(jobId, family, "IN_PROGRESS"));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return root;
    }

    public ObjectNode startFaceSearch(JsonNode request) {
        String collectionId = stringField(request, "CollectionId");
        if (collectionId == null || collectionId.isBlank() || !collections.containsKey(collectionId)) {
            throw notFound("The collection " + collectionId + " is not found.");
        }
        return startVideoJob("FACE_SEARCH", request);
    }

    public ObjectNode getVideoJob(String family, JsonNode request) {
        String jobId = requireField(request, "JobId");
        VideoJob job = videoJobs.get(jobKey(family, jobId));
        if (job == null) {
            throw notFound("The job id " + jobId + " is not found.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobStatus", job.status);
        root.put("JobId", job.jobId);
        root.putArray("Celebrities");
        root.putArray("ModerationLabels");
        root.putArray("Faces");
        root.putArray("Persons");
        root.putArray("Labels");
        root.putArray("Segments");
        root.putArray("TextDetections");
        return root;
    }

    public ObjectNode startMediaAnalysisJob(JsonNode request) {
        JsonNode input = request == null ? null : request.get("Input");
        requireS3Object(input == null ? null : input.get("S3Object"));
        String jobId = newJobId();
        mediaJobs.put(jobId, new MediaJob(jobId, "IN_PROGRESS"));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", jobId);
        return root;
    }

    public ObjectNode getMediaAnalysisJob(JsonNode request) {
        String jobId = requireField(request, "JobId");
        MediaJob job = mediaJobs.get(jobId);
        if (job == null) {
            throw notFound("The job id " + jobId + " is not found.");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("JobId", job.jobId);
        root.put("Status", job.status);
        return root;
    }

    public ObjectNode listMediaAnalysisJobs() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode jobs = root.putArray("MediaAnalysisJobs");
        mediaJobs.values().stream()
                .sorted((a, b) -> a.jobId.compareTo(b.jobId))
                .forEach(job -> {
                    ObjectNode node = jobs.addObject();
                    node.put("JobId", job.jobId);
                    node.put("Status", job.status);
                });
        return root;
    }

    public ObjectNode listStreamProcessors() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("StreamProcessors");
        return root;
    }

    public ObjectNode describeStreamProcessor(JsonNode request) {
        requireField(request, "Name");
        throw notFound("The stream processor was not found.");
    }

    public ObjectNode startStreamProcessor(JsonNode request) {
        requireField(request, "Name");
        throw notFound("The stream processor was not found.");
    }

    public ObjectNode stopStreamProcessor(JsonNode request) {
        requireField(request, "Name");
        throw notFound("The stream processor was not found.");
    }

    public ObjectNode detectCustomLabels(JsonNode request) {
        requireField(request, "ProjectVersionArn");
        requireImage(request, "Image");
        throw notFound("The project version was not found.");
    }

    public ObjectNode describeProjects() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("ProjectDescriptions");
        return root;
    }

    public ObjectNode describeProjectVersions(JsonNode request) {
        requireField(request, "ProjectArn");
        throw notFound("The project was not found.");
    }

    public ObjectNode startProjectVersion(JsonNode request) {
        requireField(request, "ProjectVersionArn");
        throw notFound("The project version was not found.");
    }

    public ObjectNode stopProjectVersion(JsonNode request) {
        requireField(request, "ProjectVersionArn");
        throw notFound("The project version was not found.");
    }

    private void requireImage(JsonNode request, String field) {
        JsonNode image = request == null ? null : request.get(field);
        if (image == null || image.isNull() || image.isMissingNode()) {
            throw invalid(field + " is required.");
        }
        JsonNode bytes = image.get("Bytes");
        JsonNode s3 = image.get("S3Object");
        boolean hasBytes = bytes != null && !bytes.isNull() && !bytes.isMissingNode()
                && !(bytes.isTextual() && bytes.asText().isBlank());
        boolean hasS3 = s3 != null && !s3.isNull() && !s3.isMissingNode();
        if (!hasBytes && !hasS3) {
            throw invalid(field + " must contain Bytes or S3Object.");
        }
        if (hasS3) {
            requireS3Object(s3);
        }
    }

    private void requireS3Video(JsonNode request) {
        JsonNode video = request == null ? null : request.get("Video");
        if (video == null || video.isNull() || video.isMissingNode()) {
            throw invalid("Video is required.");
        }
        requireS3Object(video.get("S3Object"));
    }

    private void requireS3Object(JsonNode s3Object) {
        if (s3Object == null || s3Object.isNull() || s3Object.isMissingNode()) {
            throw invalid("S3Object is required.");
        }
        String bucket = stringField(s3Object, "Bucket");
        String name = stringField(s3Object, "Name");
        if (bucket == null || bucket.isBlank() || name == null || name.isBlank()) {
            throw invalid("S3Object Bucket and Name are required.");
        }
        try {
            s3Service.headBucket(bucket);
            s3Service.getObject(bucket, name);
        } catch (AwsException e) {
            throw new AwsException("InvalidS3ObjectException",
                    "Unable to get object metadata from S3. Check object key, region and/or access permissions.",
                    400);
        } catch (RuntimeException e) {
            throw new AwsException("InvalidS3ObjectException",
                    "Unable to get object metadata from S3. Check object key, region and/or access permissions.",
                    400);
        }
    }

    private Collection requireCollection(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw invalid("CollectionId is required.");
        }
        Collection collection = collections.get(collectionId);
        if (collection == null) {
            throw notFound("The collection " + collectionId + " is not found.");
        }
        return collection;
    }

    private static void requireUser(Collection collection, String userId) {
        if (userId == null || userId.isBlank()) {
            throw invalid("UserId is required.");
        }
        if (!collection.users.containsKey(userId)) {
            throw notFound("The user " + userId + " is not found.");
        }
    }

    private static String requireField(JsonNode request, String field) {
        String value = stringField(request, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String stringField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    private static String jobKey(String family, String jobId) {
        return family + ":" + jobId;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException noFaces() {
        return new AwsException("InvalidParameterException",
                "There are no faces in the image. To add a face to a collection, use the IndexFaces operation.",
                400);
    }
}
