package io.floci.aws.services.appflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.ScheduleExpressionParser;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Running AppFlow flows. A run starts {@code InProgress} and, a moment later on the run thread,
 * pulls the S3 source's objects under its prefix (for an incremental scheduled flow, only those
 * changed since the last run), applies the flow's tasks, and writes the records to each S3
 * destination as CSV, JSON lines or Parquet, aggregated per source object or into one file, under
 * {@code <prefix>/<flowName>/<executionId>/} (or the date path its prefix configuration asks for).
 * A run with a connector Floci can't reach ends in {@code Error} with the reason. Each run's end is
 * announced on EventBridge as an {@code AppFlow End Flow Run Report}. Scheduled flows run on their
 * {@code rate()} or {@code cron()} expression while active.
 */
@ApplicationScoped
public class AppFlowRuns implements Resettable {
    private static final Logger LOG = Logger.getLogger(AppFlowRuns.class);
    static final long RUN_MILLIS = 1_000;
    private static final Set<String> RUNNING = Set.of("InProgress", "CancelStarted");

    private final AccountAwareStorageBackend<ObjectNode> executions;
    private final Map<String, ScheduledFuture<?>> schedules = new ConcurrentHashMap<>();
    private final AppFlowService flows;
    private final Instance<S3Service> s3;
    private final Instance<EventBridgeService> eventBridge;
    private final Instance<FlociDuckClient> duck;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService runner = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "appflow-runs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public AppFlowRuns(StorageFactory storageFactory, AppFlowService flows, Instance<S3Service> s3,
                       Instance<EventBridgeService> eventBridge, Instance<FlociDuckClient> duck,
                       RegionResolver regionResolver, ObjectMapper mapper) {
        this.executions = storageFactory.create("appflow", "appflow-executions.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.flows = flows;
        this.s3 = s3;
        this.eventBridge = eventBridge;
        this.duck = duck;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    // ── Starting and stopping ──────────────────────────────────────────────

    /** On-demand flows run now; scheduled and event flows are activated. */
    public synchronized ObjectNode startFlow(JsonNode in, String region) {
        AppFlowService.check("StartFlow", in);
        String name = in.path("flowName").asText();
        ObjectNode flow = flows.requireFlow(name, region);
        String trigger = flow.path("triggerConfig").path("triggerType").asText();
        ObjectNode out = mapper.createObjectNode().put("flowArn", flow.path("flowArn").asText());
        if ("OnDemand".equals(trigger)) {
            if (executions.scan(k -> k.startsWith(prefix(region, name))).stream()
                    .filter(e -> RUNNING.contains(e.path("executionStatus").asText())).count() >= 10) {
                throw new AwsException("ServiceQuotaExceededException", "Flow " + name + " has too many runs in "
                        + "progress.", 402);
            }
            out.put("executionId", start(flow, region, "OnDemand"));
            out.put("flowStatus", flow.path("flowStatus").asText());
            return out;
        }
        if ("Active".equals(flow.path("flowStatus").asText())) {
            throw new AwsException("ConflictException", "Flow " + name + " is already active.", 409);
        }
        flow.put("flowStatus", "Active");
        flows.saveFlow(region, flow);
        if ("Scheduled".equals(trigger)) {
            schedule(flow, region);
        }
        return out.put("flowStatus", "Active");
    }

    public synchronized ObjectNode stopFlow(JsonNode in, String region) {
        AppFlowService.check("StopFlow", in);
        String name = in.path("flowName").asText();
        ObjectNode flow = flows.requireFlow(name, region);
        if ("OnDemand".equals(flow.path("triggerConfig").path("triggerType").asText())) {
            throw new AwsException("UnsupportedOperationException", "An on-demand flow can't be stopped; cancel its "
                    + "runs with CancelFlowExecutions.", 400);
        }
        if (!"Active".equals(flow.path("flowStatus").asText())) {
            throw new AwsException("ConflictException", "Flow " + name + " isn't active.", 409);
        }
        unschedule(name, region);
        flow.put("flowStatus", "Suspended");
        flows.saveFlow(region, flow);
        return mapper.createObjectNode().put("flowArn", flow.path("flowArn").asText()).put("flowStatus", "Suspended");
    }

    /** Runs a scheduled flow on its expression, from its start time, until its end time. */
    synchronized void schedule(ObjectNode flow, String region) {
        String name = flow.path("flowName").asText();
        unschedule(name, region);
        JsonNode scheduled = flow.path("triggerConfig").path("triggerProperties").path("Scheduled");
        String expression = scheduled.path("scheduleExpression").asText();
        String account = regionResolver.getAccountId();
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                RequestScopes.runAs(account, region, () -> {
                    try {
                        ObjectNode current = flows.requireFlow(name, region);
                        if (!"Active".equals(current.path("flowStatus").asText())) {
                            return;
                        }
                        JsonNode properties = current.path("triggerConfig").path("triggerProperties").path("Scheduled");
                        Instant now = Instant.now();
                        boolean started = !properties.has("scheduleStartTime")
                                || now.getEpochSecond() >= properties.get("scheduleStartTime").asDouble();
                        if (properties.has("scheduleEndTime")
                                && now.getEpochSecond() > properties.get("scheduleEndTime").asDouble()) {
                            unschedule(name, region);
                            return;
                        }
                        if (started) {
                            start(current, region, "Scheduled");
                        }
                        String key = key(account, region, name);
                        if (schedules.containsKey(key)) {
                            schedules.put(key, runner.schedule(this, delay(expression, properties), TimeUnit.MILLISECONDS));
                        }
                    } catch (AwsException e) {
                        unschedule(name, region);
                    } catch (RuntimeException e) {
                        LOG.warnv(e, "Scheduled run of AppFlow flow {0} failed to start", name);
                    }
                });
            }
        };
        schedules.put(key(account, region, name), runner.schedule(tick, delay(expression, scheduled),
                TimeUnit.MILLISECONDS));
    }

    private static long delay(String expression, JsonNode properties) {
        ZoneId zone = properties.hasNonNull("timezone") ? ZoneId.of(properties.get("timezone").asText())
                : ZoneOffset.UTC;
        long offset = properties.path("scheduleOffset").asLong(0) * 1000;
        Long rate = rateMillis(expression);
        if (rate != null) {
            return rate + offset;
        }
        return ScheduleExpressionParser.millisUntilNextFire(expression, ZonedDateTime.now(zone)) + offset;
    }

    private static final Pattern RATE = Pattern.compile("^rate\\((\\d+)\\s*(minute|minutes|hour|hours|day|days)\\)$");

    /** An AppFlow rate ({@code rate(5minutes)}, {@code rate(1 hour)}) in milliseconds, or null for a cron. */
    static Long rateMillis(String expression) {
        Matcher matcher = RATE.matcher(expression.strip());
        if (!matcher.matches()) {
            return null;
        }
        long value = Long.parseLong(matcher.group(1));
        long unit = switch (matcher.group(2)) {
            case "minute", "minutes" -> 60_000L;
            case "hour", "hours" -> 3_600_000L;
            default -> 86_400_000L;
        };
        return value * unit;
    }

    void unschedule(String name, String region) {
        ScheduledFuture<?> future = schedules.remove(key(regionResolver.getAccountId(), region, name));
        if (future != null) {
            future.cancel(false);
        }
    }

    // ── Runs ───────────────────────────────────────────────────────────────

    private String start(ObjectNode flow, String region, String trigger) {
        String name = flow.path("flowName").asText();
        String executionId = UUID.randomUUID().toString();
        double now = Instant.now().toEpochMilli() / 1000.0;
        ObjectNode execution = mapper.createObjectNode();
        execution.put("executionId", executionId);
        execution.put("executionStatus", "InProgress");
        execution.putObject("executionResult").put("bytesProcessed", 0).put("bytesWritten", 0)
                .put("recordsProcessed", 0).put("numParallelProcesses", 1).put("maxPageSize", 0);
        execution.put("startedAt", now);
        execution.put("lastUpdatedAt", now);
        execution.put("trigger", trigger);
        executions.put(prefix(region, name) + executionId, execution);
        String account = regionResolver.getAccountId();
        runner.schedule(() -> RequestScopes.runAs(account, region, () -> {
            try {
                run(region, name, executionId);
            } catch (RuntimeException e) {
                LOG.warnv(e, "AppFlow run {0} of {1} failed", executionId, name);
                finish(region, name, executionId, "Error", e.getMessage(), null);
            }
        }), RUN_MILLIS, TimeUnit.MILLISECONDS);
        return executionId;
    }

    private void run(String region, String name, String executionId) {
        ObjectNode execution;
        ObjectNode flow;
        synchronized (this) {
            execution = executions.get(prefix(region, name) + executionId).orElse(null);
            if (execution == null) {
                return;
            }
            if ("CancelStarted".equals(execution.path("executionStatus").asText())) {
                finish(region, name, executionId, "Canceled", null, null);
                return;
            }
            flow = flows.findFlow(name, region).orElse(null);
        }
        if (flow == null) {
            return;
        }
        if (!AppFlowService.runnable(flow)) {
            finish(region, name, executionId, "Error", "Floci moves data only between S3 buckets; it can't reach "
                    + flow.path("sourceFlowConfig").path("connectorType").asText() + " or "
                    + flow.withArray("destinationFlowConfigList").findValuesAsText("connectorType") + ".", null);
            return;
        }
        S3Service storage = s3.get();
        JsonNode source = flow.path("sourceFlowConfig").path("sourceConnectorProperties").path("S3");
        String bucket = source.path("bucketName").asText();
        String sourcePrefix = source.path("bucketPrefix").asText("");
        String inputType = source.path("s3InputFormatConfig").path("s3InputFileType").asText(null);
        double pullStart = Instant.now().toEpochMilli() / 1000.0;
        Instant since = incrementalSince(flow, region, name);
        Map<String, List<Map<String, Object>>> perObject = new LinkedHashMap<>();
        long bytesIn = 0;
        for (S3Object object : storage.listObjects(bucket, sourcePrefix.isEmpty() ? null : sourcePrefix, null,
                100_000)) {
            if (object.getKey().endsWith("/") || (since != null && object.getLastModified() != null
                    && !object.getLastModified().isAfter(since))) {
                continue;
            }
            byte[] data = storage.getObject(bucket, object.getKey()).getData();
            bytesIn += data.length;
            perObject.put(object.getKey(), AppFlowRecords.parse(new String(data, StandardCharsets.UTF_8), inputType,
                    mapper));
        }
        double pullEnd = Instant.now().toEpochMilli() / 1000.0;
        int recordsIn = perObject.values().stream().mapToInt(List::size).sum();
        long bytesOut = 0;
        int written = 0;
        try {
            for (JsonNode destination : flow.withArray("destinationFlowConfigList")) {
                JsonNode properties = destination.path("destinationConnectorProperties").path("S3");
                JsonNode format = properties.path("s3OutputFormatConfig");
                boolean single = "SingleFile".equals(format.path("aggregationConfig").path("aggregationType")
                        .asText("None"));
                Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
                if (single) {
                    List<Map<String, Object>> all = new ArrayList<>();
                    perObject.values().forEach(all::addAll);
                    groups.put("all", all);
                } else {
                    groups.putAll(perObject);
                }
                int index = 0;
                for (List<Map<String, Object>> group : groups.values()) {
                    AppFlowRecords.Result result = AppFlowRecords.apply(group, flow.withArray("tasks"));
                    written += result.records().size();
                    if (result.records().isEmpty() && !single) {
                        continue;
                    }
                    bytesOut += write(flow, properties, format, executionId, index++, result);
                }
            }
        } catch (AppFlowRecords.Terminated e) {
            finish(region, name, executionId, "Error", e.getMessage(), null);
            return;
        } catch (AwsException e) {
            finish(region, name, executionId, "Error", e.getMessage(), null);
            return;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("bytesProcessed", bytesIn);
        result.put("bytesWritten", bytesOut);
        result.put("recordsProcessed", recordsIn);
        result.put("numParallelProcesses", 1);
        result.put("maxPageSize", recordsIn);
        result.put("dataPullStartTime", pullStart);
        result.put("dataPullEndTime", pullEnd);
        result.put("recordsWritten", written);
        finish(region, name, executionId, "Successful", null, result);
    }

    /** For an incremental scheduled run, the end of the last successful pull. */
    private Instant incrementalSince(ObjectNode flow, String region, String name) {
        if (!"Incremental".equals(flow.path("triggerConfig").path("triggerProperties").path("Scheduled")
                .path("dataPullMode").asText())) {
            return null;
        }
        return executions.scan(k -> k.startsWith(prefix(region, name))).stream()
                .filter(e -> "Successful".equals(e.path("executionStatus").asText()) && e.has("dataPullEndTime"))
                .map(e -> Instant.ofEpochMilli((long) (e.path("dataPullEndTime").asDouble() * 1000)))
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private long write(ObjectNode flow, JsonNode properties, JsonNode format, String executionId, int index,
                       AppFlowRecords.Result result) {
        String bucket = properties.path("bucketName").asText();
        String fileType = format.path("fileType").asText("JSON");
        String name = flow.path("flowName").asText();
        String key = outputKey(properties.path("bucketPrefix").asText(""), name, executionId, index,
                format.path("prefixConfig"), fileType);
        S3Service storage = s3.get();
        if ("PARQUET".equals(fileType)) {
            if (!duck.isResolvable()) {
                throw new AwsException("ConnectorServerException", "PARQUET output needs Floci's DuckDB sidecar.", 400);
            }
            String staging = key + ".staging.ndjson";
            byte[] lines = AppFlowRecords.jsonLines(result.records(), mapper).getBytes(StandardCharsets.UTF_8);
            storage.putObject(bucket, staging, lines, "application/x-ndjson", Map.of());
            try {
                duck.get().execute("COPY (SELECT * FROM read_json_auto('s3://" + bucket + "/" + staging.replace("'",
                        "''") + "')) TO 's3://" + bucket + "/" + key.replace("'", "''") + "' (FORMAT PARQUET)", null,
                        null, regionResolver.getAccountId());
            } finally {
                storage.deleteObject(bucket, staging);
            }
            return storage.headObject(bucket, key).getSize();
        }
        byte[] body = ("CSV".equals(fileType) ? AppFlowRecords.csv(result.records(), result.fields())
                : AppFlowRecords.jsonLines(result.records(), mapper)).getBytes(StandardCharsets.UTF_8);
        storage.putObject(bucket, key, body, "CSV".equals(fileType) ? "text/csv" : "application/json", Map.of());
        return body.length;
    }

    /**
     * Where a run writes a file: under the destination prefix and the flow's name, then the
     * execution id (or the date path a PATH prefix asks for), then a file name, prefixed with the
     * date when the prefix type includes FILENAME.
     */
    static String outputKey(String bucketPrefix, String flowName, String executionId, int index, JsonNode prefix,
                            String fileType) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String type = prefix.path("prefixType").asText("");
        String datePattern = switch (prefix.path("prefixFormat").asText("")) {
            case "YEAR" -> "yyyy";
            case "MONTH" -> "yyyy/MM";
            case "DAY" -> "yyyy/MM/dd";
            case "HOUR" -> "yyyy/MM/dd/HH";
            case "MINUTE" -> "yyyy/MM/dd/HH/mm";
            default -> null;
        };
        StringBuilder key = new StringBuilder();
        if (!bucketPrefix.isEmpty()) {
            key.append(bucketPrefix.replaceAll("/+$", "")).append('/');
        }
        key.append(flowName).append('/');
        List<String> hierarchy = new ArrayList<>();
        prefix.path("pathPrefixHierarchy").forEach(p -> hierarchy.add(p.asText()));
        if (type.contains("PATH") && datePattern != null) {
            key.append(DateTimeFormatter.ofPattern(datePattern).format(now)).append('/');
        }
        if (hierarchy.isEmpty() || hierarchy.contains("EXECUTION_ID")) {
            key.append(executionId).append('/');
        }
        if (hierarchy.contains("SCHEMA_VERSION")) {
            key.append("schemaVersion_1/");
        }
        if (type.contains("FILENAME") && datePattern != null) {
            key.append(DateTimeFormatter.ofPattern(datePattern.replace("/", "-")).format(now)).append('-');
        }
        key.append(flowName).append('-').append(index).append('-').append(now.toInstant().toEpochMilli());
        key.append(switch (fileType) {
            case "CSV" -> ".csv";
            case "PARQUET" -> ".parquet";
            default -> ".json";
        });
        return key.toString();
    }

    private synchronized void finish(String region, String name, String executionId, String status, String error,
                                     ObjectNode result) {
        ObjectNode execution = executions.get(prefix(region, name) + executionId).orElse(null);
        if (execution == null) {
            return;
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        execution.put("executionStatus", status);
        execution.put("lastUpdatedAt", now);
        ObjectNode executionResult = (ObjectNode) execution.get("executionResult");
        if (result != null) {
            for (String field : List.of("bytesProcessed", "bytesWritten", "recordsProcessed", "numParallelProcesses",
                    "maxPageSize")) {
                executionResult.set(field, result.get(field));
            }
            execution.put("dataPullStartTime", result.path("dataPullStartTime").asDouble());
            execution.put("dataPullEndTime", result.path("dataPullEndTime").asDouble());
        }
        if (error != null) {
            executionResult.putObject("errorInfo").put("putFailuresCount", 0).put("executionMessage", error);
        }
        executions.put(prefix(region, name) + executionId, execution);
        flows.findFlow(name, region).ifPresent(flow -> {
            ObjectNode details = flow.putObject("lastRunExecutionDetails");
            details.put("mostRecentExecutionMessage", error == null ? "Successfully ran the flow" : error);
            details.put("mostRecentExecutionTime", now);
            details.put("mostRecentExecutionStatus", status);
            flows.saveFlow(region, flow);
            publish(flow, executionId, status, error, result, region);
        });
    }

    private void publish(ObjectNode flow, String executionId, String status, String error, ObjectNode result,
                         String region) {
        if (!eventBridge.isResolvable() || "Canceled".equals(status)) {
            return;
        }
        ObjectNode detail = mapper.createObjectNode();
        detail.put("flow-name", flow.path("flowName").asText());
        detail.put("created-by", flow.path("createdBy").asText());
        detail.put("flow-arn", flow.path("flowArn").asText());
        detail.put("source", flow.path("sourceFlowConfig").path("connectorType").asText());
        detail.put("destination", flow.path("destinationFlowConfigList").path(0).path("connectorType").asText());
        detail.put("source-object", flow.path("sourceFlowConfig").path("sourceConnectorProperties").path("S3")
                .path("bucketName").asText(""));
        detail.put("status", "Successful".equals(status) ? "Execution Successful" : "Execution Failed");
        detail.put("execution-id", executionId);
        detail.put("num-of-records-processed", result == null ? "0" : result.path("recordsProcessed").asText());
        detail.put("num-of-records-filtered", result == null ? "0"
                : Long.toString(result.path("recordsProcessed").asLong() - result.path("recordsWritten").asLong()));
        detail.put("data-processed", result == null ? "0" : result.path("bytesProcessed").asText());
        if (error != null) {
            detail.put("error-message", error);
        }
        eventBridge.get().publishServiceEvent("aws.appflow", "AppFlow End Flow Run Report", detail.toString(),
                flow.path("flowArn").asText(), region);
    }

    public synchronized ObjectNode cancel(JsonNode in, String region) {
        AppFlowService.check("CancelFlowExecutions", in);
        String name = in.path("flowName").asText();
        flows.requireFlow(name, region);
        List<String> ids = new ArrayList<>();
        in.path("executionIds").forEach(id -> ids.add(id.asText()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode invalid = out.putArray("invalidExecutions");
        List<ObjectNode> targets = executions.scan(k -> k.startsWith(prefix(region, name))).stream()
                .filter(e -> ids.isEmpty() || ids.contains(e.path("executionId").asText())).toList();
        for (String id : ids) {
            if (targets.stream().noneMatch(e -> id.equals(e.path("executionId").asText()))) {
                invalid.add(id);
            }
        }
        for (ObjectNode execution : targets) {
            if ("InProgress".equals(execution.path("executionStatus").asText())) {
                execution.put("executionStatus", "CancelStarted");
                execution.put("lastUpdatedAt", Instant.now().toEpochMilli() / 1000.0);
                executions.put(prefix(region, name) + execution.path("executionId").asText(), execution);
            } else if (!ids.isEmpty()) {
                invalid.add(execution.path("executionId").asText());
            }
        }
        return out;
    }

    public ObjectNode records(JsonNode in, String region) {
        AppFlowService.check("DescribeFlowExecutionRecords", in);
        String name = in.path("flowName").asText();
        flows.requireFlow(name, region);
        List<ObjectNode> all = executions.scan(k -> k.startsWith(prefix(region, name))).stream()
                .sorted(Comparator.comparingDouble((ObjectNode e) -> e.path("startedAt").asDouble()).reversed())
                .map(e -> {
                    ObjectNode copy = e.deepCopy();
                    copy.remove("trigger");
                    return copy;
                }).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("flowExecutions");
        AppFlowService.page(all, in, out).forEach(list::add);
        return out;
    }

    boolean hasRunning(String name, String region) {
        return executions.scan(k -> k.startsWith(prefix(region, name))).stream()
                .anyMatch(e -> RUNNING.contains(e.path("executionStatus").asText()));
    }

    synchronized void forget(String name, String region) {
        unschedule(name, region);
        executions.keys().stream().filter(k -> k.startsWith(prefix(region, name))).toList()
                .forEach(executions::delete);
    }

    private static String prefix(String region, String name) {
        return region + "::" + name + "::";
    }

    private static String key(String account, String region, String name) {
        return account + "::" + region + "::" + name;
    }

    @Override
    public void clear() {
        schedules.values().forEach(f -> f.cancel(false));
        schedules.clear();
        executions.clear();
    }
}
