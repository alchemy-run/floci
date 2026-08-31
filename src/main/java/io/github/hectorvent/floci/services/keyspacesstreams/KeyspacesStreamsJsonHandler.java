package io.github.hectorvent.floci.services.keyspacesstreams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.keyspacesstreams.model.KeyspacesChangeRecord;
import io.github.hectorvent.floci.services.keyspacesstreams.model.KeyspacesStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * JSON 1.0 handler for Amazon Keyspaces Streams. Dispatched from
 * {@code AwsJsonController} under the {@code KeyspacesStreams.} target prefix.
 *
 * @see <a href="https://docs.aws.amazon.com/keyspaces/latest/APIReference/API_Operations_Amazon_Keyspaces_Streams.html">Keyspaces Streams API</a>
 */
@ApplicationScoped
public class KeyspacesStreamsJsonHandler {

    private static final String TARGET_PREFIX = "KeyspacesStreams.";

    private final KeyspacesStreamsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public KeyspacesStreamsJsonHandler(KeyspacesStreamsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        return switch (action) {
            case "ListStreams" -> handleListStreams(body);
            case "GetStream" -> handleGetStream(body);
            case "GetShardIterator" -> handleGetShardIterator(body);
            case "GetRecords" -> handleGetRecords(body);
            default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
        };
    }

    private Response handleListStreams(JsonNode request) {
        String keyspaceName = text(request, "keyspaceName", "KeyspaceName");
        String tableName = text(request, "tableName", "TableName");
        List<KeyspacesStream> streams = service.listStreams(keyspaceName, tableName);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = objectMapper.createArrayNode();
        for (KeyspacesStream stream : streams) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("streamArn", stream.getStreamArn());
            entry.put("keyspaceName", stream.getKeyspaceName());
            entry.put("tableName", stream.getTableName());
            entry.put("streamLabel", stream.getStreamLabel());
            list.add(entry);
        }
        response.set("streams", list);
        return ok(response);
    }

    private Response handleGetStream(JsonNode request) {
        String streamArn = text(request, "streamArn", "StreamArn");
        KeyspacesStream stream = service.getStream(streamArn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("streamArn", stream.getStreamArn());
        response.put("streamLabel", stream.getStreamLabel());
        response.put("streamStatus", stream.getStreamStatus());
        response.put("streamViewType", stream.getStreamViewType());
        response.put("creationRequestDateTime", stream.getCreationRequestDateTime().getEpochSecond());
        response.put("keyspaceName", stream.getKeyspaceName());
        response.put("tableName", stream.getTableName());

        ArrayNode shards = objectMapper.createArrayNode();
        ObjectNode shard = objectMapper.createObjectNode();
        shard.put("shardId", KeyspacesStream.SHARD_ID);
        ObjectNode range = objectMapper.createObjectNode();
        range.put("startingSequenceNumber", stream.getStartingSequenceNumber());
        shard.set("sequenceNumberRange", range);
        shards.add(shard);
        response.set("shards", shards);
        return ok(response);
    }

    private Response handleGetShardIterator(JsonNode request) {
        String streamArn = text(request, "streamArn", "StreamArn");
        String shardId = text(request, "shardId", "ShardId");
        String iteratorType = text(request, "shardIteratorType", "ShardIteratorType");
        String sequenceNumber = text(request, "sequenceNumber", "SequenceNumber");
        String iterator = service.getShardIterator(streamArn, shardId, iteratorType, sequenceNumber);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("shardIterator", iterator);
        return ok(response);
    }

    private Response handleGetRecords(JsonNode request) {
        String shardIterator = text(request, "shardIterator", "ShardIterator");
        Integer maxResults = integer(request, "maxResults", "MaxResults");
        KeyspacesStreamsService.GetRecordsResult result = service.getRecords(shardIterator, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = objectMapper.createArrayNode();
        for (KeyspacesChangeRecord record : result.records()) {
            records.add(recordToNode(record));
        }
        response.set("changeRecords", records);
        response.put("nextShardIterator", result.nextShardIterator());
        ObjectNode iteratorDescription = objectMapper.createObjectNode();
        iteratorDescription.put("iteratorPosition", result.iteratorPosition());
        response.set("iteratorDescription", iteratorDescription);
        return ok(response);
    }

    private ObjectNode recordToNode(KeyspacesChangeRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        if (record.getEventVersion() != null) {
            node.put("eventVersion", record.getEventVersion());
        }
        node.put("createdAt", record.getCreatedAt());
        if (record.getOrigin() != null) {
            node.put("origin", record.getOrigin());
        }
        if (record.getPartitionKeys() != null) {
            node.set("partitionKeys", record.getPartitionKeys());
        }
        if (record.getClusteringKeys() != null) {
            node.set("clusteringKeys", record.getClusteringKeys());
        }
        if (record.getNewImage() != null) {
            node.set("newImage", record.getNewImage());
        }
        if (record.getOldImage() != null) {
            node.set("oldImage", record.getOldImage());
        }
        if (record.getSequenceNumber() != null) {
            node.put("sequenceNumber", record.getSequenceNumber());
        }
        return node;
    }

    private static String text(JsonNode node, String camel, String pascal) {
        if (node.hasNonNull(camel)) {
            return node.get(camel).asText();
        }
        if (node.hasNonNull(pascal)) {
            return node.get(pascal).asText();
        }
        return null;
    }

    private static Integer integer(JsonNode node, String camel, String pascal) {
        if (node.hasNonNull(camel)) {
            return node.get(camel).asInt();
        }
        if (node.hasNonNull(pascal)) {
            return node.get(pascal).asInt();
        }
        return null;
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
