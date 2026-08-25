package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dsql.model.CdcStream;
import io.github.hectorvent.floci.services.dsql.model.Cluster;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlDataPlane;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Aurora DSQL restJson1 — cluster and CDC stream lifecycle.
 *
 * <p>Clusters and streams become {@code ACTIVE} immediately so local stacks do not wait
 * on the live-AWS asynchronous provisioning window. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController} using ARN
 * service {@code dsql}.
 */
@ApplicationScoped
public class DsqlService implements TagHandler {

    static final String SERVICE = "dsql";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETING = "DELETING";
    private static final String RESOURCE_CLUSTER = "CLUSTER";
    private static final String RESOURCE_POLICY = "CLUSTER_POLICY";
    private static final String RESOURCE_STREAM = "STREAM";
    private static final String DEFAULT_ORDERING = "UNORDERED";
    private static final String DEFAULT_FORMAT = "JSON";
    private static final Set<String> ORDERINGS = Set.of("UNORDERED");
    private static final Set<String> FORMATS = Set.of("JSON");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, CdcStream> streams;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    DsqlDataPlane dataPlane;

    @Inject
    public DsqlService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("dsql", "dsql-clusters.json",
                        new TypeReference<Map<String, Cluster>>() {
                        }),
                storageFactory.create("dsql", "dsql-streams.json",
                        new TypeReference<Map<String, CdcStream>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    DsqlService(StorageBackend<String, Cluster> clusters, RegionResolver regionResolver) {
        this(clusters, new InMemoryStorage<>(), regionResolver, new ObjectMapper());
    }

    DsqlService(
            StorageBackend<String, Cluster> clusters,
            StorageBackend<String, CdcStream> streams,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.clusters = clusters;
        this.streams = streams;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Cluster createCluster(String region, JsonNode request) {
        requireObject(request, "Request body");
        boolean deletionProtection = optionalBoolean(request, "deletionProtectionEnabled", false);
        String kmsKey = optionalText(request, "kmsEncryptionKey");
        Map<String, String> tags = readTags(request.get("tags"));

        String identifier = newId();
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();

        Cluster cluster = new Cluster();
        cluster.setIdentifier(identifier);
        cluster.setArn(clusterArn(region, account, identifier));
        cluster.setStatus(STATUS_ACTIVE);
        cluster.setCreationTime(now);
        cluster.setDeletionProtectionEnabled(deletionProtection);
        cluster.setKmsEncryptionKey(kmsKey);
        cluster.setEncryptionType(
                kmsKey == null ? "AWS_OWNED_KMS_KEY" : "CUSTOMER_MANAGED_KMS_KEY");
        cluster.setEncryptionStatus("ENABLED");
        cluster.setRegion(region);
        cluster.setEndpoint(identifier + ".dsql." + region + ".on.aws");
        cluster.setTags(tags);
        applyMultiRegion(cluster, request.get("multiRegionProperties"));
        String createPolicy = optionalText(request, "policy");
        if (createPolicy != null) {
            cluster.setPolicy(createPolicy);
            cluster.setPolicyVersion("1");
        }
        clusters.put(clusterKey(region, identifier), cluster);
        if (dataPlane != null) {
            dataPlane.ensureStarted();
            dataPlane.registerEndpoint(cluster.getEndpoint());
        }
        return cluster;
    }

    public Cluster getCluster(String region, String identifier) {
        return requireCluster(region, identifier);
    }

    public List<Cluster> listClusters(String region) {
        List<Cluster> items = clusters.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Cluster::getIdentifier, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Cluster updateCluster(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        Cluster cluster = requireCluster(region, identifier);
        if (request.has("deletionProtectionEnabled")) {
            cluster.setDeletionProtectionEnabled(requireBoolean(request, "deletionProtectionEnabled"));
        }
        if (request.has("kmsEncryptionKey")) {
            cluster.setKmsEncryptionKey(optionalText(request, "kmsEncryptionKey"));
        }
        clusters.put(clusterKey(region, cluster.getIdentifier()), cluster);
        return cluster;
    }

    public synchronized Cluster deleteCluster(String region, String identifier) {
        Cluster cluster = requireCluster(region, identifier);
        if (cluster.isDeletionProtectionEnabled()) {
            throw validation(
                    "Cannot delete cluster " + cluster.getIdentifier()
                            + " because deletion protection is enabled.",
                    "deletionProtectionEnabled");
        }
        if (!listStreams(region, cluster.getIdentifier()).isEmpty()) {
            throw conflict(
                    cluster.getIdentifier(),
                    RESOURCE_CLUSTER,
                    "Cannot delete cluster " + cluster.getIdentifier() + " because it has active streams.");
        }
        clusters.delete(clusterKey(region, cluster.getIdentifier()));
        cluster.setStatus(STATUS_DELETING);
        if (dataPlane != null) {
            dataPlane.unregisterEndpoint(cluster.getEndpoint());
        }
        return cluster;
    }

    public ObjectNode getVpcEndpointServiceName(String region, String identifier) {
        Cluster cluster = requireCluster(region, identifier);
        ObjectNode node = objectMapper.createObjectNode();
        String vpcRegion = cluster.getRegion() == null ? region : cluster.getRegion();
        node.put("serviceName", "com.amazonaws." + vpcRegion + ".dsql");
        return node;
    }

    public Cluster getClusterPolicy(String region, String identifier) {
        Cluster cluster = requireCluster(region, identifier);
        if (cluster.getPolicy() == null || cluster.getPolicy().isBlank()) {
            throw notFound(cluster.getIdentifier(), "CLUSTER_POLICY");
        }
        return cluster;
    }

    public synchronized Cluster putClusterPolicy(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        Cluster cluster = requireCluster(region, identifier);
        String policy = requireText(request, "policy");
        String expected = optionalText(request, "expectedPolicyVersion");
        if (expected != null) {
            String current = cluster.getPolicyVersion();
            if (current == null || !current.equals(expected)) {
                throw conflict(
                        cluster.getIdentifier(),
                        "CLUSTER_POLICY",
                        "Policy version mismatch for cluster " + cluster.getIdentifier() + ".");
            }
        }
        int next = cluster.getPolicyVersion() == null ? 1 : Integer.parseInt(cluster.getPolicyVersion()) + 1;
        cluster.setPolicy(policy);
        cluster.setPolicyVersion(Integer.toString(next));
        clusters.put(clusterKey(region, cluster.getIdentifier()), cluster);
        return cluster;
    }

    public synchronized Cluster deleteClusterPolicy(String region, String identifier, String expectedPolicyVersion) {
        Cluster cluster = getClusterPolicy(region, identifier);
        if (expectedPolicyVersion != null
                && !expectedPolicyVersion.isBlank()
                && !expectedPolicyVersion.equals(cluster.getPolicyVersion())) {
            throw conflict(
                    cluster.getIdentifier(),
                    "CLUSTER_POLICY",
                    "Policy version mismatch for cluster " + cluster.getIdentifier() + ".");
        }
        cluster.setPolicy(null);
        clusters.put(clusterKey(region, cluster.getIdentifier()), cluster);
        return cluster;
    }

    public ObjectNode toClusterPolicy(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policy", cluster.getPolicy());
        node.put("policyVersion", cluster.getPolicyVersion());
        return node;
    }

    public ObjectNode toPolicyVersion(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policyVersion", cluster.getPolicyVersion());
        return node;
    }

    public synchronized CdcStream createStream(String region, String clusterIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        Cluster cluster = requireCluster(region, clusterIdentifier);
        if (!STATUS_ACTIVE.equals(cluster.getStatus())) {
            throw validation(
                    "Aurora DSQL can't create a stream because cluster "
                            + cluster.getIdentifier() + " is not ACTIVE (status: " + cluster.getStatus() + ").",
                    "fieldValidationFailed");
        }
        JsonNode target = requireObjectField(request, "targetDefinition");
        JsonNode kinesis = requireObjectField(target, "kinesis");
        String streamArn = requireText(kinesis, "streamArn");
        String roleArn = requireText(kinesis, "roleArn");
        String ordering = optionalText(request, "ordering");
        if (ordering == null || ordering.isBlank()) {
            ordering = DEFAULT_ORDERING;
        }
        if (!ORDERINGS.contains(ordering)) {
            throw validation("ordering must be UNORDERED.", "fieldValidationFailed");
        }
        String format = optionalText(request, "format");
        if (format == null || format.isBlank()) {
            format = DEFAULT_FORMAT;
        }
        if (!FORMATS.contains(format)) {
            throw validation("format must be JSON.", "fieldValidationFailed");
        }
        Map<String, String> tags = readTags(request.get("tags"));

        String streamId = newId();
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();

        CdcStream stream = new CdcStream();
        stream.setClusterIdentifier(cluster.getIdentifier());
        stream.setStreamIdentifier(streamId);
        stream.setArn(streamArn(region, account, cluster.getIdentifier(), streamId));
        stream.setStatus(STATUS_ACTIVE);
        stream.setCreationTime(now);
        stream.setOrdering(ordering);
        stream.setFormat(format);
        stream.setKinesisStreamArn(streamArn);
        stream.setRoleArn(roleArn);
        stream.setTags(tags);
        streams.put(streamKey(region, cluster.getIdentifier(), streamId), stream);
        return stream;
    }

    public CdcStream getStream(String region, String clusterIdentifier, String streamIdentifier) {
        requireCluster(region, clusterIdentifier);
        return requireStream(region, clusterIdentifier, streamIdentifier);
    }

    public List<CdcStream> listStreams(String region, String clusterIdentifier) {
        requireCluster(region, clusterIdentifier);
        String prefix = streamPrefix(region, decode(clusterIdentifier));
        List<CdcStream> items = streams.scan(key -> key.startsWith(prefix));
        items.sort(Comparator.comparing(CdcStream::getStreamIdentifier, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized CdcStream deleteStream(String region, String clusterIdentifier, String streamIdentifier) {
        requireCluster(region, clusterIdentifier);
        CdcStream stream = requireStream(region, clusterIdentifier, streamIdentifier);
        streams.delete(streamKey(region, stream.getClusterIdentifier(), stream.getStreamIdentifier()));
        stream.setStatus(STATUS_DELETING);
        return stream;
    }

    public ObjectNode toCluster(Cluster cluster, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("identifier", cluster.getIdentifier());
        node.put("arn", cluster.getArn());
        node.put("status", cluster.getStatus());
        node.put("creationTime", cluster.getCreationTime());
        node.put("deletionProtectionEnabled", cluster.isDeletionProtectionEnabled());
        node.put("endpoint", cluster.getEndpoint());
        ObjectNode encryption = node.putObject("encryptionDetails");
        String encryptionType = cluster.getEncryptionType();
        if (encryptionType == null || encryptionType.isBlank()) {
            encryptionType = cluster.getKmsEncryptionKey() == null
                    ? "AWS_OWNED_KMS_KEY"
                    : "CUSTOMER_MANAGED_KMS_KEY";
        }
        encryption.put("encryptionType", encryptionType);
        if (cluster.getKmsEncryptionKey() != null && !cluster.getKmsEncryptionKey().isBlank()) {
            encryption.put("kmsKeyArn", cluster.getKmsEncryptionKey());
        }
        encryption.put(
                "encryptionStatus",
                cluster.getEncryptionStatus() == null ? "ENABLED" : cluster.getEncryptionStatus());
        if (cluster.getWitnessRegion() != null || (cluster.getLinkedClusters() != null
                && !cluster.getLinkedClusters().isEmpty())) {
            ObjectNode multi = node.putObject("multiRegionProperties");
            if (cluster.getWitnessRegion() != null) {
                multi.put("witnessRegion", cluster.getWitnessRegion());
            }
            if (cluster.getLinkedClusters() != null) {
                ArrayNode linked = multi.putArray("clusters");
                for (String linkedArn : cluster.getLinkedClusters()) {
                    linked.add(linkedArn);
                }
            }
        }
        if (includeTags) {
            node.set("tags", tagsNode(cluster.getTags()));
        }
        return node;
    }

    public ObjectNode toClusterSummary(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("identifier", cluster.getIdentifier());
        node.put("arn", cluster.getArn());
        return node;
    }

    public ObjectNode toCreateStream(CdcStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        putStreamIdentity(node, stream);
        node.put("ordering", stream.getOrdering());
        node.put("format", stream.getFormat());
        return node;
    }

    public ObjectNode toStream(CdcStream stream) {
        ObjectNode node = toCreateStream(stream);
        ObjectNode target = node.putObject("targetDefinition");
        ObjectNode kinesis = target.putObject("kinesis");
        kinesis.put("streamArn", stream.getKinesisStreamArn());
        kinesis.put("roleArn", stream.getRoleArn());
        node.set("tags", tagsNode(stream.getTags()));
        return node;
    }

    public ObjectNode toStreamSummary(CdcStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        putStreamIdentity(node, stream);
        return node;
    }

    public ObjectNode toDeleteStream(CdcStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        putStreamIdentity(node, stream);
        return node;
    }

    public ObjectNode toDeleteCluster(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("identifier", cluster.getIdentifier());
        node.put("arn", cluster.getArn());
        node.put("status", cluster.getStatus());
        node.put("creationTime", cluster.getCreationTime());
        return node;
    }

    public ObjectNode toUpdateCluster(Cluster cluster) {
        return toDeleteCluster(cluster);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        resource.applyTags(current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged resource = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(resource.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        resource.applyTags(current);
    }

    private Cluster requireCluster(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw validation("identifier is required.", "fieldValidationFailed");
        }
        String decoded = decode(identifier);
        return clusters.get(clusterKey(region, decoded)).orElseThrow(() -> notFound(decoded, RESOURCE_CLUSTER));
    }

    private CdcStream requireStream(String region, String clusterIdentifier, String streamIdentifier) {
        if (streamIdentifier == null || streamIdentifier.isBlank()) {
            throw validation("streamIdentifier is required.", "fieldValidationFailed");
        }
        String decodedCluster = decode(clusterIdentifier);
        String decodedStream = decode(streamIdentifier);
        return streams.get(streamKey(region, decodedCluster, decodedStream))
                .orElseThrow(() -> notFound(decodedStream, RESOURCE_STREAM));
    }

    private Tagged requireTagged(String region, String arn) {
        String decoded = decode(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw validation("Invalid resource ARN: " + decoded, "fieldValidationFailed");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound(decoded, RESOURCE_CLUSTER);
        }
        String resource = parsed.resource();
        int streamIdx = resource.indexOf("/stream/");
        if (resource.startsWith("cluster/") && streamIdx > 0) {
            String clusterId = resource.substring("cluster/".length(), streamIdx);
            String streamId = resource.substring(streamIdx + "/stream/".length());
            CdcStream stream = requireStream(region, clusterId, streamId);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return stream.getTags() == null ? Map.of() : stream.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    stream.setTags(tags);
                    streams.put(streamKey(region, stream.getClusterIdentifier(), stream.getStreamIdentifier()), stream);
                }
            };
        }
        if (resource.startsWith("cluster/")) {
            String clusterId = resource.substring("cluster/".length());
            Cluster cluster = requireCluster(region, clusterId);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return cluster.getTags() == null ? Map.of() : cluster.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    cluster.setTags(tags);
                    clusters.put(clusterKey(region, cluster.getIdentifier()), cluster);
                }
            };
        }
        throw notFound(decoded, RESOURCE_CLUSTER);
    }

    private void putStreamIdentity(ObjectNode node, CdcStream stream) {
        node.put("clusterIdentifier", stream.getClusterIdentifier());
        node.put("streamIdentifier", stream.getStreamIdentifier());
        node.put("arn", stream.getArn());
        node.put("status", stream.getStatus());
        node.put("creationTime", stream.getCreationTime());
    }

    private static void applyMultiRegion(Cluster cluster, JsonNode multi) {
        if (multi == null || multi.isNull() || !multi.isObject()) {
            return;
        }
        JsonNode witness = multi.get("witnessRegion");
        if (witness != null && witness.isTextual() && !witness.textValue().isBlank()) {
            cluster.setWitnessRegion(witness.textValue());
        }
        JsonNode linked = multi.get("clusters");
        if (linked != null && linked.isArray()) {
            List<String> arns = new ArrayList<>();
            for (JsonNode value : linked) {
                if (value.isTextual() && !value.textValue().isBlank()) {
                    arns.add(value.textValue());
                }
            }
            cluster.setLinkedClusters(arns);
        }
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    public ArrayNode clusterSummaries(List<Cluster> items) {
        ArrayNode list = objectMapper.createArrayNode();
        for (Cluster cluster : items) {
            list.add(toClusterSummary(cluster));
        }
        return list;
    }

    public ArrayNode streamSummaries(List<CdcStream> items) {
        ArrayNode list = objectMapper.createArrayNode();
        for (CdcStream stream : items) {
            list.add(toStreamSummary(stream));
        }
        return list;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    private static String nextPolicyVersion(String current) {
        if (current == null || current.isBlank()) {
            return "1";
        }
        try {
            return Integer.toString(Integer.parseInt(current) + 1);
        } catch (NumberFormatException e) {
            return "1";
        }
    }

    private static String clusterArn(String region, String account, String identifier) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "cluster/" + identifier).toString();
    }

    private static String streamArn(String region, String account, String clusterId, String streamId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "cluster/" + clusterId + "/stream/" + streamId).toString();
    }

    private static String clusterKey(String region, String identifier) {
        return region + "::" + identifier;
    }

    private static String streamKey(String region, String clusterId, String streamId) {
        return region + "::" + clusterId + "::" + streamId;
    }

    private static String streamPrefix(String region, String clusterId) {
        return region + "::" + clusterId + "::";
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.", "cannotParse");
        }
    }

    private static JsonNode requireObjectField(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is required.", "fieldValidationFailed");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.", "fieldValidationFailed");
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.", "fieldValidationFailed");
        }
        return value.booleanValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.", "fieldValidationFailed");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.", "fieldValidationFailed");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.", "fieldValidationFailed");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException validation(String message, String reason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", reason);
        return new AwsException("ValidationException", message, 400, extra);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", resourceId);
        extra.put("resourceType", resourceType);
        return new AwsException(
                "ResourceNotFoundException",
                resourceType.toLowerCase() + " " + resourceId + " not found.",
                404,
                extra);
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", resourceId);
        extra.put("resourceType", resourceType);
        return new AwsException("ConflictException", message, 409, extra);
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }
}
