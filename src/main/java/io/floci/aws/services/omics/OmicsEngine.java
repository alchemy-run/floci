package io.floci.aws.services.omics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs HealthOmics workflows on their languages' engines, one container per run: Cromwell for WDL, Nextflow for
 * Nextflow, cwltool for CWL. Floci runs each task's commands in the engine's container, not in the task's image.
 * Parameters that are {@code s3://} URIs are staged into the run; outputs are written under the run's output URI
 * ({@code out/} for files, {@code logs/outputs.json} for values), the engine's log goes to CloudWatch Logs
 * ({@code /aws/omics/WorkflowLog}), and tasks come from the engine's own record of them. Runs beyond their run group's
 * {@code maxRuns} wait as {@code PENDING}; a run past its group's {@code maxDuration} fails.
 */
@ApplicationScoped
public class OmicsEngine implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(OmicsEngine.class);
    static final String LOG_GROUP = "/aws/omics/WorkflowLog";
    private static final String WORK = "/work";
    private static final Pattern CWL_STEP = Pattern.compile("\\[(?:step|job) (\\S+)] completed (\\w+)");
    private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED", "CANCELLED", "DELETED");

    private static final String CROMWELL_CONF = """
            include required(classpath("application"))
            backend.providers.Local.config {
              root = "/cromwell-executions"
              run-in-background = true
              runtime-attributes = "String? docker\\nInt? cpus"
              submit = "/usr/bin/env bash ${script}"
              submit-docker = "/usr/bin/env bash ${script}"
              dockerRoot = "/cromwell-executions"
            }
            """;

    private final OmicsStore store;
    private final OmicsCommon common;
    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final Provider<CloudWatchLogsService> logs;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final Map<String, String> runningContainers = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "omics-runs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public OmicsEngine(OmicsStore store, OmicsCommon common, ContainerBuilder containers,
                       ContainerLifecycleManager lifecycle, Provider<CloudWatchLogsService> logs,
                       EmulatorConfig config) {
        this.store = store;
        this.common = common;
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.logs = logs;
        this.config = config;
        this.mapper = store.mapper();
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private EmulatorConfig.OmicsServiceConfig settings() {
        return config.services().omics();
    }

    boolean enabled() {
        return !"none".equalsIgnoreCase(settings().engine());
    }

    static String version(String engine) {
        return switch (engine) {
            case "NEXTFLOW" -> "24.10.4";
            case "CWL" -> "1.2";
            default -> "1.0";
        };
    }

    private static String key(String account, String region, String runId) {
        return account + "/" + region + "/" + runId;
    }

    // ================================================================ scheduling

    /** Queues a run; it starts when its run group has room. */
    void submit(String account, String region, String runId) {
        common.later(account, region, this::schedule);
    }

    /** Starts the pending runs whose run groups have room, highest priority first. */
    synchronized void schedule() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(OmicsStore.RUN)) {
            ObjectNode run = entry.value();
            if (!"PENDING".equals(run.path("status").asText())) {
                continue;
            }
            String account = entry.accountId();
            String region = OmicsStore.meta(run).path("region").asText();
            String group = run.path("runGroupId").asText("");
            if (!group.isEmpty()) {
                ObjectNode runGroup = store.getForAccount(account, region, OmicsStore.RUN_GROUP, group).orElse(null);
                int limit = runGroup == null ? Integer.MAX_VALUE : runGroup.path("maxRuns").asInt(Integer.MAX_VALUE);
                long active = store.listForAccount(account, region, OmicsStore.RUN).stream().filter(other ->
                        group.equals(other.path("runGroupId").asText()) && Set.of("STARTING", "RUNNING", "STOPPING")
                        .contains(other.path("status").asText())).count();
                if (active >= limit) {
                    continue;
                }
            }
            run.put("status", "STARTING");
            store.putForAccount(account, region, OmicsStore.RUN, run.path("id").asText(), run);
            String runId = run.path("id").asText();
            worker.submit(() -> RequestScopes.runAs(account, region, () -> execute(account, region, runId)));
        }
    }

    /** Cancels a run: a pending one at once, a running one by stopping its engine. */
    void cancel(String account, String region, String runId) {
        ObjectNode run = store.getForAccount(account, region, OmicsStore.RUN, runId).orElse(null);
        if (run == null) {
            return;
        }
        if ("PENDING".equals(run.path("status").asText())) {
            finish(account, region, runId, "CANCELLED", "The run was cancelled.", null);
            return;
        }
        run.put("status", "STOPPING");
        store.putForAccount(account, region, OmicsStore.RUN, runId, run);
        cancelled.add(key(account, region, runId));
        String container = runningContainers.get(key(account, region, runId));
        if (container != null) {
            try {
                lifecycle.getDockerClient().stopContainerCmd(container).withTimeout(5).exec();
            } catch (RuntimeException e) {
                LOG.debugv("Run {0}'s engine couldn't be stopped: {1}", runId, e.getMessage());
            }
        }
    }

    private void finish(String account, String region, String runId, String status, String message,
                        String failureReason) {
        ObjectNode run = store.getForAccount(account, region, OmicsStore.RUN, runId).orElse(null);
        if (run == null || FINISHED.contains(run.path("status").asText())) {
            return;
        }
        run.put("status", status);
        run.put("statusMessage", message);
        if (failureReason != null) {
            run.put("failureReason", failureReason);
        }
        run.put("stopTime", OmicsStore.timestamp());
        store.putForAccount(account, region, OmicsStore.RUN, runId, run);
        common.later(account, region, this::schedule);
    }

    // ================================================================ execution

    private Map<String, byte[]> files(String account, String key) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        store.data(account, key).ifPresent(data -> {
            try {
                mapper.readTree(data).properties().forEach(entry -> files.put(entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asText())));
            } catch (IOException e) {
                throw new IllegalStateException("The workflow's files can't be read.", e);
            }
        });
        return files;
    }

    private void execute(String account, String region, String runId) {
        ObjectNode run = store.getForAccount(account, region, OmicsStore.RUN, runId).orElse(null);
        if (run == null || !"STARTING".equals(run.path("status").asText())) {
            return;
        }
        if (!enabled()) {
            finish(account, region, runId, "FAILED", "Floci runs no workflow engine (floci.services.omics.engine is "
                    + "none).", "ENGINE_DISABLED");
            return;
        }
        ObjectNode meta = OmicsStore.meta(run);
        String language = meta.path("engine").asText("WDL");
        String main = meta.path("main").asText();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files(account, meta.path("filesKey").asText()).forEach((name, data) -> files.put("workflow/" + name, data));
        String containerId = null;
        String engineLog = "";
        try {
            JsonNode parameters = stage(run.path("parameters"), files, language);
            String command;
            String image;
            switch (language) {
                case "NEXTFLOW" -> {
                    files.put("params.json", parameters.toString().getBytes(StandardCharsets.UTF_8));
                    image = settings().nextflowImage();
                    command = "mkdir -p /mnt/workflow/pubdir && cd /work && nextflow run /work/workflow/" + main
                            + " -params-file /work/params.json -with-trace /work/trace.txt -w /work/scratch"
                            + " -ansi-log false";
                }
                case "CWL" -> {
                    files.put("job.json", parameters.toString().getBytes(StandardCharsets.UTF_8));
                    image = settings().cwlImage();
                    command = "mkdir -p /work/out && cd /work && cwltool --no-container --outdir /work/out "
                            + "/work/workflow/" + main + " /work/job.json > /work/outputs.json";
                }
                default -> {
                    String workflowName = wdlWorkflow(new String(files.get("workflow/" + main),
                            StandardCharsets.UTF_8));
                    ObjectNode inputs = mapper.createObjectNode();
                    parameters.properties().forEach(entry -> inputs.set(entry.getKey().contains(".") ? entry.getKey()
                            : workflowName + "." + entry.getKey(), entry.getValue()));
                    files.put("inputs.json", inputs.toString().getBytes(StandardCharsets.UTF_8));
                    files.put("cromwell.conf", CROMWELL_CONF.getBytes(StandardCharsets.UTF_8));
                    image = settings().wdlImage();
                    command = "cd /work && java -Dconfig.file=/work/cromwell.conf -Xmx" + settings().engineHeapMb()
                            + "m -jar /app/cromwell.jar run /work/workflow/" + main + " --inputs /work/inputs.json"
                            + " --metadata-output /work/metadata.json";
                }
            }
            String name = ContainerStorageHelper.resourceName(config, "omics", null, account + "-" + region + "-"
                    + runId);
            lifecycle.removeIfExists(name, true);
            ContainerSpec spec = containers.newContainer(image).withName(name).withEntrypoint(List.of("/bin/sh",
                            "-c")).withCmd(List.of(command)).withWorkingDir(WORK).withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(OmicsStore.SERVICE, runId, account,
                            region)).build();
            try {
                containerId = lifecycle.create(spec);
            } catch (RuntimeException e) {
                if (e.getMessage() == null || !e.getMessage().contains("no matching manifest")) {
                    throw e;
                }
                // The engine's image is built for amd64 only; Docker runs it emulated.
                containerId = lifecycle.create(spec, "linux/amd64");
            }
            copyIn(containerId, files);
            lifecycle.startCreated(containerId, spec);
            runningContainers.put(key(account, region, runId), containerId);
            run = store.getForAccount(account, region, OmicsStore.RUN, runId).orElse(run);
            if ("STARTING".equals(run.path("status").asText())) {
                run.put("status", "RUNNING");
                run.put("startTime", OmicsStore.timestamp());
                store.putForAccount(account, region, OmicsStore.RUN, runId, run);
            }
            long exit = await(account, region, run, containerId);
            engineLog = log(containerId);
            ship(account, region, "run/" + runId + "/engine", engineLog);
            ArrayNode tasks = tasks(language, containerId, engineLog);
            boolean stopped = cancelled.remove(key(account, region, runId));
            ObjectNode current = store.getForAccount(account, region, OmicsStore.RUN, runId).orElse(run);
            OmicsStore.meta(current).set("tasks", tasks);
            store.putForAccount(account, region, OmicsStore.RUN, runId, current);
            if (stopped) {
                finish(account, region, runId, "CANCELLED", "The run was cancelled.", null);
            } else if (exit == -2) {
                finish(account, region, runId, "FAILED", "The run exceeded its run group's maximum duration.",
                        "TIMED_OUT");
            } else if (exit != 0) {
                finish(account, region, runId, "FAILED", tail(engineLog), "WORKFLOW_RUN_FAILED");
            } else {
                outputs(language, containerId, current.path("runOutputUri").asText());
                finish(account, region, runId, "COMPLETED", "The run completed.", null);
            }
        } catch (RuntimeException | IOException e) {
            LOG.infov("Run {0} failed: {1}", runId, e.getMessage());
            finish(account, region, runId, "FAILED", e.getMessage(), "RUN_SETUP_FAILED");
        } finally {
            runningContainers.remove(key(account, region, runId));
            if (containerId != null) {
                lifecycle.stopAndRemove(containerId, null, 5, true);
            }
        }
    }

    private static String wdlWorkflow(String text) {
        Matcher matcher = Pattern.compile("\\bworkflow\\s+(\\w+)").matcher(text);
        return matcher.find() ? matcher.group(1) : "main";
    }

    /** Parameters with their {@code s3://} values downloaded into the run and replaced by local paths. */
    private JsonNode stage(JsonNode parameters, Map<String, byte[]> files, String language) {
        ObjectNode staged = mapper.createObjectNode();
        int[] count = {0};
        parameters.properties().forEach(entry -> staged.set(entry.getKey(), stageValue(entry.getValue(), files,
                language, count)));
        return staged;
    }

    private JsonNode stageValue(JsonNode value, Map<String, byte[]> files, String language, int[] count) {
        if (value.isTextual() && value.asText().startsWith("s3://")) {
            String uri = value.asText();
            String base = uri.substring(uri.lastIndexOf('/') + 1);
            String path = "inputs/" + (count[0]++) + "/" + base;
            files.put(path, common.read(uri));
            String local = WORK + "/" + path;
            if ("CWL".equals(language)) {
                return mapper.createObjectNode().put("class", "File").put("path", local);
            }
            return mapper.getNodeFactory().textNode(local);
        }
        if (value.isArray()) {
            ArrayNode list = mapper.createArrayNode();
            value.forEach(item -> list.add(stageValue(item, files, language, count)));
            return list;
        }
        return value.deepCopy();
    }

    private void copyIn(String id, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        }
        lifecycle.getDockerClient().copyArchiveToContainerCmd(id).withRemotePath(WORK)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray())).exec();
    }

    /** Waits for the engine to exit: its exit code, or -2 when the run outlived its run group's maxDuration. */
    private long await(String account, String region, ObjectNode run, String containerId) {
        long minutes = settings().runTimeoutMinutes();
        String group = run.path("runGroupId").asText("");
        if (!group.isEmpty()) {
            minutes = store.getForAccount(account, region, OmicsStore.RUN_GROUP, group)
                    .map(record -> record.path("maxDuration").asLong(settings().runTimeoutMinutes()))
                    .orElse(minutes);
        }
        long deadline = System.currentTimeMillis() + minutes * 60_000L;
        while (true) {
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient()
                    .inspectContainerCmd(containerId).exec().getState();
            if (state != null && !Boolean.TRUE.equals(state.getRunning())) {
                Long code = state.getExitCodeLong();
                return code == null ? -1 : code;
            }
            if (System.currentTimeMillis() > deadline) {
                lifecycle.getDockerClient().stopContainerCmd(containerId).withTimeout(5).exec();
                return -2;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    private String log(String containerId) {
        StringBuilder text = new StringBuilder();
        try {
            lifecycle.getDockerClient().logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            text.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            return "";
        }
        return text.toString();
    }

    private static String tail(String log) {
        List<String> lines = new ArrayList<>();
        for (String line : log.split("\n")) {
            if (line.contains("ERROR") || line.contains("Exception") || line.contains("failed") || line.contains(
                    "Error")) {
                lines.add(line.trim());
            }
        }
        String text = lines.isEmpty() ? "The workflow engine exited with an error." : String.join(" ",
                lines.subList(Math.max(0, lines.size() - 3), lines.size()));
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    private void ship(String account, String region, String stream, String text) {
        try {
            try {
                logs.get().createLogGroupForAccount(account, LOG_GROUP, null, Map.of(), region);
            } catch (AwsException e) {
                LOG.tracev("Log group exists: {0}", e.getMessage());
            }
            try {
                logs.get().createLogStreamForAccount(account, LOG_GROUP, stream, region);
            } catch (AwsException e) {
                LOG.tracev("Log stream exists: {0}", e.getMessage());
            }
            List<Map<String, Object>> events = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (String line : text.split("\n")) {
                if (!line.isBlank() && events.size() < 5000) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("timestamp", now);
                    event.put("message", line);
                    events.add(event);
                }
            }
            if (!events.isEmpty()) {
                logs.get().putLogEventsForAccount(account, LOG_GROUP, stream, events, region);
            }
        } catch (RuntimeException e) {
            LOG.debugv("The run log couldn't be written: {0}", e.getMessage());
        }
    }

    /** Files under a container path, by path relative to it. */
    private Map<String, byte[]> copyOut(String containerId, String path) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream archive = lifecycle.getDockerClient().copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            String root = path.substring(path.lastIndexOf('/') + 1) + "/";
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = entry.getName().startsWith(root) ? entry.getName().substring(root.length())
                            : entry.getName();
                    files.put(name, tar.readAllBytes());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugv("Nothing to copy from {0}: {1}", path, e.getMessage());
        }
        return files;
    }

    private void outputs(String language, String containerId, String runOutput) throws IOException {
        String base = runOutput.replaceAll("/+$", "");
        switch (language) {
            case "NEXTFLOW" -> copyOut(containerId, "/mnt/workflow/pubdir").forEach((name, data) ->
                    common.write(base + "/out/" + name, data, "application/octet-stream"));
            case "CWL" -> {
                copyOut(containerId, "/work/out").forEach((name, data) -> common.write(base + "/out/" + name, data,
                        "application/octet-stream"));
                copyOut(containerId, "/work/outputs.json").values().forEach(data -> common.write(base
                        + "/logs/outputs.json", data, "application/json"));
            }
            default -> {
                byte[] metadata = copyOut(containerId, "/work/metadata.json").values().stream().findFirst()
                        .orElse(null);
                if (metadata == null) {
                    return;
                }
                JsonNode outputs = mapper.readTree(metadata).path("outputs");
                ObjectNode values = mapper.createObjectNode();
                for (Map.Entry<String, JsonNode> output : outputs.properties()) {
                    JsonNode value = output.getValue();
                    if (value.isTextual() && value.asText().startsWith("/cromwell-executions/")) {
                        String file = value.asText();
                        String name = file.substring(file.lastIndexOf('/') + 1);
                        byte[] data = copyOut(containerId, file).values().stream().findFirst().orElse(null);
                        if (data != null) {
                            common.write(base + "/out/" + name, data, "application/octet-stream");
                            values.put(output.getKey(), base + "/out/" + name);
                            continue;
                        }
                    }
                    values.set(output.getKey(), value);
                }
                common.write(base + "/logs/outputs.json", values.toString().getBytes(StandardCharsets.UTF_8),
                        "application/json");
            }
        }
    }

    /** The run's tasks, from the engine's record of them. */
    private ArrayNode tasks(String language, String containerId, String engineLog) throws IOException {
        ArrayNode tasks = mapper.createArrayNode();
        switch (language) {
            case "NEXTFLOW" -> {
                byte[] trace = copyOut(containerId, "/work/trace.txt").values().stream().findFirst().orElse(null);
                if (trace == null) {
                    return tasks;
                }
                String[] lines = new String(trace, StandardCharsets.UTF_8).split("\n");
                List<String> header = List.of(lines[0].split("\t"));
                for (int i = 1; i < lines.length; i++) {
                    String[] cells = lines[i].split("\t");
                    Map<String, String> row = new HashMap<>();
                    for (int c = 0; c < cells.length && c < header.size(); c++) {
                        row.put(header.get(c), cells[c]);
                    }
                    task(tasks, row.getOrDefault("name", "task"), "COMPLETED".equals(row.get("status"))
                            ? "COMPLETED" : "FAILED", row.get("submit"), row.get("submit"));
                }
            }
            case "CWL" -> {
                Matcher matcher = CWL_STEP.matcher(engineLog);
                while (matcher.find()) {
                    task(tasks, matcher.group(1), "success".equals(matcher.group(2)) ? "COMPLETED" : "FAILED", null,
                            null);
                }
            }
            default -> {
                byte[] metadata = copyOut(containerId, "/work/metadata.json").values().stream().findFirst()
                        .orElse(null);
                if (metadata == null) {
                    return tasks;
                }
                for (Map.Entry<String, JsonNode> call : mapper.readTree(metadata).path("calls").properties()) {
                    for (JsonNode attempt : call.getValue()) {
                        ObjectNode task = task(tasks, call.getKey(), "Done".equals(attempt.path("executionStatus")
                                .asText()) ? "COMPLETED" : "FAILED", attempt.path("start").asText(null),
                                attempt.path("end").asText(null));
                        JsonNode runtime = attempt.path("runtimeAttributes");
                        task.put("cpus", runtime.path("cpus").asInt(runtime.path("cpu").asInt(1)));
                        String memory = runtime.path("memory").asText("1 GB").replaceAll("[^0-9.]", "");
                        task.put("memory", (int) Math.max(1, Double.parseDouble(memory.isEmpty() ? "1" : memory)));
                        if (runtime.hasNonNull("docker")) {
                            task.putObject("imageDetails").put("image", runtime.path("docker").asText())
                                    .put("sourceImage", runtime.path("docker").asText());
                        }
                    }
                }
            }
        }
        return tasks;
    }

    private ObjectNode task(ArrayNode tasks, String name, String status, String start, String stop) {
        ObjectNode task = tasks.addObject();
        task.put("taskId", String.valueOf(1000000 + tasks.size()));
        task.put("uuid", UUID.randomUUID().toString());
        task.put("status", status);
        task.put("name", name);
        task.put("cpus", 1);
        task.put("memory", 1);
        task.put("cacheHit", false);
        String now = Instant.now().toString();
        task.put("creationTime", start != null ? start : now);
        task.put("startTime", start != null ? start : now);
        task.put("stopTime", stop != null && !stop.isBlank() ? stop : now);
        task.put("logStream", "run/" + name);
        return task;
    }

    @Override
    public void stopManagedContainers() {
        for (String container : new ArrayList<>(runningContainers.values())) {
            lifecycle.stopAndRemove(container, null, 5, true);
        }
        runningContainers.clear();
    }
}
