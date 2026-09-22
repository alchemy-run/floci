package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class EmrServerlessSparkRunner {

    static final String LOG_GROUP = "/aws/emr-serverless";
    static final int MEMORY_MB = 1536;
    private final ContainerBuilder builder;
    private final ContainerLifecycleManager lifecycle;
    private final ContainerLogStreamer logs;
    private final ContainerDetector detector;
    private final EmulatorConfig config;
    private final IamService iam;
    private final String image;
    private final String sparkHome;

    @Inject
    public EmrServerlessSparkRunner(ContainerBuilder builder, ContainerLifecycleManager lifecycle,
                                   ContainerLogStreamer logs, ContainerDetector detector, EmulatorConfig config,
                                   IamService iam,
                                   @ConfigProperty(name = "floci.services.emrserverless.spark-image",
                                           defaultValue = "apache/spark:3.5.2-scala2.12-java17-python3-ubuntu") String image,
                                   @ConfigProperty(name = "floci.services.emrserverless.spark-home",
                                           defaultValue = "/opt/spark") String sparkHome) {
        this.builder = builder;
        this.lifecycle = lifecycle;
        this.logs = logs;
        this.detector = detector;
        this.config = config;
        this.iam = iam;
        this.image = image;
        this.sparkHome = sparkHome;
    }

    static final class Execution {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean interruptRequested = new AtomicBoolean();
        volatile Thread thread;

        void stop(boolean timeout) {
            if (timeout) {
                timedOut.set(true);
            } else {
                cancelled.set(true);
            }
            Thread running = thread;
            if (interruptRequested.compareAndSet(false, true) && running != null) {
                running.interrupt();
            }
        }

        void check() throws InterruptedException {
            if (cancelled.get() || timedOut.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Execution stopped");
            }
        }
    }

    record Result(int exitCode, String details) {}

    String containerName(ObjectNode job) {
        if (job.hasNonNull("_containerName")) {
            return job.path("_containerName").asText();
        }
        return ContainerStorageHelper.dockerName(config, "floci-emrserverless-"
                + job.path("_accountId").asText() + "-" + job.path("_region").asText()
                + "-" + job.path("applicationId").asText() + "-" + job.path("jobRunId").asText());
    }

    void validate(JsonNode request) {
        command(request.path("jobDriver").path("sparkSubmit"), sparkHome);
    }

    Result run(ObjectNode job, Execution execution, Consumer<String> onCreated, Runnable onStarted) {
        String account = job.path("_accountId").asText();
        String region = job.path("_region").asText();
        String role = job.path("executionRole").asText();
        String key = "ASIA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String secret = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        Closeable logHandle = null;
        String containerId = null;
        boolean creationAttempted = false;
        try {
            execution.check();
            iam.registerSessionForAccount(account, key, secret, token, role,
                    Instant.ofEpochMilli(job.path("_deadline").asLong()).plusSeconds(30), null);
            String host = detector.isRunningInContainer()
                    ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX) : "host.docker.internal";
            String endpoint = "http://" + host + ":" + config.port();
            List<String> env = List.of("AWS_REGION=" + region, "AWS_DEFAULT_REGION=" + region,
                    "AWS_ACCESS_KEY_ID=" + key, "AWS_SECRET_ACCESS_KEY=" + secret, "AWS_SESSION_TOKEN=" + token,
                    "AWS_ENDPOINT_URL=" + endpoint, "FLOCI_ENDPOINT=" + endpoint,
                    "SPARK_LOCAL_IP=127.0.0.1", "HOME=/tmp");
            String selectedImage = job.path("imageConfiguration").path("imageUri").asText(image);
            List<String> containerCommand = new ArrayList<>(List.of(sparkHome + "/bin/spark-submit"));
            containerCommand.addAll(command(job.path("jobDriver").path("sparkSubmit"), sparkHome));
            ContainerSpec spec = builder.newContainer(selectedImage)
                    .withName(containerName(job))
                    // Preserve the image entrypoint's user and signal-forwarding setup.
                    .withCmd(containerCommand)
                    .withEnv(env).withMemoryMb(MEMORY_MB).withCpuUnits(1024)
                    .withDockerNetwork(Optional.empty()).withHostDockerInternalOnLinux()
                    .withEmbeddedDns().withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels("emrserverless",
                            job.path("jobRunId").asText(), account, region)).build();
            execution.check();
            creationAttempted = true;
            String architecture = job.path("_architecture").asText();
            containerId = architecture.isBlank() ? lifecycle.create(spec)
                    : lifecycle.create(spec, "ARM64".equals(architecture) ? "linux/arm64" : "linux/amd64");
            onCreated.accept(containerId);
            execution.check();
            lifecycle.startCreated(containerId, spec);
            onStarted.run();
            logHandle = logs.attachForAccount(account, containerId, LOG_GROUP,
                    logStream(job), region, "emrserverless:" + job.path("jobRunId").asText());
            while (System.currentTimeMillis() < job.path("_deadline").asLong()) {
                execution.check();
                InspectContainerResponse.ContainerState state = lifecycle.getDockerClient()
                        .inspectContainerCmd(containerId).exec().getState();
                if (!Boolean.TRUE.equals(state.getRunning())
                        && ("exited".equals(state.getStatus()) || "dead".equals(state.getStatus()))) {
                    if ("dead".equals(state.getStatus())) {
                        return new Result(-1, "Docker reported a dead Spark worker");
                    }
                    Long code = state.getExitCodeLong();
                    if (code == null) {
                        return new Result(-1, "Docker did not report an exit code");
                    }
                    return new Result(code.intValue(), "Spark container exited with code " + code
                            + (Boolean.TRUE.equals(state.getOOMKilled()) ? " (out of memory)" : ""));
                }
                Thread.sleep(200);
            }
            execution.timedOut.set(true);
            return new Result(-1, "Local Spark execution deadline exceeded");
        } catch (InterruptedException e) {
            return new Result(-1, execution.timedOut.get() ? "Local Spark execution deadline exceeded" : "Execution interrupted");
        } catch (Exception e) {
            return new Result(-1, "Spark execution failed: " + e.getMessage());
        } finally {
            // Cleanup must not inherit the interrupt used to cancel image pulls or polling.
            Thread.interrupted();
            try {
                if (creationAttempted) {
                    cleanup(job, logHandle);
                }
            } finally {
                iam.unregisterSession(account, key);
            }
        }
    }

    void cleanup(ObjectNode job) {
        cleanup(job, null);
    }

    private void cleanup(ObjectNode job, Closeable logHandle) {
        try {
            InspectContainerResponse inspect = lifecycle.getDockerClient().inspectContainerCmd(containerName(job)).exec();
            Map<String, String> labels = inspect.getConfig().getLabels();
            Map<String, String> expected = ContainerStorageHelper.resourceIdentityLabels("emrserverless",
                    job.path("jobRunId").asText(), job.path("_accountId").asText(), job.path("_region").asText());
            if (labels == null || !labels.entrySet().containsAll(expected.entrySet())) {
                throw new IllegalStateException("Refusing to remove a container with foreign EMR Serverless ownership labels");
            }
            lifecycle.stopAndRemoveStrict(inspect.getId(), null);
        } catch (NotFoundException ignored) {
            // A queued or already removed worker has nothing left to stop.
        } finally {
            if (logHandle != null) {
                lifecycle.closeLogStreamAfterContainerStop(logHandle);
            }
        }
    }

    static String logStream(JsonNode job) {
        return job.path("applicationId").asText() + "/" + job.path("jobRunId").asText() + "/SPARK_DRIVER";
    }

    static List<String> command(JsonNode spark, String home) {
        if (!spark.isObject() || !spark.path("entryPoint").isTextual()) {
            throw invalid("jobDriver.sparkSubmit.entryPoint is required");
        }
        String entry = spark.path("entryPoint").asText();
        if (entry.startsWith("local://")) {
            entry = entry.substring("local://".length());
        }
        if (!entry.startsWith("/") || entry.contains("\u0000")) {
            throw new AwsException("UnsupportedOperationException", "Only image-local Spark entry points are supported", 501);
        }
        // EMR's Spark home differs from the Apache Spark image layout.
        if (entry.startsWith("/usr/lib/spark/")) {
            entry = home + entry.substring("/usr/lib/spark".length());
        }
        if (spark.has("sparkSubmitParameters") && !spark.path("sparkSubmitParameters").isTextual()) {
            throw invalid("sparkSubmitParameters must be a string");
        }
        List<String> args = new ArrayList<>(tokenize(spark.path("sparkSubmitParameters").asText("")));
        for (int i = 0; i < args.size(); i++) {
            String option = args.get(i).split("=", 2)[0];
            if (!List.of("--conf", "--class", "--jars", "--py-files", "--files", "--archives", "--name",
                    "--driver-memory", "--executor-memory", "--executor-cores", "--num-executors",
                    "--driver-java-options", "--driver-class-path").contains(option)) {
                throw new AwsException("UnsupportedOperationException", "Unsupported local Spark option: " + option, 501);
            }
            if (!args.get(i).contains("=") && ++i >= args.size()) {
                throw invalid("Missing value for " + option);
            }
        }
        // One local worker, with hard container limits independent of requested cloud capacity.
        args.addAll(List.of("--master", "local[1]", "--deploy-mode", "client", "--driver-memory", "512m"));
        for (String conf : List.of("spark.master=local[1]", "spark.submit.deployMode=client",
                "spark.driver.memory=512m", "spark.executor.memory=512m", "spark.executor.cores=1",
                "spark.driver.cores=1", "spark.executor.instances=1", "spark.dynamicAllocation.enabled=false",
                "spark.ui.enabled=false", "spark.driver.host=127.0.0.1", "spark.driver.bindAddress=127.0.0.1",
                "spark.default.parallelism=1", "spark.sql.shuffle.partitions=1")) {
            args.add("--conf");
            args.add(conf);
        }
        args.add(entry);
        JsonNode arguments = spark.path("entryPointArguments");
        if (!arguments.isMissingNode()) {
            if (!arguments.isArray()) {
                throw invalid("entryPointArguments must be an array");
            }
            for (JsonNode argument : arguments) {
                if (!argument.isTextual() || argument.asText().contains("\u0000")) {
                    throw invalid("entryPointArguments must contain strings without null bytes");
                }
                args.add(argument.asText());
            }
        }
        return args;
    }

    static List<String> tokenize(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        boolean started = false;
        for (char c : value.toCharArray()) {
            if (c == '\0') {
                throw invalid("Spark parameters must not contain null bytes");
            }
            if (escape) {
                token.append(c);
                escape = false;
            } else if (c == '\\' && quote != '\'') {
                escape = true;
                started = true;
            } else if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    token.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quote != 0 || escape) {
            throw invalid("Unterminated quote or escape in sparkSubmitParameters");
        }
        if (started) {
            result.add(token.toString());
        }
        return result;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
