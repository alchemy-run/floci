package io.github.hectorvent.floci.services.neptunegraph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptunegraph.model.Graph;
import io.github.hectorvent.floci.services.neptunegraph.model.GraphSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Neptune Analytics (neptune-graph) restJson1 — graphs, snapshots, and
 * import/export task lookups.
 */
@ApplicationScoped
public class NeptuneGraphService implements TagHandler {

    static final String SERVICE = "neptune-graph";
    private static final Pattern GRAPH_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{0,61}[a-zA-Z0-9]|[a-zA-Z]");
    private static final Pattern SNAPSHOT_NAME = GRAPH_NAME;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;

    private final StorageBackend<String, Graph> graphs;
    private final StorageBackend<String, GraphSnapshot> snapshots;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public NeptuneGraphService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("neptunegraph", "neptune-graph-graphs.json",
                        new TypeReference<Map<String, Graph>>() {
                        }),
                storageFactory.create("neptunegraph", "neptune-graph-snapshots.json",
                        new TypeReference<Map<String, GraphSnapshot>>() {
                        }),
                regionResolver, objectMapper);
    }

    NeptuneGraphService(StorageBackend<String, Graph> graphs, RegionResolver regionResolver) {
        this(graphs, null, regionResolver, new ObjectMapper());
    }

    NeptuneGraphService(
            StorageBackend<String, Graph> graphs,
            StorageBackend<String, GraphSnapshot> snapshots,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.graphs = graphs;
        this.snapshots = snapshots;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Graph createGraph(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "graphName");
        validateGraphName(name);
        int memory = requireInt(request, "provisionedMemory");
        if (memory < 16) {
            throw validation("provisionedMemory must be at least 16.");
        }
        if (findByName(region, name) != null) {
            throw new AwsException(
                    "ConflictException",
                    "Graph " + name + " already exists.",
                    409);
        }

        boolean publicConnectivity = optionalBoolean(request, "publicConnectivity", false);
        int replicaCount = optionalInt(request, "replicaCount", 1);
        if (replicaCount < 0 || replicaCount > 2) {
            throw validation("replicaCount must be between 0 and 2.");
        }
        boolean deletionProtection = optionalBoolean(request, "deletionProtection", true);
        Integer dimension = readVectorDimension(request.get("vectorSearchConfiguration"));

        String id = newId("g-");
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        Graph graph = new Graph();
        graph.setId(id);
        graph.setName(name);
        graph.setArn(AwsArnUtils.Arn.of(SERVICE, region, account, "graph/" + id).toString());
        graph.setStatus("AVAILABLE");
        graph.setCreateTime(now);
        graph.setProvisionedMemory(memory);
        graph.setEndpoint(id + "." + region + ".neptune-graph.amazonaws.com");
        graph.setPublicConnectivity(publicConnectivity);
        graph.setReplicaCount(replicaCount);
        graph.setKmsKeyIdentifier(optionalText(request, "kmsKeyIdentifier"));
        graph.setVectorSearchDimension(dimension);
        graph.setDeletionProtection(deletionProtection);
        graph.setBuildNumber("1.0.0");
        graph.setRegion(region);
        graph.setTags(readTags(request.get("tags")));
        graphs.put(storageKey(region, id), graph);
        return graph;
    }

    public Graph getGraph(String region, String graphIdentifier) {
        return requireGraph(region, graphIdentifier);
    }

    public synchronized Graph updateGraph(String region, String graphIdentifier, JsonNode request) {
        requireObject(request, "Request body");
        Graph graph = requireGraph(region, graphIdentifier);
        if (request.has("provisionedMemory") && !request.get("provisionedMemory").isNull()) {
            int memory = requireInt(request, "provisionedMemory");
            if (memory < 16) {
                throw validation("provisionedMemory must be at least 16.");
            }
            graph.setProvisionedMemory(memory);
        }
        if (request.has("publicConnectivity") && !request.get("publicConnectivity").isNull()) {
            graph.setPublicConnectivity(requireBoolean(request, "publicConnectivity"));
        }
        if (request.has("deletionProtection") && !request.get("deletionProtection").isNull()) {
            graph.setDeletionProtection(requireBoolean(request, "deletionProtection"));
        }
        graphs.put(storageKey(region, graph.getId()), graph);
        return graph;
    }

    public synchronized Graph deleteGraph(String region, String graphIdentifier, String skipSnapshot) {
        if (skipSnapshot == null || skipSnapshot.isBlank()) {
            throw validation("skipSnapshot is required.");
        }
        if (!"true".equalsIgnoreCase(skipSnapshot) && !"false".equalsIgnoreCase(skipSnapshot)) {
            throw validation("skipSnapshot must be true or false.");
        }
        Graph graph = requireGraph(region, graphIdentifier);
        if (graph.isDeletionProtection()) {
            throw new AwsException(
                    "ConflictException",
                    "Graph " + graphIdentifier + " has deletion protection enabled.",
                    409);
        }
        graphs.delete(storageKey(region, graph.getId()));
        graph.setStatus("DELETING");
        return graph;
    }

    public Page<Graph> listGraphs(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Graph> items = new ArrayList<>(graphs.scan(key -> key.startsWith(region + "::")));
        items.sort(Comparator.comparing(Graph::getName, Comparator.nullsLast(String::compareTo)));
        return paginate(items, maxResults, nextToken);
    }

    public synchronized GraphSnapshot createGraphSnapshot(String region, JsonNode request) {
        requireObject(request, "Request body");
        Graph graph = requireGraph(region, requireText(request, "graphIdentifier"));
        String name = requireText(request, "snapshotName");
        validateSnapshotName(name);
        String id = newId("gs-");
        long now = Instant.now().getEpochSecond();
        String account = regionResolver.getAccountId();
        GraphSnapshot snapshot = new GraphSnapshot();
        snapshot.setId(id);
        snapshot.setName(name);
        snapshot.setArn(AwsArnUtils.Arn.of(SERVICE, region, account, "snapshot/" + id).toString());
        snapshot.setSourceGraphId(graph.getId());
        snapshot.setSnapshotCreateTime(now);
        snapshot.setStatus("AVAILABLE");
        snapshot.setKmsKeyIdentifier(graph.getKmsKeyIdentifier());
        snapshot.setRegion(region);
        snapshot.setTags(readTags(request.get("tags")));
        snapshots.put(snapshotKey(region, id), snapshot);
        return snapshot;
    }

    public GraphSnapshot getGraphSnapshot(String region, String snapshotIdentifier) {
        return requireSnapshot(region, snapshotIdentifier);
    }

    public Page<GraphSnapshot> listGraphSnapshots(String region, String graphIdentifier, String maxResultsValue,
            String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<GraphSnapshot> items = new ArrayList<>(
                snapshots.scan(key -> key.startsWith(region + "::")));
        if (graphIdentifier != null && !graphIdentifier.isBlank()) {
            items.removeIf(snapshot -> !graphIdentifier.equals(snapshot.getSourceGraphId()));
        }
        items.sort(Comparator.comparing(GraphSnapshot::getName, Comparator.nullsLast(String::compareTo)));
        return paginate(items, maxResults, nextToken);
    }

    public void getImportTask(String taskIdentifier) {
        throw notFound("Import task " + taskIdentifier + " not found.");
    }

    public void getExportTask(String taskIdentifier) {
        throw notFound("Export task " + taskIdentifier + " not found.");
    }

    public ObjectNode toGraph(Graph graph) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", graph.getId());
        node.put("name", graph.getName());
        node.put("arn", graph.getArn());
        node.put("status", graph.getStatus());
        node.put("createTime", graph.getCreateTime());
        node.put("provisionedMemory", graph.getProvisionedMemory());
        if (graph.getEndpoint() != null) {
            node.put("endpoint", graph.getEndpoint());
        }
        node.put("publicConnectivity", graph.isPublicConnectivity());
        node.put("replicaCount", graph.getReplicaCount());
        if (graph.getKmsKeyIdentifier() != null) {
            node.put("kmsKeyIdentifier", graph.getKmsKeyIdentifier());
        }
        if (graph.getVectorSearchDimension() != null) {
            node.putObject("vectorSearchConfiguration")
                    .put("dimension", graph.getVectorSearchDimension());
        }
        node.put("deletionProtection", graph.isDeletionProtection());
        if (graph.getBuildNumber() != null) {
            node.put("buildNumber", graph.getBuildNumber());
        }
        return node;
    }

    public ObjectNode toGraphSummary(Graph graph) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", graph.getId());
        node.put("name", graph.getName());
        node.put("arn", graph.getArn());
        node.put("status", graph.getStatus());
        node.put("provisionedMemory", graph.getProvisionedMemory());
        node.put("publicConnectivity", graph.isPublicConnectivity());
        if (graph.getEndpoint() != null) {
            node.put("endpoint", graph.getEndpoint());
        }
        node.put("replicaCount", graph.getReplicaCount());
        if (graph.getKmsKeyIdentifier() != null) {
            node.put("kmsKeyIdentifier", graph.getKmsKeyIdentifier());
        }
        node.put("deletionProtection", graph.isDeletionProtection());
        return node;
    }

    public ObjectNode toSnapshot(GraphSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", snapshot.getId());
        node.put("name", snapshot.getName());
        node.put("arn", snapshot.getArn());
        if (snapshot.getSourceGraphId() != null) {
            node.put("sourceGraphId", snapshot.getSourceGraphId());
        }
        node.put("snapshotCreateTime", snapshot.getSnapshotCreateTime());
        node.put("status", snapshot.getStatus());
        if (snapshot.getKmsKeyIdentifier() != null) {
            node.put("kmsKeyIdentifier", snapshot.getKmsKeyIdentifier());
        }
        return node;
    }

    public ArrayNode graphSummaries(List<Graph> items) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Graph graph : items) {
            array.add(toGraphSummary(graph));
        }
        return array;
    }

    public ArrayNode snapshotSummaries(List<GraphSnapshot> items) {
        ArrayNode array = objectMapper.createArrayNode();
        for (GraphSnapshot snapshot : items) {
            array.add(toSnapshot(snapshot));
        }
        return array;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Tagged tagged = requireTagged(region, arn);
        return tagged.tags() == null ? Map.of() : Map.copyOf(tagged.tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = tagged.tags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        tagged.setTags(current);
        persistTagged(region, tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        if (tagged.tags() != null && tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        persistTagged(region, tagged);
    }

    private Graph requireGraph(String region, String graphIdentifier) {
        if (graphIdentifier == null || graphIdentifier.isBlank()) {
            throw validation("graphIdentifier is required.");
        }
        Graph byId = graphs.get(storageKey(region, graphIdentifier)).orElse(null);
        if (byId != null) {
            return byId;
        }
        Graph byName = findByName(region, graphIdentifier);
        if (byName != null) {
            return byName;
        }
        throw notFound("Graph " + graphIdentifier + " not found.");
    }

    private GraphSnapshot requireSnapshot(String region, String snapshotIdentifier) {
        if (snapshotIdentifier == null || snapshotIdentifier.isBlank()) {
            throw validation("snapshotIdentifier is required.");
        }
        return snapshots.get(snapshotKey(region, snapshotIdentifier)).orElseThrow(
                () -> notFound("Snapshot " + snapshotIdentifier + " not found."));
    }

    private Graph findByName(String region, String name) {
        for (Graph graph : graphs.scan(key -> key.startsWith(region + "::"))) {
            if (name.equals(graph.getName())) {
                return graph;
            }
        }
        return null;
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw validation("resourceArn is invalid.");
        }
        String resource = parsed.resource();
        String lookupRegion = parsed.region() == null || parsed.region().isEmpty() ? region : parsed.region();
        if (resource.startsWith("graph/")) {
            return new Tagged.GraphTagged(requireGraph(lookupRegion, resource.substring("graph/".length())));
        }
        if (resource.startsWith("snapshot/")) {
            return new Tagged.SnapshotTagged(requireSnapshot(lookupRegion, resource.substring("snapshot/".length())));
        }
        throw notFound("Resource " + arn + " not found.");
    }

    private void persistTagged(String region, Tagged tagged) {
        if (tagged instanceof Tagged.GraphTagged graphTagged) {
            Graph graph = graphTagged.graph();
            graphs.put(storageKey(region, graph.getId()), graph);
        } else if (tagged instanceof Tagged.SnapshotTagged snapshotTagged) {
            GraphSnapshot snapshot = snapshotTagged.snapshot();
            snapshots.put(snapshotKey(region, snapshot.getId()), snapshot);
        }
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String snapshotKey(String region, String id) {
        return region + "::" + id;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void validateGraphName(String name) {
        if (name == null || name.length() < 1 || name.length() > 63 || !GRAPH_NAME.matcher(name).matches()) {
            throw validation("graphName must start with a letter and contain only letters, numbers, and hyphens.");
        }
        if (name.startsWith("g-") || name.startsWith("gs-") || name.startsWith("t-")) {
            throw validation("graphName must not start with g-, gs-, or t-.");
        }
    }

    private static void validateSnapshotName(String name) {
        if (name == null || name.length() < 1 || name.length() > 63 || !SNAPSHOT_NAME.matcher(name).matches()) {
            throw validation("snapshotName must start with a letter and contain only letters, numbers, and hyphens.");
        }
    }

    private static Integer readVectorDimension(JsonNode configuration) {
        if (configuration == null || configuration.isNull()) {
            return null;
        }
        if (!configuration.isObject()) {
            throw validation("vectorSearchConfiguration must be an object.");
        }
        if (!configuration.has("dimension") || !configuration.get("dimension").isNumber()) {
            throw validation("vectorSearchConfiguration.dimension must be an integer.");
        }
        int dimension = configuration.get("dimension").intValue();
        if (dimension < 1 || dimension > 65535) {
            throw validation("vectorSearchConfiguration.dimension must be between 1 and 65535.");
        }
        return dimension;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && !entry.getValue().isNull()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static <T> Page<T> paginate(List<T> items, int maxResults, String nextToken) {
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw validation("nextToken is invalid.");
            }
            if (offset < 0 || offset >= items.size()) {
                throw validation("nextToken is invalid.");
            }
        }
        int end = Math.min(offset + maxResults, items.size());
        String token = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer.");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) {
            throw validation(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static int optionalInt(JsonNode parent, String field, int fallback) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return fallback;
        }
        return requireInt(parent, field);
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return fallback;
        }
        return requireBoolean(parent, field);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private sealed interface Tagged {
        Map<String, String> tags();

        void setTags(Map<String, String> tags);

        record GraphTagged(Graph graph) implements Tagged {
            @Override
            public Map<String, String> tags() {
                return graph.getTags();
            }

            @Override
            public void setTags(Map<String, String> tags) {
                graph.setTags(tags);
            }
        }

        record SnapshotTagged(GraphSnapshot snapshot) implements Tagged {
            @Override
            public Map<String, String> tags() {
                return snapshot.getTags();
            }

            @Override
            public void setTags(Map<String, String> tags) {
                snapshot.setTags(tags);
            }
        }
    }
}
