package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.keyspaces.KeyspacesStore.STREAM;
import static io.floci.aws.services.keyspaces.KeyspacesStore.meta;
import static io.floci.aws.services.keyspaces.KeyspacesStore.text;

/**
 * Amazon Keyspaces Streams: the CDC stream of a table, read from the table's mutation log. A stream has one shard;
 * records are the writes, deletes and TTL expiries made while the stream was enabled, kept 24 hours, with the keys,
 * and the new and old images its view type asks for. Iterators start at the trim horizon, the tip, or a sequence
 * number, and expire after 15 minutes.
 */
@ApplicationScoped
public class KeyspacesStreams {

    static final long ITERATOR_MILLIS = 15 * 60_000L;

    private final KeyspacesStore store;
    private final KeyspacesService service;

    @Inject
    public KeyspacesStreams(KeyspacesStore store, KeyspacesService service) {
        this.store = store;
        this.service = service;
    }

    private ObjectMapper mapper() {
        return store.mapper();
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException invalid(String message, String code) {
        return new AwsException("ValidationException", message, 400, Map.of("errorCode", code));
    }

    static String sequence(long seq) {
        return String.format("%021d", seq);
    }

    private List<ObjectNode> streams(String account, String region, String keyspace, String table) {
        List<ObjectNode> streams = new ArrayList<>();
        String prefix = keyspace == null ? "" : keyspace + "/" + (table == null ? "" : table + "/");
        long now = System.currentTimeMillis();
        for (ObjectNode stream : store.list(account, region, STREAM, prefix)) {
            ObjectNode settled = service.settle(account, region, STREAM, stream);
            if (settled == null) {
                continue;
            }
            if (meta(settled).has("endedAt") && meta(settled).get("endedAt").asLong()
                    + KeyspacesService.STREAM_RETENTION_MILLIS < now) {
                store.delete(account, region, STREAM, meta(settled).path("path").asText());
                continue;
            }
            if (keyspace != null && table == null && !settled.path("keyspaceName").asText().equals(keyspace)) {
                continue;
            }
            streams.add(settled);
        }
        return streams;
    }

    private ObjectNode stream(String account, String region, String arn) {
        ObjectNode stream = service.stream(account, region, arn);
        if (stream == null) {
            throw notFound("Stream " + arn + " doesn't exist.");
        }
        return stream;
    }

    public ObjectNode listStreams(JsonNode in, String region) {
        String account = store.account();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stream : streams(account, region, text(in, "keyspaceName"), text(in, "tableName"))) {
            items.add(mapper().createObjectNode().put("streamArn", stream.path("streamArn").asText())
                    .put("keyspaceName", stream.path("keyspaceName").asText())
                    .put("tableName", stream.path("tableName").asText())
                    .put("streamLabel", stream.path("streamLabel").asText()));
        }
        ObjectNode out = mapper().createObjectNode();
        KeyspacesStore.page(items, in, out, "streams", 100);
        return out;
    }

    public ObjectNode getStream(JsonNode in, String region) {
        String account = store.account();
        ObjectNode stream = stream(account, region, text(in, "streamArn"));
        ObjectNode out = KeyspacesStore.view(stream);
        ArrayNode shards = out.putArray("shards");
        boolean childFilter = "CHILD_SHARDS".equals(in.path("shardFilter").path("type").asText());
        if (!childFilter) {
            ObjectNode shard = shards.addObject().put("shardId", meta(stream).path("shard").asText());
            ObjectNode range = shard.putObject("sequenceNumberRange");
            range.put("startingSequenceNumber", sequence(meta(stream).path("startSeq").asLong(0)));
            if (meta(stream).has("endedAt")) {
                range.put("endingSequenceNumber", sequence(lastSeq(account, region, stream)));
            }
            shard.putArray("parentShardIds");
        }
        return out;
    }

    private long lastSeq(String account, String region, ObjectNode stream) {
        long last = 0;
        for (ObjectNode change : records(account, region, stream)) {
            last = Math.max(last, change.path("seq").asLong());
        }
        return last;
    }

    /** The changes a stream covers: made while it was enabled and within the last day. */
    private List<ObjectNode> records(String account, String region, ObjectNode stream) {
        long from = Math.max(meta(stream).path("createdAt").asLong(), System.currentTimeMillis()
                - KeyspacesService.STREAM_RETENTION_MILLIS);
        long until = meta(stream).path("endedAt").asLong(Long.MAX_VALUE);
        List<ObjectNode> records = new ArrayList<>();
        for (ObjectNode change : store.changes(account, region, stream.path("keyspaceName").asText(),
                stream.path("tableName").asText())) {
            long at = change.path("at").asLong();
            if (at >= from && at <= until) {
                records.add(change);
            }
        }
        return records;
    }

    public ObjectNode getShardIterator(JsonNode in, String region) {
        String account = store.account();
        String arn = text(in, "streamArn");
        ObjectNode stream = stream(account, region, arn);
        if (!meta(stream).path("shard").asText().equals(text(in, "shardId"))) {
            throw notFound("Shard " + text(in, "shardId") + " doesn't exist in " + arn + ".");
        }
        String type = text(in, "shardIteratorType");
        long after;
        switch (type) {
            case "TRIM_HORIZON" -> after = -1;
            case "LATEST" -> after = lastSeq(account, region, stream);
            case "AT_SEQUENCE_NUMBER", "AFTER_SEQUENCE_NUMBER" -> {
                String seq = text(in, "sequenceNumber");
                if (seq == null) {
                    throw invalid(type + " needs a sequenceNumber.", "InvalidFormat");
                }
                long n;
                try {
                    n = Long.parseLong(seq);
                } catch (NumberFormatException e) {
                    throw invalid("The sequence number " + seq + " is not valid.", "InvalidFormat");
                }
                after = "AT_SEQUENCE_NUMBER".equals(type) ? n - 1 : n;
            }
            default -> throw invalid("The shard iterator type " + type + " is not valid.", "InvalidFormat");
        }
        return mapper().createObjectNode().put("shardIterator", iterator(arn, after));
    }

    private String iterator(String arn, long after) {
        ObjectNode it = mapper().createObjectNode();
        it.put("a", arn).put("q", after).put("x", System.currentTimeMillis() + ITERATOR_MILLIS);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(it.toString().getBytes(StandardCharsets.UTF_8));
    }

    public ObjectNode getRecords(JsonNode in, String region) {
        String account = store.account();
        JsonNode it;
        try {
            it = mapper().readTree(Base64.getUrlDecoder().decode(text(in, "shardIterator")));
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("The shard iterator is not valid.", "InvalidFormat");
        }
        if (it.path("x").asLong() < System.currentTimeMillis()) {
            throw invalid("The shard iterator has expired.", "ExpiredIterator");
        }
        ObjectNode stream = stream(account, region, it.path("a").asText());
        long after = it.path("q").asLong();
        int max = in.hasNonNull("maxResults") ? in.get("maxResults").asInt() : 1000;
        ObjectNode table = service.find(account, region, KeyspacesStore.TABLE, stream.path("keyspaceName").asText()
                + "/" + stream.path("tableName").asText());
        Map<String, CqlType> types = new HashMap<>();
        if (table != null) {
            for (JsonNode column : table.path("schemaDefinition").path("allColumns")) {
                types.put(column.path("name").asText(), CqlType.parse(column.path("type").asText(), u -> true));
            }
        }
        ObjectNode out = mapper().createObjectNode();
        ArrayNode records = out.putArray("changeRecords");
        List<ObjectNode> pending = records(account, region, stream).stream().filter(c -> c.path("seq").asLong()
                > after).toList();
        long last = after;
        String view = stream.path("streamViewType").asText();
        for (ObjectNode change : pending.subList(0, Math.min(max, pending.size()))) {
            records.add(record(change, view, types, account, region, stream));
            last = change.path("seq").asLong();
        }
        boolean atTip = pending.size() <= max;
        out.putObject("iteratorDescription").put("iteratorPosition", atTip ? "AT_TIP" : "BEHIND_TIP");
        if (!(atTip && meta(stream).has("endedAt"))) {
            out.put("nextShardIterator", iterator(it.path("a").asText(), last));
        }
        return out;
    }

    private ObjectNode record(ObjectNode change, String view, Map<String, CqlType> types, String account,
                              String region, ObjectNode stream) {
        ObjectNode record = mapper().createObjectNode();
        record.put("eventVersion", "1.0").put("createdAt", KeyspacesStore.epoch(change.path("at").asLong()))
                .put("origin", change.path("origin").asText("USER"))
                .put("sequenceNumber", sequence(change.path("seq").asLong()));
        ObjectNode pk = record.putObject("partitionKeys");
        change.path("pk").fields().forEachRemaining(e -> pk.set(e.getKey(), CqlValues.cell(mapper(),
                types.get(e.getKey()), e.getValue(), udt -> udtFields(account, region, stream, udt))));
        ObjectNode ck = record.putObject("clusteringKeys");
        change.path("ck").fields().forEachRemaining(e -> ck.set(e.getKey(), CqlValues.cell(mapper(),
                types.get(e.getKey()), e.getValue(), udt -> udtFields(account, region, stream, udt))));
        if (view.contains("NEW") && change.path("new").isObject()) {
            record.set("newImage", image(change.get("new"), types, account, region, stream));
        }
        if (view.contains("OLD") && change.path("old").isObject()) {
            record.set("oldImage", image(change.get("old"), types, account, region, stream));
        }
        return record;
    }

    private ObjectNode image(JsonNode row, Map<String, CqlType> types, String account, String region,
                             ObjectNode stream) {
        ObjectNode image = mapper().createObjectNode();
        ObjectNode cells = image.putObject(row.path("static").asBoolean(false) ? "staticCells" : "valueCells");
        long newest = 0;
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) row.path("cells")::fields) {
            JsonNode cell = e.getValue();
            ObjectNode out = cells.putObject(e.getKey());
            out.set("value", CqlValues.cell(mapper(), types.get(e.getKey()), cell.get("v"), udt -> udtFields(
                    account, region, stream, udt)));
            ObjectNode metadata = out.putObject("metadata");
            metadata.put("writeTime", cell.path("wt").asText());
            if (cell.hasNonNull("exp")) {
                metadata.put("expirationTime", Instant.ofEpochSecond(cell.get("exp").asLong()).toString());
            }
            newest = Math.max(newest, cell.path("wt").asLong());
        }
        if (newest > 0) {
            image.putObject("rowMetadata").put("writeTime", String.valueOf(newest));
        }
        return image;
    }

    private Map<String, CqlType> udtFields(String account, String region, ObjectNode stream, String udt) {
        Map<String, CqlType> fields = new HashMap<>();
        store.get(account, region, KeyspacesStore.TYPE, stream.path("keyspaceName").asText() + "/" + udt)
                .ifPresent(type -> type.path("fieldDefinitions").forEach(f -> fields.put(f.path("name").asText(),
                        CqlType.parse(f.path("type").asText(), u -> true))));
        return fields;
    }
}
