package io.github.hectorvent.floci.services.keyspacesstreams;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.keyspacesstreams.model.KeyspacesChangeRecord;
import io.github.hectorvent.floci.services.keyspacesstreams.model.KeyspacesStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Keyspaces CDC streams data plane ({@code cassandra:ListStreams} /
 * {@code GetStream} / {@code GetShardIterator} / {@code GetRecords}).
 *
 * @see <a href="https://docs.aws.amazon.com/keyspaces/latest/APIReference/API_Operations_Amazon_Keyspaces_Streams.html">Keyspaces Streams API</a>
 */
@ApplicationScoped
public class KeyspacesStreamsService implements Resettable {

    static final String SERVICE = "keyspacesstreams";

    private static final DateTimeFormatter STREAM_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final Pattern STREAM_ARN = Pattern.compile(
            "^arn:aws:cassandra:([^:]+):([^:]+):/keyspace/([^/]+)/table/([^/]+)/stream/(.+)$");

    private static final int MAX_RECORDS = 1000;

    private final ConcurrentHashMap<String, KeyspacesStream> streams = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final String defaultAccountId;

    @Inject
    public KeyspacesStreamsService(EmulatorConfig config) {
        this.defaultAccountId = config.defaultAccountId();
    }

    KeyspacesStreamsService(String defaultAccountId) {
        this.defaultAccountId = defaultAccountId;
    }

    @Override
    public void clear() {
        streams.clear();
        sequenceCounter.set(0);
    }

    public KeyspacesStream enableStream(String keyspaceName, String tableName, String viewType,
                                        String region, String accountId) {
        String key = streamKey(region, keyspaceName, tableName);
        KeyspacesStream existing = streams.get(key);
        if (existing != null && "ENABLED".equals(existing.getStreamStatus())) {
            return existing;
        }

        Instant now = Instant.now();
        String label = STREAM_LABEL_FORMAT.format(now);
        String account = accountId != null && !accountId.isBlank() ? accountId : defaultAccountId;
        String streamArn = "arn:aws:cassandra:" + region + ":" + account
                + ":/keyspace/" + keyspaceName + "/table/" + tableName + "/stream/" + label;

        KeyspacesStream stream = new KeyspacesStream();
        stream.setStreamArn(streamArn);
        stream.setStreamLabel(label);
        stream.setStreamStatus("ENABLED");
        stream.setStreamViewType(viewType == null || viewType.isBlank() ? "NEW_AND_OLD_IMAGES" : viewType);
        stream.setKeyspaceName(keyspaceName);
        stream.setTableName(tableName);
        stream.setCreationRequestDateTime(now);
        stream.setStartingSequenceNumber(String.format("%021d", 1));
        streams.put(key, stream);
        return stream;
    }

    public void disableStream(String keyspaceName, String tableName, String region) {
        KeyspacesStream stream = streams.get(streamKey(region, keyspaceName, tableName));
        if (stream != null) {
            stream.setStreamStatus("DISABLED");
        }
    }

    public void deleteStream(String keyspaceName, String tableName, String region) {
        streams.remove(streamKey(region, keyspaceName, tableName));
    }

    public List<KeyspacesStream> listStreams(String keyspaceName, String tableName) {
        List<KeyspacesStream> result = new ArrayList<>();
        for (KeyspacesStream stream : streams.values()) {
            if (keyspaceName != null && !keyspaceName.equals(stream.getKeyspaceName())) {
                continue;
            }
            if (tableName != null && !tableName.equals(stream.getTableName())) {
                continue;
            }
            result.add(stream);
        }
        return result;
    }

    public KeyspacesStream getStream(String streamArn) {
        requireStreamArn(streamArn);
        for (KeyspacesStream stream : streams.values()) {
            if (streamArn.equals(stream.getStreamArn())) {
                return stream;
            }
        }
        throw notFound(streamArn);
    }

    public String getShardIterator(String streamArn, String shardId, String iteratorType,
                                   String sequenceNumber) {
        KeyspacesStream stream = getStream(streamArn);
        if (shardId == null || shardId.isBlank()) {
            throw validation("shardId is a required parameter");
        }
        if (!KeyspacesStream.SHARD_ID.equals(shardId)) {
            throw notFound(streamArn);
        }
        if (iteratorType == null || iteratorType.isBlank()) {
            throw validation("shardIteratorType is a required parameter");
        }

        List<KeyspacesChangeRecord> snapshot = stream.snapshotRecords();
        int position = switch (iteratorType) {
            case "TRIM_HORIZON" -> 0;
            case "LATEST" -> snapshot.size();
            case "AT_SEQUENCE_NUMBER" -> findSequencePosition(snapshot, sequenceNumber, false);
            case "AFTER_SEQUENCE_NUMBER" -> findSequencePosition(snapshot, sequenceNumber, true);
            default -> throw validation("Unknown iterator type: " + iteratorType);
        };
        return encodeIterator(streamArn, position);
    }

    public GetRecordsResult getRecords(String shardIterator, Integer maxResults) {
        if (shardIterator == null || shardIterator.isBlank()) {
            throw validation("shardIterator is a required parameter");
        }
        String[] parts = decodeIterator(shardIterator);
        String streamArn = parts[0];
        int position;
        try {
            position = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw validation("Invalid shard iterator");
        }

        KeyspacesStream stream = getStream(streamArn);
        List<KeyspacesChangeRecord> snapshot = stream.snapshotRecords();
        int effectiveLimit = maxResults != null ? maxResults : 100;
        int end = Math.min(position + effectiveLimit, snapshot.size());
        List<KeyspacesChangeRecord> page = new ArrayList<>(snapshot.subList(position, end));
        return new GetRecordsResult(page, encodeIterator(streamArn, end),
                end >= snapshot.size() ? "AT_TIP" : "BEHIND_TIP");
    }

    public void captureChange(String keyspaceName, String tableName, String region,
                              KeyspacesChangeRecord record) {
        KeyspacesStream stream = streams.get(streamKey(region, keyspaceName, tableName));
        if (stream == null || !"ENABLED".equals(stream.getStreamStatus())) {
            return;
        }
        long seq = sequenceCounter.incrementAndGet();
        record.setSequenceNumber(String.format("%021d", seq));
        if (record.getCreatedAt() == 0) {
            record.setCreatedAt(Instant.now().getEpochSecond());
        }
        ConcurrentLinkedDeque<KeyspacesChangeRecord> deque = stream.getRecords();
        deque.addLast(record);
        while (deque.size() > MAX_RECORDS) {
            deque.pollFirst();
        }
    }

    public static String parseKeyspace(String streamArn) {
        Matcher matcher = STREAM_ARN.matcher(streamArn);
        return matcher.matches() ? matcher.group(3) : null;
    }

    public static String parseTable(String streamArn) {
        Matcher matcher = STREAM_ARN.matcher(streamArn);
        return matcher.matches() ? matcher.group(4) : null;
    }

    private static void requireStreamArn(String streamArn) {
        if (streamArn == null || streamArn.isBlank()) {
            throw validation("streamArn is a required parameter");
        }
        if (!STREAM_ARN.matcher(streamArn).matches()) {
            throw validation("Invalid stream ARN format");
        }
    }

    private int findSequencePosition(List<KeyspacesChangeRecord> records, String targetSeq, boolean after) {
        if (targetSeq == null || targetSeq.isBlank()) {
            throw validation("sequenceNumber is required for " + (after ? "AFTER" : "AT") + "_SEQUENCE_NUMBER");
        }
        for (int i = 0; i < records.size(); i++) {
            int cmp = records.get(i).getSequenceNumber().compareTo(targetSeq);
            if (after ? cmp > 0 : cmp >= 0) {
                return i;
            }
        }
        return records.size();
    }

    private static String encodeIterator(String streamArn, int position) {
        String raw = streamArn + "|" + position;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeIterator(String iterator) {
        try {
            String raw = new String(Base64.getDecoder().decode(iterator), StandardCharsets.UTF_8);
            int lastPipe = raw.lastIndexOf('|');
            if (lastPipe < 0) {
                throw validation("Invalid shard iterator");
            }
            return new String[]{raw.substring(0, lastPipe), raw.substring(lastPipe + 1)};
        } catch (IllegalArgumentException e) {
            throw validation("Invalid shard iterator");
        }
    }

    private static String streamKey(String region, String keyspaceName, String tableName) {
        return region + "::" + keyspaceName + "::" + tableName;
    }

    private static AwsException notFound(String streamArn) {
        return new AwsException("ResourceNotFoundException", "Stream not found: " + streamArn, 404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record GetRecordsResult(List<KeyspacesChangeRecord> records, String nextShardIterator,
                                   String iteratorPosition) {
    }
}
