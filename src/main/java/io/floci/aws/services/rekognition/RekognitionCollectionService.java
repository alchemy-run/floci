package io.floci.aws.services.rekognition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AiMockConfigLoader;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rekognition's stored state: face collections with their faces and users,
 * Face Liveness sessions, Custom Labels projects, stream processors, and tags.
 * <p>
 * Floci decodes no images, so an image finds no faces unless the mock config
 * names one: {@code IndexFaces}, {@code SearchFacesByImage}, and
 * {@code SearchUsersByImage} look up the {@code DetectFaces} mock of the
 * image's {@code Bucket/Name} and index or search its {@code FaceDetails}.
 * Without one, IndexFaces indexes nothing and the by-image searches report
 * the {@code InvalidParameterException} Rekognition gives an image without a face.
 * Projects can be created but never trained, so every model version is unknown.
 */
@ApplicationScoped
public class RekognitionCollectionService {
    static final String FACE_MODEL_VERSION = "7.0";
    private static final Pattern ID = Pattern.compile("^[a-zA-Z0-9_.\\-]+$");
    private static final Pattern FACE_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** A liveness session can be used for three minutes, then it has expired. */
    private static final long LIVENESS_TTL_SECONDS = 180;

    private final StorageBackend<String, ObjectNode> collections;
    private final StorageBackend<String, ObjectNode> sessions;
    private final StorageBackend<String, ObjectNode> projects;
    private final StorageBackend<String, ObjectNode> processors;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final AiMockConfigLoader mockConfigLoader;
    private final ObjectMapper objectMapper;

    @Inject
    public RekognitionCollectionService(StorageFactory storageFactory, RegionResolver regionResolver,
                                        AiMockConfigLoader mockConfigLoader, ObjectMapper objectMapper) {
        this.collections = store(storageFactory, "rekognition-collections.json");
        this.sessions = store(storageFactory, "rekognition-liveness-sessions.json");
        this.projects = store(storageFactory, "rekognition-projects.json");
        this.processors = store(storageFactory, "rekognition-stream-processors.json");
        this.tags = store(storageFactory, "rekognition-tags.json");
        this.regionResolver = regionResolver;
        this.mockConfigLoader = mockConfigLoader;
        this.objectMapper = objectMapper;
    }

    private static StorageBackend<String, ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("rekognition", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    // ── Collections ────────────────────────────────────────────────────────

    public synchronized ObjectNode createCollection(JsonNode request, String region) {
        String id = requireId(request, "CollectionId");
        if (collections.get(key(region, id)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The collection id: " + id + " already exists", 400);
        }
        String arn = regionResolver.buildArn("rekognition", region, "collection/" + id);
        ObjectNode collection = objectMapper.createObjectNode();
        collection.put("CollectionId", id);
        collection.put("CollectionARN", arn);
        collection.put("CreationTimestamp", now());
        collection.putObject("faces");
        collection.putObject("users");
        collections.put(key(region, id), collection);
        putTags(arn, request.get("Tags"));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("StatusCode", 200);
        response.put("CollectionArn", arn);
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        return response;
    }

    public ObjectNode describeCollection(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FaceCount", collection.path("faces").size());
        response.put("UserCount", collection.path("users").size());
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        response.put("CollectionARN", collection.path("CollectionARN").asText());
        response.put("CreationTimestamp", collection.path("CreationTimestamp").asDouble());
        return response;
    }

    public ObjectNode listCollections(JsonNode request, String region) {
        List<String> ids = collections.scan(k -> k.startsWith(region + "/")).stream()
                .map(c -> c.path("CollectionId").asText()).sorted().toList();
        Page page = page(ids.size(), request, 4096);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("CollectionIds");
        ArrayNode versions = response.putArray("FaceModelVersions");
        for (String id : ids.subList(page.start(), page.end())) {
            list.add(id);
            versions.add(FACE_MODEL_VERSION);
        }
        page.next().ifPresent(next -> response.put("NextToken", next));
        return response;
    }

    public synchronized ObjectNode deleteCollection(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        collections.delete(key(region, collection.path("CollectionId").asText()));
        tags.delete(collection.path("CollectionARN").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StatusCode", 200);
        return response;
    }

    // ── Faces ──────────────────────────────────────────────────────────────

    public synchronized ObjectNode indexFaces(JsonNode request, String region, String imageKey) {
        ObjectNode collection = requireCollection(request, region);
        int maxFaces = request.path("MaxFaces").asInt(Integer.MAX_VALUE);
        String externalImageId = text(request, "ExternalImageId");
        String imageId = UUID.randomUUID().toString();
        ObjectNode faces = (ObjectNode) collection.get("faces");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = response.putArray("FaceRecords");
        ArrayNode unindexed = response.putArray("UnindexedFaces");
        int indexed = 0;
        for (JsonNode detail : facesIn(imageKey)) {
            if (indexed >= maxFaces) {
                ObjectNode skipped = unindexed.addObject();
                skipped.putArray("Reasons").add("EXCEEDS_MAX_FACES");
                skipped.set("FaceDetail", detail.deepCopy());
                continue;
            }
            ObjectNode face = objectMapper.createObjectNode();
            face.put("FaceId", UUID.randomUUID().toString());
            face.set("BoundingBox", detail.path("BoundingBox").deepCopy());
            face.put("ImageId", imageId);
            if (externalImageId != null) {
                face.put("ExternalImageId", externalImageId);
            }
            face.put("Confidence", detail.path("Confidence").asDouble(99.9));
            face.put("IndexFacesModelVersion", FACE_MODEL_VERSION);
            faces.set(face.path("FaceId").asText(), face);
            ObjectNode record = records.addObject();
            record.set("Face", face.deepCopy());
            record.set("FaceDetail", detail.deepCopy());
            indexed++;
        }
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        return response;
    }

    public ObjectNode listFaces(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = text(request, "UserId");
        List<String> filter = strings(request.get("FaceIds"));
        List<JsonNode> faces = new ArrayList<>();
        collection.path("faces").forEach(face -> {
            boolean userMatch = userId == null || userId.equals(face.path("UserId").asText(null));
            boolean idMatch = filter.isEmpty() || filter.contains(face.path("FaceId").asText());
            if (userMatch && idMatch) {
                faces.add(face);
            }
        });
        Page page = page(faces.size(), request, 4096);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Faces");
        faces.subList(page.start(), page.end()).forEach(face -> list.add(face.deepCopy()));
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        page.next().ifPresent(next -> response.put("NextToken", next));
        return response;
    }

    public synchronized ObjectNode deleteFaces(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        List<String> ids = requireFaceIds(request);
        ObjectNode faces = (ObjectNode) collection.get("faces");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode deleted = response.putArray("DeletedFaces");
        ArrayNode failed = response.putArray("UnsuccessfulFaceDeletions");
        for (String id : ids) {
            JsonNode face = faces.get(id);
            if (face == null) {
                ObjectNode failure = failed.addObject().put("FaceId", id);
                failure.putArray("Reasons").add("FACE_NOT_FOUND");
                continue;
            }
            if (face.hasNonNull("UserId")) {
                ObjectNode failure = failed.addObject().put("FaceId", id).put("UserId", face.path("UserId").asText());
                failure.putArray("Reasons").add("ASSOCIATED_TO_AN_EXISTING_USER");
                continue;
            }
            faces.remove(id);
            deleted.add(id);
        }
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        return response;
    }

    public ObjectNode searchFaces(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String faceId = text(request, "FaceId");
        if (faceId == null || collection.path("faces").get(faceId) == null) {
            throw new AwsException("InvalidParameterException", "Invalid face ID " + faceId, 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SearchedFaceId", faceId);
        response.putArray("FaceMatches");
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        return response;
    }

    public ObjectNode searchFacesByImage(JsonNode request, String region, String imageKey) {
        requireCollection(request, region);
        JsonNode face = firstFace(imageKey);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("SearchedFaceBoundingBox", face.path("BoundingBox").deepCopy());
        response.put("SearchedFaceConfidence", face.path("Confidence").asDouble(99.9));
        response.putArray("FaceMatches");
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        return response;
    }

    // ── Users ──────────────────────────────────────────────────────────────

    public synchronized ObjectNode createUser(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = requireId(request, "UserId");
        ObjectNode users = (ObjectNode) collection.get("users");
        if (users.has(userId)) {
            throw new AwsException("ConflictException", "User " + userId + " already exists.", 400);
        }
        users.putObject(userId).put("UserId", userId).put("UserStatus", "ACTIVE");
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteUser(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = requireUser(collection, text(request, "UserId"));
        ((ObjectNode) collection.get("users")).remove(userId);
        collection.path("faces").forEach(face -> {
            if (userId.equals(face.path("UserId").asText(null))) {
                ((ObjectNode) face).remove("UserId");
            }
        });
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listUsers(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        List<JsonNode> users = new ArrayList<>();
        collection.path("users").forEach(users::add);
        Page page = page(users.size(), request, 500);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Users");
        users.subList(page.start(), page.end()).forEach(user -> list.add(user.deepCopy()));
        page.next().ifPresent(next -> response.put("NextToken", next));
        return response;
    }

    public synchronized ObjectNode associateFaces(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = requireUser(collection, text(request, "UserId"));
        ObjectNode faces = (ObjectNode) collection.get("faces");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode associated = response.putArray("AssociatedFaces");
        ArrayNode failed = response.putArray("UnsuccessfulFaceAssociations");
        for (String id : requireFaceIds(request)) {
            JsonNode face = faces.get(id);
            String owner = face == null ? null : face.path("UserId").asText(null);
            if (face == null || (owner != null && !owner.equals(userId))) {
                ObjectNode failure = failed.addObject().put("FaceId", id);
                failure.putArray("Reasons").add(face == null ? "FACE_NOT_FOUND" : "ASSOCIATED_TO_A_DIFFERENT_USER");
                if (owner != null) {
                    failure.put("UserId", owner);
                }
                continue;
            }
            ((ObjectNode) face).put("UserId", userId);
            associated.addObject().put("FaceId", id);
        }
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        response.put("UserStatus", "ACTIVE");
        return response;
    }

    public synchronized ObjectNode disassociateFaces(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = requireUser(collection, text(request, "UserId"));
        ObjectNode faces = (ObjectNode) collection.get("faces");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode disassociated = response.putArray("DisassociatedFaces");
        ArrayNode failed = response.putArray("UnsuccessfulFaceDisassociations");
        for (String id : requireFaceIds(request)) {
            JsonNode face = faces.get(id);
            if (face == null || !userId.equals(face.path("UserId").asText(null))) {
                ObjectNode failure = failed.addObject().put("FaceId", id);
                failure.putArray("Reasons").add(face == null ? "FACE_NOT_FOUND" : "ASSOCIATED_TO_A_DIFFERENT_USER");
                continue;
            }
            ((ObjectNode) face).remove("UserId");
            disassociated.addObject().put("FaceId", id);
        }
        collections.put(key(region, collection.path("CollectionId").asText()), collection);
        response.put("UserStatus", "ACTIVE");
        return response;
    }

    /** SearchUsers by a user or a face: the matches are the users other faces belong to. */
    public ObjectNode searchUsers(JsonNode request, String region) {
        ObjectNode collection = requireCollection(request, region);
        String userId = text(request, "UserId");
        String faceId = text(request, "FaceId");
        if ((userId == null) == (faceId == null)) {
            throw new AwsException("InvalidParameterException", "Specify exactly one of UserId or FaceId.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("UserMatches");
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        if (userId != null) {
            requireUser(collection, userId);
            response.putObject("SearchedUser").put("UserId", userId);
        } else {
            if (collection.path("faces").get(faceId) == null) {
                throw new AwsException("InvalidParameterException", "Invalid face ID " + faceId, 400);
            }
            response.putObject("SearchedFace").put("FaceId", faceId);
        }
        return response;
    }

    public ObjectNode searchUsersByImage(JsonNode request, String region, String imageKey) {
        requireCollection(request, region);
        JsonNode face = firstFace(imageKey);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("UserMatches");
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        ObjectNode searched = response.putObject("SearchedFace");
        searched.set("FaceDetail", face.deepCopy());
        response.putArray("UnsearchedFaces");
        return response;
    }

    private List<JsonNode> facesIn(String imageKey) {
        List<JsonNode> faces = new ArrayList<>();
        if (imageKey != null) {
            mockConfigLoader.lookup("rekognition", imageKey, "DetectFaces")
                    .ifPresent(mock -> mock.path("FaceDetails").forEach(faces::add));
        }
        return faces;
    }

    private JsonNode firstFace(String imageKey) {
        List<JsonNode> faces = facesIn(imageKey);
        if (faces.isEmpty()) {
            throw new AwsException("InvalidParameterException", "There are no faces in the image.", 400);
        }
        return faces.getFirst();
    }

    // ── Face Liveness ──────────────────────────────────────────────────────

    public synchronized ObjectNode createFaceLivenessSession(JsonNode request, String region) {
        String sessionId = UUID.randomUUID().toString();
        ObjectNode session = objectMapper.createObjectNode();
        session.put("SessionId", sessionId);
        session.put("CreatedAt", now());
        session.put("region", region);
        if (request.has("Settings")) {
            session.set("Settings", request.get("Settings").deepCopy());
        }
        sessions.put(sessionId, session);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SessionId", sessionId);
        return response;
    }

    /**
     * A session no client has streamed to is {@code CREATED} until it expires
     * three minutes after creation; Floci runs no liveness check, so a session
     * never reaches {@code SUCCEEDED}.
     */
    public ObjectNode getFaceLivenessSessionResults(JsonNode request) {
        String sessionId = text(request, "SessionId");
        ObjectNode session = sessionId == null ? null : sessions.get(sessionId).orElse(null);
        if (session == null) {
            throw new AwsException("SessionNotFoundException",
                    "Face Liveness session " + sessionId + " was not found.", 400);
        }
        boolean expired = now() - session.path("CreatedAt").asDouble() > LIVENESS_TTL_SECONDS;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SessionId", sessionId);
        response.put("Status", expired ? "EXPIRED" : "CREATED");
        return response;
    }

    // ── Custom Labels projects ─────────────────────────────────────────────

    public synchronized ObjectNode createProject(JsonNode request, String region) {
        String name = requireId(request, "ProjectName");
        boolean exists = projects.scan(k -> k.startsWith(region + "/")).stream()
                .anyMatch(p -> name.equals(p.path("name").asText()));
        if (exists) {
            throw new AwsException("ResourceInUseException", "Project " + name + " already exists.", 400);
        }
        long created = Instant.now().toEpochMilli();
        String arn = regionResolver.buildArn("rekognition", region, "project/" + name + "/" + created);
        ObjectNode project = objectMapper.createObjectNode();
        project.put("ProjectArn", arn);
        project.put("CreationTimestamp", created / 1000.0);
        project.put("Status", "CREATED");
        project.put("Feature", text(request, "Feature") == null ? "CUSTOM_LABELS" : text(request, "Feature"));
        project.put("AutoUpdate", text(request, "AutoUpdate") == null ? "DISABLED" : text(request, "AutoUpdate"));
        project.put("name", name);
        projects.put(key(region, arn), project);
        putTags(arn, request.get("Tags"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ProjectArn", arn);
        return response;
    }

    public synchronized ObjectNode deleteProject(JsonNode request, String region) {
        String arn = requireProject(request, region).path("ProjectArn").asText();
        projects.delete(key(region, arn));
        tags.delete(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Status", "DELETING");
        return response;
    }

    public ObjectNode describeProjects(JsonNode request, String region) {
        List<String> names = strings(request.get("ProjectNames"));
        List<ObjectNode> found = projects.scan(k -> k.startsWith(region + "/")).stream()
                .filter(p -> names.isEmpty() || names.contains(p.path("name").asText()))
                .toList();
        Page page = page(found.size(), request, 100);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ProjectDescriptions");
        found.subList(page.start(), page.end()).forEach(p -> {
            ObjectNode copy = p.deepCopy();
            copy.remove("name");
            list.add(copy);
        });
        page.next().ifPresent(next -> response.put("NextToken", next));
        return response;
    }

    /** Projects are never trained, so a project has no versions and every version ARN is unknown. */
    public ObjectNode describeProjectVersions(JsonNode request, String region) {
        requireProject(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ProjectVersionDescriptions");
        return response;
    }

    public ObjectNode unknownProjectVersion(JsonNode request) {
        String arn = text(request, "ProjectVersionArn");
        if (arn == null) {
            throw new AwsException("InvalidParameterException", "ProjectVersionArn is a required field.", 400);
        }
        throw new AwsException("ResourceNotFoundException", "The project version " + arn + " was not found.", 400);
    }

    private ObjectNode requireProject(JsonNode request, String region) {
        String arn = text(request, "ProjectArn");
        if (arn == null) {
            throw new AwsException("InvalidParameterException", "ProjectArn is a required field.", 400);
        }
        return projects.get(key(region, arn)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The project " + arn + " was not found.", 400));
    }

    // ── Stream processors ──────────────────────────────────────────────────

    public synchronized ObjectNode createStreamProcessor(JsonNode request, String region) {
        String name = requireId(request, "Name");
        if (processors.get(key(region, name)).isPresent()) {
            throw new AwsException("ResourceInUseException", "Stream processor " + name + " already exists.", 400);
        }
        for (String field : List.of("Input", "Output", "Settings", "RoleArn")) {
            if (!request.hasNonNull(field)) {
                throw new AwsException("InvalidParameterException", field + " is a required field.", 400);
            }
        }
        JsonNode faceSearch = request.path("Settings").path("FaceSearch");
        if (faceSearch.hasNonNull("CollectionId")
                && collections.get(key(region, faceSearch.path("CollectionId").asText())).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The collection id: " + faceSearch.path("CollectionId").asText() + " does not exist", 400);
        }
        String arn = regionResolver.buildArn("rekognition", region, "streamprocessor/" + name);
        ObjectNode processor = objectMapper.createObjectNode();
        processor.put("Name", name);
        processor.put("StreamProcessorArn", arn);
        processor.put("Status", "STOPPED");
        processor.put("CreationTimestamp", now());
        processor.put("LastUpdateTimestamp", now());
        for (String field : List.of("Input", "Output", "Settings", "RoleArn", "NotificationChannel", "KmsKeyId",
                "RegionsOfInterest", "DataSharingPreference")) {
            if (request.has(field)) {
                processor.set(field, request.get(field).deepCopy());
            }
        }
        processors.put(key(region, name), processor);
        putTags(arn, request.get("Tags"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StreamProcessorArn", arn);
        return response;
    }

    public ObjectNode describeStreamProcessor(JsonNode request, String region) {
        return requireProcessor(request, region).deepCopy();
    }

    public ObjectNode listStreamProcessors(JsonNode request, String region) {
        List<ObjectNode> all = processors.scan(k -> k.startsWith(region + "/"));
        Page page = page(all.size(), request, 100);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("StreamProcessors");
        all.subList(page.start(), page.end()).forEach(p -> list.addObject()
                .put("Name", p.path("Name").asText()).put("Status", p.path("Status").asText()));
        page.next().ifPresent(next -> response.put("NextToken", next));
        return response;
    }

    public synchronized ObjectNode startStreamProcessor(JsonNode request, String region) {
        ObjectNode processor = requireProcessor(request, region);
        if ("RUNNING".equals(processor.path("Status").asText())) {
            throw new AwsException("ResourceInUseException", "The stream processor is already running.", 400);
        }
        processor.put("Status", "RUNNING");
        processor.put("LastUpdateTimestamp", now());
        processors.put(key(region, processor.path("Name").asText()), processor);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SessionId", UUID.randomUUID().toString());
        return response;
    }

    public synchronized ObjectNode stopStreamProcessor(JsonNode request, String region) {
        ObjectNode processor = requireProcessor(request, region);
        if (!"RUNNING".equals(processor.path("Status").asText())) {
            throw new AwsException("ResourceInUseException", "The stream processor is not running.", 400);
        }
        processor.put("Status", "STOPPED");
        processor.put("LastUpdateTimestamp", now());
        processors.put(key(region, processor.path("Name").asText()), processor);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteStreamProcessor(JsonNode request, String region) {
        ObjectNode processor = requireProcessor(request, region);
        processors.delete(key(region, processor.path("Name").asText()));
        tags.delete(processor.path("StreamProcessorArn").asText());
        return objectMapper.createObjectNode();
    }

    private ObjectNode requireProcessor(JsonNode request, String region) {
        String name = text(request, "Name");
        if (name == null) {
            throw new AwsException("InvalidParameterException", "Name is a required field.", 400);
        }
        return processors.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Stream processor " + name + " was not found.", 400));
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode tagResource(JsonNode request) {
        String arn = requireTaggable(text(request, "ResourceArn"));
        JsonNode map = request.get("Tags");
        if (map != null && map.isObject()) {
            ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
            current.setAll((ObjectNode) map);
            tags.put(arn, current);
        }
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
        response.set("Tags", tags.get(arn).orElse(objectMapper.createObjectNode()).deepCopy());
        return response;
    }

    /** Rekognition tags are a string map on the wire ({@code "Tags": {"k": "v"}}). */
    private void putTags(String arn, JsonNode map) {
        if (map != null && map.isObject() && !map.isEmpty()) {
            ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
            current.setAll((ObjectNode) map);
            tags.put(arn, current);
        }
    }

    private String requireTaggable(String arn) {
        if (arn == null) {
            throw new AwsException("InvalidParameterException", "ResourceArn is a required field.", 400);
        }
        boolean known = collections.scan(k -> true).stream().anyMatch(c -> arn.equals(c.path("CollectionARN").asText()))
                || projects.scan(k -> true).stream().anyMatch(p -> arn.equals(p.path("ProjectArn").asText()))
                || processors.scan(k -> true).stream().anyMatch(p -> arn.equals(p.path("StreamProcessorArn").asText()));
        if (!known) {
            throw new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 400);
        }
        return arn;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    boolean collectionExists(String region, String id) {
        return id != null && collections.get(key(region, id)).isPresent();
    }

    private ObjectNode requireCollection(JsonNode request, String region) {
        String id = text(request, "CollectionId");
        if (id == null || id.isEmpty()) {
            throw new AwsException("InvalidParameterException", "CollectionId is a required field.", 400);
        }
        return collections.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The collection id: " + id + " does not exist", 400));
    }

    private static String requireUser(ObjectNode collection, String userId) {
        if (userId == null || !collection.path("users").has(userId)) {
            throw new AwsException("ResourceNotFoundException", "User " + userId + " was not found.", 400);
        }
        return userId;
    }

    private static List<String> requireFaceIds(JsonNode request) {
        List<String> ids = strings(request.get("FaceIds"));
        if (ids.isEmpty()) {
            throw new AwsException("InvalidParameterException", "FaceIds is a required field.", 400);
        }
        for (String id : ids) {
            if (!FACE_ID.matcher(id).matches()) {
                throw new AwsException("InvalidParameterException", "Invalid face ID " + id, 400);
            }
        }
        return ids;
    }

    private static String requireId(JsonNode request, String field) {
        String id = text(request, field);
        if (id == null || id.isEmpty() || id.length() > 255 || !ID.matcher(id).matches()) {
            throw new AwsException("InvalidParameterException",
                    field + " must match [a-zA-Z0-9_.\\-]+ and be at most 255 characters.", 400);
        }
        return id;
    }

    private record Page(int start, int end, Optional<String> next) {}

    private static Page page(int size, JsonNode request, int maxLimit) {
        int max = request.path("MaxResults").asInt(maxLimit);
        if (max < 1 || max > maxLimit) {
            throw new AwsException("InvalidParameterException",
                    "MaxResults must be between 1 and " + maxLimit + ".", 400);
        }
        int start = 0;
        String token = text(request, "NextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidPaginationTokenException", "Invalid pagination token.", 400);
            }
        }
        if (start < 0 || start > size) {
            throw new AwsException("InvalidPaginationTokenException", "Invalid pagination token.", 400);
        }
        int end = Math.min(size, start + max);
        return new Page(start, end, end < size ? Optional.of(Integer.toString(end)) : Optional.empty());
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            Iterator<JsonNode> items = array.elements();
            while (items.hasNext()) {
                values.add(items.next().asText());
            }
        }
        return values;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }
}
