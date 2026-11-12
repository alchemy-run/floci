package io.floci.aws.services.synthetics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.scheduler.SchedulerExpressionParser;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon CloudWatch Synthetics. A canary's code is published as a layer and run by a backing Lambda function
 * ({@link SyntheticsEngine}). Started canaries run on their schedule ({@code rate(...)}, {@code cron(...)}, or
 * {@code rate(0 minute)} for a single run), retry failed runs, write each run's report and log to the artifact
 * bucket (as the execution role, which must be allowed to), publish {@code CloudWatchSynthetics} metrics, and send
 * {@code aws.synthetics} status-change and test-run events to EventBridge. Runs are kept for the canary's retention
 * periods. Groups are global to the account and hold up to ten canaries from any Region.
 */
@ApplicationScoped
public class SyntheticsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SyntheticsService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RATE = Pattern.compile("rate\\((\\d+) (minute|minutes|hour|hours)\\)");
    private static final Set<String> BUSY = Set.of("CREATING", "UPDATING", "DELETING", "STARTING", "STOPPING");
    private static final int MAX_RUNS = 1000;
    private static final int MAX_GROUPS = 20;
    private static final int MAX_GROUP_RESOURCES = 10;
    private static final DateTimeFormatter RUN_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/mm-ss-SSS")
            .withZone(ZoneOffset.UTC);

    private final AccountAwareStorageBackend<ObjectNode> canaries;
    private final AccountAwareStorageBackend<ObjectNode> groups;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final SyntheticsEngine engine;
    private final Provider<S3Service> s3;
    private final Provider<EventBridgeService> events;
    private final Provider<CloudWatchMetricsService> metrics;
    private final Provider<ServiceRoleAuthorizer> authorizer;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "synthetics-canaries");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public SyntheticsService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config,
                             SyntheticsEngine engine, Provider<S3Service> s3, Provider<EventBridgeService> events,
                             Provider<CloudWatchMetricsService> metrics,
                             Provider<ServiceRoleAuthorizer> authorizer) {
        this.canaries = storageFactory.create("synthetics", "canaries.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.groups = storageFactory.create("synthetics", "groups.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
        this.engine = engine;
        this.s3 = s3;
        this.events = events;
        this.metrics = metrics;
        this.authorizer = authorizer;
    }

    /** Started canaries keep running across a restart; a transition that was under way finishes. */
    void onStart(@Observes StartupEvent ignored) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : canaries.scanAllAccountEntries(k -> true)) {
            String region = entry.key().substring(0, entry.key().indexOf('/'));
            String name = entry.value().path("Name").asText();
            String state = entry.value().path("Status").path("State").asText();
            if ("RUNNING".equals(state)) {
                schedule(entry.accountId(), region, name, 0);
            } else if (BUSY.contains(state)) {
                settleLater(entry.accountId(), region, name);
            }
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        timers.values().forEach(timer -> timer.cancel(false));
        timers.clear();
        canaries.clear();
        groups.clear();
    }

    public ObjectNode handle(String op, JsonNode in, String region) {
        return switch (op) {
            case "CreateCanary" -> createCanary(in, region);
            case "GetCanary" -> getCanary(in, region);
            case "DescribeCanaries" -> describeCanaries(in, region);
            case "UpdateCanary" -> updateCanary(in, region);
            case "DeleteCanary" -> deleteCanary(in, region);
            case "StartCanary" -> startCanary(in, region);
            case "StopCanary" -> stopCanary(in, region);
            case "StartCanaryDryRun" -> startDryRun(in, region);
            case "GetCanaryRuns" -> getCanaryRuns(in, region);
            case "DescribeCanariesLastRun" -> describeLastRuns(in, region);
            case "DescribeRuntimeVersions" -> describeRuntimeVersions(in);
            case "CreateGroup" -> createGroup(in, region);
            case "GetGroup" -> wrap("Group", groupView(group(in.path("GroupIdentifier").asText()), true));
            case "DeleteGroup" -> deleteGroup(in);
            case "ListGroups" -> listGroups(in);
            case "AssociateResource" -> associate(in, region);
            case "DisassociateResource" -> disassociate(in);
            case "ListGroupResources" -> listGroupResources(in);
            case "ListAssociatedGroups" -> listAssociatedGroups(in, region);
            case "TagResource" -> tagResource(in, region);
            case "UntagResource" -> untagResource(in, region);
            case "ListTagsForResource" -> wrap("Tags", taggable(in.path("ResourceArn").asText(), region)
                    .path("Tags"));
            default -> throw new AwsException("ValidationException", "Unsupported operation " + op, 400);
        };
    }

    // ---------- canaries ----------

    private ObjectNode createCanary(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        SyntheticsRuntimes.Version runtime = runtime(in.path("RuntimeVersion").asText());
        JsonNode schedule = in.path("Schedule");
        long frequency = checkSchedule(schedule);
        String handler = in.path("Code").path("Handler").asText("");
        if (handler.isEmpty()) {
            throw validation("Code.Handler is required.");
        }
        artifactLocation(in.path("ArtifactS3Location").asText());
        if (in.has("ResourcesToReplicateTags") && in.path("Tags").isEmpty()) {
            throw validation("ResourcesToReplicateTags requires Tags.");
        }
        byte[] code = engine.readCode(in.path("Code"));
        engine.checkCode(code, handler, runtime);
        synchronized (lock(account, region, name)) {
            if (canaries.get(key(region, name)).isPresent()) {
                throw validation("A canary named " + name + " already exists.");
            }
            Instant now = Instant.now();
            String id = UUID.randomUUID().toString();
            ObjectNode canary = mapper.createObjectNode();
            canary.put("Id", id).put("Name", name);
            canary.putObject("Code").put("Handler", handler);
            canary.put("ExecutionRoleArn", in.path("ExecutionRoleArn").asText());
            canary.set("Schedule", scheduleOut(schedule));
            canary.set("RunConfig", runConfigOut(in.path("RunConfig"), frequency, null));
            canary.put("SuccessRetentionPeriodInDays", in.path("SuccessRetentionPeriodInDays").asInt(31));
            canary.put("FailureRetentionPeriodInDays", in.path("FailureRetentionPeriodInDays").asInt(31));
            setStatus(canary, "CREATING", null, "CREATE_IN_PROGRESS");
            canary.putObject("Timeline").put("Created", seconds(now)).put("LastModified", seconds(now));
            canary.put("ArtifactS3Location", in.path("ArtifactS3Location").asText());
            canary.put("RuntimeVersion", runtime.name());
            canary.set("VpcConfig", vpcOut(in.path("VpcConfig")));
            canary.put("ProvisionedResourceCleanup", in.path("ProvisionedResourceCleanup").asText("AUTOMATIC"));
            canary.set("BrowserConfigs", browserConfigs(in.path("BrowserConfigs")));
            canary.set("Tags", in.path("Tags").isObject() ? in.get("Tags").deepCopy() : mapper.createObjectNode());
            if (in.path("ArtifactConfig").isObject()) {
                canary.set("ArtifactConfig", in.get("ArtifactConfig").deepCopy());
            }
            if (in.hasNonNull("KmsKeyArn")) {
                canary.put("KmsKeyArn", in.get("KmsKeyArn").asText());
            }
            ObjectNode meta = canary.putObject("_");
            meta.put("functionName", functionName(name, id));
            meta.set("environment", environment(in.path("RunConfig")));
            meta.put("replicateTags", replicateTags(in));
            meta.putArray("layerVersions");
            meta.putArray("runs");
            meta.putObject("dryRuns");
            // Build the layer and backing function now; the canary reports the outcome once its create settles.
            try {
                deployFunction(region, canary, code);
                meta.put("settleTo", "READY");
            } catch (AwsException e) {
                meta.put("settleTo", "ERROR").put("settleReason", e.getMessage()).put("settleCode", "CREATE_FAILED");
            }
            save(region, canary);
            statusEvent(region, canary, null);
            settleLater(account, region, name);
            return wrap("Canary", view(region, canary, null));
        }
    }

    /** Publishes the code layer and deploys the backing function for the canary as it is now configured. */
    private void deployFunction(String region, ObjectNode canary, byte[] code) {
        String account = canaries.accountId();
        SyntheticsRuntimes.Version runtime = runtime(canary.path("RuntimeVersion").asText());
        engine.roleRefusal(account, canary.path("ExecutionRoleArn").asText()).ifPresent(reason -> {
            throw validation(reason);
        });
        ObjectNode meta = meta(canary);
        String functionName = meta.path("functionName").asText();
        if (code != null) {
            SyntheticsEngine.Code published = engine.publishCode(region, functionName, code, runtime);
            ((ArrayNode) meta.withArray("layerVersions")).add(published.version());
            ((ObjectNode) canary.path("Code")).put("SourceLocationArn", published.layerVersionArn());
        }
        String engineArn = engine.deploy(region, spec(region, canary, functionName, runtime),
                new SyntheticsEngine.Code(canary.path("Code").path("SourceLocationArn").asText(), 0));
        canary.put("EngineArn", engineArn);
        ArrayNode engines = canary.putArray("EngineConfigs");
        engines.addObject().put("EngineArn", engineArn).put("BrowserType", "CHROME");
    }

    private SyntheticsEngine.Spec spec(String region, ObjectNode canary, String functionName,
                                       SyntheticsRuntimes.Version runtime) {
        JsonNode run = canary.path("RunConfig");
        Map<String, String> environment = new LinkedHashMap<>();
        meta(canary).path("environment").fields().forEachRemaining(e -> environment.put(e.getKey(),
                e.getValue().asText()));
        Map<String, String> tags = new LinkedHashMap<>();
        if (meta(canary).path("replicateTags").asBoolean()) {
            canary.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return new SyntheticsEngine.Spec(functionName, runtime, canary.path("Code").path("Handler").asText(),
                canary.path("ExecutionRoleArn").asText(), run.path("TimeoutInSeconds").asInt(840),
                run.path("MemoryInMB").asInt(1000),
                run.has("EphemeralStorage") ? run.get("EphemeralStorage").asInt() : null,
                run.path("ActiveTracing").asBoolean(false), environment,
                strings(canary.path("VpcConfig").path("SubnetIds")),
                strings(canary.path("VpcConfig").path("SecurityGroupIds")), tags,
                canary.path("Name").asText(), canaryArn(region, canary.path("Name").asText()));
    }

    private ObjectNode getCanary(JsonNode in, String region) {
        ObjectNode canary = canary(region, in.path("Name").asText());
        String dryRunId = in.hasNonNull("DryRunId") ? in.get("DryRunId").asText() : null;
        if (dryRunId != null && !meta(canary).path("dryRuns").has(dryRunId)) {
            throw notFound("The dry run " + dryRunId + " doesn't exist for canary " + canary.path("Name").asText()
                    + ".");
        }
        return wrap("Canary", view(region, canary, dryRunId));
    }

    private ObjectNode describeCanaries(JsonNode in, String region) {
        List<String> names = strings(in.path("Names"));
        if (names.size() > 5) {
            throw validation("Names can list at most 5 canaries.");
        }
        List<ObjectNode> all = canaries.scan(k -> k.startsWith(region + "/")).stream()
                .filter(c -> names.isEmpty() || names.contains(c.path("Name").asText()))
                .sorted(Comparator.comparing(c -> c.path("Name").asText())).toList();
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, 20, 20, c -> out.add(view(region, c, null)));
        return withToken(wrap("Canaries", out), next);
    }

    private ObjectNode updateCanary(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        synchronized (lock(account, region, name)) {
            ObjectNode canary = canary(region, name);
            String state = state(canary);
            if (BUSY.contains(state)) {
                throw conflict("The canary " + name + " is " + state + "; wait for it to finish before updating it.");
            }
            ObjectNode request = in.deepCopy();
            if (in.hasNonNull("DryRunId")) {
                JsonNode dryRun = meta(canary).path("dryRuns").path(in.get("DryRunId").asText());
                if (dryRun.isMissingNode()) {
                    throw notFound("The dry run " + in.get("DryRunId").asText() + " doesn't exist.");
                }
                if (!"PASSED".equals(dryRun.path("status").asText())) {
                    throw validation("The dry run " + in.get("DryRunId").asText()
                            + " hasn't passed, so its configuration can't be applied.");
                }
                dryRun.path("request").fields().forEachRemaining(e -> {
                    if (!request.has(e.getKey())) {
                        request.set(e.getKey(), e.getValue());
                    }
                });
            }
            ObjectNode previous = canary.deepCopy();
            ObjectNode updated = canary.deepCopy();
            byte[] code = apply(region, updated, request);
            boolean codeChanged = code != null || request.has("RuntimeVersion") || request.has("ExecutionRoleArn")
                    || request.has("RunConfig") || request.has("VpcConfig") || dryRunCode(canary, request) != null;
            if (code == null) {
                String dryRunLayer = dryRunCode(canary, request);
                if (dryRunLayer != null) {
                    ((ObjectNode) updated.path("Code")).put("SourceLocationArn", dryRunLayer);
                }
            }
            ObjectNode meta = meta(updated);
            try {
                if (codeChanged) {
                    deployFunction(region, updated, code);
                }
                meta.put("settleTo", state).put("settleCode", "UPDATE_COMPLETE");
                meta.remove("settleReason");
                ((ObjectNode) updated.path("Timeline")).put("LastModified", seconds(Instant.now()));
                setStatus(updated, "UPDATING", null, "UPDATE_IN_PROGRESS");
                save(region, updated);
                statusEvent(region, updated, previous);
            } catch (AwsException e) {
                // The update rolls back: the canary keeps its previous configuration.
                ObjectNode rolledBack = previous.deepCopy();
                meta(rolledBack).put("settleTo", state).put("settleCode", "ROLLBACK_COMPLETE")
                        .put("settleReason", e.getMessage());
                setStatus(rolledBack, "UPDATING", null, "UPDATE_IN_PROGRESS");
                save(region, rolledBack);
                statusEvent(region, rolledBack, previous);
            }
            settleLater(account, region, name);
            return mapper.createObjectNode();
        }
    }

    /** Applies an update request's fields to the canary; returns new code when the request carries some. */
    private byte[] apply(String region, ObjectNode canary, JsonNode in) {
        if (in.hasNonNull("RuntimeVersion")) {
            canary.put("RuntimeVersion", runtime(in.get("RuntimeVersion").asText()).name());
        }
        if (in.hasNonNull("ExecutionRoleArn")) {
            canary.put("ExecutionRoleArn", in.get("ExecutionRoleArn").asText());
        }
        long frequency = frequency(canary.path("Schedule").path("Expression").asText());
        if (in.path("Schedule").isObject()) {
            frequency = checkSchedule(in.get("Schedule"));
            canary.set("Schedule", scheduleOut(in.get("Schedule")));
        }
        if (in.path("RunConfig").isObject()) {
            canary.set("RunConfig", runConfigOut(in.get("RunConfig"), frequency, canary.path("RunConfig")));
            meta(canary).set("environment", environment(in.get("RunConfig")));
        }
        for (String days : List.of("SuccessRetentionPeriodInDays", "FailureRetentionPeriodInDays")) {
            if (in.has(days)) {
                canary.put(days, in.get(days).asInt());
            }
        }
        if (in.path("VpcConfig").isObject()) {
            canary.set("VpcConfig", vpcOut(in.get("VpcConfig")));
        }
        if (in.hasNonNull("ArtifactS3Location")) {
            artifactLocation(in.get("ArtifactS3Location").asText());
            canary.put("ArtifactS3Location", in.get("ArtifactS3Location").asText());
        }
        if (in.path("ArtifactConfig").isObject()) {
            canary.set("ArtifactConfig", in.get("ArtifactConfig").deepCopy());
        }
        if (in.hasNonNull("ProvisionedResourceCleanup")) {
            canary.put("ProvisionedResourceCleanup", in.get("ProvisionedResourceCleanup").asText());
        }
        if (in.path("BrowserConfigs").isArray()) {
            canary.set("BrowserConfigs", browserConfigs(in.get("BrowserConfigs")));
        }
        if (in.hasNonNull("KmsKeyArn")) {
            canary.put("KmsKeyArn", in.get("KmsKeyArn").asText());
        }
        if (!in.path("Code").isObject()) {
            return null;
        }
        JsonNode code = in.get("Code");
        String handler = code.path("Handler").asText(canary.path("Code").path("Handler").asText());
        ((ObjectNode) canary.path("Code")).put("Handler", handler);
        if (!code.hasNonNull("ZipFile") && !code.hasNonNull("S3Bucket")) {
            return null;
        }
        byte[] zip = engine.readCode(code);
        engine.checkCode(zip, handler, runtime(canary.path("RuntimeVersion").asText()));
        return zip;
    }

    private String dryRunCode(ObjectNode canary, JsonNode request) {
        if (!request.hasNonNull("DryRunId")) {
            return null;
        }
        JsonNode layer = meta(canary).path("dryRuns").path(request.get("DryRunId").asText()).path("layerArn");
        return layer.isTextual() ? layer.asText() : null;
    }

    private ObjectNode deleteCanary(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        synchronized (lock(account, region, name)) {
            ObjectNode canary = canary(region, name);
            String state = state(canary);
            if (BUSY.contains(state) || "RUNNING".equals(state)) {
                throw conflict("The canary " + name + " is " + state + "; stop it before deleting it.");
            }
            ObjectNode previous = canary.deepCopy();
            boolean deleteLambda = in.path("DeleteLambda").asBoolean(false)
                    || "AUTOMATIC".equals(canary.path("ProvisionedResourceCleanup").asText());
            meta(canary).put("settleTo", "DELETED").put("deleteLambda", deleteLambda);
            setStatus(canary, "DELETING", null, "DELETE_IN_PROGRESS");
            save(region, canary);
            statusEvent(region, canary, previous);
            settleLater(account, region, name);
            return mapper.createObjectNode();
        }
    }

    private ObjectNode startCanary(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        synchronized (lock(account, region, name)) {
            ObjectNode canary = canary(region, name);
            String state = state(canary);
            if (!Set.of("READY", "STOPPED", "ERROR").contains(state)
                    || "CREATE_FAILED".equals(canary.path("Status").path("StateReasonCode").asText())) {
                throw conflict("The canary " + name + " can't be started while it is " + state + ".");
            }
            ObjectNode previous = canary.deepCopy();
            meta(canary).put("settleTo", "RUNNING");
            setStatus(canary, "STARTING", null, null);
            save(region, canary);
            statusEvent(region, canary, previous);
            settleLater(account, region, name);
            return mapper.createObjectNode();
        }
    }

    private ObjectNode stopCanary(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        synchronized (lock(account, region, name)) {
            ObjectNode canary = canary(region, name);
            if (!"RUNNING".equals(state(canary))) {
                throw conflict("The canary " + name + " is not running.");
            }
            cancelTimer(account, region, name);
            ObjectNode previous = canary.deepCopy();
            meta(canary).put("settleTo", "STOPPED");
            setStatus(canary, "STOPPING", null, null);
            save(region, canary);
            statusEvent(region, canary, previous);
            settleLater(account, region, name);
            return mapper.createObjectNode();
        }
    }

    /** Finishes a canary's transition once its settle delay has passed. */
    private void settleLater(String account, String region, String name) {
        executor.schedule(() -> RequestScopes.runAs(account, region, () -> settle(account, region, name)),
                config.services().synthetics().transitionMillis(), TimeUnit.MILLISECONDS);
    }

    private void settle(String account, String region, String name) {
        try {
            synchronized (lock(account, region, name)) {
                Optional<ObjectNode> found = canaries.get(key(region, name));
                if (found.isEmpty() || !BUSY.contains(state(found.get()))) {
                    return;
                }
                ObjectNode canary = found.get();
                ObjectNode previous = canary.deepCopy();
                ObjectNode meta = meta(canary);
                String target = meta.path("settleTo").asText();
                Instant now = Instant.now();
                switch (target) {
                    case "DELETED" -> {
                        removeCanary(region, canary, meta.path("deleteLambda").asBoolean());
                        return;
                    }
                    case "RUNNING" -> {
                        ((ObjectNode) canary.path("Timeline")).put("LastStarted", seconds(now));
                        meta.put("startedAt", now.toEpochMilli());
                        setStatus(canary, "RUNNING", null, null);
                    }
                    case "STOPPED" -> {
                        ((ObjectNode) canary.path("Timeline")).put("LastStopped", seconds(now));
                        setStatus(canary, "STOPPED", null, null);
                    }
                    default -> setStatus(canary, target, meta.path("settleReason").asText(null),
                            meta.path("settleCode").asText(null));
                }
                meta.remove(List.of("settleTo", "settleReason", "settleCode"));
                save(region, canary);
                statusEvent(region, canary, previous);
                if ("RUNNING".equals(target)) {
                    schedule(account, region, name, 0);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synthetics could not settle canary {0}", name);
        }
    }

    private void removeCanary(String region, ObjectNode canary, boolean deleteLambda) {
        String name = canary.path("Name").asText();
        ObjectNode meta = meta(canary);
        if (deleteLambda) {
            String functionName = meta.path("functionName").asText();
            engine.deleteFunction(region, functionName);
            for (JsonNode version : meta.path("layerVersions")) {
                engine.deleteLayer(region, functionName, version.asLong());
            }
        }
        canaries.delete(key(region, name));
        String arn = canaryArn(region, name);
        for (ObjectNode group : groups.scan(k -> true)) {
            ArrayNode resources = (ArrayNode) group.withArray("/_/resources");
            for (Iterator<JsonNode> it = resources.iterator(); it.hasNext(); ) {
                if (arn.equals(it.next().asText())) {
                    it.remove();
                    groups.put(group.path("Id").asText(), group);
                }
            }
        }
    }

    // ---------- runs ----------

    /** Schedules the canary's next run {@code delayMillis} from now. */
    private void schedule(String account, String region, String name, long delayMillis) {
        String timerKey = account + "/" + region + "/" + name;
        ScheduledFuture<?> timer = executor.schedule(() -> RequestScopes.runAs(account, region,
                () -> scheduledRun(account, region, name)), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = timers.put(timerKey, timer);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelTimer(String account, String region, String name) {
        ScheduledFuture<?> timer = timers.remove(account + "/" + region + "/" + name);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private void scheduledRun(String account, String region, String name) {
        try {
            Instant scheduledAt = Instant.now();
            String scheduledRunId = UUID.randomUUID().toString();
            ObjectNode canary = canaries.get(key(region, name)).orElse(null);
            if (canary == null || !"RUNNING".equals(state(canary))) {
                return;
            }
            int maxRetries = canary.path("Schedule").path("RetryConfig").path("MaxRetries").asInt(0);
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (runOnce(region, name, scheduledRunId, attempt, null)) {
                    break;
                }
            }
            synchronized (lock(account, region, name)) {
                ObjectNode current = canaries.get(key(region, name)).orElse(null);
                if (current == null || !"RUNNING".equals(state(current))) {
                    return;
                }
                Optional<Instant> next = nextRun(current, scheduledAt);
                if (next.isEmpty()) {
                    ObjectNode previous = current.deepCopy();
                    ((ObjectNode) current.path("Timeline")).put("LastStopped", seconds(Instant.now()));
                    setStatus(current, "STOPPED", null, null);
                    save(region, current);
                    statusEvent(region, current, previous);
                    timers.remove(account + "/" + region + "/" + name);
                    return;
                }
                schedule(account, region, name, Instant.now().until(next.get(), ChronoUnit.MILLIS));
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synthetics could not run canary {0}", name);
        }
    }

    /** When the canary runs next, or empty when it is done: a one-time schedule, or its duration has passed. */
    private Optional<Instant> nextRun(ObjectNode canary, Instant scheduledAt) {
        String expression = canary.path("Schedule").path("Expression").asText();
        Instant next;
        if (expression.startsWith("cron(")) {
            next = SchedulerExpressionParser.nextCronFire(expression, Instant.now(), "UTC");
        } else {
            long frequency = frequency(expression);
            if (frequency == 0) {
                return Optional.empty();
            }
            next = scheduledAt.plusSeconds(frequency);
        }
        long duration = canary.path("Schedule").path("DurationInSeconds").asLong(0);
        if (duration > 0 && next != null) {
            Instant end = Instant.ofEpochMilli(meta(canary).path("startedAt").asLong()).plusSeconds(duration);
            if (next.isAfter(end)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(next);
    }

    /** One run, recorded, reported to S3, CloudWatch and EventBridge. Returns whether it passed. */
    private boolean runOnce(String region, String name, String scheduledRunId, int attempt, String dryRunId) {
        String account = canaries.accountId();
        String runId = UUID.randomUUID().toString();
        ObjectNode canary;
        ObjectNode run = mapper.createObjectNode();
        Instant started = Instant.now();
        synchronized (lock(account, region, name)) {
            canary = canary(region, name);
            run.put("Id", runId).put("ScheduledRunId", scheduledRunId);
            if (attempt > 0) {
                run.put("RetryAttempt", attempt);
            }
            run.put("Name", name);
            run.putObject("Status").put("State", "RUNNING");
            run.putObject("Timeline").put("Started", seconds(started));
            run.put("ArtifactS3Location", runLocation(region, artifacts(canary, dryRunId), name, started));
            run.put("BrowserType", "CHROME");
            if (dryRunId != null) {
                run.putObject("DryRunConfig").put("DryRunId", dryRunId);
                run.put("_type", "DRY_RUN");
            }
            run.put("Location", region);
            ((ArrayNode) meta(canary).withArray("runs")).insert(0, run);
            save(region, canary);
        }
        String engineArn = dryRunId == null ? canary.path("EngineArn").asText()
                : meta(canary).path("dryRuns").path(dryRunId).path("engineArn").asText();
        SyntheticsEngine.Outcome outcome = engine.invoke(region, engineArn, runId);
        Instant completed = Instant.now();
        String reason = outcome.reason();
        boolean passed = outcome.passed();
        String roleArn = dryRunId == null ? canary.path("ExecutionRoleArn").asText()
                : meta(canary).path("dryRuns").path(dryRunId).path("roleArn").asText();
        Optional<String> uploadFailure = upload(account, region, roleArn, run.path("ArtifactS3Location").asText(),
                canary, outcome, passed, started, completed);
        if (uploadFailure.isPresent()) {
            passed = false;
            reason = uploadFailure.get();
        }
        ObjectNode status = (ObjectNode) run.path("Status");
        status.put("State", passed ? "PASSED" : "FAILED").put("TestResult", passed ? "PASSED" : "FAILED");
        if (!passed) {
            status.put("StateReason", reason == null || reason.isBlank() ? "Canary failed" : truncate(reason))
                    .put("StateReasonCode", outcome.executionFailure() || uploadFailure.isPresent()
                            ? "EXECUTION_FAILURE" : "CANARY_FAILURE");
        }
        ((ObjectNode) run.path("Timeline")).put("Completed", seconds(completed))
                .put("MetricTimestampForRunAndRetries", seconds(started));
        synchronized (lock(account, region, name)) {
            Optional<ObjectNode> current = canaries.get(key(region, name));
            if (current.isPresent()) {
                ArrayNode runs = (ArrayNode) meta(current.get()).withArray("runs");
                for (int i = 0; i < runs.size(); i++) {
                    if (runId.equals(runs.get(i).path("Id").asText())) {
                        runs.set(i, run);
                    }
                }
                while (runs.size() > MAX_RUNS) {
                    runs.remove(runs.size() - 1);
                }
                if (dryRunId != null) {
                    ((ObjectNode) meta(current.get()).path("dryRuns").path(dryRunId))
                            .put("status", passed ? "PASSED" : "FAILED");
                }
                save(region, current.get());
            }
        }
        if (dryRunId == null) {
            publishMetrics(region, name, passed, started, completed);
        }
        runEvent(region, canary, run, passed);
        return passed;
    }

    /** Writes the run's report and log where the canary keeps its artifacts, as its execution role. */
    private Optional<String> upload(String account, String region, String roleArn, String location,
                                    ObjectNode canary, SyntheticsEngine.Outcome outcome, boolean passed,
                                    Instant started, Instant completed) {
        int slash = location.indexOf('/');
        String bucket = slash < 0 ? location : location.substring(0, slash);
        String prefix = slash < 0 ? "" : location.substring(slash + 1) + "/";
        ObjectNode report = mapper.createObjectNode();
        report.put("canaryName", canary.path("Name").asText());
        report.put("canaryArn", canaryArn(region, canary.path("Name").asText()));
        report.put("runtimeVersion", canary.path("RuntimeVersion").asText());
        report.put("startTime", outcome.startTime() != null ? outcome.startTime() : started.toString());
        report.put("endTime", outcome.endTime() != null ? outcome.endTime() : completed.toString());
        report.put("status", passed ? "PASSED" : "FAILED");
        if (outcome.reason() != null) {
            report.put("failureReason", outcome.reason());
        }
        report.set("stepsReport", outcome.steps());
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(prefix + "SyntheticsReport-" + (passed ? "PASSED" : "FAILED") + ".json",
                report.toPrettyString().getBytes(StandardCharsets.UTF_8));
        files.put(prefix + "log.txt", outcome.log().getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String objectArn = AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "s3", "",
                    bucket + "/" + file.getKey()).toString();
            Optional<String> refusal = authorizer.get().refusal(roleArn, account, ServicePrincipals.of("lambda"),
                    null, List.of(new ServiceRoleAuthorizer.Permission("s3:PutObject", objectArn)));
            if (refusal.isPresent()) {
                return Optional.of("Unable to upload the canary artifacts to S3: " + refusal.get());
            }
            try {
                s3.get().putObject(bucket, file.getKey(), file.getValue(),
                        file.getKey().endsWith(".json") ? "application/json" : "text/plain", Map.of());
            } catch (AwsException e) {
                return Optional.of("Unable to upload the canary artifacts to S3: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private void publishMetrics(String region, String name, boolean passed, Instant started, Instant completed) {
        try {
            List<Dimension> dimensions = List.of(new Dimension("CanaryName", name));
            long at = started.toEpochMilli();
            metrics.get().putMetricData("CloudWatchSynthetics", List.of(
                    MetricDatum.of("SuccessPercent", "Percent", passed ? 100 : 0, dimensions, at),
                    MetricDatum.of("Duration", "Milliseconds", started.until(completed, ChronoUnit.MILLIS),
                            dimensions, at),
                    MetricDatum.of("Failed", "Count", passed ? 0 : 1, dimensions, at)), region);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Synthetics could not publish metrics for canary {0}", name);
        }
    }

    private ObjectNode getCanaryRuns(JsonNode in, String region) {
        ObjectNode canary = canary(region, in.path("Name").asText());
        String dryRunId = in.hasNonNull("DryRunId") ? in.get("DryRunId").asText() : null;
        String type = dryRunId != null ? "DRY_RUN" : in.path("RunType").asText("CANARY_RUN");
        List<ObjectNode> runs = new ArrayList<>();
        for (JsonNode run : retained(canary)) {
            boolean dry = "DRY_RUN".equals(run.path("_type").asText());
            if (dry != "DRY_RUN".equals(type)) {
                continue;
            }
            if (dryRunId != null && !dryRunId.equals(run.path("DryRunConfig").path("DryRunId").asText())) {
                continue;
            }
            runs.add((ObjectNode) run);
        }
        ArrayNode out = mapper.createArrayNode();
        String next = page(runs, in, 100, 100, run -> out.add(runView(run)));
        return withToken(wrap("CanaryRuns", out), next);
    }

    private ObjectNode describeLastRuns(JsonNode in, String region) {
        List<String> names = strings(in.path("Names"));
        if (names.size() > 5) {
            throw validation("Names can list at most 5 canaries.");
        }
        List<ObjectNode> withRuns = new ArrayList<>();
        for (ObjectNode canary : canaries.scan(k -> k.startsWith(region + "/"))) {
            if (!names.isEmpty() && !names.contains(canary.path("Name").asText())) {
                continue;
            }
            for (JsonNode run : retained(canary)) {
                if (!"DRY_RUN".equals(run.path("_type").asText())) {
                    ObjectNode last = mapper.createObjectNode().put("CanaryName", canary.path("Name").asText());
                    last.set("LastRun", runView(run));
                    withRuns.add(last);
                    break;
                }
            }
        }
        withRuns.sort(Comparator.comparing(r -> r.path("CanaryName").asText()));
        ArrayNode out = mapper.createArrayNode();
        String next = page(withRuns, in, 100, 100, out::add);
        return withToken(wrap("CanariesLastRun", out), next);
    }

    /** The canary's runs, newest first, without those past its retention periods. */
    private List<JsonNode> retained(ObjectNode canary) {
        double now = seconds(Instant.now());
        double passed = canary.path("SuccessRetentionPeriodInDays").asInt(31) * 86400.0;
        double failed = canary.path("FailureRetentionPeriodInDays").asInt(31) * 86400.0;
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode run : meta(canary).path("runs")) {
            String state = run.path("Status").path("State").asText();
            double age = now - run.path("Timeline").path("Started").asDouble();
            if (("PASSED".equals(state) && age > passed) || ("FAILED".equals(state) && age > failed)) {
                continue;
            }
            out.add(run);
        }
        return out;
    }

    private ObjectNode runView(JsonNode run) {
        ObjectNode view = ((ObjectNode) run).deepCopy();
        view.remove("_type");
        return view;
    }

    // ---------- dry runs ----------

    private ObjectNode startDryRun(JsonNode in, String region) {
        String name = in.path("Name").asText();
        String account = canaries.accountId();
        String dryRunId = UUID.randomUUID().toString();
        synchronized (lock(account, region, name)) {
            ObjectNode canary = canary(region, name);
            String state = state(canary);
            if (BUSY.contains(state)) {
                throw conflict("The canary " + name + " is " + state + "; wait for it before a dry run.");
            }
            for (JsonNode dryRun : meta(canary).path("dryRuns")) {
                if ("RUNNING".equals(dryRun.path("status").asText())) {
                    throw conflict("A dry run of canary " + name + " is already running.");
                }
            }
            ObjectNode trial = canary.deepCopy();
            ObjectNode request = in.deepCopy();
            request.remove(List.of("Name", "VisualReference", "VisualReferences"));
            byte[] code = apply(region, trial, request);
            String functionName = functionName(name, dryRunId);
            SyntheticsRuntimes.Version runtime = runtime(trial.path("RuntimeVersion").asText());
            engine.roleRefusal(account, trial.path("ExecutionRoleArn").asText()).ifPresent(reason -> {
                throw new AwsException("AccessDeniedException", reason, 403);
            });
            String layerArn = trial.path("Code").path("SourceLocationArn").asText();
            if (code != null) {
                SyntheticsEngine.Code published = engine.publishCode(region, meta(canary).path("functionName")
                        .asText(), code, runtime);
                ((ArrayNode) meta(canary).withArray("layerVersions")).add(published.version());
                layerArn = published.layerVersionArn();
                ((ObjectNode) trial.path("Code")).put("SourceLocationArn", layerArn);
            }
            String engineArn = engine.deploy(region, spec(region, trial, functionName, runtime),
                    new SyntheticsEngine.Code(layerArn, 0));
            ObjectNode dryRun = ((ObjectNode) meta(canary).path("dryRuns")).putObject(dryRunId);
            dryRun.put("status", "RUNNING").put("engineArn", engineArn).put("functionName", functionName)
                    .put("roleArn", trial.path("ExecutionRoleArn").asText())
                    .put("artifacts", trial.path("ArtifactS3Location").asText());
            if (code != null) {
                dryRun.put("layerArn", layerArn);
            }
            dryRun.set("request", request);
            dryRun.set("view", trial.deepCopy());
            canary.putObject("DryRunConfig").put("DryRunId", dryRunId).put("LastDryRunExecutionStatus", "RUNNING");
            save(region, canary);
        }
        executor.execute(() -> RequestScopes.runAs(account, region, () -> {
            try {
                boolean passed = runOnce(region, name, UUID.randomUUID().toString(), 0, dryRunId);
                synchronized (lock(account, region, name)) {
                    canaries.get(key(region, name)).ifPresent(current -> {
                        ((ObjectNode) current.path("DryRunConfig")).put("LastDryRunExecutionStatus",
                                passed ? "PASSED" : "FAILED");
                        engine.deleteFunction(region, meta(current).path("dryRuns").path(dryRunId)
                                .path("functionName").asText());
                        save(region, current);
                    });
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "Synthetics dry run {0} of canary {1} failed", dryRunId, name);
            }
        }));
        ObjectNode out = mapper.createObjectNode();
        out.putObject("DryRunConfig").put("DryRunId", dryRunId).put("LastDryRunExecutionStatus", "RUNNING");
        return out;
    }

    // ---------- runtime versions ----------

    private ObjectNode describeRuntimeVersions(JsonNode in) {
        ArrayNode out = mapper.createArrayNode();
        String next = page(SyntheticsRuntimes.all(), in, 100, 100, version -> {
            ObjectNode node = out.addObject().put("VersionName", version.name())
                    .put("Description", version.description());
            version.deprecationDate().ifPresent(date -> node.put("DeprecationDate", seconds(date)));
        });
        return withToken(wrap("RuntimeVersions", out), next);
    }

    // ---------- groups ----------

    private ObjectNode createGroup(JsonNode in, String region) {
        String name = in.path("Name").asText();
        synchronized (groups) {
            List<ObjectNode> all = groups.scan(k -> true);
            if (all.stream().anyMatch(g -> g.path("Name").asText().equalsIgnoreCase(name))) {
                throw conflict("A group named " + name + " already exists.");
            }
            if (all.size() >= MAX_GROUPS) {
                throw new AwsException("ServiceQuotaExceededException",
                        "An account can have at most " + MAX_GROUPS + " groups.", 402);
            }
            String id = randomId();
            Instant now = Instant.now();
            ObjectNode group = mapper.createObjectNode();
            group.put("Id", id).put("Name", name)
                    .put("Arn", AwsArnUtils.Arn.of("synthetics", region, groups.accountId(), "group:" + id)
                            .toString());
            group.set("Tags", in.path("Tags").isObject() ? in.get("Tags").deepCopy() : mapper.createObjectNode());
            group.put("CreatedTime", seconds(now)).put("LastModifiedTime", seconds(now));
            group.putObject("_").putArray("resources");
            groups.put(id, group);
            return wrap("Group", groupView(group, true));
        }
    }

    private ObjectNode group(String identifier) {
        for (ObjectNode group : groups.scan(k -> true)) {
            if (identifier.equals(group.path("Id").asText()) || identifier.equals(group.path("Arn").asText())
                    || identifier.equalsIgnoreCase(group.path("Name").asText())) {
                return group;
            }
        }
        throw notFound("The group " + identifier + " doesn't exist.");
    }

    private ObjectNode groupView(ObjectNode group, boolean full) {
        ObjectNode view = mapper.createObjectNode().put("Id", group.path("Id").asText())
                .put("Name", group.path("Name").asText()).put("Arn", group.path("Arn").asText());
        if (full) {
            view.set("Tags", group.path("Tags"));
            view.set("CreatedTime", group.path("CreatedTime"));
            view.set("LastModifiedTime", group.path("LastModifiedTime"));
        }
        return view;
    }

    private ObjectNode deleteGroup(JsonNode in) {
        synchronized (groups) {
            groups.delete(group(in.path("GroupIdentifier").asText()).path("Id").asText());
        }
        return mapper.createObjectNode();
    }

    private ObjectNode listGroups(JsonNode in) {
        List<ObjectNode> all = new ArrayList<>(groups.scan(k -> true));
        all.sort(Comparator.comparing(g -> g.path("Name").asText()));
        ArrayNode out = mapper.createArrayNode();
        String next = page(all, in, 20, 20, g -> out.add(groupView(g, false)));
        return withToken(wrap("Groups", out), next);
    }

    private ObjectNode associate(JsonNode in, String region) {
        String arn = in.path("ResourceArn").asText();
        ObjectNode canary = canaryByArn(arn, region);
        String state = state(canary);
        if ("CREATING".equals(state) || "DELETING".equals(state)) {
            throw conflict("The canary " + canary.path("Name").asText() + " is " + state + ".");
        }
        synchronized (groups) {
            ObjectNode group = group(in.path("GroupIdentifier").asText());
            ArrayNode resources = (ArrayNode) group.withArray("/_/resources");
            for (JsonNode member : resources) {
                if (arn.equals(member.asText())) {
                    return mapper.createObjectNode();
                }
            }
            if (resources.size() >= MAX_GROUP_RESOURCES) {
                throw new AwsException("ServiceQuotaExceededException",
                        "A group can hold at most " + MAX_GROUP_RESOURCES + " canaries.", 402);
            }
            resources.add(arn);
            group.put("LastModifiedTime", seconds(Instant.now()));
            groups.put(group.path("Id").asText(), group);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode disassociate(JsonNode in) {
        String arn = in.path("ResourceArn").asText();
        synchronized (groups) {
            ObjectNode group = group(in.path("GroupIdentifier").asText());
            ArrayNode resources = (ArrayNode) group.withArray("/_/resources");
            for (Iterator<JsonNode> it = resources.iterator(); it.hasNext(); ) {
                if (arn.equals(it.next().asText())) {
                    it.remove();
                    group.put("LastModifiedTime", seconds(Instant.now()));
                    groups.put(group.path("Id").asText(), group);
                    return mapper.createObjectNode();
                }
            }
        }
        throw notFound("The canary " + arn + " isn't in the group.");
    }

    private ObjectNode listGroupResources(JsonNode in) {
        ObjectNode group = group(in.path("GroupIdentifier").asText());
        List<JsonNode> members = new ArrayList<>();
        group.path("_").path("resources").forEach(members::add);
        ArrayNode out = mapper.createArrayNode();
        String next = page(members, in, 20, 20, out::add);
        return withToken(wrap("Resources", out), next);
    }

    private ObjectNode listAssociatedGroups(JsonNode in, String region) {
        String arn = in.path("ResourceArn").asText();
        canaryByArn(arn, region);
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode group : groups.scan(k -> true)) {
            for (JsonNode member : group.path("_").path("resources")) {
                if (arn.equals(member.asText())) {
                    found.add(group);
                }
            }
        }
        found.sort(Comparator.comparing(g -> g.path("Name").asText()));
        ArrayNode out = mapper.createArrayNode();
        String next = page(found, in, 20, 20, g -> out.add(groupView(g, false)));
        return withToken(wrap("Groups", out), next);
    }

    // ---------- tags ----------

    private ObjectNode tagResource(JsonNode in, String region) {
        String arn = in.path("ResourceArn").asText();
        mutateTags(arn, region, tags -> in.path("Tags").fields()
                .forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText())));
        return mapper.createObjectNode();
    }

    private ObjectNode untagResource(JsonNode in, String region) {
        String arn = in.path("ResourceArn").asText();
        List<String> keys = strings(in.path("TagKeys"));
        mutateTags(arn, region, tags -> keys.forEach(tags::remove));
        return mapper.createObjectNode();
    }

    private void mutateTags(String arn, String region, Consumer<ObjectNode> change) {
        ObjectNode resource = taggable(arn, region);
        if (resource.has("Arn")) {
            synchronized (groups) {
                ObjectNode group = group(arn);
                change.accept((ObjectNode) group.withObject("/Tags"));
                if (group.path("Tags").size() > 50) {
                    throw new AwsException("BadRequestException", "A resource can have at most 50 tags.", 400);
                }
                groups.put(group.path("Id").asText(), group);
            }
            return;
        }
        String name = resource.path("Name").asText();
        String canaryRegion = AwsArnUtils.parse(arn).region();
        synchronized (lock(canaries.accountId(), canaryRegion, name)) {
            ObjectNode canary = canary(canaryRegion, name);
            change.accept((ObjectNode) canary.withObject("/Tags"));
            if (canary.path("Tags").size() > 50) {
                throw new AwsException("BadRequestException", "A resource can have at most 50 tags.", 400);
            }
            save(canaryRegion, canary);
        }
    }

    /** The canary or group a tagging ARN names. */
    private ObjectNode taggable(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "synthetics")) {
            throw new AwsException("BadRequestException", "The ARN " + arn + " is not a Synthetics resource.", 400);
        }
        String resource = AwsArnUtils.parse(arn).resource();
        try {
            if (resource.startsWith("group:")) {
                return group(arn);
            }
            if (resource.startsWith("canary:")) {
                return canaryByArn(arn, region);
            }
        } catch (AwsException e) {
            throw new AwsException("NotFoundException", "The resource " + arn + " doesn't exist.", 404);
        }
        throw new AwsException("BadRequestException", "The ARN " + arn + " is not a canary or group.", 400);
    }

    // ---------- events ----------

    private void statusEvent(String region, ObjectNode canary, ObjectNode previous) {
        String account = canaries.accountId();
        ObjectNode detail = mapper.createObjectNode();
        detail.put("account-id", account).put("canary-id", canary.path("Id").asText())
                .put("canary-name", canary.path("Name").asText())
                .put("current-state", state(canary));
        if (previous != null) {
            detail.put("previous-state", state(previous));
        }
        detail.put("source-location", canary.path("Code").path("SourceLocationArn").asText("NULL"))
                .put("updated-on", seconds(Instant.now()));
        ObjectNode changed = detail.putObject("changed-config");
        if (previous != null) {
            changes(changed, "executionArn", previous.path("EngineArn"), canary.path("EngineArn"));
            changes(changed, "testCodeLayerVersionArn", previous.path("Code").path("SourceLocationArn"),
                    canary.path("Code").path("SourceLocationArn"));
        }
        if (canary.path("Status").hasNonNull("StateReason")) {
            detail.put("state-reason", canary.path("Status").path("StateReason").asText());
        }
        detail.put("message", "Canary status has changed");
        events.get().publishServiceEvent("aws.synthetics", "Synthetics Canary Status Change", detail.toString(),
                canaryArn(region, canary.path("Name").asText()), region);
    }

    private static void changes(ObjectNode changed, String key, JsonNode before, JsonNode after) {
        if (!before.asText("").equals(after.asText(""))) {
            changed.putObject(key).put("previous-value", before.asText(null)).put("current-value", after.asText(null));
        }
    }

    private void runEvent(String region, ObjectNode canary, ObjectNode run, boolean passed) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("account-id", canaries.accountId()).put("canary-id", canary.path("Id").asText())
                .put("canary-name", canary.path("Name").asText()).put("canary-run-id", run.path("Id").asText())
                .put("artifact-location", run.path("ArtifactS3Location").asText())
                .put("test-run-status", passed ? "PASSED" : "FAILED");
        if (passed) {
            detail.putNull("state-reason");
        } else {
            detail.put("state-reason", run.path("Status").path("StateReason").asText());
        }
        detail.putObject("canary-run-timeline").put("started", run.path("Timeline").path("Started").asDouble())
                .put("completed", run.path("Timeline").path("Completed").asDouble());
        detail.put("message", passed ? "Test run result is generated successfully" : "Test run failed");
        events.get().publishServiceEvent("aws.synthetics",
                passed ? "Synthetics Canary TestRun Successful" : "Synthetics Canary TestRun Failure",
                detail.toString(), canaryArn(region, canary.path("Name").asText()), region);
    }

    // ---------- validation ----------

    private static SyntheticsRuntimes.Version runtime(String name) {
        SyntheticsRuntimes.Version version = SyntheticsRuntimes.find(name)
                .orElseThrow(() -> validation("The runtime version " + name + " is not valid."));
        if (version.deprecated() != null || version.lambdaRuntime() == null) {
            throw validation("The runtime version " + name + " is deprecated.");
        }
        return version;
    }

    /** Validates a schedule and returns its frequency in seconds (0 for a single run or a cron schedule). */
    private static long checkSchedule(JsonNode schedule) {
        String expression = schedule.path("Expression").asText();
        if (expression.startsWith("cron(")) {
            try {
                SchedulerExpressionParser.validate(expression, "UTC");
            } catch (AwsException e) {
                throw validation("The schedule expression " + expression + " is not valid: " + e.getMessage());
            }
            return 0;
        }
        Matcher rate = RATE.matcher(expression);
        if (!rate.matches()) {
            throw validation("The schedule expression " + expression + " is not valid. Use rate(number unit) or "
                    + "cron(expression).");
        }
        long frequency = frequency(expression);
        if (frequency != 0 && (frequency < 60 || frequency > 3600)) {
            throw validation("A canary's rate must be between rate(1 minute) and rate(1 hour), or rate(0 minute).");
        }
        return frequency;
    }

    private static long frequency(String expression) {
        Matcher rate = RATE.matcher(expression);
        if (!rate.matches()) {
            return 0;
        }
        long value = Long.parseLong(rate.group(1));
        return rate.group(2).startsWith("hour") ? value * 3600 : value * 60;
    }

    private ObjectNode scheduleOut(JsonNode in) {
        ObjectNode out = mapper.createObjectNode().put("Expression", in.path("Expression").asText())
                .put("DurationInSeconds", in.path("DurationInSeconds").asLong(0));
        out.putObject("RetryConfig").put("MaxRetries", in.path("RetryConfig").path("MaxRetries").asInt(0));
        return out;
    }

    private ObjectNode runConfigOut(JsonNode in, long frequency, JsonNode current) {
        int defaultTimeout = frequency == 0 ? 840 : (int) Math.min(frequency, 840);
        int timeout = in.has("TimeoutInSeconds") ? in.get("TimeoutInSeconds").asInt()
                : current != null ? current.path("TimeoutInSeconds").asInt(defaultTimeout) : defaultTimeout;
        if (frequency > 0 && timeout > frequency) {
            throw validation("TimeoutInSeconds can't be longer than the canary's frequency (" + frequency + "s).");
        }
        int memory = in.has("MemoryInMB") ? in.get("MemoryInMB").asInt()
                : current != null ? current.path("MemoryInMB").asInt(1000) : 1000;
        if (in.has("MemoryInMB") && memory % 64 != 0) {
            throw validation("MemoryInMB must be a multiple of 64.");
        }
        ObjectNode out = mapper.createObjectNode().put("TimeoutInSeconds", timeout).put("MemoryInMB", memory)
                .put("ActiveTracing", in.has("ActiveTracing") ? in.get("ActiveTracing").asBoolean()
                        : current != null && current.path("ActiveTracing").asBoolean(false));
        int storage = in.has("EphemeralStorage") ? in.get("EphemeralStorage").asInt()
                : current != null ? current.path("EphemeralStorage").asInt(1024) : 1024;
        out.put("EphemeralStorage", storage);
        return out;
    }

    private ObjectNode environment(JsonNode runConfig) {
        ObjectNode out = mapper.createObjectNode();
        runConfig.path("EnvironmentVariables").fields().forEachRemaining(e -> {
            if (e.getKey().startsWith("AWS_") || e.getKey().startsWith("SYNTHETICS_")) {
                throw validation("The environment variable " + e.getKey() + " is reserved.");
            }
            out.put(e.getKey(), e.getValue().asText());
        });
        return out;
    }

    private ObjectNode vpcOut(JsonNode in) {
        ObjectNode out = mapper.createObjectNode();
        if (in.isObject() && in.path("SubnetIds").size() > 0) {
            out.set("SubnetIds", in.path("SubnetIds").deepCopy());
            out.set("SecurityGroupIds", in.path("SecurityGroupIds").isArray()
                    ? in.get("SecurityGroupIds").deepCopy() : mapper.createArrayNode());
            if (in.has("Ipv6AllowedForDualStack")) {
                out.put("Ipv6AllowedForDualStack", in.get("Ipv6AllowedForDualStack").asBoolean());
            }
        }
        return out;
    }

    private ArrayNode browserConfigs(JsonNode in) {
        ArrayNode out = mapper.createArrayNode();
        if (in.isArray() && !in.isEmpty()) {
            in.forEach(b -> out.addObject().put("BrowserType", b.path("BrowserType").asText("CHROME")));
        } else {
            out.addObject().put("BrowserType", "CHROME");
        }
        return out;
    }

    private static boolean replicateTags(JsonNode in) {
        for (JsonNode value : in.path("ResourcesToReplicateTags")) {
            if ("lambda-function".equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    /** {@code s3://bucket/prefix} or {@code bucket/prefix}, as {@code bucket/prefix}. */
    private static String artifactLocation(String location) {
        String trimmed = location.startsWith("s3://") ? location.substring(5) : location;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String bucket = trimmed.contains("/") ? trimmed.substring(0, trimmed.indexOf('/')) : trimmed;
        if (!bucket.matches("[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]")) {
            throw validation("ArtifactS3Location " + location + " must be s3://bucket-name/optional-prefix.");
        }
        return trimmed;
    }

    private String artifacts(ObjectNode canary, String dryRunId) {
        String location = dryRunId == null ? canary.path("ArtifactS3Location").asText()
                : meta(canary).path("dryRuns").path(dryRunId).path("artifacts").asText();
        return artifactLocation(location);
    }

    private static String runLocation(String region, String artifacts, String name, Instant started) {
        return artifacts + "/canary/" + region + "/" + name + "/" + RUN_PATH.format(started);
    }

    // ---------- helpers ----------

    private ObjectNode view(String region, ObjectNode canary, String dryRunId) {
        ObjectNode source = dryRunId == null ? canary
                : (ObjectNode) meta(canary).path("dryRuns").path(dryRunId).path("view");
        ObjectNode view = source.deepCopy();
        view.remove("_");
        if (dryRunId != null) {
            view.set("Status", canary.path("Status"));
            view.put("EngineArn", meta(canary).path("dryRuns").path(dryRunId).path("engineArn").asText());
            view.putObject("DryRunConfig").put("DryRunId", dryRunId).put("LastDryRunExecutionStatus",
                    meta(canary).path("dryRuns").path(dryRunId).path("status").asText());
        }
        if (view.path("VpcConfig").isEmpty()) {
            view.remove("VpcConfig");
        }
        if (view.path("Tags").isEmpty()) {
            view.remove("Tags");
        }
        return view;
    }

    private ObjectNode canary(String region, String name) {
        return canaries.get(key(region, name))
                .orElseThrow(() -> notFound("Canary " + name + " does not exist."));
    }

    private ObjectNode canaryByArn(String arn, String region) {
        Optional<AwsArnUtils.Arn> parsed = AwsArnUtils.resourceIfArnFor(arn, "synthetics").isPresent()
                ? Optional.of(AwsArnUtils.parse(arn)) : Optional.empty();
        if (parsed.isEmpty() || !parsed.get().resource().startsWith("canary:")
                || !parsed.get().accountId().equals(canaries.accountId())) {
            throw notFound("The canary " + arn + " doesn't exist.");
        }
        String canaryRegion = parsed.get().region().isEmpty() ? region : parsed.get().region();
        return canaries.get(key(canaryRegion, parsed.get().resource().substring("canary:".length())))
                .orElseThrow(() -> notFound("The canary " + arn + " doesn't exist."));
    }

    private void save(String region, ObjectNode canary) {
        canaries.put(key(region, canary.path("Name").asText()), canary);
    }

    private static ObjectNode meta(ObjectNode canary) {
        return (ObjectNode) canary.withObject("/_");
    }

    private static String state(ObjectNode canary) {
        return canary.path("Status").path("State").asText();
    }

    private static void setStatus(ObjectNode canary, String state, String reason, String code) {
        ObjectNode status = canary.putObject("Status").put("State", state);
        if (reason != null) {
            status.put("StateReason", truncate(reason));
        }
        if (code != null) {
            status.put("StateReasonCode", code);
        }
    }

    private String canaryArn(String region, String name) {
        return AwsArnUtils.Arn.of("synthetics", region, canaries.accountId(), "canary:" + name).toString();
    }

    /** The backing function's name, {@code cwsyn-<name>-<id>}, within Lambda's 64 characters. */
    private static String functionName(String name, String id) {
        return "cwsyn-" + (name.length() > 21 ? name.substring(0, 21) : name) + "-" + id;
    }

    private Object lock(String account, String region, String name) {
        return locks.computeIfAbsent(account + "/" + region + "/" + name, k -> new Object());
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static double seconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String truncate(String text) {
        return text.length() > 1024 ? text.substring(0, 1024) : text;
    }

    private static String randomId() {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    /** Emits one page of {@code items}; returns the next token, or null on the last page. */
    private static <T> String page(List<T> items, JsonNode in, int defaultSize, int maxSize, Consumer<T> emit) {
        int size = in.has("MaxResults") ? in.get("MaxResults").asInt() : defaultSize;
        if (size < 1 || size > maxSize) {
            throw validation("MaxResults must be between 1 and " + maxSize + ".");
        }
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
        }
        int end = Math.min(items.size(), start + size);
        for (int i = Math.min(start, items.size()); i < end; i++) {
            emit.accept(items.get(i));
        }
        return end < items.size()
                ? Base64.getEncoder().encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)) : null;
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

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
