package io.floci.aws.services.textract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Textract custom query adapters, their versions, and the tags on both.
 * <p>
 * An adapter version reads its training manifest from S3 when it is created
 * ({@code InvalidS3ObjectException} when it can't be read) and is
 * {@code ACTIVE} at once: Floci trains nothing, so a version changes no
 * analysis. Adapter ARNs keep Textract's own form,
 * {@code arn:aws:textract:<region>:<account>:/adapters/<AdapterId>}.
 * Deleting a version that doesn't exist succeeds, as it does on AWS.
 */
@ApplicationScoped
public class TextractAdapterService {
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9-_]+$");
    private static final Set<String> FEATURE_TYPES = Set.of("TABLES", "FORMS", "QUERIES", "SIGNATURES", "LAYOUT");
    private static final Set<String> AUTO_UPDATE = Set.of("ENABLED", "DISABLED");

    private final StorageBackend<String, ObjectNode> adapters;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public TextractAdapterService(StorageFactory storageFactory, RegionResolver regionResolver, S3Service s3Service,
                                  ObjectMapper objectMapper) {
        this.adapters = storageFactory.create("textract", "textract-adapters.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tags = storageFactory.create("textract", "textract-tags.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    // ── Adapters ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createAdapter(JsonNode request, String region) {
        String name = requireName(text(request, "AdapterName"));
        JsonNode features = request.get("FeatureTypes");
        if (features == null || !features.isArray() || features.isEmpty()) {
            throw validation("FeatureTypes is a required field.");
        }
        features.forEach(feature -> {
            if (!FEATURE_TYPES.contains(feature.asText())) {
                throw validation("Unknown feature type: " + feature.asText());
            }
        });
        String autoUpdate = autoUpdate(request, "DISABLED");
        String token = text(request, "ClientRequestToken");
        for (ObjectNode adapter : adapters.scan(k -> k.startsWith(region + "/"))) {
            if (token != null && token.equals(adapter.path("token").asText(null))) {
                return objectMapper.createObjectNode().put("AdapterId", adapter.path("AdapterId").asText());
            }
            if (name.equals(adapter.path("AdapterName").asText())) {
                throw new AwsException("ConflictException", "An adapter named " + name + " already exists.", 400);
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ObjectNode adapter = objectMapper.createObjectNode();
        adapter.put("AdapterId", id);
        adapter.put("AdapterName", name);
        adapter.put("CreationTime", now());
        if (text(request, "Description") != null) {
            adapter.put("Description", text(request, "Description"));
        }
        adapter.set("FeatureTypes", features.deepCopy());
        adapter.put("AutoUpdate", autoUpdate);
        if (token != null) {
            adapter.put("token", token);
        }
        adapter.putObject("versions");
        adapters.put(key(region, id), adapter);
        putTags(arn(region, id), request.get("Tags"));
        return objectMapper.createObjectNode().put("AdapterId", id);
    }

    public ObjectNode getAdapter(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        ObjectNode response = view(adapter);
        response.set("Tags", tags.get(arn(region, adapter.path("AdapterId").asText()))
                .orElse(objectMapper.createObjectNode()).deepCopy());
        return response;
    }

    public synchronized ObjectNode updateAdapter(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        if (text(request, "AdapterName") != null) {
            String name = requireName(text(request, "AdapterName"));
            boolean taken = adapters.scan(k -> k.startsWith(region + "/")).stream()
                    .anyMatch(a -> name.equals(a.path("AdapterName").asText())
                            && !a.path("AdapterId").asText().equals(adapter.path("AdapterId").asText()));
            if (taken) {
                throw new AwsException("ConflictException", "An adapter named " + name + " already exists.", 400);
            }
            adapter.put("AdapterName", name);
        }
        if (text(request, "Description") != null) {
            adapter.put("Description", text(request, "Description"));
        }
        if (text(request, "AutoUpdate") != null) {
            adapter.put("AutoUpdate", autoUpdate(request, "DISABLED"));
        }
        adapters.put(key(region, adapter.path("AdapterId").asText()), adapter);
        return view(adapter);
    }

    public synchronized ObjectNode deleteAdapter(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        String id = adapter.path("AdapterId").asText();
        adapters.delete(key(region, id));
        tags.delete(arn(region, id));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAdapters(JsonNode request, String region) {
        double after = request.path("AfterCreationTime").asDouble(0);
        double before = request.path("BeforeCreationTime").asDouble(Double.MAX_VALUE);
        List<ObjectNode> found = adapters.scan(k -> k.startsWith(region + "/")).stream()
                .filter(a -> a.path("CreationTime").asDouble() > after && a.path("CreationTime").asDouble() < before)
                .sorted(Comparator.comparingDouble((ObjectNode a) -> a.path("CreationTime").asDouble()))
                .toList();
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Adapters");
        found.subList(page.start(), page.end()).forEach(a -> list.addObject()
                .put("AdapterId", a.path("AdapterId").asText())
                .put("AdapterName", a.path("AdapterName").asText())
                .put("CreationTime", a.path("CreationTime").asDouble())
                .set("FeatureTypes", a.path("FeatureTypes").deepCopy()));
        page.next(response);
        return response;
    }

    // ── Adapter versions ───────────────────────────────────────────────────

    public synchronized ObjectNode createAdapterVersion(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        JsonNode manifest = request.path("DatasetConfig").path("ManifestS3Object");
        String bucket = text(manifest, "Bucket");
        String name = text(manifest, "Name");
        if (bucket == null || name == null) {
            throw new AwsException("InvalidParameterException", "DatasetConfig.ManifestS3Object is required.", 400);
        }
        if (text(request.path("OutputConfig"), "S3Bucket") == null) {
            throw new AwsException("InvalidParameterException", "OutputConfig.S3Bucket is required.", 400);
        }
        try {
            s3Service.getObject(bucket, name);
        } catch (AwsException e) {
            throw new AwsException("InvalidS3ObjectException",
                    "Unable to get object metadata from S3. Check object key, region and/or access permissions.", 400);
        }
        ObjectNode versions = (ObjectNode) adapter.get("versions");
        String version = Integer.toString(versions.size() + 1);
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("AdapterId", adapter.path("AdapterId").asText());
        entry.put("AdapterVersion", version);
        entry.put("CreationTime", now());
        entry.set("FeatureTypes", adapter.path("FeatureTypes").deepCopy());
        entry.put("Status", "ACTIVE");
        entry.put("StatusMessage", "Floci trains no adapters; the version changes no analysis.");
        entry.set("DatasetConfig", request.path("DatasetConfig").deepCopy());
        entry.set("OutputConfig", request.path("OutputConfig").deepCopy());
        if (text(request, "KMSKeyId") != null) {
            entry.put("KMSKeyId", text(request, "KMSKeyId"));
        }
        entry.putArray("EvaluationMetrics");
        versions.set(version, entry);
        adapters.put(key(region, adapter.path("AdapterId").asText()), adapter);
        putTags(arn(region, adapter.path("AdapterId").asText()) + "/" + version, request.get("Tags"));
        return objectMapper.createObjectNode()
                .put("AdapterId", adapter.path("AdapterId").asText())
                .put("AdapterVersion", version);
    }

    public ObjectNode getAdapterVersion(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        String version = text(request, "AdapterVersion");
        JsonNode entry = version == null ? null : adapter.path("versions").get(version);
        if (entry == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Adapter version " + version + " was not found.", 400);
        }
        ObjectNode response = entry.deepCopy();
        response.set("Tags", tags.get(arn(region, adapter.path("AdapterId").asText()) + "/" + version)
                .orElse(objectMapper.createObjectNode()).deepCopy());
        return response;
    }

    public synchronized ObjectNode deleteAdapterVersion(JsonNode request, String region) {
        ObjectNode adapter = requireAdapter(request, region);
        String version = text(request, "AdapterVersion");
        if (version != null && adapter.path("versions").has(version)) {
            ((ObjectNode) adapter.get("versions")).remove(version);
            adapters.put(key(region, adapter.path("AdapterId").asText()), adapter);
            tags.delete(arn(region, adapter.path("AdapterId").asText()) + "/" + version);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAdapterVersions(JsonNode request, String region) {
        String adapterId = text(request, "AdapterId");
        List<JsonNode> found = new ArrayList<>();
        adapters.scan(k -> k.startsWith(region + "/")).stream()
                .filter(a -> adapterId == null || adapterId.equals(a.path("AdapterId").asText()))
                .forEach(a -> a.path("versions").forEach(found::add));
        Page page = page(found.size(), request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AdapterVersions");
        found.subList(page.start(), page.end()).forEach(v -> list.addObject()
                .put("AdapterId", v.path("AdapterId").asText())
                .put("AdapterVersion", v.path("AdapterVersion").asText())
                .put("CreationTime", v.path("CreationTime").asDouble())
                .put("Status", v.path("Status").asText())
                .put("StatusMessage", v.path("StatusMessage").asText())
                .set("FeatureTypes", v.path("FeatureTypes").deepCopy()));
        page.next(response);
        return response;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        String arn = requireTaggable(text(request, "ResourceARN"), region);
        putTags(arn, request.get("Tags"));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        String arn = requireTaggable(text(request, "ResourceARN"), region);
        ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
        request.path("TagKeys").forEach(k -> current.remove(k.asText()));
        tags.put(arn, current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request, String region) {
        String arn = requireTaggable(text(request, "ResourceARN"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", tags.get(arn).orElse(objectMapper.createObjectNode()).deepCopy());
        return response;
    }

    /** Textract tags are a string map on the wire. */
    private void putTags(String arn, JsonNode map) {
        if (map != null && map.isObject() && !map.isEmpty()) {
            ObjectNode current = tags.get(arn).orElse(objectMapper.createObjectNode());
            current.setAll((ObjectNode) map);
            tags.put(arn, current);
        }
    }

    /** An adapter ({@code …:/adapters/<id>}) or adapter version ({@code …:/adapters/<id>/<version>}) that exists. */
    private String requireTaggable(String arn, String region) {
        if (arn == null) {
            throw validation("ResourceARN is a required field.");
        }
        int marker = arn.indexOf(":/adapters/");
        if (marker >= 0) {
            String[] path = arn.substring(marker + ":/adapters/".length()).split("/", 2);
            ObjectNode adapter = adapters.get(key(region, path[0])).orElse(null);
            if (adapter != null && (path.length == 1 || adapter.path("versions").has(path[1]))) {
                return arn;
            }
        }
        throw new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 400);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode view(ObjectNode adapter) {
        ObjectNode view = adapter.deepCopy();
        view.remove(List.of("versions", "token"));
        return view;
    }

    private ObjectNode requireAdapter(JsonNode request, String region) {
        String id = text(request, "AdapterId");
        if (id == null) {
            throw validation("AdapterId is a required field.");
        }
        return adapters.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Adapter " + id + " was not found.", 400));
    }

    /** {@code arn:aws:textract:<region>:<account>:/adapters/<id>}, Textract's own leading-slash form. */
    String arn(String region, String id) {
        return regionResolver.buildArn("textract", region, "/adapters/" + id);
    }

    private static String autoUpdate(JsonNode request, String fallback) {
        String value = text(request, "AutoUpdate");
        if (value == null) {
            return fallback;
        }
        if (!AUTO_UPDATE.contains(value)) {
            throw validation("AutoUpdate must be ENABLED or DISABLED.");
        }
        return value;
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128 || !NAME.matcher(name).matches()) {
            throw validation("AdapterName must match [a-zA-Z0-9-_]+ and be at most 128 characters.");
        }
        return name;
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
        if (max < 1 || max > 1000) {
            throw validation("MaxResults must be between 1 and 1000.");
        }
        int start = 0;
        String token = text(request, "NextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw validation("Invalid NextToken.");
            }
        }
        start = Math.min(Math.max(start, 0), size);
        int end = Math.min(size, start + max);
        return new Page(start, end, end < size ? Integer.toString(end) : null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
