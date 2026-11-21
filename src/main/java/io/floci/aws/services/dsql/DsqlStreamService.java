package io.floci.aws.services.dsql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.dsql.model.DsqlCluster;
import io.floci.aws.services.kinesis.KinesisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aurora DSQL change data capture streams into Kinesis: the control plane
 * (create, get, list, delete) and tags.
 * <p>
 * Creating a stream checks what DSQL checks: the cluster exists and is
 * {@code ACTIVE}, the target Kinesis stream exists, and the ordering and
 * format are ones DSQL supports ({@code UNORDERED}, {@code JSON}). A stream is
 * {@code ACTIVE} at once ({@code CREATING} in the create response) and is gone
 * once deleted, or once its cluster is. Floci captures no changes: no records
 * reach the Kinesis stream.
 */
@ApplicationScoped
public class DsqlStreamService {
    private static final Set<String> ORDERINGS = Set.of("UNORDERED");
    private static final Set<String> FORMATS = Set.of("JSON");

    private final StorageBackend<String, ObjectNode> streams;
    private final DsqlService clusters;
    private final KinesisService kinesis;
    private final ObjectMapper objectMapper;

    @Inject
    public DsqlStreamService(StorageFactory storageFactory, DsqlService clusters, KinesisService kinesis,
                             ObjectMapper objectMapper) {
        this.streams = storageFactory.create("dsql", "dsql-streams.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.clusters = clusters;
        this.kinesis = kinesis;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createStream(String region, String clusterId, JsonNode request) {
        DsqlCluster cluster = clusters.getCluster(region, clusterId);
        if (!"ACTIVE".equals(cluster.getStatus())) {
            throw new AwsException("ConflictException", "Cluster " + clusterId + " is " + cluster.getStatus()
                    + "; a stream needs an ACTIVE cluster.", 409);
        }
        JsonNode target = request.path("targetDefinition").path("kinesis");
        String streamArn = target.path("streamArn").asText(null);
        String roleArn = target.path("roleArn").asText(null);
        if (streamArn == null || roleArn == null) {
            throw validation("targetDefinition.kinesis needs a streamArn and a roleArn.");
        }
        try {
            kinesis.describeStreamByArn(streamArn, region);
        } catch (AwsException e) {
            throw validation("The Kinesis stream " + streamArn + " doesn't exist.");
        }
        String ordering = requireOne(request, "ordering", ORDERINGS);
        String format = requireOne(request, "format", FORMATS);
        String token = request.path("clientToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : streamsOf(region, clusterId)) {
                if (token.equals(existing.path("clientToken").asText(null))) {
                    return view(existing, false);
                }
            }
        }
        String id = DsqlIdentifiers.newId();
        ObjectNode stream = objectMapper.createObjectNode();
        stream.put("clusterIdentifier", clusterId);
        stream.put("streamIdentifier", id);
        stream.put("arn", cluster.getArn() + "/stream/" + id);
        stream.put("status", "ACTIVE");
        stream.put("creationTime", Instant.now().toEpochMilli() / 1000.0);
        stream.put("ordering", ordering);
        stream.put("format", format);
        stream.putObject("targetDefinition").putObject("kinesis").put("streamArn", streamArn).put("roleArn", roleArn);
        ObjectNode tags = stream.putObject("tags");
        if (request.path("tags").isObject()) {
            tags.setAll((ObjectNode) request.get("tags"));
        }
        if (token != null) {
            stream.put("clientToken", token);
        }
        streams.put(key(region, clusterId, id), stream);
        ObjectNode response = view(stream, false);
        response.put("status", "CREATING");
        return response;
    }

    public ObjectNode getStream(String region, String clusterId, String streamId) {
        return view(requireStream(region, clusterId, streamId), true);
    }

    public ObjectNode listStreams(String region, String clusterId, Integer maxResults, String nextToken) {
        clusters.getCluster(region, clusterId);
        List<ObjectNode> all = streamsOf(region, clusterId).stream()
                .sorted(Comparator.comparingDouble((ObjectNode s) -> s.path("creationTime").asDouble()))
                .toList();
        int max = maxResults == null ? 100 : maxResults;
        if (max < 1 || max > 100) {
            throw validation("max-results must be between 1 and 100.");
        }
        int start = 0;
        if (nextToken != null) {
            try {
                start = Math.max(0, Integer.parseInt(nextToken));
            } catch (NumberFormatException e) {
                throw validation("Invalid next-token.");
            }
        }
        int end = Math.min(all.size(), start + max);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("streams");
        for (ObjectNode stream : all.subList(Math.min(start, all.size()), end)) {
            ObjectNode summary = list.addObject();
            for (String field : List.of("clusterIdentifier", "streamIdentifier", "arn", "creationTime", "status")) {
                summary.set(field, stream.get(field).deepCopy());
            }
        }
        if (end < all.size()) {
            response.put("nextToken", Integer.toString(end));
        }
        return response;
    }

    public synchronized ObjectNode deleteStream(String region, String clusterId, String streamId) {
        ObjectNode stream = requireStream(region, clusterId, streamId);
        streams.delete(key(region, clusterId, streamId));
        ObjectNode response = view(stream, false);
        response.put("status", "DELETING");
        return response;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public static boolean isStreamArn(String arn) {
        return arn != null && arn.contains(":cluster/") && arn.contains("/stream/");
    }

    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = new LinkedHashMap<>();
        streamOfArn(region, arn).path("tags").fields()
                .forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        ObjectNode stream = streamOfArn(region, arn);
        ObjectNode current = (ObjectNode) stream.get("tags");
        (tags == null ? Map.<String, String>of() : tags).forEach(current::put);
        save(region, stream);
    }

    public synchronized void untagResource(String region, String arn, List<String> keys) {
        ObjectNode stream = streamOfArn(region, arn);
        ObjectNode current = (ObjectNode) stream.get("tags");
        (keys == null ? List.<String>of() : keys).forEach(current::remove);
        save(region, stream);
    }

    private ObjectNode streamOfArn(String region, String arn) {
        String afterCluster = arn.substring(arn.indexOf(":cluster/") + ":cluster/".length());
        String[] parts = afterCluster.split("/stream/", 2);
        return requireStream(region, parts[0], parts.length > 1 ? parts[1] : "");
    }

    private void save(String region, ObjectNode stream) {
        streams.put(key(region, stream.path("clusterIdentifier").asText(), stream.path("streamIdentifier").asText()),
                stream);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode requireStream(String region, String clusterId, String streamId) {
        ObjectNode stream = streams.get(key(region, clusterId, streamId)).orElse(null);
        if (stream == null || !clusterExists(region, clusterId)) {
            throw new AwsException("ResourceNotFoundException", "The stream " + streamId + " of cluster " + clusterId
                    + " doesn't exist.", 404, Map.of("resourceId", streamId, "resourceType", "stream"));
        }
        return stream;
    }

    private boolean clusterExists(String region, String clusterId) {
        try {
            clusters.getCluster(region, clusterId);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private List<ObjectNode> streamsOf(String region, String clusterId) {
        String prefix = region + "/" + clusterId + "/";
        return streams.scan(k -> k.startsWith(prefix));
    }

    private ObjectNode view(ObjectNode stream, boolean detailed) {
        ObjectNode view = objectMapper.createObjectNode();
        for (String field : List.of("clusterIdentifier", "streamIdentifier", "arn", "status", "creationTime",
                "ordering", "format")) {
            view.set(field, stream.get(field).deepCopy());
        }
        if (detailed) {
            view.set("targetDefinition", stream.get("targetDefinition").deepCopy());
            view.set("tags", stream.get("tags").deepCopy());
        }
        return view;
    }

    private static String requireOne(JsonNode request, String field, Set<String> allowed) {
        String value = request.path(field).asText(null);
        if (value == null || !allowed.contains(value)) {
            throw validation(field + " must be one of " + allowed + ".");
        }
        return value;
    }

    private static String key(String region, String clusterId, String streamId) {
        return region + "/" + clusterId + "/" + streamId;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
