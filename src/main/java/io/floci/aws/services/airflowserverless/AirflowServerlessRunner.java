package io.floci.aws.services.airflowserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs MWAA Serverless workflow runs in Apache Airflow. One container of the configured Airflow image serves every
 * run: its metadata database is migrated once, and each run gets its own copy of it, its definition, its code package
 * and the workflow role's credentials pointed at Floci, then {@code runner.py} builds the DAG and runs it with
 * {@code DAG.test()}. The runner reports task states, XComs and log lines as it goes; a stop kills its process, and a
 * run that outlives the configured limit times out.
 */
@ApplicationScoped
public class AirflowServerlessRunner implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(AirflowServerlessRunner.class);
    private static final String PREFIX = "@@floci@@";
    private static final String HOME = "/opt/airflow";
    private static final Pattern ERROR_LINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*(Error|Exception)(: |$)");

    /** A run to execute: its account, Region, workflow, role, definition, code package and parameters. */
    record Job(String account, String region, String workflowArn, String runId, String roleArn, String definition,
               byte[] code, JsonNode overrides, String logicalDate) {
    }

    /** What a run reports as it goes. */
    interface Listener {
        void run(String state, String error);

        void task(JsonNode event);

        void log(String taskId, long timestamp, String message);
    }

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final AirflowServerlessStore store;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "airflow-serverless-run");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Boolean> stopped = new ConcurrentHashMap<>();
    private volatile String containerId;

    @Inject
    public AirflowServerlessRunner(ContainerBuilder containers, ContainerLifecycleManager lifecycle,
                                   LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                                   AirflowServerlessStore store) {
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.store = store;
        this.mapper = store.mapper();
    }

    private String containerName() {
        return ContainerStorageHelper.dockerName(store.emulator(), "airflow-serverless");
    }

    private synchronized String ensureStarted() {
        if (containerId != null && running(containerId)) {
            return containerId;
        }
        String image = store.config().airflowImage();
        String name = containerName();
        LOG.infov("Starting the MWAA Serverless Airflow runtime {0} using image {1}", name, image);
        lifecycle.removeIfExists(name);
        ContainerSpec spec = containers.newContainer(image).withName(name)
                .withEntrypoint(List.of("sleep", "infinity"))
                .withEnv(List.of("AIRFLOW_HOME=" + HOME, "AIRFLOW__CORE__LOAD_EXAMPLES=false",
                        "AIRFLOW__CORE__DAGS_FOLDER=" + HOME + "/no-dags"))
                .withDockerNetwork(store.config().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(AirflowServerlessStore.SERVICE,
                        "runtime", "", ""))
                .build();
        String id = lifecycle.create(spec);
        lifecycle.startCreated(id, spec);
        String[] migrated = exec(id, List.of("airflow", "db", "migrate"), List.of(), null, 10);
        if (!"0".equals(migrated[0])) {
            throw new IllegalStateException("The Airflow database could not be migrated: " + tail(migrated[1]));
        }
        containerId = id;
        return id;
    }

    private boolean running(String id) {
        try {
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient().inspectContainerCmd(id).exec()
                    .getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Starts a run in the background, reporting to {@code listener}. */
    void submit(Job job, Listener listener) {
        executor.submit(() -> execute(job, listener));
    }

    /** Stops a run: its process is killed and it ends STOPPED. */
    void stop(String runId) {
        stopped.put(runId, true);
        String id = containerId;
        if (id != null) {
            executor.submit(() -> kill(id, "/tmp/run-" + runId));
        }
    }

    private void execute(Job job, Listener listener) {
        String work = "/tmp/run-" + job.runId();
        String id;
        try {
            listener.run("QUEUED", null);
            id = ensureStarted();
        } catch (RuntimeException e) {
            LOG.warnv("MWAA Serverless run {0} could not start Airflow: {1}", job.runId(), e.getMessage());
            listener.run("FAILED", "The Airflow runtime could not start: " + e.getMessage());
            return;
        }
        if (stopped.containsKey(job.runId())) {
            listener.run("STOPPED", null);
            return;
        }
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            ObjectNode spec = mapper.createObjectNode();
            spec.put("definition", job.definition());
            spec.put("runId", job.runId());
            spec.put("logicalDate", job.logicalDate());
            if (job.overrides() != null) {
                spec.set("overrides", job.overrides());
            }
            files.put("run.json", spec.toString().getBytes(StandardCharsets.UTF_8));
            files.put("runner.py", script());
            files.put("home/aws-config", ("[default]\nregion = " + job.region() + "\ns3 =\n    addressing_style = path\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (job.code() != null) {
                files.putAll(unzip(job.code(), "code/"));
            }
            exec(id, List.of("mkdir", "-p", work + "/home"), List.of(), null, 1);
            copyIn(id, work, files);
            exec(id, List.of("cp", HOME + "/airflow.db", work + "/home/airflow.db"), List.of(), null, 1);
            Optional<SessionCreds> credentials = Optional.empty();
            try {
                credentials = roleCredentials.forRole(job.roleArn(), job.account(), "airflow-serverless-"
                        + job.runId());
            } catch (RuntimeException e) {
                LOG.debugv("MWAA Serverless run {0} runs without its role's session: {1}", job.runId(),
                        e.getMessage());
            }
            List<String> env = new ArrayList<>(awsEnv.sdkBaselineEnv(job.region(), Optional.empty(), credentials,
                    job.account()));
            env.add("AWS_CONFIG_FILE=" + work + "/home/aws-config");
            env.add("AIRFLOW_HOME=" + work + "/home");
            env.add("AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=sqlite:///" + work + "/home/airflow.db");
            env.add("AIRFLOW__CORE__LOAD_EXAMPLES=false");
            env.add("AIRFLOW__CORE__DAGS_FOLDER=" + work + "/home/dags");
            env.add("AIRFLOW_CONN_AWS_DEFAULT=" + mapper.createObjectNode().put("conn_type", "aws")
                    .set("extra", mapper.createObjectNode().put("region_name", job.region())));
            Map<String, String> errors = new LinkedHashMap<>();
            boolean[] finished = {false};
            String[] current = {null};
            String[] result = exec(id, List.of("python", work + "/runner.py", work), env, line -> {
                if (!line.startsWith(PREFIX)) {
                    if (current[0] != null && !line.isBlank()) {
                        listener.log(current[0], System.currentTimeMillis(), line);
                        if (ERROR_LINE.matcher(line.strip()).find()) {
                            errors.put(current[0], line.strip());
                        }
                        if (line.contains("[DAG TEST] end task")) {
                            current[0] = null;
                        }
                    }
                    return;
                }
                try {
                    JsonNode event = mapper.readTree(line.substring(PREFIX.length()));
                    switch (event.path("type").asText()) {
                        case "run" -> {
                            String state = event.path("state").asText();
                            finished[0] |= !"RUNNING".equals(state);
                            listener.run(state, event.path("error").asText(null));
                        }
                        case "task" -> {
                            ObjectNode task = (ObjectNode) event;
                            if ("RUNNING".equals(event.path("state").asText())) {
                                current[0] = event.path("task").asText();
                            } else if ("FAILED".equals(event.path("state").asText()) && !event.has("error")
                                    && errors.containsKey(event.path("task").asText())) {
                                task.put("error", errors.get(event.path("task").asText()));
                            }
                            listener.task(task);
                        }
                        case "log" -> listener.log(event.path("task").asText(),
                                (long) (event.path("time").asDouble() * 1000), event.path("message").asText());
                        default -> {
                        }
                    }
                } catch (IOException e) {
                    LOG.debugv("MWAA Serverless run {0} reported a line that isn't JSON: {1}", job.runId(), line);
                }
            }, store.config().maxRunMinutes());
            if (stopped.containsKey(job.runId())) {
                listener.run("STOPPED", null);
            } else if ("timeout".equals(result[0])) {
                kill(id, work);
                listener.run("TIMEOUT", "The run exceeded " + store.config().maxRunMinutes() + " minutes.");
            } else if (!finished[0]) {
                listener.run("FAILED", "The Airflow runner exited without a result: " + tail(result[1]));
            }
        } catch (RuntimeException e) {
            LOG.warnv("MWAA Serverless run {0} failed: {1}", job.runId(), e.getMessage());
            listener.run("FAILED", e.getMessage());
        } finally {
            stopped.remove(job.runId());
            exec(containerId == null ? "" : containerId, List.of("rm", "-rf", work), List.of(), null, 1);
        }
    }

    /** Kills every process whose command line names {@code marker}, with the processes they started. */
    private void kill(String id, String marker) {
        String script = "tree() { for c in $(grep -l \"^PPid:[[:space:]]*$1$\" /proc/[0-9]*/status 2>/dev/null "
                + "| cut -d/ -f3); do tree $c; done; kill -9 $1 2>/dev/null; }; "
                + "for p in $(grep -l -F \"" + marker + "\" /proc/[0-9]*/cmdline 2>/dev/null | cut -d/ -f3); do "
                + "[ \"$p\" != \"$$\" ] && tree $p; done; true";
        exec(id, List.of("sh", "-c", script), List.of(), null, 1);
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() > 200 ? trimmed.substring(trimmed.length() - 200) : trimmed;
    }

    private static byte[] script() {
        try (InputStream in = AirflowServerlessRunner.class.getResourceAsStream("/airflowserverless/runner.py")) {
            if (in == null) {
                throw new IllegalStateException("runner.py is missing from the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] archive, String prefix) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().contains("..")) {
                    files.put(prefix + entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The code package could not be unpacked: " + e.getMessage());
        }
        return files;
    }

    /** Runs a command, handing each output line to {@code lines}; "timeout" when it outlives {@code minutes}. */
    private String[] exec(String id, List<String> command, List<String> env, LineSink lines, int minutes) {
        if (id == null || id.isEmpty()) {
            return new String[] {"-1", ""};
        }
        try {
            ExecCreateCmdResponse created = lifecycle.getDockerClient().execCreateCmd(id)
                    .withCmd(command.toArray(String[]::new)).withEnv(env).withAttachStdout(true)
                    .withAttachStderr(true).exec();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            StringBuilder pending = new StringBuilder();
            boolean done = lifecycle.getDockerClient().execStartCmd(created.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            byte[] payload = frame.getPayload();
                            if (output.size() < 1_000_000) {
                                output.writeBytes(payload);
                            }
                            if (lines == null) {
                                return;
                            }
                            pending.append(new String(payload, StandardCharsets.UTF_8));
                            int newline;
                            while ((newline = pending.indexOf("\n")) >= 0) {
                                String line = pending.substring(0, newline);
                                pending.delete(0, newline + 1);
                                lines.accept(line);
                            }
                        }
                    }).awaitCompletion(minutes, TimeUnit.MINUTES);
            if (lines != null && !pending.isEmpty()) {
                lines.accept(pending.toString());
            }
            if (!done) {
                return new String[] {"timeout", output.toString(StandardCharsets.UTF_8)};
            }
            Long code = lifecycle.getDockerClient().inspectExecCmd(created.getId()).exec().getExitCodeLong();
            return new String[] {String.valueOf(code == null ? -1 : code), output.toString(StandardCharsets.UTF_8)};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new String[] {"-1", ""};
        } catch (RuntimeException e) {
            return new String[] {"-1", e.getMessage() == null ? "" : e.getMessage()};
        }
    }

    private interface LineSink {
        void accept(String line);
    }

    private void copyIn(String id, String directory, Map<String, byte[]> files) {
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
        } catch (IOException e) {
            throw new IllegalStateException("Could not package the run: " + e.getMessage());
        }
        lifecycle.getDockerClient().copyArchiveToContainerCmd(id).withRemotePath(directory)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray())).exec();
    }

    @Override
    public synchronized void stopManagedContainers() {
        String id = containerId;
        containerId = null;
        if (id != null) {
            lifecycle.stopAndRemove(id, null);
        } else {
            lifecycle.removeIfExists(containerName());
        }
    }
}
