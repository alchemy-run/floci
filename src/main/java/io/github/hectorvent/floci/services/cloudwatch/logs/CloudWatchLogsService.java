package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.Destination;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class CloudWatchLogsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchLogsService.class);

    /**
     * Orders events by timestamp, then by ingestion sequence so that events sharing the same
     * millisecond timestamp are returned in the order they were ingested (matching CloudWatch Logs).
     * Falls back to {@code eventId} so ordering stays deterministic even for legacy events that
     * predate {@code sequence} and therefore share the default value of {@code 0}.
     */
    private static final Comparator<LogEvent> EVENT_ORDER =
            Comparator.comparingLong(LogEvent::getTimestamp)
                    .thenComparingLong(LogEvent::getSequence)
                    .thenComparing(LogEvent::getEventId);

    /** AWS PutLogEvents rejects events more than two hours in the future. */
    private static final long TOO_NEW_HORIZON_MS = 2L * 60 * 60 * 1000;
    private static final String DEFAULT_LOG_GROUP_CLASS = "STANDARD";
    private static final String CANONICAL_ACCOUNT = "000000000000";
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final StorageBackend<String, LogGroup> groupStore;
    private final StorageBackend<String, LogStream> streamStore;
    private final StorageBackend<String, LogEvent> eventStore;
    private final StorageBackend<String, SubscriptionFilter> subscriptionFilterStore;
    private final StorageBackend<String, Destination> destinationStore;
    private final StorageBackend<String, MetricFilter> metricFilterStore;
    private final RegionResolver regionResolver;
    private final String canonicalAccountId;
    private final Instance<LambdaService> lambdaServices;
    private final Instance<IamService> iamServices;
    private final Instance<KinesisService> kinesisServices;
    private final int maxEventsPerQuery;
    /**
     * Monotonic counter assigning an ingestion sequence to each stored event. Seeded from
     * the highest sequence already in the store so ordering survives persistence reloads.
     */
    private final AtomicLong ingestionSequence;
    private final long queryCompletionDelayMs;

    // Wall-clock source of "now" for the query lifecycle, injected so tests can drive it deterministically.
    // Non-monotonic: a backward NTP step could briefly flip a Complete query back to Running — a rare,
    // self-healing emulator artifact we accept rather than complicate the injectable clock with nanoTime.
    private final LongSupplier clock;

    /** Cached Logs Insights queries keyed by queryId, bounded with LRU-style eviction. */
    private static final int MAX_STORED_QUERIES = 100;
    private final Map<String, QueryRecord> insightsQueries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, QueryRecord> eldest) {
                    return size() > MAX_STORED_QUERIES;
                }
            });

    @Inject
    public CloudWatchLogsService(StorageFactory storageFactory,
                                  EmulatorConfig config,
                                  RegionResolver regionResolver,
                                  Instance<LambdaService> lambdaServices,
                                  Instance<IamService> iamServices,
                                  Instance<KinesisService> kinesisServices) {
        this(
                storageFactory.create("cloudwatchlogs", "cwlogs-groups.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-streams.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-events.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-subscription-filters.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-destinations.json",
                        new TypeReference<>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-metric-filters.json",
                        new TypeReference<>() {}),
                config.services().cloudwatchlogs().maxEventsPerQuery(),
                regionResolver,
                config.defaultAccountId() != null ? config.defaultAccountId() : CANONICAL_ACCOUNT,
                lambdaServices,
                iamServices,
                kinesisServices,
                config.services().cloudwatchlogs().queryCompletionDelayMs(),
                System::currentTimeMillis
        );
    }

    CloudWatchLogsService(StorageBackend<String, LogGroup> groupStore,
                           StorageBackend<String, LogStream> streamStore,
                           StorageBackend<String, LogEvent> eventStore,
                           StorageBackend<String, SubscriptionFilter> subscriptionFilterStore,
                           int maxEventsPerQuery,
                           RegionResolver regionResolver) {
        this(groupStore, streamStore, eventStore, subscriptionFilterStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                maxEventsPerQuery, regionResolver, CANONICAL_ACCOUNT,
                null, null, null, 0L, System::currentTimeMillis);
    }

    CloudWatchLogsService(StorageBackend<String, LogGroup> groupStore,
                           StorageBackend<String, LogStream> streamStore,
                           StorageBackend<String, LogEvent> eventStore,
                           StorageBackend<String, SubscriptionFilter> subscriptionFilterStore,
                           int maxEventsPerQuery,
                           RegionResolver regionResolver,
                           long queryCompletionDelayMs,
                           LongSupplier clock) {
        this(groupStore, streamStore, eventStore, subscriptionFilterStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                maxEventsPerQuery, regionResolver, CANONICAL_ACCOUNT,
                null, null, null, queryCompletionDelayMs, clock);
    }

    CloudWatchLogsService(StorageBackend<String, LogGroup> groupStore,
                           StorageBackend<String, LogStream> streamStore,
                           StorageBackend<String, LogEvent> eventStore,
                           StorageBackend<String, SubscriptionFilter> subscriptionFilterStore,
                           StorageBackend<String, Destination> destinationStore,
                           StorageBackend<String, MetricFilter> metricFilterStore,
                           int maxEventsPerQuery,
                           RegionResolver regionResolver,
                           String canonicalAccountId,
                           Instance<LambdaService> lambdaServices,
                           Instance<IamService> iamServices,
                           Instance<KinesisService> kinesisServices,
                           long queryCompletionDelayMs,
                           LongSupplier clock) {
        this.groupStore = groupStore;
        this.streamStore = streamStore;
        this.eventStore = eventStore;
        this.subscriptionFilterStore = subscriptionFilterStore;
        this.destinationStore = destinationStore;
        this.metricFilterStore = metricFilterStore;
        this.maxEventsPerQuery = maxEventsPerQuery;
        this.regionResolver = regionResolver;
        this.canonicalAccountId = canonicalAccountId != null ? canonicalAccountId : CANONICAL_ACCOUNT;
        this.lambdaServices = lambdaServices;
        this.iamServices = iamServices;
        this.kinesisServices = kinesisServices;
        long maxSequence = eventStore.scan(k -> true).stream()
                .mapToLong(LogEvent::getSequence)
                .max()
                .orElse(0L);
        this.ingestionSequence = new AtomicLong(maxSequence);
        // A negative delay is meaningless; treat it as instant completion.
        this.queryCompletionDelayMs = Math.max(0, queryCompletionDelayMs);
        this.clock = clock;
    }

    // ──────────────────────────── Resource Policies ─────────────────────

    /** An account-level Logs resource policy (used by Route53 query logging, etc.). */
    public record ResourcePolicy(String policyName, String policyDocument, long lastUpdatedTime) {}

    /**
     * Resource policies keyed by {@code region::policyName}. Kept in memory:
     * they are tiny metadata consulted only by control-plane callers (e.g.
     * Route53 CreateQueryLoggingConfig checks one exists for the log group).
     */
    private final Map<String, ResourcePolicy> resourcePolicies =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ResourcePolicy putResourcePolicy(String policyName, String policyDocument, String region) {
        if (policyName == null || policyName.isBlank()) {
            throw new AwsException("InvalidParameterException", "policyName is required.", 400);
        }
        if (policyDocument == null || policyDocument.isBlank()) {
            throw new AwsException("InvalidParameterException", "policyDocument is required.", 400);
        }
        ResourcePolicy policy = new ResourcePolicy(policyName, policyDocument, clock.getAsLong());
        resourcePolicies.put(region + "::" + policyName, policy);
        return policy;
    }

    public List<ResourcePolicy> describeResourcePolicies(String region) {
        String prefix = region + "::";
        return resourcePolicies.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted((a, b) -> a.policyName().compareTo(b.policyName()))
                .toList();
    }

    public void deleteResourcePolicy(String policyName, String region) {
        if (resourcePolicies.remove(region + "::" + policyName) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Policy with name [" + policyName + "] does not exist", 400);
        }
    }

    // ──────────────────────────── Log Groups ────────────────────────────

    public void createLogGroup(String name, Integer retentionInDays, Map<String, String> tags, String region) {
        createLogGroup(name, retentionInDays, tags, null, null, null, region);
    }

    public void createLogGroup(String name, Integer retentionInDays, Map<String, String> tags,
                               String logGroupClass, Boolean deletionProtectionEnabled,
                               String kmsKeyId, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "logGroupName is required.", 400);
        }
        String key = groupKey(region, name);
        if (groupStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The specified log group already exists: " + name, 400);
        }
        LogGroup group = new LogGroup();
        group.setLogGroupName(name);
        group.setCreatedTime(System.currentTimeMillis());
        group.setRetentionInDays(retentionInDays);
        group.setLogGroupClass(normalizeLogGroupClass(logGroupClass));
        group.setDeletionProtectionEnabled(Boolean.TRUE.equals(deletionProtectionEnabled));
        group.setKmsKeyId(kmsKeyId);
        if (tags != null) {
            group.setTags(new HashMap<>(tags));
        }
        groupStore.put(key, group);
        LOG.infov("Created log group: {0} in region {1}", name, region);
    }

    public void deleteLogGroup(String name, String region) {
        String key = groupKey(region, name);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + name, 400));
        if (Boolean.TRUE.equals(group.getDeletionProtectionEnabled())) {
            throw new AwsException("InvalidParameterException",
                    "Unable to delete log group due to deletion protection", 400);
        }

        // Cascade: delete all streams, events, subscription filters, and metric filters
        String streamPrefix = streamKeyPrefix(region, name);
        List<String> streamKeys = streamStore.keys().stream()
                .filter(k -> k.startsWith(streamPrefix))
                .toList();
        for (String sk : streamKeys) {
            LogStream stream = streamStore.get(sk).orElse(null);
            if (stream != null) {
                deleteEventsForStream(region, name, stream.getLogStreamName());
                streamStore.delete(sk);
            }
        }
        deleteByPrefix(subscriptionFilterStore, subscriptionFilterKeyPrefix(region, name));
        deleteByPrefix(metricFilterStore, metricFilterKeyPrefix(region, name));
        groupStore.delete(key);
        LOG.infov("Deleted log group: {0}", name);
    }

    public boolean logGroupExists(String name, String region) {
        return groupStore.get(groupKey(region, name)).isPresent();
    }

    public List<LogGroup> describeLogGroups(String prefix, String region) {
        String storagePrefix = groupKeyPrefix(region);
        List<LogGroup> result = groupStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (prefix == null || prefix.isBlank()) {
                return true;
            }
            String groupName = k.substring(storagePrefix.length());
            return groupName.startsWith(prefix);
        });
        result.sort(Comparator.comparing(LogGroup::getLogGroupName));
        return result;
    }

    public void putRetentionPolicy(String groupName, int days, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.setRetentionInDays(days);
        groupStore.put(key, group);
    }

    public void putLogGroupDeletionProtection(String groupName, boolean enabled, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.setDeletionProtectionEnabled(enabled);
        groupStore.put(key, group);
    }

    public void deleteRetentionPolicy(String groupName, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.setRetentionInDays(null);
        groupStore.put(key, group);
    }

    public void tagLogGroup(String groupName, Map<String, String> tags, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        group.getTags().putAll(tags);
        groupStore.put(key, group);
    }

    public void untagLogGroup(String groupName, List<String> tagKeys, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        tagKeys.forEach(group.getTags()::remove);
        groupStore.put(key, group);
    }

    public Map<String, String> listTagsLogGroup(String groupName, String region) {
        String key = groupKey(region, groupName);
        LogGroup group = groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
        return group.getTags();
    }

    // ──────────────────────────── Log Streams ────────────────────────────

    public void createLogStream(String groupName, String streamName, String region) {
        String groupKey = groupKey(region, groupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));

        String streamKey = streamKey(region, groupName, streamName);
        if (streamStore.get(streamKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The specified log stream already exists: " + streamName, 400);
        }

        LogStream stream = new LogStream();
        stream.setLogGroupName(groupName);
        stream.setLogStreamName(streamName);
        stream.setCreatedTime(System.currentTimeMillis());
        stream.setUploadSequenceToken(UUID.randomUUID().toString());
        streamStore.put(streamKey, stream);
        LOG.infov("Created log stream: {0}/{1}", groupName, streamName);
    }

    public void deleteLogStream(String groupName, String streamName, String region) {
        String streamKey = streamKey(region, groupName, streamName);
        streamStore.get(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log stream does not exist: " + streamName, 400));

        deleteEventsForStream(region, groupName, streamName);
        streamStore.delete(streamKey);
        LOG.infov("Deleted log stream: {0}/{1}", groupName, streamName);
    }

    public List<LogStream> describeLogStreams(String groupName, String prefix, String region) {
        // Verify group exists
        groupStore.get(groupKey(region, groupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));

        String storagePrefix = streamKeyPrefix(region, groupName);
        List<LogStream> result = streamStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (prefix == null || prefix.isBlank()) {
                return true;
            }
            String streamName = k.substring(storagePrefix.length());
            return streamName.startsWith(prefix);
        });
        result.sort(Comparator.comparing(LogStream::getLogStreamName));
        return result;
    }

    // ──────────────────────────── Log Events ────────────────────────────

    public record PutLogEventsResult(String nextSequenceToken, Integer tooNewLogEventStartIndex) {}

    public PutLogEventsResult putLogEvents(String groupName, String streamName,
                               List<Map<String, Object>> events, String region) {
        requireLogGroup(groupName, region);
        String streamKey = streamKey(region, groupName, streamName);
        LogStream stream = streamStore.get(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log stream does not exist: " + streamName, 400));

        long now = System.currentTimeMillis();
        long tooNewAfter = now + TOO_NEW_HORIZON_MS;
        long totalBytes = 0;
        Long minTs = null;
        Long maxTs = null;
        Integer tooNewStart = null;
        List<LogEvent> ingested = new ArrayList<>();

        List<Map<String, Object>> batch = events != null ? events : List.of();
        for (int i = 0; i < batch.size(); i++) {
            Map<String, Object> evt = batch.get(i);
            long ts = toLong(evt.get("timestamp"), now);
            if (ts > tooNewAfter) {
                if (tooNewStart == null) {
                    tooNewStart = i;
                }
                continue;
            }
            String msg = (String) evt.getOrDefault("message", "");

            LogEvent logEvent = new LogEvent();
            logEvent.setEventId(UUID.randomUUID().toString());
            logEvent.setLogGroupName(groupName);
            logEvent.setLogStreamName(streamName);
            logEvent.setTimestamp(ts);
            logEvent.setMessage(msg);
            logEvent.setIngestionTime(now);
            logEvent.setSequence(ingestionSequence.incrementAndGet());

            String eventKey = eventKey(region, groupName, streamName, ts, logEvent.getEventId());
            eventStore.put(eventKey, logEvent);
            ingested.add(logEvent);

            totalBytes += msg.getBytes().length + 26; // approx overhead
            if (minTs == null || ts < minTs) { minTs = ts; }
            if (maxTs == null || ts > maxTs) { maxTs = ts; }
        }

        // Update stream metadata
        if (minTs != null) {
            if (stream.getFirstEventTimestamp() == null || minTs < stream.getFirstEventTimestamp()) {
                stream.setFirstEventTimestamp(minTs);
            }
        }
        if (maxTs != null) {
            stream.setLastEventTimestamp(maxTs);
        }
        stream.setLastIngestionTime(now);
        stream.setStoredBytes(stream.getStoredBytes() + totalBytes);
        String nextToken = UUID.randomUUID().toString();
        stream.setUploadSequenceToken(nextToken);
        streamStore.put(streamKey, stream);

        if (!ingested.isEmpty()) {
            deliverSubscriptionMatches(groupName, streamName, ingested, region);
        }

        return new PutLogEventsResult(nextToken, tooNewStart);
    }

    public record LogEventsResult(List<LogEvent> events, String nextForwardToken, String nextBackwardToken) {}

    public LogEventsResult getLogEvents(String groupName, String streamName,
                                        Long startTime, Long endTime,
                                        int limit, boolean startFromHead, String nextToken, String region) {
        requireLogGroup(groupName, region);
        int maxEvents = Math.min(limit > 0 ? limit : Integer.MAX_VALUE,
                maxEventsPerQuery);

        String eventPrefix = eventKeyPrefix(region, groupName, streamName);
        List<LogEvent> all = eventStore.scan(k -> k.startsWith(eventPrefix));
        all.sort(EVENT_ORDER);

        List<LogEvent> filtered = all.stream()
                .filter(e -> (startTime == null || e.getTimestamp() >= startTime)
                        && (endTime == null || e.getTimestamp() <= endTime))
                .toList();

        int total = filtered.size();
        int pageStart;
        int pageEnd;

        if (nextToken != null && nextToken.startsWith("f/")) {
            int offset = parseTokenIndex(nextToken, 2);
            pageStart = Math.min(offset, total);
            pageEnd = Math.min(pageStart + maxEvents, total);
        } else if (nextToken != null && nextToken.startsWith("b/")) {
            int end = parseTokenIndex(nextToken, 2);
            pageEnd = Math.min(end, total);
            pageStart = Math.max(pageEnd - maxEvents, 0);
        } else if (!startFromHead) {
            pageEnd = total;
            pageStart = Math.max(total - maxEvents, 0);
        } else {
            pageStart = 0;
            pageEnd = Math.min(maxEvents, total);
        }

        List<LogEvent> page = filtered.subList(pageStart, pageEnd);
        return new LogEventsResult(page, "f/" + pageEnd, "b/" + pageStart);
    }

    private int parseTokenIndex(String token, int prefixLen) {
        try {
            return Integer.parseInt(token.substring(prefixLen));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record FilteredLogEventsResult(List<LogEvent> events, String nextToken) {}

    public FilteredLogEventsResult filterLogEvents(String groupName, List<String> streamNames,
                                                    Long startTime, Long endTime,
                                                    String filterPattern, int limit,
                                                    String region) {
        requireLogGroup(groupName, region);
        int maxEvents = Math.min(limit > 0 ? limit : Integer.MAX_VALUE,
                maxEventsPerQuery);

        String groupPrefix = groupKeyPrefix(region) + groupName + "::";
        List<LogEvent> all = new ArrayList<>();

        if (streamNames != null && !streamNames.isEmpty()) {
            for (String sn : streamNames) {
                String eventPrefix = eventKeyPrefix(region, groupName, sn);
                all.addAll(eventStore.scan(k -> k.startsWith(eventPrefix)));
            }
        } else {
            // All streams in group
            all.addAll(eventStore.scan(k -> k.startsWith(groupPrefix)));
        }

        all.sort(EVENT_ORDER);

        List<LogEvent> result = all.stream()
                .filter(e -> (startTime == null || e.getTimestamp() >= startTime)
                        && (endTime == null || e.getTimestamp() <= endTime))
                .filter(e -> matchesFilterPattern(e.getMessage(), filterPattern))
                .limit(maxEvents)
                .toList();

        String nextToken = result.size() >= maxEvents ? UUID.randomUUID().toString() : null;
        return new FilteredLogEventsResult(result, nextToken);
    }

    // ──────────────────────────── Logs Insights Queries ────────────────────────────

    /** A query's status and (once Complete) its projected rows — the AWS GetQueryResults shape. */
    public record QueryState(String status, List<LinkedHashMap<String, String>> rows,
                             long recordsScanned, long recordsMatched) {}

    /** Lifecycle state of a stored Insights query; {@link #label()} is the AWS wire form. */
    private enum InsightsQueryStatus {
        RUNNING("Running"),
        COMPLETE("Complete"),
        CANCELLED("Cancelled");

        private final String label;

        InsightsQueryStatus(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * A stored Insights query. Results are computed eagerly at StartQuery, but the query reports
     * {@code Running} until {@code completeAtMs} (an artificial delay emulating AWS's asynchronous
     * execution), then {@code Complete} — unless cancelled by StopQuery, after which it is
     * {@code Cancelled}. State transitions are time-driven and computed on read. {@code recordsMatched}
     * is captured at construction so it survives the Running/Cancelled row masking and the row-drop on cancel.
     */
    private static final class QueryRecord {
        private List<LinkedHashMap<String, String>> rows;
        private final long recordsScanned;
        private final long recordsMatched;
        private final long completeAtMs;
        private boolean cancelled;

        QueryRecord(List<LinkedHashMap<String, String>> rows, long recordsScanned, long completeAtMs) {
            this.rows = rows;
            this.recordsScanned = recordsScanned;
            this.recordsMatched = rows.size();
            this.completeAtMs = completeAtMs;
        }

        private InsightsQueryStatus status(long nowMs) {
            if (cancelled) {
                return InsightsQueryStatus.CANCELLED;
            }
            return nowMs >= completeAtMs ? InsightsQueryStatus.COMPLETE : InsightsQueryStatus.RUNNING;
        }

        /**
         * Snapshot this query in the AWS GetQueryResults shape. Rows are exposed only once
         * {@code Complete}, and always as a defensive copy (the cached list is never handed out); while
         * Running or Cancelled the rows are masked empty, but {@code recordsMatched} still reports the
         * full match count.
         */
        synchronized QueryState snapshot(long nowMs) {
            InsightsQueryStatus status = status(nowMs);
            List<LinkedHashMap<String, String>> visible =
                    status == InsightsQueryStatus.COMPLETE ? List.copyOf(rows) : List.of();
            return new QueryState(status.label(), visible, recordsScanned, recordsMatched);
        }

        /** Cancels the query iff still running, dropping its now-unreachable rows. Returns true if this call stopped it. */
        synchronized boolean stopIfRunning(long nowMs) {
            if (!cancelled && nowMs < completeAtMs) {
                cancelled = true;
                rows = List.of();
                return true;
            }
            return false;
        }
    }

    /**
     * Start a CloudWatch Logs Insights query and cache it under a new queryId. Results are computed
     * eagerly (the scan is in-memory); the query then reports {@code Running} until the configured
     * completion delay elapses (default 0 = immediate), emulating AWS's async execution.
     * {@code startTimeSeconds}/{@code endTimeSeconds} are epoch <em>seconds</em> (the StartQuery
     * contract); {@link LogEvent} timestamps are epoch millis, so they are scaled for comparison.
     */
    public String startQuery(List<String> logGroupNames, long startTimeSeconds, long endTimeSeconds,
                             String queryString, Integer limit, String region) {
        long startMs = startTimeSeconds * 1000L;
        long endMs = endTimeSeconds * 1000L;

        // De-duplicate the requested groups (the same group can arrive via multiple selectors, e.g.
        // logGroupNames + logGroupIdentifiers) so it is scanned — and counted — once, not twice.
        List<String> distinctGroups = logGroupNames.stream()
                .filter(g -> g != null && !g.isBlank())
                .distinct()
                .toList();
        if (distinctGroups.isEmpty()) {
            // AWS StartQuery requires a log-group selector; an empty or all-blank one is an invalid
            // request, not a valid query that happens to match nothing.
            throw new AwsException("InvalidParameterException",
                    "StartQuery must specify at least one log group.", 400);
        }

        List<LogEvent> gathered = new ArrayList<>();
        for (String groupName : distinctGroups) {
            // Real AWS StartQuery returns ResourceNotFoundException for a log group that does not exist,
            // rather than a successful empty query; mirror that instead of silently scanning nothing.
            groupStore.get(groupKey(region, groupName)).orElseThrow(() ->
                    new AwsException("ResourceNotFoundException",
                            "The specified log group does not exist: " + groupName, 400));
            String prefix = region + "::" + groupName + "::";
            for (LogEvent e : eventStore.scan(k -> k.startsWith(prefix))) {
                if (e.getTimestamp() >= startMs && e.getTimestamp() <= endMs) {
                    gathered.add(e);
                }
            }
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, maxEventsPerQuery) : maxEventsPerQuery;
        List<LinkedHashMap<String, String>> rows =
                LogsInsightsQuery.parse(queryString).evaluate(gathered, effectiveLimit);

        String queryId = UUID.randomUUID().toString();
        long completeAtMs = clock.getAsLong() + queryCompletionDelayMs;
        insightsQueries.put(queryId, new QueryRecord(rows, gathered.size(), completeAtMs));
        LOG.infov("Logs Insights query {0}: scanned {1} event(s) across {2} group(s) -> {3} row(s)",
                queryId, gathered.size(), distinctGroups.size(), rows.size());
        return queryId;
    }

    /**
     * Return a query's status and, once {@code Complete}, its rows. Mirrors AWS: while {@code Running}
     * or after a StopQuery ({@code Cancelled}) the result set is empty (though {@code recordsMatched}
     * still reports the full match count); only a Complete query exposes rows. An unknown queryId is an
     * error on real AWS — and a query that has fallen out of the bounded LRU cache 404s the same way.
     */
    public QueryState getQueryResults(String queryId) {
        QueryRecord rec = insightsQueries.get(queryId);
        if (rec == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified query does not exist.", 400);
        }
        return rec.snapshot(clock.getAsLong());
    }

    /**
     * Stop an in-progress query. Mirrors AWS StopQuery: a running query is cancelled and returns
     * {@code success=true}; an already-ended query throws {@code InvalidParameterException} ("not
     * running"); an unknown queryId throws {@code ResourceNotFoundException}.
     */
    public boolean stopQuery(String queryId) {
        QueryRecord rec = insightsQueries.get(queryId);
        if (rec == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified query does not exist.", 400);
        }
        if (rec.stopIfRunning(clock.getAsLong())) {
            return true;
        }
        throw new AwsException("InvalidParameterException",
                "The query you are trying to stop is not running.", 400);
    }

    // ──────────────────────────── Subscription Filters ────────────────────────────

    public void putSubscriptionFilter(String logGroupName, String filterName, String filterPattern,
                                       String destinationArn, String distribution, String region) {
        putSubscriptionFilter(logGroupName, filterName, filterPattern, destinationArn, null, distribution, region);
    }

    public void putSubscriptionFilter(String logGroupName, String filterName, String filterPattern,
                                       String destinationArn, String roleArn, String distribution, String region) {
        requireLogGroup(logGroupName, region);
        if (destinationArn == null || destinationArn.isBlank()) {
            throw new AwsException("InvalidParameterException", "destinationArn is required.", 400);
        }
        validateSubscriptionDestination(destinationArn, roleArn, region);

        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setFilterName(filterName);
        filter.setLogGroupName(logGroupName);
        filter.setFilterPattern(filterPattern != null ? filterPattern : "");
        filter.setDestinationArn(destinationArn);
        filter.setRoleArn(roleArn);
        filter.setDistribution(distribution != null ? distribution : "ByLogStream");
        filter.setCreationTime(System.currentTimeMillis());

        String filterKey = subscriptionFilterKey(region, logGroupName, filterName);
        subscriptionFilterStore.put(filterKey, filter);
        LOG.infov("Created subscription filter: {0} on log group: {1}", filterName, logGroupName);
    }

    public record DescribeSubscriptionFiltersResult(List<SubscriptionFilter> subscriptionFilters, String nextToken) {}

    public DescribeSubscriptionFiltersResult describeSubscriptionFilters(String logGroupName, String filterNamePrefix,
                                                                          String nextToken, int limit, String region) {
        String groupKey = groupKey(region, logGroupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + logGroupName, 400));

        String prefix = subscriptionFilterKeyPrefix(region, logGroupName);
        List<SubscriptionFilter> all = subscriptionFilterStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (filterNamePrefix == null || filterNamePrefix.isBlank()) return true;
            String name = k.substring(prefix.length());
            return name.startsWith(filterNamePrefix);
        });
        all.sort(Comparator.comparing(SubscriptionFilter::getFilterName));

        int maxResults = Math.min(limit > 0 ? limit : 50, 50);
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                offset = 0;
            }
        }

        int end = Math.min(offset + maxResults, all.size());
        List<SubscriptionFilter> page = all.subList(offset, end);
        String token = end < all.size() ? String.valueOf(end) : null;
        return new DescribeSubscriptionFiltersResult(page, token);
    }

    public void deleteSubscriptionFilter(String logGroupName, String filterName, String region) {
        String groupKey = groupKey(region, logGroupName);
        groupStore.get(groupKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + logGroupName, 400));

        String filterKey = subscriptionFilterKey(region, logGroupName, filterName);
        subscriptionFilterStore.get(filterKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified subscription filter does not exist: " + filterName, 400));
        subscriptionFilterStore.delete(filterKey);
        LOG.infov("Deleted subscription filter: {0} on log group: {1}", filterName, logGroupName);
    }

    // ──────────────────────────── Destinations ────────────────────────────

    public Destination putDestination(String destinationName, String targetArn, String roleArn, String region) {
        if (destinationName == null || destinationName.isBlank()) {
            throw new AwsException("InvalidParameterException", "destinationName is required.", 400);
        }
        if (targetArn == null || targetArn.isBlank()) {
            throw new AwsException("InvalidParameterException", "targetArn is required.", 400);
        }
        requireSameAccountRole(roleArn);

        String key = destinationKey(region, destinationName);
        Destination existing = destinationStore.get(key).orElse(null);
        Destination dest = existing != null ? existing : new Destination();
        dest.setDestinationName(destinationName);
        dest.setTargetArn(targetArn);
        dest.setRoleArn(roleArn);
        dest.setArn(regionResolver.buildArn("logs", region, "destination:" + destinationName));
        if (existing == null) {
            dest.setCreationTime(System.currentTimeMillis());
        }
        destinationStore.put(key, dest);
        LOG.infov("Put destination: {0}", destinationName);
        return dest;
    }

    public void putDestinationPolicy(String destinationName, String accessPolicy, String region) {
        String key = destinationKey(region, destinationName);
        Destination dest = destinationStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified destination does not exist: " + destinationName, 400));
        dest.setAccessPolicy(accessPolicy);
        destinationStore.put(key, dest);
    }

    public List<Destination> describeDestinations(String prefix, String region) {
        String storagePrefix = region + "::destination::";
        List<Destination> result = destinationStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (prefix == null || prefix.isBlank()) {
                return true;
            }
            return k.substring(storagePrefix.length()).startsWith(prefix);
        });
        result.sort(Comparator.comparing(Destination::getDestinationName));
        return result;
    }

    public void deleteDestination(String destinationName, String region) {
        String key = destinationKey(region, destinationName);
        if (destinationStore.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified destination does not exist: " + destinationName, 400);
        }
        destinationStore.delete(key);
    }

    // ──────────────────────────── Metric Filters ────────────────────────────

    public void putMetricFilter(String logGroupName, String filterName, String filterPattern,
                                List<Map<String, Object>> transformations, String region) {
        requireLogGroup(logGroupName, region);
        if (filterName == null || filterName.isBlank()) {
            throw new AwsException("InvalidParameterException", "filterName is required.", 400);
        }
        MetricFilter filter = new MetricFilter();
        filter.setFilterName(filterName);
        filter.setLogGroupName(logGroupName);
        filter.setFilterPattern(filterPattern != null ? filterPattern : "");
        filter.setMetricTransformations(transformations != null ? transformations : List.of());
        filter.setCreationTime(System.currentTimeMillis());
        metricFilterStore.put(metricFilterKey(region, logGroupName, filterName), filter);
        LOG.infov("Put metric filter: {0} on log group: {1}", filterName, logGroupName);
    }

    public record DescribeMetricFiltersResult(List<MetricFilter> metricFilters, String nextToken) {}

    public DescribeMetricFiltersResult describeMetricFilters(String logGroupName, String filterNamePrefix,
                                                              String nextToken, int limit, String region) {
        if (logGroupName != null && !logGroupName.isBlank()) {
            requireLogGroup(logGroupName, region);
        }
        List<MetricFilter> all = metricFilterStore.scan(k -> {
            if (!k.startsWith(region + "::")) {
                return false;
            }
            if (logGroupName != null && !logGroupName.isBlank()
                    && !k.startsWith(metricFilterKeyPrefix(region, logGroupName))) {
                return false;
            }
            if (filterNamePrefix == null || filterNamePrefix.isBlank()) {
                return true;
            }
            int marker = k.lastIndexOf("::filter::");
            String name = marker >= 0 ? k.substring(marker + "::filter::".length()) : k;
            return name.startsWith(filterNamePrefix);
        });
        all.sort(Comparator.comparing(MetricFilter::getFilterName));
        int maxResults = Math.min(limit > 0 ? limit : 50, 50);
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                offset = 0;
            }
        }
        int end = Math.min(offset + maxResults, all.size());
        List<MetricFilter> page = all.subList(offset, end);
        String token = end < all.size() ? String.valueOf(end) : null;
        return new DescribeMetricFiltersResult(page, token);
    }

    public void deleteMetricFilter(String logGroupName, String filterName, String region) {
        requireLogGroup(logGroupName, region);
        String key = metricFilterKey(region, logGroupName, filterName);
        if (metricFilterStore.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified metric filter does not exist: " + filterName, 400);
        }
        metricFilterStore.delete(key);
    }

    // ──────────────────────────── Insights record / fields ────────────────────────────

    public Map<String, String> getLogRecord(String logRecordPointer) {
        if (logRecordPointer == null || logRecordPointer.isBlank()) {
            throw new AwsException("InvalidParameterException", "logRecordPointer is required.", 400);
        }
        for (LogEvent event : eventStore.scan(k -> true)) {
            if (logRecordPointer.equals(event.getEventId())) {
                return toLogRecord(event);
            }
        }
        throw new AwsException("InvalidParameterException",
                "The specified log record pointer is invalid.", 400);
    }

    public record LogGroupField(String name, double percent) {}

    public List<LogGroupField> getLogGroupFields(String groupName, String region) {
        requireLogGroup(groupName, region);
        String prefix = region + "::" + groupName + "::";
        List<LogEvent> events = eventStore.scan(k -> k.startsWith(prefix));
        Set<String> names = new LinkedHashSet<>();
        names.add("@timestamp");
        names.add("@message");
        names.add("@logStream");
        names.add("@logGroup");
        for (LogEvent event : events) {
            if (event.getLogStreamName() != null) {
                names.add("@logStream");
            }
            try {
                var node = PAYLOAD_MAPPER.readTree(event.getMessage() != null ? event.getMessage() : "");
                if (node.isObject()) {
                    node.fieldNames().forEachRemaining(names::add);
                }
            } catch (Exception ignored) {
                // plain-text events have no discovered JSON fields
            }
        }
        List<LogGroupField> fields = new ArrayList<>();
        for (String name : names) {
            fields.add(new LogGroupField(name, 100.0));
        }
        return fields;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void requireLogGroup(String groupName, String region) {
        groupStore.get(groupKey(region, groupName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified log group does not exist: " + groupName, 400));
    }

    private static String normalizeLogGroupClass(String logGroupClass) {
        if (logGroupClass == null || logGroupClass.isBlank()) {
            return DEFAULT_LOG_GROUP_CLASS;
        }
        return logGroupClass;
    }

    private void deleteByPrefix(StorageBackend<String, ?> store, String prefix) {
        List<String> keys = store.keys().stream().filter(k -> k.startsWith(prefix)).toList();
        keys.forEach(store::delete);
    }

    /**
     * Same-account pass-role. The emulator aliases the request-scoped account
     * (from SigV4 / assumed-role sessions) with the configured canonical
     * account {@code 000000000000}; comparing only one of those two IDs
     * mis-detects in-account role passes as cross-account.
     */
    private void requireSameAccountRole(String roleArn) {
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidParameterException", "roleArn is required.", 400);
        }
        if (isEmulatorAccount(accountFromArn(roleArn)) || roleExistsLocally(roleArn)) {
            return;
        }
        throw new AwsException("InvalidParameterException",
                "Cross-account role passing is not allowed.", 400);
    }

    private void validateSubscriptionDestination(String destinationArn, String roleArn, String region) {
        if (destinationArn.contains(":destination:")) {
            String name = resourceNameAfter(destinationArn, ":destination:");
            if (destinationStore.get(destinationKey(region, name)).isEmpty()) {
                throw new AwsException("InvalidParameterException",
                        "Could not deliver test message to specified destination. Check if the destination exists.",
                        400);
            }
            return;
        }
        if (destinationArn.contains(":function:")) {
            if (!isEmulatorAccount(accountFromArn(destinationArn)) && !lambdaExistsLocally(destinationArn, region)) {
                throw new AwsException("AccessDeniedException",
                        "Cross-account lambda invocation passing is not allowed. You must use DestinationPolicies to enable cross-account subscriptions.",
                        400);
            }
            if (lambdaExistsLocally(destinationArn, region) || isEmulatorAccount(accountFromArn(destinationArn))) {
                return;
            }
        }
        if (roleArn != null && !roleArn.isBlank()) {
            requireSameAccountRole(roleArn);
        }
    }

    private boolean isEmulatorAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return true;
        }
        String caller = regionResolver.getAccountId();
        // The request-scoped identity and Floci's configured account are the
        // same tenant. Comparing against only one of them is what produced
        // false "cross-account role passing" rejections.
        return accountId.equals(caller) || accountId.equals(canonicalAccountId);
    }

    private boolean roleExistsLocally(String roleArn) {
        if (iamServices == null || iamServices.isUnsatisfied()) {
            return false;
        }
        String name = resourceNameAfter(roleArn, ":role/");
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        IamService iam = iamServices.get();
        String arnAccount = accountFromArn(roleArn);
        return iam.findRole(arnAccount, name).isPresent()
                || iam.findRole(canonicalAccountId, name).isPresent()
                || iam.findRole(regionResolver.getAccountId(), name).isPresent();
    }

    private boolean lambdaExistsLocally(String functionArn, String region) {
        if (lambdaServices == null || lambdaServices.isUnsatisfied()) {
            return false;
        }
        try {
            lambdaServices.get().getFunction(region, functionArn);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private static String accountFromArn(String arn) {
        return AwsArnUtils.accountOrDefault(arn, "");
    }

    private static String resourceNameAfter(String arn, String marker) {
        int idx = arn.indexOf(marker);
        return idx >= 0 ? arn.substring(idx + marker.length()) : arn;
    }

    static boolean matchesFilterPattern(String message, String filterPattern) {
        if (filterPattern == null || filterPattern.isBlank()) {
            return true;
        }
        String haystack = message != null ? message : "";
        String pattern = filterPattern.trim();
        if (pattern.length() >= 2 && pattern.startsWith("\"") && pattern.endsWith("\"")) {
            return haystack.contains(pattern.substring(1, pattern.length() - 1));
        }
        if (pattern.startsWith("?")) {
            for (String token : pattern.split("\\s+")) {
                String term = token.startsWith("?") ? token.substring(1) : token;
                if (!term.isBlank() && haystack.contains(term)) {
                    return true;
                }
            }
            return false;
        }
        return haystack.contains(pattern);
    }

    private Map<String, String> toLogRecord(LogEvent event) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("@ptr", event.getEventId());
        record.put("@timestamp", String.valueOf(event.getTimestamp()));
        record.put("@message", event.getMessage() != null ? event.getMessage() : "");
        if (event.getLogGroupName() != null) {
            record.put("@logGroup", event.getLogGroupName());
        }
        if (event.getLogStreamName() != null) {
            record.put("@logStream", event.getLogStreamName());
        }
        return record;
    }

    private void deliverSubscriptionMatches(String groupName, String streamName,
                                            List<LogEvent> ingested, String region) {
        String prefix = subscriptionFilterKeyPrefix(region, groupName);
        List<SubscriptionFilter> filters = subscriptionFilterStore.scan(k -> k.startsWith(prefix));
        if (filters.isEmpty()) {
            return;
        }
        for (SubscriptionFilter filter : filters) {
            List<LogEvent> matched = ingested.stream()
                    .filter(e -> matchesFilterPattern(e.getMessage(), filter.getFilterPattern()))
                    .toList();
            if (matched.isEmpty()) {
                continue;
            }
            try {
                deliverToDestination(filter, groupName, streamName, matched, region);
            } catch (Exception e) {
                LOG.warnv("Failed to deliver subscription filter {0}: {1}",
                        filter.getFilterName(), e.getMessage());
            }
        }
    }

    private void deliverToDestination(SubscriptionFilter filter, String groupName, String streamName,
                                      List<LogEvent> matched, String region) throws Exception {
        String destinationArn = filter.getDestinationArn();
        if (destinationArn != null && destinationArn.contains(":destination:")) {
            String name = resourceNameAfter(destinationArn, ":destination:");
            Destination dest = destinationStore.get(destinationKey(region, name)).orElse(null);
            if (dest == null) {
                return;
            }
            destinationArn = dest.getTargetArn();
        }
        if (destinationArn != null && destinationArn.contains(":function:")) {
            invokeLambdaSubscription(destinationArn, filter, groupName, streamName, matched, region);
            return;
        }
        if (destinationArn != null && destinationArn.contains(":stream/") && kinesisServices != null
                && !kinesisServices.isUnsatisfied()) {
            String stream = resourceNameAfter(destinationArn, ":stream/");
            byte[] payload = gzipJson(subscriptionPayload(filter, groupName, streamName, matched, region));
            kinesisServices.get().putRecord(stream, payload, streamName, region);
        }
    }

    private void invokeLambdaSubscription(String functionArn, SubscriptionFilter filter,
                                          String groupName, String streamName,
                                          List<LogEvent> matched, String region) throws Exception {
        if (lambdaServices == null || lambdaServices.isUnsatisfied()) {
            return;
        }
        Map<String, Object> envelope = Map.of("awslogs", Map.of("data",
                Base64.getEncoder().encodeToString(
                        gzipJson(subscriptionPayload(filter, groupName, streamName, matched, region)))));
        byte[] body = PAYLOAD_MAPPER.writeValueAsBytes(envelope);
        lambdaServices.get().invoke(region, functionArn, body, InvocationType.Event);
    }

    private Map<String, Object> subscriptionPayload(SubscriptionFilter filter, String groupName,
                                                    String streamName, List<LogEvent> matched, String region) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (LogEvent event : matched) {
            events.add(Map.of(
                    "id", event.getEventId(),
                    "timestamp", event.getTimestamp(),
                    "message", event.getMessage() != null ? event.getMessage() : ""));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", "DATA_MESSAGE");
        payload.put("owner", regionResolver.getAccountId());
        payload.put("logGroup", groupName);
        payload.put("logStream", streamName);
        payload.put("subscriptionFilters", List.of(filter.getFilterName()));
        payload.put("logEvents", events);
        return payload;
    }

    private static byte[] gzipJson(Map<String, Object> payload) throws Exception {
        byte[] json = PAYLOAD_MAPPER.writeValueAsBytes(payload);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(json);
        }
        return bos.toByteArray();
    }

    private void deleteEventsForStream(String region, String groupName, String streamName) {
        String eventPrefix = eventKeyPrefix(region, groupName, streamName);
        List<String> keys = eventStore.keys().stream()
                .filter(k -> k.startsWith(eventPrefix))
                .toList();
        keys.forEach(eventStore::delete);
    }

    public String buildArn(String groupName, String region) {
        return regionResolver.buildArn("logs", region, "log-group:" + groupName);
    }

    private static String groupKeyPrefix(String region) {
        return region + "::";
    }

    private static String groupKey(String region, String groupName) {
        return region + "::" + groupName;
    }

    private static String streamKeyPrefix(String region, String groupName) {
        return region + "::" + groupName + "::";
    }

    private static String streamKey(String region, String groupName, String streamName) {
        return region + "::" + groupName + "::" + streamName;
    }

    private static String eventKeyPrefix(String region, String groupName, String streamName) {
        return region + "::" + groupName + "::" + streamName + "::";
    }

    private static String eventKey(String region, String groupName, String streamName,
                                    long timestamp, String uuid) {
        return region + "::" + groupName + "::" + streamName + "::"
                + String.format("%015d", timestamp) + "::" + uuid;
    }

    private static String subscriptionFilterKeyPrefix(String region, String logGroupName) {
        return region + "::" + logGroupName + "::filter::";
    }

    private static String subscriptionFilterKey(String region, String logGroupName, String filterName) {
        return region + "::" + logGroupName + "::filter::" + filterName;
    }

    private static String destinationKey(String region, String destinationName) {
        return region + "::destination::" + destinationName;
    }

    private static String metricFilterKeyPrefix(String region, String logGroupName) {
        return region + "::" + logGroupName + "::filter::";
    }

    private static String metricFilterKey(String region, String logGroupName, String filterName) {
        return region + "::" + logGroupName + "::filter::" + filterName;
    }

    private static long toLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
