package io.github.hectorvent.floci.services.s3files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3files.model.S3FilesAccessPoint;
import io.github.hectorvent.floci.services.s3files.model.S3FilesFileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Amazon S3 Files restJson1 — file systems and access points over S3 buckets.
 *
 * <p>Resources become {@code available} immediately so Alchemy wait-loops do not stall.
 */
@ApplicationScoped
public class S3FilesService implements Resettable {

    static final String SERVICE = "s3files";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final StorageBackend<String, S3FilesFileSystem> fileSystems;
    private final StorageBackend<String, S3FilesAccessPoint> accessPoints;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public S3FilesService(StorageFactory factory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.fileSystems = factory.create("s3files", "s3files-file-systems.json",
                new TypeReference<Map<String, S3FilesFileSystem>>() {
                });
        this.accessPoints = factory.create("s3files", "s3files-access-points.json",
                new TypeReference<Map<String, S3FilesAccessPoint>>() {
                });
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    S3FilesService(StorageBackend<String, S3FilesFileSystem> fileSystems,
                   StorageBackend<String, S3FilesAccessPoint> accessPoints,
                   RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.fileSystems = fileSystems;
        this.accessPoints = accessPoints;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        fileSystems.clear();
        accessPoints.clear();
    }

    public synchronized ObjectNode createFileSystem(String region, JsonNode request) {
        String bucket = requireText(request, "bucket");
        String roleArn = requireText(request, "roleArn");
        String prefix = textOrNull(request, "prefix");
        String clientToken = textOr(request, "clientToken", UUID.randomUUID().toString());

        S3FilesFileSystem byToken = findFileSystemByToken(region, clientToken);
        if (byToken != null) {
            return toFileSystemNode(byToken);
        }
        S3FilesFileSystem byScope = findFileSystemByScope(region, bucket, prefix);
        if (byScope != null) {
            throw s3filesError("ConflictException",
                    "A file system already exists for bucket '" + bucket + "'.",
                    409, Map.of("errorCode", "ConflictException",
                            "resourceId", byScope.getFileSystemId(),
                            "resourceType", "FileSystem"));
        }

        String fileSystemId = "fs-" + randomHex(17);
        S3FilesFileSystem fs = new S3FilesFileSystem();
        fs.setFileSystemId(fileSystemId);
        fs.setOwnerId(regionResolver.getAccountId());
        fs.setClientToken(clientToken);
        fs.setCreationTime(Instant.now().getEpochSecond());
        fs.setStatus("available");
        fs.setBucket(bucket);
        fs.setPrefix(prefix);
        fs.setRoleArn(roleArn);
        fs.setKmsKeyId(textOrNull(request, "kmsKeyId"));
        fs.setRegion(region);
        fs.setTags(readTags(request.get("tags")));
        String name = fs.getTags().get("Name");
        if (name != null) {
            fs.setName(name);
        }
        fs.setFileSystemArn(regionResolver.buildArn(SERVICE, region, "file-system/" + fileSystemId));
        fileSystems.put(storageKey(region, fileSystemId), fs);
        return toFileSystemNode(fs);
    }

    public ObjectNode getFileSystem(String region, String fileSystemId) {
        return toFileSystemNode(requireFileSystem(region, fileSystemId));
    }

    public ObjectNode listFileSystems(String region, String bucket, Integer maxResults) {
        List<S3FilesFileSystem> matches = new ArrayList<>();
        for (S3FilesFileSystem fs : fileSystems.values()) {
            if (!region.equals(fs.getRegion()) || "deleted".equals(fs.getStatus())) {
                continue;
            }
            if (bucket != null && !bucket.isBlank() && !bucket.equals(fs.getBucket())) {
                continue;
            }
            matches.add(fs);
        }
        if (maxResults != null && maxResults > 0 && matches.size() > maxResults) {
            matches = matches.subList(0, maxResults);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("fileSystems");
        for (S3FilesFileSystem fs : matches) {
            list.add(toListFileSystemNode(fs));
        }
        return response;
    }

    public synchronized void deleteFileSystem(String region, String fileSystemId) {
        S3FilesFileSystem fs = requireFileSystem(region, fileSystemId);
        for (S3FilesAccessPoint accessPoint : accessPointsFor(fs.getFileSystemId())) {
            accessPoints.delete(storageKey(region, accessPoint.getAccessPointId()));
        }
        fileSystems.delete(storageKey(region, fs.getFileSystemId()));
    }

    public ObjectNode getFileSystemPolicy(String region, String fileSystemId) {
        S3FilesFileSystem fs = requireFileSystem(region, fileSystemId);
        if (fs.getPolicy() == null || fs.getPolicy().isBlank()) {
            throw s3filesError("ResourceNotFoundException",
                    "File system '" + fileSystemId + "' does not have a policy.", 404);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("fileSystemId", fs.getFileSystemId());
        response.put("policy", fs.getPolicy());
        return response;
    }

    public synchronized ObjectNode putFileSystemPolicy(String region, String fileSystemId, JsonNode request) {
        S3FilesFileSystem fs = requireFileSystem(region, fileSystemId);
        String policy = requireText(request, "policy");
        fs.setPolicy(policy);
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        return objectMapper.createObjectNode();
    }

    public synchronized void deleteFileSystemPolicy(String region, String fileSystemId) {
        S3FilesFileSystem fs = requireFileSystem(region, fileSystemId);
        if (fs.getPolicy() == null || fs.getPolicy().isBlank()) {
            throw s3filesError("ResourceNotFoundException",
                    "File system '" + fileSystemId + "' does not have a policy.", 404);
        }
        fs.setPolicy(null);
        fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
    }

    public synchronized ObjectNode createAccessPoint(String region, JsonNode request) {
        String fileSystemId = requireText(request, "fileSystemId");
        requireFileSystem(region, fileSystemId);
        String clientToken = textOr(request, "clientToken", UUID.randomUUID().toString());
        S3FilesAccessPoint existing = findAccessPointByToken(region, clientToken);
        if (existing != null) {
            return toAccessPointNode(existing);
        }

        String accessPointId = "fsap-" + randomHex(17);
        S3FilesAccessPoint accessPoint = new S3FilesAccessPoint();
        accessPoint.setAccessPointId(accessPointId);
        accessPoint.setAccessPointArn(regionResolver.buildArn(SERVICE, region, "access-point/" + accessPointId));
        accessPoint.setFileSystemId(fileSystemId);
        accessPoint.setClientToken(clientToken);
        accessPoint.setOwnerId(regionResolver.getAccountId());
        accessPoint.setStatus("available");
        accessPoint.setRegion(region);
        accessPoint.setTags(readTags(request.get("tags")));
        String name = accessPoint.getTags().get("Name");
        if (name != null) {
            accessPoint.setName(name);
        }
        if (request.hasNonNull("posixUser")) {
            accessPoint.setPosixUser(objectMapper.convertValue(request.get("posixUser"), MAP));
        }
        if (request.hasNonNull("rootDirectory")) {
            accessPoint.setRootDirectory(objectMapper.convertValue(request.get("rootDirectory"), MAP));
        }
        accessPoints.put(storageKey(region, accessPointId), accessPoint);
        return toAccessPointNode(accessPoint);
    }

    public ObjectNode getAccessPoint(String region, String accessPointId) {
        return toAccessPointNode(requireAccessPoint(region, accessPointId));
    }

    public ObjectNode listAccessPoints(String region, String fileSystemId, Integer maxResults) {
        requireFileSystem(region, fileSystemId);
        List<S3FilesAccessPoint> matches = accessPointsFor(fileSystemId);
        if (maxResults != null && maxResults > 0 && matches.size() > maxResults) {
            matches = matches.subList(0, maxResults);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("accessPoints");
        for (S3FilesAccessPoint accessPoint : matches) {
            list.add(toListAccessPointNode(accessPoint));
        }
        return response;
    }

    public synchronized void deleteAccessPoint(String region, String accessPointId) {
        S3FilesAccessPoint accessPoint = requireAccessPoint(region, accessPointId);
        accessPoints.delete(storageKey(region, accessPoint.getAccessPointId()));
    }

    public synchronized ObjectNode tagResource(String region, String resourceId, JsonNode request) {
        Map<String, String> incoming = readTags(request.get("tags"));
        if (incoming.isEmpty()) {
            incoming = readTags(request.get("Tags"));
        }
        if (isAccessPointId(resourceId)) {
            S3FilesAccessPoint accessPoint = requireAccessPoint(region, resourceId);
            accessPoint.getTags().putAll(incoming);
            String name = accessPoint.getTags().get("Name");
            if (name != null) {
                accessPoint.setName(name);
            }
            accessPoints.put(storageKey(region, accessPoint.getAccessPointId()), accessPoint);
        } else {
            S3FilesFileSystem fs = requireFileSystem(region, resourceId);
            fs.getTags().putAll(incoming);
            String name = fs.getTags().get("Name");
            if (name != null) {
                fs.setName(name);
            }
            fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(String region, String resourceId, List<String> tagKeys) {
        Map<String, String> tags = isAccessPointId(resourceId)
                ? requireAccessPoint(region, resourceId).getTags()
                : requireFileSystem(region, resourceId).getTags();
        if (tagKeys != null) {
            for (String key : expandTagKeys(tagKeys)) {
                tags.remove(key);
            }
        }
        if (isAccessPointId(resourceId)) {
            S3FilesAccessPoint accessPoint = requireAccessPoint(region, resourceId);
            accessPoints.put(storageKey(region, accessPoint.getAccessPointId()), accessPoint);
        } else {
            S3FilesFileSystem fs = requireFileSystem(region, resourceId);
            fileSystems.put(storageKey(region, fs.getFileSystemId()), fs);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(String region, String resourceId) {
        Map<String, String> tags = isAccessPointId(resourceId)
                ? requireAccessPoint(region, resourceId).getTags()
                : requireFileSystem(region, resourceId).getTags();
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagsArray(tags));
        return response;
    }

    private S3FilesFileSystem requireFileSystem(String region, String fileSystemId) {
        if (fileSystemId == null || fileSystemId.isBlank()) {
            throw s3filesError("ValidationException", "fileSystemId is required.", 400);
        }
        S3FilesFileSystem fs = fileSystems.get(storageKey(region, fileSystemId)).orElse(null);
        if (fs == null || "deleted".equals(fs.getStatus())) {
            throw s3filesError("ResourceNotFoundException",
                    "File system '" + fileSystemId + "' does not exist.", 404);
        }
        return fs;
    }

    private S3FilesAccessPoint requireAccessPoint(String region, String accessPointId) {
        if (accessPointId == null || accessPointId.isBlank()) {
            throw s3filesError("ValidationException", "accessPointId is required.", 400);
        }
        S3FilesAccessPoint accessPoint = accessPoints.get(storageKey(region, accessPointId)).orElse(null);
        if (accessPoint == null || "deleted".equals(accessPoint.getStatus())) {
            throw s3filesError("ResourceNotFoundException",
                    "Access point '" + accessPointId + "' does not exist.", 404);
        }
        return accessPoint;
    }

    private S3FilesFileSystem findFileSystemByToken(String region, String token) {
        for (S3FilesFileSystem fs : fileSystems.values()) {
            if (region.equals(fs.getRegion()) && token.equals(fs.getClientToken())
                    && !"deleted".equals(fs.getStatus())) {
                return fs;
            }
        }
        return null;
    }

    private S3FilesFileSystem findFileSystemByScope(String region, String bucket, String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix;
        for (S3FilesFileSystem fs : fileSystems.values()) {
            if (!region.equals(fs.getRegion()) || "deleted".equals(fs.getStatus())) {
                continue;
            }
            String existingPrefix = fs.getPrefix() == null ? "" : fs.getPrefix();
            if (bucket.equals(fs.getBucket()) && normalizedPrefix.equals(existingPrefix)) {
                return fs;
            }
        }
        return null;
    }

    private S3FilesAccessPoint findAccessPointByToken(String region, String token) {
        for (S3FilesAccessPoint accessPoint : accessPoints.values()) {
            if (region.equals(accessPoint.getRegion()) && token.equals(accessPoint.getClientToken())
                    && !"deleted".equals(accessPoint.getStatus())) {
                return accessPoint;
            }
        }
        return null;
    }

    private List<S3FilesAccessPoint> accessPointsFor(String fileSystemId) {
        List<S3FilesAccessPoint> matches = new ArrayList<>();
        for (S3FilesAccessPoint accessPoint : accessPoints.values()) {
            if (fileSystemId.equals(accessPoint.getFileSystemId())
                    && !"deleted".equals(accessPoint.getStatus())) {
                matches.add(accessPoint);
            }
        }
        return matches;
    }

    private ObjectNode toFileSystemNode(S3FilesFileSystem fs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("creationTime", fs.getCreationTime());
        node.put("fileSystemArn", fs.getFileSystemArn());
        node.put("fileSystemId", fs.getFileSystemId());
        node.put("bucket", fs.getBucket());
        if (fs.getPrefix() != null) {
            node.put("prefix", fs.getPrefix());
        }
        node.put("clientToken", fs.getClientToken());
        if (fs.getKmsKeyId() != null) {
            node.put("kmsKeyId", fs.getKmsKeyId());
        }
        node.put("status", fs.getStatus());
        node.put("roleArn", fs.getRoleArn());
        node.put("ownerId", fs.getOwnerId());
        node.set("tags", tagsArray(fs.getTags()));
        if (fs.getName() != null) {
            node.put("name", fs.getName());
        }
        return node;
    }

    private ObjectNode toListFileSystemNode(S3FilesFileSystem fs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("creationTime", fs.getCreationTime());
        node.put("fileSystemArn", fs.getFileSystemArn());
        node.put("fileSystemId", fs.getFileSystemId());
        if (fs.getName() != null) {
            node.put("name", fs.getName());
        }
        node.put("bucket", fs.getBucket());
        node.put("status", fs.getStatus());
        node.put("roleArn", fs.getRoleArn());
        node.put("ownerId", fs.getOwnerId());
        return node;
    }

    private ObjectNode toAccessPointNode(S3FilesAccessPoint accessPoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accessPointArn", accessPoint.getAccessPointArn());
        node.put("accessPointId", accessPoint.getAccessPointId());
        node.put("clientToken", accessPoint.getClientToken());
        node.put("fileSystemId", accessPoint.getFileSystemId());
        node.put("status", accessPoint.getStatus());
        node.put("ownerId", accessPoint.getOwnerId());
        if (accessPoint.getPosixUser() != null) {
            node.set("posixUser", objectMapper.valueToTree(accessPoint.getPosixUser()));
        }
        if (accessPoint.getRootDirectory() != null) {
            node.set("rootDirectory", objectMapper.valueToTree(accessPoint.getRootDirectory()));
        }
        node.set("tags", tagsArray(accessPoint.getTags()));
        if (accessPoint.getName() != null) {
            node.put("name", accessPoint.getName());
        }
        return node;
    }

    private ObjectNode toListAccessPointNode(S3FilesAccessPoint accessPoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("accessPointArn", accessPoint.getAccessPointArn());
        node.put("accessPointId", accessPoint.getAccessPointId());
        node.put("fileSystemId", accessPoint.getFileSystemId());
        node.put("status", accessPoint.getStatus());
        node.put("ownerId", accessPoint.getOwnerId());
        if (accessPoint.getPosixUser() != null) {
            node.set("posixUser", objectMapper.valueToTree(accessPoint.getPosixUser()));
        }
        if (accessPoint.getRootDirectory() != null) {
            node.set("rootDirectory", objectMapper.valueToTree(accessPoint.getRootDirectory()));
        }
        if (accessPoint.getName() != null) {
            node.put("name", accessPoint.getName());
        }
        return node;
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("key", key);
            tag.put("value", value);
            array.add(tag);
        });
        return array;
    }

    private static AwsException s3filesError(String code, String message, int status) {
        return s3filesError(code, message, status, Map.of("errorCode", code));
    }

    private static AwsException s3filesError(String code, String message, int status, Map<String, Object> extra) {
        return new AwsException(code, message, status, extra);
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static boolean isAccessPointId(String resourceId) {
        return resourceId != null && resourceId.startsWith("fsap-");
    }

    private static String requireText(JsonNode request, String field) {
        if (request == null || request.isMissingNode() || request.isNull() || !request.hasNonNull(field)) {
            throw s3filesError("ValidationException", field + " is required.", 400);
        }
        String value = request.get(field).asText();
        if (value == null || value.isBlank()) {
            throw s3filesError("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value != null ? value : fallback;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String key = tag.hasNonNull("key") ? tag.get("key").asText()
                    : tag.hasNonNull("Key") ? tag.get("Key").asText() : null;
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = tag.hasNonNull("value") ? tag.get("value").asText()
                    : tag.path("Value").asText("");
            tags.put(key, Objects.requireNonNullElse(value, ""));
        }
        return tags;
    }

    private static List<String> expandTagKeys(List<String> tagKeys) {
        List<String> expanded = new ArrayList<>();
        for (String key : tagKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            for (String part : key.split(",")) {
                if (!part.isBlank()) {
                    expanded.add(part.trim());
                }
            }
        }
        return expanded;
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder();
        while (hex.length() < length) {
            hex.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return hex.substring(0, length);
    }
}
