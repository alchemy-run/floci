package io.floci.aws.services.xray;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS X-Ray. Segment documents (sent with {@code PutTraceSegments}, through the daemon, or recorded for traced Lambda
 * invocations) are kept as traces for 30 days and answer trace summaries filtered with X-Ray's filter expressions,
 * batch trace reads, service and trace graphs, and time series statistics. Sampling rules decide what is traced and
 * hand clients their reservoir quotas. Groups scope graphs and insights; insights open when a service's faults rise
 * above its baseline and are announced to EventBridge. With Transaction Search ({@code CloudWatchLogs} destination)
 * segments are also written as spans to the {@code aws/spans} log group, and trace retrieval reads them back.
 */
@ApplicationScoped
public class XRayService implements Resettable {

    private static final Logger LOG = Logger.getLogger(XRayService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Pattern TRACE_ID = Pattern.compile("1-([0-9a-f]{8})-[0-9a-f]{24}");
    private static final Pattern SEGMENT_ID = Pattern.compile("[0-9a-f]{16}");
    private static final long RETENTION_SECONDS = 30L * 86400;
    private static final int MAX_RULES = 25;
    private static final int MAX_POLICIES = 5;
    private static final int MAX_POLICY_BYTES = 5120;
    private static final int PAGE = 100;

    private final AccountAwareStorageBackend<ObjectNode> traces;
    private final AccountAwareStorageBackend<ObjectNode> state;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Provider<KmsService> kms;
    private final Provider<CloudWatchLogsService> logs;
    final XRaySampling sampling = new XRaySampling();

    @Inject
    public XRayService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                       Provider<KmsService> kms, Provider<CloudWatchLogsService> logs) {
        this.traces = storageFactory.create("xray", "traces.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.state = storageFactory.create("xray", "state.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.kms = kms;
        this.logs = logs;
    }

    @Override
    public void clear() {
        traces.clear();
        state.clear();
        sampling.clear();
    }

    public ObjectNode handle(String op, JsonNode in, String region) {
        return switch (op) {
            case "PutTraceSegments" -> putTraceSegments(in, region);
            case "BatchGetTraces" -> batchGetTraces(in, region);
            case "GetTraceSummaries" -> traceSummaries(in, region);
            case "GetServiceGraph" -> serviceGraph(in, region);
            case "GetTraceGraph" -> traceGraph(in, region);
            case "GetTimeSeriesServiceStatistics" -> timeSeries(in, region);
            case "PutTelemetryRecords" -> putTelemetry(in, region);
            case "CreateGroup" -> createGroup(in, region);
            case "GetGroup" -> wrap("Group", groupView(group(region, in)));
            case "GetGroups" -> getGroups(in, region);
            case "UpdateGroup" -> updateGroup(in, region);
            case "DeleteGroup" -> deleteGroup(in, region);
            case "CreateSamplingRule" -> createRule(in, region);
            case "GetSamplingRules" -> getRules(in, region);
            case "UpdateSamplingRule" -> updateRule(in, region);
            case "DeleteSamplingRule" -> deleteRule(in, region);
            case "GetSamplingTargets" -> samplingTargets(in, region);
            case "GetSamplingStatisticSummaries" -> statisticSummaries(in, region);
            case "GetEncryptionConfig" -> wrap("EncryptionConfig", encryption(region));
            case "PutEncryptionConfig" -> putEncryption(in, region);
            case "PutResourcePolicy" -> putPolicy(in, region);
            case "ListResourcePolicies" -> listPolicies(in, region);
            case "DeleteResourcePolicy" -> deletePolicy(in, region);
            case "GetIndexingRules" -> indexingRules(region);
            case "UpdateIndexingRule" -> updateIndexingRule(in, region);
            case "GetTraceSegmentDestination" -> destination(region);
            case "UpdateTraceSegmentDestination" -> updateDestination(in, region);
            case "StartTraceRetrieval" -> startRetrieval(in, region);
            case "ListRetrievedTraces" -> listRetrieved(in, region);
            case "GetRetrievedTracesGraph" -> retrievedGraph(in, region);
            case "CancelTraceRetrieval" -> cancelRetrieval(in, region);
            case "GetInsightSummaries" -> insightSummaries(in, region);
            case "GetInsight" -> wrap("Insight", insightView(insight(region, in.path("InsightId").asText()), false));
            case "GetInsightEvents" -> insightEvents(in, region);
            case "GetInsightImpactGraph" -> impactGraph(in, region);
            case "TagResource" -> tagResource(in, region);
            case "UntagResource" -> untagResource(in, region);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw invalid("Unsupported operation " + op);
        };
    }

    // ---------- segments ----------

    private ObjectNode putTraceSegments(JsonNode in, String region) {
        ArrayNode unprocessed = mapper.createArrayNode();
        for (JsonNode document : in.path("TraceSegmentDocuments")) {
            Optional<ObjectNode> rejected = ingest(region, document.asText());
            rejected.ifPresent(unprocessed::add);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("UnprocessedTraceSegments", unprocessed);
        return out;
    }

    /** Validates and stores one segment document; returns why it was rejected, if it was. */
    Optional<ObjectNode> ingest(String region, String document) {
        JsonNode json;
        try {
            json = mapper.readTree(document);
        } catch (Exception e) {
            return Optional.of(rejection(null, "InvalidSegment", "The segment document is not valid JSON."));
        }
        if (json == null || !json.isObject()) {
            return Optional.of(rejection(null, "InvalidSegment", "The segment document is not a JSON object."));
        }
        String id = json.path("id").asText(null);
        if (document.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            return Optional.of(rejection(id, "SegmentTooLarge", "A segment document is at most 64 KB."));
        }
        if (id == null || !SEGMENT_ID.matcher(id).matches()) {
            return Optional.of(rejection(id, "InvalidId", "id must be 16 hexadecimal digits."));
        }
        String traceId = json.path("trace_id").asText("");
        Matcher trace = TRACE_ID.matcher(traceId);
        if (!trace.matches()) {
            return Optional.of(rejection(id, "InvalidTraceId", "trace_id must be 1-<8 hex>-<24 hex>."));
        }
        long epoch = Long.parseLong(trace.group(1), 16);
        long now = Instant.now().getEpochSecond();
        if (epoch < now - RETENTION_SECONDS || epoch > now + 300) {
            return Optional.of(rejection(id, "InvalidTraceId", "The trace id's time is outside X-Ray's 30-day "
                    + "retention window."));
        }
        String name = json.path("name").asText("");
        if (name.isEmpty() || name.length() > 200) {
            return Optional.of(rejection(id, "InvalidSegment", "name is required and at most 200 characters."));
        }
        if (!json.path("start_time").isNumber()) {
            return Optional.of(rejection(id, "InvalidSegment", "start_time is required."));
        }
        if (!json.path("end_time").isNumber() && !json.path("in_progress").asBoolean(false)) {
            return Optional.of(rejection(id, "InvalidSegment", "end_time is required unless in_progress is true."));
        }
        if ("subsegment".equals(json.path("type").asText()) && !json.hasNonNull("parent_id")) {
            return Optional.of(rejection(id, "InvalidSegment", "A subsegment sent on its own needs parent_id."));
        }
        store(region, traceId, document);
        return Optional.empty();
    }

    private ObjectNode rejection(String id, String code, String message) {
        ObjectNode out = mapper.createObjectNode();
        if (id != null) {
            out.put("Id", id);
        }
        return out.put("ErrorCode", code).put("Message", message);
    }

    private synchronized void store(String region, String traceId, String document) {
        String key = region + "/" + traceId;
        ObjectNode trace = traces.get(key).orElseGet(() -> mapper.createObjectNode().put("id", traceId)
                .put("revision", 0));
        ArrayNode documents = (ArrayNode) trace.withArray("documents");
        documents.add(document);
        trace.put("revision", trace.path("revision").asInt() + 1).put("updated", now());
        traces.put(key, trace);
        if ("CloudWatchLogs".equals(destination(region).path("Destination").asText())) {
            writeSpan(region, document);
        }
    }

    /** The account's traces in the Region whose time range overlaps the window. */
    List<XRayTraces.Trace> tracesBetween(String region, double start, double end) {
        List<XRayTraces.Trace> out = new ArrayList<>();
        double cutoff = now() - RETENTION_SECONDS;
        for (ObjectNode stored : traces.scan(k -> k.startsWith(region + "/"))) {
            XRayTraces.Trace trace = parse(stored);
            if (trace.segments.isEmpty() || trace.end() < cutoff) {
                continue;
            }
            if (trace.start() <= end && trace.end() >= start) {
                out.add(trace);
            }
        }
        return out;
    }

    private XRayTraces.Trace parse(ObjectNode stored) {
        List<String> documents = new ArrayList<>();
        stored.path("documents").forEach(d -> documents.add(d.asText()));
        return XRayTraces.parse(mapper, stored.path("id").asText(), documents);
    }

    private ObjectNode batchGetTraces(JsonNode in, String region) {
        List<String> ids = strings(in.path("TraceIds"));
        if (ids.size() > 5) {
            throw invalid("BatchGetTraces takes at most 5 trace ids.");
        }
        ArrayNode found = mapper.createArrayNode();
        ArrayNode unprocessed = mapper.createArrayNode();
        for (String id : ids) {
            Optional<ObjectNode> stored = traces.get(region + "/" + id);
            if (stored.isEmpty()) {
                unprocessed.add(id);
                continue;
            }
            XRayTraces.Trace trace = parse(stored.get());
            ObjectNode out = found.addObject().put("Id", id).put("Duration", XRayTraces.round(trace.duration()))
                    .put("LimitExceeded", false);
            ArrayNode segments = out.putArray("Segments");
            for (ObjectNode document : mergedDocuments(stored.get())) {
                segments.addObject().put("Id", document.path("id").asText()).put("Document", document.toString());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Traces", found);
        out.set("UnprocessedTraceIds", unprocessed);
        return out;
    }

    /** The trace's segment documents, latest version of each, with separately sent subsegments merged in. */
    private List<ObjectNode> mergedDocuments(ObjectNode stored) {
        Map<String, ObjectNode> segments = new LinkedHashMap<>();
        List<ObjectNode> detached = new ArrayList<>();
        for (JsonNode raw : stored.path("documents")) {
            try {
                ObjectNode document = (ObjectNode) mapper.readTree(raw.asText());
                if ("subsegment".equals(document.path("type").asText())) {
                    detached.add(document);
                } else {
                    segments.put(document.path("id").asText(), document);
                }
            } catch (Exception e) {
                LOG.debugv("Skipping an unreadable segment of trace {0}", stored.path("id").asText());
            }
        }
        for (ObjectNode subsegment : detached) {
            for (ObjectNode segment : segments.values()) {
                if (attach(segment, subsegment)) {
                    break;
                }
            }
        }
        return new ArrayList<>(segments.values());
    }

    private boolean attach(ObjectNode parent, ObjectNode subsegment) {
        if (parent.path("id").asText().equals(subsegment.path("parent_id").asText())) {
            ObjectNode child = subsegment.deepCopy();
            child.remove(List.of("type", "parent_id", "trace_id"));
            ArrayNode children = (ArrayNode) parent.withArray("subsegments");
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).path("id").asText().equals(child.path("id").asText())) {
                    children.set(i, child);
                    return true;
                }
            }
            children.add(child);
            return true;
        }
        for (JsonNode child : parent.path("subsegments")) {
            if (attach((ObjectNode) child, subsegment)) {
                return true;
            }
        }
        return false;
    }

    // ---------- queries ----------

    private ObjectNode traceSummaries(JsonNode in, String region) {
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        checkWindow(start, end, 86400, "24 hours");
        Predicate<XRayTraces.Scope> filter = XRayFilter.compile(in.path("FilterExpression").asText(null),
                groupFilters(region));
        String rangeType = in.path("TimeRangeType").asText("TraceId");
        List<XRayTraces.Trace> matching = new ArrayList<>();
        Map<String, Double> matchedAt = new LinkedHashMap<>();
        for (XRayTraces.Trace trace : tracesBetween(region, start, end)) {
            // TraceId: the time in the trace id; Service: when its last segment ended; Event: when it started.
            double at = "TraceId".equals(rangeType) ? Long.parseLong(trace.id.substring(2, 10), 16)
                    : "Service".equals(rangeType) ? trace.segments.stream().mapToDouble(n -> n.end).max().orElse(0)
                    : trace.start();
            if (at < start || at > end || !filter.test(XRayTraces.Scope.of(trace))) {
                continue;
            }
            matching.add(trace);
            matchedAt.put(trace.id, at);
        }
        matching.sort(Comparator.comparingDouble(XRayTraces.Trace::start).reversed());
        if (in.path("Sampling").asBoolean(false)) {
            double rate = in.path("SamplingStrategy").path("Value").asDouble(0.1);
            matching.removeIf(t -> (t.id.hashCode() & 0x7fffffff) / (double) Integer.MAX_VALUE > rate);
        }
        ArrayNode out = mapper.createArrayNode();
        String next = page(matching, in, PAGE, trace -> out.add(XRayTraces.summary(mapper, trace,
                traces.get(region + "/" + trace.id).map(s -> s.path("revision").asInt()).orElse(1),
                matchedAt.get(trace.id), traces.accountId())));
        ObjectNode result = mapper.createObjectNode();
        result.set("TraceSummaries", out);
        result.put("ApproximateTime", now()).put("TracesProcessedCount", (long) matching.size());
        return withToken(result, next);
    }

    private ObjectNode serviceGraph(JsonNode in, String region) {
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        checkWindow(start, end, 6 * 3600, "6 hours");
        Predicate<XRayTraces.Scope> filter = groupFilterOf(region, in);
        List<XRayTraces.Trace> selected = tracesBetween(region, start, end).stream()
                .filter(t -> filter.test(XRayTraces.Scope.of(t))).toList();
        ObjectNode out = mapper.createObjectNode().put("StartTime", start).put("EndTime", end);
        out.set("Services", XRayTraces.render(mapper, XRayTraces.graph(selected, traces.accountId())));
        out.put("ContainsOldGroupVersions", false);
        return out;
    }

    private ObjectNode traceGraph(JsonNode in, String region) {
        List<XRayTraces.Trace> selected = new ArrayList<>();
        for (String id : strings(in.path("TraceIds"))) {
            traces.get(region + "/" + id).map(this::parse).ifPresent(selected::add);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Services", XRayTraces.render(mapper, XRayTraces.graph(selected, traces.accountId())));
        return out;
    }

    private ObjectNode timeSeries(JsonNode in, String region) {
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        checkWindow(start, end, 86400, "24 hours");
        int period = in.has("Period") ? in.get("Period").asInt() : 60;
        if (period < 60 || period % 60 != 0) {
            throw invalid("Period must be a multiple of 60 seconds.");
        }
        Predicate<XRayTraces.Scope> group = groupFilterOf(region, in);
        String selector = in.path("EntitySelectorExpression").asText(null);
        XRayTraces.ServiceKey selected = selector == null ? null : entity(selector);
        Map<Long, XRayTraces.Stats> buckets = new TreeMap<>();
        Map<Long, XRayTraces.Stats> edges = new TreeMap<>();
        for (XRayTraces.Trace trace : tracesBetween(region, start, end)) {
            if (!group.test(XRayTraces.Scope.of(trace))) {
                continue;
            }
            XRayTraces.Node root = trace.root();
            for (XRayTraces.Node segment : trace.segments) {
                if (selected != null && !selected.matches(segment)) {
                    continue;
                }
                long bucket = (long) (segment.start - (segment.start - start) % period);
                buckets.computeIfAbsent(bucket, b -> new XRayTraces.Stats()).add(segment);
                if (segment == root) {
                    edges.computeIfAbsent(bucket, b -> new XRayTraces.Stats()).add(segment);
                }
            }
        }
        ArrayNode out = mapper.createArrayNode();
        buckets.forEach((bucket, stats) -> {
            ObjectNode point = out.addObject().put("Timestamp", bucket);
            point.set("ServiceSummaryStatistics", stats.render(mapper));
            XRayTraces.Stats edge = edges.get(bucket);
            if (edge != null) {
                point.set("EdgeSummaryStatistics", edge.render(mapper));
            }
            point.set("ResponseTimeHistogram", stats.histogram(mapper));
            if (in.path("ForecastStatistics").asBoolean(false)) {
                point.putObject("ServiceForecastStatistics").put("FaultCountHigh", stats.fault + 1)
                        .put("FaultCountLow", 0);
            }
        });
        ObjectNode result = mapper.createObjectNode();
        result.set("TimeSeriesServiceStatistics", out);
        result.put("ContainsOldGroupVersions", false);
        return result;
    }

    /** {@code service("name")} or {@code service(id(name: ..., type: ...))}: the one entity a selector names. */
    private XRayTraces.ServiceKey entity(String selector) {
        Matcher named = Pattern.compile("service\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(selector.trim());
        if (named.matches()) {
            return new XRayTraces.ServiceKey(named.group(1), null);
        }
        Matcher id = Pattern.compile("service\\(\\s*id\\((.*)\\)\\s*\\)").matcher(selector.trim());
        if (id.matches()) {
            String name = field(id.group(1), "name");
            String type = field(id.group(1), "type");
            return new XRayTraces.ServiceKey(name, type);
        }
        throw invalid("EntitySelectorExpression must select one service: service(\"name\") or service(id(...)).");
    }

    private static String field(String arguments, String name) {
        Matcher m = Pattern.compile(name + "\\s*:\\s*\"([^\"]*)\"").matcher(arguments);
        return m.find() ? m.group(1) : null;
    }

    private static void checkWindow(double start, double end, long max, String label) {
        if (end <= start) {
            throw invalid("EndTime must be after StartTime.");
        }
        if (end - start > max) {
            throw invalid("The time range can't be longer than " + label + ".");
        }
    }

    private ObjectNode putTelemetry(JsonNode in, String region) {
        for (JsonNode record : in.path("TelemetryRecords")) {
            if (!record.path("Timestamp").isNumber()) {
                throw invalid("Each telemetry record needs a Timestamp.");
            }
        }
        ObjectNode telemetry = state(region, "telemetry");
        ArrayNode records = (ArrayNode) telemetry.withArray("records");
        for (JsonNode record : in.path("TelemetryRecords")) {
            ObjectNode copy = ((ObjectNode) record).deepCopy();
            copy.put("Hostname", in.path("Hostname").asText(null)).put("ResourceARN", in.path("ResourceARN")
                    .asText(null)).put("EC2InstanceId", in.path("EC2InstanceId").asText(null));
            records.add(copy);
        }
        while (records.size() > 1000) {
            records.remove(0);
        }
        save(region, "telemetry", telemetry);
        return mapper.createObjectNode();
    }

    // ---------- groups ----------

    private ObjectNode createGroup(JsonNode in, String region) {
        String name = in.path("GroupName").asText();
        if ("Default".equals(name) || state.get(key(region, "group/" + name)).isPresent()) {
            throw invalid("Group " + name + " already exists");
        }
        String filter = in.path("FilterExpression").asText(null);
        XRayFilter.compile(filter, groupFilters(region));
        ObjectNode group = mapper.createObjectNode().put("GroupName", name)
                .put("GroupARN", arn(region, "group/" + name + "/" + randomHex(26)));
        if (filter != null) {
            group.put("FilterExpression", filter);
        }
        group.set("InsightsConfiguration", insights(in.path("InsightsConfiguration"), null));
        group.set("_tags", tags(in.path("Tags")));
        state.put(key(region, "group/" + name), group);
        return wrap("Group", groupView(group));
    }

    private ObjectNode defaultGroup(String region) {
        return state.get(key(region, "group/Default")).orElseGet(() -> {
            ObjectNode group = mapper.createObjectNode().put("GroupName", "Default")
                    .put("GroupARN", arn(region, "group/Default"));
            group.set("InsightsConfiguration", insights(null, null));
            group.putObject("_tags");
            return group;
        });
    }

    /** The group a request names by {@code GroupName} or {@code GroupARN}. */
    ObjectNode group(String region, JsonNode in) {
        String name = in.hasNonNull("GroupName") ? in.get("GroupName").asText() : null;
        String arn = in.hasNonNull("GroupARN") ? in.get("GroupARN").asText() : null;
        if (name == null && arn == null) {
            throw invalid("Specify GroupName or GroupARN.");
        }
        if ("Default".equals(name) || (arn != null && arn.equals(arn(region, "group/Default")))) {
            return defaultGroup(region);
        }
        for (ObjectNode group : groups(region)) {
            if ((name != null && name.equals(group.path("GroupName").asText()))
                    || (arn != null && arn.equals(group.path("GroupARN").asText()))) {
                return group;
            }
        }
        throw invalid("Group not found");
    }

    List<ObjectNode> groups(String region) {
        return state.scan(k -> k.startsWith(region + "/group/")).stream()
                .filter(g -> !"Default".equals(g.path("GroupName").asText())).toList();
    }

    /** Every group whose insights are on, including the Default group. */
    List<ObjectNode> insightGroups(String region) {
        List<ObjectNode> out = new ArrayList<>();
        out.add(defaultGroup(region));
        out.addAll(groups(region));
        out.removeIf(g -> !g.path("InsightsConfiguration").path("InsightsEnabled").asBoolean(false));
        return out;
    }

    private ObjectNode groupView(ObjectNode group) {
        ObjectNode view = group.deepCopy();
        view.remove("_tags");
        return view;
    }

    private ObjectNode getGroups(JsonNode in, String region) {
        List<ObjectNode> all = new ArrayList<>();
        all.add(defaultGroup(region));
        all.addAll(groups(region).stream().sorted(Comparator.comparing(g -> g.path("GroupName").asText())).toList());
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, PAGE, g -> out.add(groupView(g)));
        return withToken(wrap("Groups", out), next);
    }

    private ObjectNode updateGroup(JsonNode in, String region) {
        ObjectNode group = group(region, in);
        if (in.has("FilterExpression")) {
            if ("Default".equals(group.path("GroupName").asText())) {
                throw invalid("The Default group's filter expression can't be changed.");
            }
            String filter = in.path("FilterExpression").asText(null);
            XRayFilter.compile(filter, groupFilters(region));
            if (filter == null || filter.isEmpty()) {
                group.remove("FilterExpression");
            } else {
                group.put("FilterExpression", filter);
            }
        }
        if (in.path("InsightsConfiguration").isObject()) {
            group.set("InsightsConfiguration", insights(in.get("InsightsConfiguration"),
                    group.path("InsightsConfiguration")));
        }
        state.put(key(region, "group/" + group.path("GroupName").asText()), group);
        return wrap("Group", groupView(group));
    }

    private ObjectNode insights(JsonNode in, JsonNode current) {
        boolean enabled = in != null && in.has("InsightsEnabled") ? in.get("InsightsEnabled").asBoolean()
                : current != null && current.path("InsightsEnabled").asBoolean(false);
        boolean notify = in != null && in.has("NotificationsEnabled") ? in.get("NotificationsEnabled").asBoolean()
                : current != null && current.path("NotificationsEnabled").asBoolean(false);
        if (notify && !enabled) {
            throw invalid("Notifications require insights to be enabled.");
        }
        return mapper.createObjectNode().put("InsightsEnabled", enabled).put("NotificationsEnabled", notify);
    }

    private ObjectNode deleteGroup(JsonNode in, String region) {
        ObjectNode group = group(region, in);
        if ("Default".equals(group.path("GroupName").asText())) {
            throw invalid("The Default group can't be deleted.");
        }
        state.delete(key(region, "group/" + group.path("GroupName").asText()));
        return mapper.createObjectNode();
    }

    /** A group's filter, applied to traces for graphs and insights; the Default group selects every trace. */
    Predicate<XRayTraces.Scope> groupFilter(String region, ObjectNode group) {
        return XRayFilter.compile(group.path("FilterExpression").asText(null), groupFilters(region));
    }

    private Predicate<XRayTraces.Scope> groupFilterOf(String region, JsonNode in) {
        if (!in.hasNonNull("GroupName") && !in.hasNonNull("GroupARN")) {
            return scope -> true;
        }
        return groupFilter(region, group(region, in));
    }

    private XRayFilter.Groups groupFilters(String region) {
        return (field, value) -> {
            ObjectNode group = group(region, "group.name".equals(field)
                    ? mapper.createObjectNode().put("GroupName", value)
                    : mapper.createObjectNode().put("GroupARN", value));
            return scope -> groupFilter(region, group).test(scope);
        };
    }

    // ---------- sampling ----------

    private ObjectNode defaultRule(String region) {
        return state.get(key(region, "rule/Default")).orElseGet(() -> {
            ObjectNode record = mapper.createObjectNode();
            ObjectNode rule = record.putObject("SamplingRule").put("RuleName", "Default")
                    .put("RuleARN", arn(region, "sampling-rule/Default")).put("ResourceARN", "*")
                    .put("Priority", 10000).put("FixedRate", 0.05).put("ReservoirSize", 1)
                    .put("ServiceName", "*").put("ServiceType", "*").put("Host", "*").put("HTTPMethod", "*")
                    .put("URLPath", "*").put("Version", 1);
            rule.putObject("Attributes");
            record.put("CreatedAt", 0.0).put("ModifiedAt", 0.0);
            record.putObject("_tags");
            return record;
        });
    }

    List<ObjectNode> rules(String region) {
        List<ObjectNode> out = new ArrayList<>(state.scan(k -> k.startsWith(region + "/rule/")).stream()
                .filter(r -> !"Default".equals(r.path("SamplingRule").path("RuleName").asText())).toList());
        out.add(defaultRule(region));
        return out;
    }

    /** Decides whether to trace one request, as X-Ray's sampling rules do. */
    boolean sample(String region, String service, String serviceType, String host, String method, String path) {
        List<JsonNode> rules = new ArrayList<>();
        rules(region).forEach(r -> rules.add(r.path("SamplingRule")));
        JsonNode rule = XRaySampling.match(rules, service, serviceType, host, method, path, Map.of());
        return rule != null && sampling.decide(traces.accountId() + "/" + region, rule);
    }

    private ObjectNode createRule(JsonNode in, String region) {
        JsonNode rule = in.path("SamplingRule");
        String name = rule.path("RuleName").asText("");
        if (name.isEmpty()) {
            throw invalid("RuleName is required.");
        }
        if ("Default".equals(name) || state.get(key(region, "rule/" + name)).isPresent()) {
            throw invalid("Sampling rule already exists");
        }
        if (rule.path("Version").asInt() != 1) {
            throw invalid("Version must be 1.");
        }
        if (rules(region).size() > MAX_RULES) {
            throw new AwsException("RuleLimitExceededException", "An account can have at most " + MAX_RULES
                    + " sampling rules.", 400);
        }
        double now = now();
        ObjectNode record = mapper.createObjectNode();
        ObjectNode stored = ((ObjectNode) rule).deepCopy();
        stored.put("RuleARN", arn(region, "sampling-rule/" + name));
        if (!stored.has("Attributes")) {
            stored.putObject("Attributes");
        }
        record.set("SamplingRule", stored);
        record.put("CreatedAt", now).put("ModifiedAt", now);
        record.set("_tags", tags(in.path("Tags")));
        state.put(key(region, "rule/" + name), record);
        return wrap("SamplingRuleRecord", ruleView(record));
    }

    private ObjectNode rule(String region, JsonNode in) {
        String name = in.hasNonNull("RuleName") ? in.get("RuleName").asText() : null;
        String arn = in.hasNonNull("RuleARN") ? in.get("RuleARN").asText() : null;
        for (ObjectNode record : rules(region)) {
            JsonNode rule = record.path("SamplingRule");
            if ((name != null && name.equals(rule.path("RuleName").asText()))
                    || (arn != null && arn.equals(rule.path("RuleARN").asText()))) {
                return record;
            }
        }
        throw invalid("Sampling rule does not exist");
    }

    private ObjectNode ruleView(ObjectNode record) {
        ObjectNode view = record.deepCopy();
        view.remove("_tags");
        return view;
    }

    private ObjectNode getRules(JsonNode in, String region) {
        List<ObjectNode> all = rules(region).stream().sorted(Comparator.comparingInt((ObjectNode r) ->
                r.path("SamplingRule").path("Priority").asInt()).thenComparing(r ->
                r.path("SamplingRule").path("RuleName").asText())).toList();
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, PAGE, r -> out.add(ruleView(r)));
        return withToken(wrap("SamplingRuleRecords", out), next);
    }

    private ObjectNode updateRule(JsonNode in, String region) {
        JsonNode update = in.path("SamplingRuleUpdate");
        ObjectNode record = rule(region, update);
        ObjectNode rule = (ObjectNode) record.path("SamplingRule");
        boolean isDefault = "Default".equals(rule.path("RuleName").asText());
        update.fields().forEachRemaining(e -> {
            if (List.of("RuleName", "RuleARN").contains(e.getKey()) || e.getValue().isNull()) {
                return;
            }
            if (isDefault && !List.of("FixedRate", "ReservoirSize", "SamplingRateBoost").contains(e.getKey())) {
                throw invalid("Only the Default rule's FixedRate and ReservoirSize can change.");
            }
            rule.set(e.getKey(), e.getValue().deepCopy());
        });
        record.put("ModifiedAt", now());
        state.put(key(region, "rule/" + rule.path("RuleName").asText()), record);
        return wrap("SamplingRuleRecord", ruleView(record));
    }

    private ObjectNode deleteRule(JsonNode in, String region) {
        ObjectNode record = rule(region, in);
        String name = record.path("SamplingRule").path("RuleName").asText();
        if ("Default".equals(name)) {
            throw invalid("The Default sampling rule can't be deleted.");
        }
        state.delete(key(region, "rule/" + name));
        sampling.forget(traces.accountId() + "/" + region, name);
        return wrap("SamplingRuleRecord", ruleView(record));
    }

    private ObjectNode samplingTargets(JsonNode in, String region) {
        String scope = traces.accountId() + "/" + region;
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        rules(region).forEach(r -> byName.put(r.path("SamplingRule").path("RuleName").asText(), r));
        ArrayNode targets = mapper.createArrayNode();
        ArrayNode unprocessed = mapper.createArrayNode();
        double ttl = now() + XRaySampling.WINDOW_MILLIS / 1000.0;
        // Every report counts its client as active before any quota is shared out.
        for (JsonNode document : in.path("SamplingStatisticsDocuments")) {
            String name = document.path("RuleName").asText();
            if (byName.containsKey(name)) {
                sampling.report(scope, name, document.path("ClientID").asText(),
                        (long) (document.path("Timestamp").asDouble() * 1000), document.path("RequestCount").asLong(),
                        document.path("SampledCount").asLong(), document.path("BorrowCount").asLong());
            }
        }
        for (JsonNode document : in.path("SamplingStatisticsDocuments")) {
            String name = document.path("RuleName").asText();
            ObjectNode record = byName.get(name);
            if (record == null) {
                unprocessed.addObject().put("RuleName", name).put("ErrorCode", "400")
                        .put("Message", "Unknown rule " + name + ".");
                continue;
            }
            String client = document.path("ClientID").asText();
            JsonNode rule = record.path("SamplingRule");
            targets.addObject().put("RuleName", name).put("FixedRate", rule.path("FixedRate").asDouble())
                    .put("ReservoirQuota", sampling.quota(scope, name, client, rule.path("ReservoirSize").asInt()))
                    .put("ReservoirQuotaTTL", ttl).put("Interval", XRaySampling.WINDOW_MILLIS / 1000);
        }
        ArrayNode boosts = mapper.createArrayNode();
        for (JsonNode document : in.path("SamplingBoostStatisticsDocuments")) {
            if (!byName.containsKey(document.path("RuleName").asText())) {
                boosts.addObject().put("RuleName", document.path("RuleName").asText()).put("ErrorCode", "400")
                        .put("Message", "Unknown rule.");
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("SamplingTargetDocuments", targets);
        out.put("LastRuleModification", rules(region).stream()
                .mapToDouble(r -> r.path("ModifiedAt").asDouble()).max().orElse(0));
        out.set("UnprocessedStatistics", unprocessed);
        out.set("UnprocessedBoostStatistics", boosts);
        return out;
    }

    private ObjectNode statisticSummaries(JsonNode in, String region) {
        String scope = traces.accountId() + "/" + region;
        ArrayNode out = mapper.createArrayNode();
        for (ObjectNode record : rules(region)) {
            String name = record.path("SamplingRule").path("RuleName").asText();
            XRaySampling.Window window = sampling.summary(scope, name);
            if (window != null) {
                out.addObject().put("RuleName", name).put("Timestamp", window.start / 1000.0)
                        .put("RequestCount", window.requests).put("BorrowCount", window.borrowed)
                        .put("SampledCount", window.sampled);
            }
        }
        return wrap("SamplingStatisticSummaries", out);
    }

    // ---------- encryption, policies, indexing, destination ----------

    private ObjectNode encryption(String region) {
        ObjectNode encryption = state(region, "encryption");
        if (!encryption.has("Type")) {
            encryption.put("Status", "ACTIVE").put("Type", "NONE");
        }
        if ("UPDATING".equals(encryption.path("Status").asText())
                && now() - encryption.path("_since").asDouble() >= transitionSeconds()) {
            encryption.put("Status", "ACTIVE");
            save(region, "encryption", encryption);
        }
        ObjectNode view = encryption.deepCopy();
        view.remove("_since");
        return view;
    }

    private ObjectNode putEncryption(JsonNode in, String region) {
        String type = in.path("Type").asText();
        ObjectNode encryption = mapper.createObjectNode().put("Type", type).put("Status", "UPDATING")
                .put("_since", now());
        if ("KMS".equals(type)) {
            if (!in.hasNonNull("KeyId")) {
                throw invalid("KMS encryption needs a KeyId.");
            }
            String keyId = in.get("KeyId").asText();
            try {
                encryption.put("KeyId", kms.get().describeKey(keyId, region).getArn());
            } catch (AwsException e) {
                throw invalid("The KMS key " + keyId + " doesn't exist.");
            }
        } else if (in.hasNonNull("KeyId")) {
            throw invalid("A KeyId only applies to KMS encryption.");
        }
        save(region, "encryption", encryption);
        return wrap("EncryptionConfig", encryption(region));
    }

    private ObjectNode putPolicy(JsonNode in, String region) {
        String name = in.path("PolicyName").asText();
        String document = in.path("PolicyDocument").asText();
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_POLICY_BYTES) {
            throw new AwsException("PolicySizeLimitExceededException", "A resource policy is at most 5 KB.", 400);
        }
        checkPolicy(document);
        ObjectNode policies = state(region, "policies");
        ObjectNode existing = (ObjectNode) policies.get(name);
        if (in.hasNonNull("PolicyRevisionId")) {
            if (existing == null || !in.get("PolicyRevisionId").asText().equals(existing.path("PolicyRevisionId")
                    .asText())) {
                throw new AwsException("InvalidPolicyRevisionIdException",
                        "The policy revision id doesn't match the current revision.", 400);
            }
        }
        if (existing == null && policies.size() >= MAX_POLICIES) {
            throw new AwsException("PolicyCountLimitExceededException", "An account can have at most "
                    + MAX_POLICIES + " resource policies.", 400);
        }
        ObjectNode policy = mapper.createObjectNode().put("PolicyName", name).put("PolicyDocument", document)
                .put("PolicyRevisionId", String.valueOf(System.nanoTime())).put("LastUpdatedTime", now());
        policies.set(name, policy);
        save(region, "policies", policies);
        return wrap("ResourcePolicy", policy);
    }

    private void checkPolicy(String document) {
        JsonNode json;
        try {
            json = mapper.readTree(document);
        } catch (Exception e) {
            throw new AwsException("MalformedPolicyDocumentException", "The policy document is not valid JSON.", 400);
        }
        JsonNode statements = json == null ? null : json.path("Statement");
        if (statements == null || (!statements.isArray() && !statements.isObject())) {
            throw new AwsException("MalformedPolicyDocumentException", "The policy needs a Statement.", 400);
        }
        for (JsonNode statement : statements.isArray() ? statements : mapper.createArrayNode().add(statements)) {
            if (!List.of("Allow", "Deny").contains(statement.path("Effect").asText())) {
                throw new AwsException("MalformedPolicyDocumentException", "Each statement's Effect is Allow or "
                        + "Deny.", 400);
            }
            if (!statement.has("Principal")) {
                throw new AwsException("MalformedPolicyDocumentException", "Each statement needs a Principal.", 400);
            }
            JsonNode actions = statement.path("Action");
            for (JsonNode action : actions.isArray() ? actions : mapper.createArrayNode().add(actions)) {
                String value = action.asText();
                if (!value.startsWith("xray:") && !"*".equals(value)) {
                    throw new AwsException("MalformedPolicyDocumentException", "X-Ray resource policies grant "
                            + "xray actions, not " + value + ".", 400);
                }
            }
        }
    }

    private ObjectNode listPolicies(JsonNode in, String region) {
        List<JsonNode> all = new ArrayList<>();
        state(region, "policies").elements().forEachRemaining(all::add);
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, PAGE, out::add);
        return withToken(wrap("ResourcePolicies", out), next);
    }

    private ObjectNode deletePolicy(JsonNode in, String region) {
        ObjectNode policies = state(region, "policies");
        String name = in.path("PolicyName").asText();
        JsonNode existing = policies.get(name);
        if (existing != null && in.hasNonNull("PolicyRevisionId")
                && !in.get("PolicyRevisionId").asText().equals(existing.path("PolicyRevisionId").asText())) {
            throw new AwsException("InvalidPolicyRevisionIdException",
                    "The policy revision id doesn't match the current revision.", 400);
        }
        policies.remove(name);
        save(region, "policies", policies);
        return mapper.createObjectNode();
    }

    private ObjectNode indexingRules(String region) {
        ObjectNode rule = indexingRule(region);
        ArrayNode out = mapper.createArrayNode().add(rule);
        return wrap("IndexingRules", out);
    }

    private ObjectNode indexingRule(String region) {
        ObjectNode stored = state(region, "indexing");
        if (!stored.has("Name")) {
            stored.put("Name", "Default").put("ModifiedAt", 0.0);
            stored.putObject("Rule").putObject("Probabilistic").put("DesiredSamplingPercentage", 1.0)
                    .put("ActualSamplingPercentage", 1.0);
        }
        return stored;
    }

    private ObjectNode updateIndexingRule(JsonNode in, String region) {
        if (!"Default".equals(in.path("Name").asText())) {
            throw new AwsException("ResourceNotFoundException", "Indexing rule " + in.path("Name").asText()
                    + " not found.", 404, Map.of("ResourceName", in.path("Name").asText()));
        }
        double percentage = in.path("Rule").path("Probabilistic").path("DesiredSamplingPercentage").asDouble(-1);
        if (percentage < 0 || percentage > 100) {
            throw invalid("DesiredSamplingPercentage must be between 0 and 100.");
        }
        ObjectNode rule = indexingRule(region);
        rule.put("ModifiedAt", now());
        ((ObjectNode) rule.path("Rule").path("Probabilistic")).put("DesiredSamplingPercentage", percentage)
                .put("ActualSamplingPercentage", percentage);
        save(region, "indexing", rule);
        return wrap("IndexingRule", rule);
    }

    ObjectNode destination(String region) {
        ObjectNode stored = state(region, "destination");
        if (!stored.has("Destination")) {
            stored.put("Destination", "XRay").put("Status", "ACTIVE");
        }
        if ("PENDING".equals(stored.path("Status").asText())
                && now() - stored.path("_since").asDouble() >= transitionSeconds()) {
            stored.put("Status", "ACTIVE");
            save(region, "destination", stored);
        }
        ObjectNode view = stored.deepCopy();
        view.remove("_since");
        view.remove("_kmsKeyId");
        return view;
    }

    private ObjectNode updateDestination(JsonNode in, String region) {
        String destination = in.path("Destination").asText("XRay");
        if ("CloudWatchLogs".equals(destination) && !logsAllowXRay(region)) {
            throw invalid("Transaction Search needs a CloudWatch Logs resource policy that lets "
                    + ServicePrincipals.of("xray") + " write to the aws/spans log group.");
        }
        ObjectNode stored = mapper.createObjectNode().put("Destination", destination).put("Status", "PENDING")
                .put("_since", now());
        if (in.path("CloudWatchLogsConfiguration").hasNonNull("SpansLogGroupKmsKeyId")) {
            stored.put("_kmsKeyId", in.path("CloudWatchLogsConfiguration").path("SpansLogGroupKmsKeyId").asText());
        }
        save(region, "destination", stored);
        if ("CloudWatchLogs".equals(destination)) {
            ensureSpansLogGroup(region);
        }
        return destination(region);
    }

    /** Transaction Search writes spans only once a CloudWatch Logs resource policy lets X-Ray do so. */
    private boolean logsAllowXRay(String region) {
        try {
            String principal = ServicePrincipals.of("xray");
            return logs.get().describeResourcePolicies(region).stream()
                    .anyMatch(policy -> policy.getPolicyDocument() != null
                            && policy.getPolicyDocument().contains(principal));
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read CloudWatch Logs resource policies in {0}", region);
            return false;
        }
    }

    private void ensureSpansLogGroup(String region) {
        try {
            if (!logs.get().logGroupExists("aws/spans", region)) {
                logs.get().createLogGroup("aws/spans", null, Map.of(), region);
            }
        } catch (AwsException e) {
            LOG.debugv("aws/spans log group: {0}", e.getMessage());
        }
    }

    private void writeSpan(String region, String document) {
        try {
            ensureSpansLogGroup(region);
            if (logs.get().describeLogStreams("aws/spans", "default", region).stream()
                    .noneMatch(stream -> "default".equals(stream.getLogStreamName()))) {
                logs.get().createLogStream("aws/spans", "default", region);
            }
            logs.get().putLogEvents("aws/spans", "default", List.of(Map.of("timestamp", System.currentTimeMillis(),
                    "message", document)), region);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not write a span to aws/spans in {0}", region);
        }
    }

    // ---------- trace retrieval (Transaction Search) ----------

    private ObjectNode startRetrieval(JsonNode in, String region) {
        if (!"CloudWatchLogs".equals(destination(region).path("Destination").asText())) {
            throw invalid("Trace retrieval needs Transaction Search: set the trace segment destination to "
                    + "CloudWatchLogs.");
        }
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        checkWindow(start, end, 86400, "24 hours");
        String token = UUID.randomUUID().toString();
        ObjectNode retrieval = mapper.createObjectNode().put("token", token).put("status", "RUNNING")
                .put("started", now());
        retrieval.set("traceIds", in.path("TraceIds").deepCopy());
        state.put(key(region, "retrieval/" + token), retrieval);
        return mapper.createObjectNode().put("RetrievalToken", token);
    }

    private ObjectNode retrieval(String region, JsonNode in) {
        String token = in.path("RetrievalToken").asText();
        ObjectNode retrieval = state.get(key(region, "retrieval/" + token)).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Retrieval " + token + " not found.", 404,
                        Map.of("ResourceName", token)));
        if ("RUNNING".equals(retrieval.path("status").asText())
                && now() - retrieval.path("started").asDouble() >= transitionSeconds()) {
            retrieval.put("status", "COMPLETE");
            state.put(key(region, "retrieval/" + token), retrieval);
        }
        return retrieval;
    }

    private ObjectNode listRetrieved(JsonNode in, String region) {
        ObjectNode retrieval = retrieval(region, in);
        String format = in.path("TraceFormat").asText("XRAY");
        ObjectNode out = mapper.createObjectNode().put("RetrievalStatus", retrieval.path("status").asText())
                .put("TraceFormat", format);
        ArrayNode list = out.putArray("Traces");
        if ("COMPLETE".equals(retrieval.path("status").asText())) {
            for (JsonNode id : retrieval.path("traceIds")) {
                traces.get(region + "/" + id.asText()).ifPresent(stored -> {
                    XRayTraces.Trace trace = parse(stored);
                    ObjectNode entry = list.addObject().put("Id", trace.id)
                            .put("Duration", XRayTraces.round(trace.duration()));
                    ArrayNode spans = entry.putArray("Spans");
                    for (ObjectNode document : mergedDocuments(stored)) {
                        spans.addObject().put("Id", document.path("id").asText())
                                .put("Document", "OTEL".equals(format) ? otel(document).toString()
                                        : document.toString());
                    }
                });
            }
        }
        return out;
    }

    /** A segment as an OpenTelemetry span document. */
    private ObjectNode otel(ObjectNode segment) {
        ObjectNode span = mapper.createObjectNode();
        String traceId = segment.path("trace_id").asText();
        span.put("traceId", traceId.replace("1-", "").replace("-", "")).put("spanId", segment.path("id").asText())
                .put("name", segment.path("name").asText())
                .put("startTimeUnixNano", (long) (segment.path("start_time").asDouble() * 1e9))
                .put("endTimeUnixNano", (long) (segment.path("end_time").asDouble() * 1e9));
        if (segment.hasNonNull("parent_id")) {
            span.put("parentSpanId", segment.get("parent_id").asText());
        }
        span.putObject("status").put("code", segment.path("fault").asBoolean() || segment.path("error").asBoolean()
                ? "ERROR" : "UNSET");
        ObjectNode attributes = span.putObject("attributes");
        segment.path("annotations").fields().forEachRemaining(e -> attributes.set(e.getKey(), e.getValue()));
        return span;
    }

    private ObjectNode retrievedGraph(JsonNode in, String region) {
        ObjectNode retrieval = retrieval(region, in);
        ObjectNode out = mapper.createObjectNode().put("RetrievalStatus", retrieval.path("status").asText());
        ArrayNode services = out.putArray("Services");
        if ("COMPLETE".equals(retrieval.path("status").asText())) {
            List<XRayTraces.Trace> selected = new ArrayList<>();
            for (JsonNode id : retrieval.path("traceIds")) {
                traces.get(region + "/" + id.asText()).map(this::parse).ifPresent(selected::add);
            }
            for (JsonNode service : XRayTraces.render(mapper, XRayTraces.graph(selected, traces.accountId()))) {
                ObjectNode entry = services.addObject();
                entry.set("Service", service);
                entry.putArray("Links");
            }
        }
        return out;
    }

    private ObjectNode cancelRetrieval(JsonNode in, String region) {
        ObjectNode retrieval = retrieval(region, in);
        if ("COMPLETE".equals(retrieval.path("status").asText())) {
            throw invalid("The retrieval has already completed.");
        }
        retrieval.put("status", "CANCELLED");
        state.put(key(region, "retrieval/" + retrieval.path("token").asText()), retrieval);
        return mapper.createObjectNode();
    }

    // ---------- insights ----------

    ObjectNode insight(String region, String id) {
        return state.get(key(region, "insight/" + id)).orElseThrow(() -> invalid("Insight " + id + " not found."));
    }

    List<ObjectNode> insightsOf(String region) {
        return state.scan(k -> k.startsWith(region + "/insight/"));
    }

    void saveInsight(String region, ObjectNode insight) {
        state.put(key(region, "insight/" + insight.path("InsightId").asText()), insight);
    }

    ObjectNode insightView(ObjectNode insight, boolean summary) {
        ObjectNode view = insight.deepCopy();
        List<String> internal = new ArrayList<>();
        view.fieldNames().forEachRemaining(f -> {
            if (f.startsWith("_")) {
                internal.add(f);
            }
        });
        view.remove(internal);
        if (!summary) {
            view.remove("LastUpdateTime");
        }
        return view;
    }

    private ObjectNode insightSummaries(JsonNode in, String region) {
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        checkWindow(start, end, 86400, "24 hours");
        String groupArn = null;
        if (in.hasNonNull("GroupName") || in.hasNonNull("GroupARN")) {
            groupArn = group(region, in).path("GroupARN").asText();
        }
        List<String> states = strings(in.path("States"));
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode insight : insightsOf(region)) {
            double insightEnd = insight.has("EndTime") ? insight.get("EndTime").asDouble() : now();
            if ((groupArn == null || groupArn.equals(insight.path("GroupARN").asText()))
                    && (states.isEmpty() || states.contains(insight.path("State").asText()))
                    && insight.path("StartTime").asDouble() <= end && insightEnd >= start) {
                matching.add(insight);
            }
        }
        matching.sort(Comparator.comparingDouble((ObjectNode i) -> i.path("StartTime").asDouble()).reversed());
        ArrayNode out = mapper.createArrayNode();
        int size = in.has("MaxResults") ? in.get("MaxResults").asInt() : 100;
        String next = page(matching, in, size, i -> out.add(insightView(i, true)));
        return withToken(wrap("InsightSummaries", out), next);
    }

    private ObjectNode insightEvents(JsonNode in, String region) {
        ObjectNode insight = insight(region, in.path("InsightId").asText());
        List<JsonNode> events = new ArrayList<>();
        insight.path("_events").forEach(events::add);
        ArrayNode out = mapper.createArrayNode();
        int size = in.has("MaxResults") ? in.get("MaxResults").asInt() : 50;
        String next = page(events, in, size, out::add);
        return withToken(wrap("InsightEvents", out), next);
    }

    private ObjectNode impactGraph(JsonNode in, String region) {
        ObjectNode insight = insight(region, in.path("InsightId").asText());
        double start = in.path("StartTime").asDouble();
        double end = in.path("EndTime").asDouble();
        if (end <= start) {
            throw invalid("EndTime must be after StartTime.");
        }
        String root = insight.path("RootCauseServiceId").path("Name").asText();
        List<XRayTraces.Trace> impacted = tracesBetween(region, start, end).stream()
                .filter(t -> t.all().stream().anyMatch(n -> n.fault && root.equals(n.name))).toList();
        ObjectNode out = mapper.createObjectNode().put("InsightId", insight.path("InsightId").asText())
                .put("StartTime", start).put("EndTime", end).put("ServiceGraphStartTime", start)
                .put("ServiceGraphEndTime", end);
        ArrayNode services = out.putArray("Services");
        for (XRayTraces.GraphNode node : XRayTraces.graph(impacted, traces.accountId())) {
            ObjectNode service = services.addObject().put("ReferenceId", node.reference).put("Name", node.name);
            if (node.type != null) {
                service.put("Type", node.type);
            }
            service.putArray("Names").add(node.name);
            service.put("AccountId", node.account);
            ArrayNode edges = service.putArray("Edges");
            node.edges.keySet().forEach(target -> edges.addObject().put("ReferenceId", target.reference));
        }
        return out;
    }

    // ---------- tags ----------

    private ObjectNode taggable(String region, String arn) {
        for (ObjectNode group : groups(region)) {
            if (arn.equals(group.path("GroupARN").asText())) {
                return group;
            }
        }
        for (ObjectNode rule : rules(region)) {
            if (arn.equals(rule.path("SamplingRule").path("RuleARN").asText())) {
                return rule;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404,
                Map.of("ResourceName", arn));
    }

    private void saveTaggable(String region, ObjectNode resource) {
        if (resource.has("GroupName")) {
            state.put(key(region, "group/" + resource.path("GroupName").asText()), resource);
        } else {
            state.put(key(region, "rule/" + resource.path("SamplingRule").path("RuleName").asText()), resource);
        }
    }

    private ObjectNode tagResource(JsonNode in, String region) {
        String arn = in.path("ResourceARN").asText();
        ObjectNode resource = taggable(region, arn);
        ObjectNode tags = (ObjectNode) resource.withObject("/_tags");
        for (JsonNode tag : in.path("Tags")) {
            tags.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400,
                    Map.of("ResourceName", arn));
        }
        saveTaggable(region, resource);
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode resource = taggable(region, in.path("ResourceARN").asText());
        ObjectNode tags = (ObjectNode) resource.withObject("/_tags");
        strings(in.path("TagKeys")).forEach(tags::remove);
        saveTaggable(region, resource);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode resource = taggable(region, in.path("ResourceARN").asText());
        ArrayNode out = mapper.createArrayNode();
        resource.path("_tags").fields().forEachRemaining(e -> out.addObject().put("Key", e.getKey())
                .put("Value", e.getValue().asText()));
        return wrap("Tags", out);
    }

    // ---------- helpers ----------

    private ObjectNode tags(JsonNode list) {
        ObjectNode out = mapper.createObjectNode();
        for (JsonNode tag : list) {
            out.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        if (out.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400);
        }
        return out;
    }

    private ObjectNode state(String region, String name) {
        return state.get(key(region, name)).orElseGet(mapper::createObjectNode);
    }

    private void save(String region, String name, ObjectNode value) {
        state.put(key(region, name), value);
    }

    String account() {
        return traces.accountId();
    }

    List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> allStateEntries(String prefix) {
        return state.scanAllAccountEntries(k -> k.contains("/" + prefix));
    }

    /** The account that holds a trace already, so daemon segments join it; null when none does. */
    String accountHolding(String traceId) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : traces.scanAllAccountEntries(k -> k.endsWith("/" + traceId))) {
            return entry.accountId() + "|" + entry.key().substring(0, entry.key().indexOf('/'));
        }
        return null;
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("xray", region, traces.accountId(), resource).toString();
    }

    private double transitionSeconds() {
        return config.services().xray().transitionMillis() / 1000.0;
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    static String newTraceId() {
        return "1-" + Long.toHexString(Instant.now().getEpochSecond()) + "-" + randomHex(24);
    }

    static String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, length);
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private ObjectNode wrap(String field, JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        out.set(field, value);
        return out;
    }

    private static ObjectNode withToken(ObjectNode out, String next) {
        if (next != null) {
            out.put("NextToken", next);
        }
        return out;
    }

    private static <T> String page(List<T> items, JsonNode in, int size, Consumer<T> emit) {
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        for (int i = Math.min(start, items.size()); i < end; i++) {
            emit.accept(items.get(i));
        }
        return end < items.size()
                ? Base64.getEncoder().encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
