package io.github.hectorvent.floci.services.sagemaker;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provisions HyperPod nodes for real: each node is a short-lived, memory-capped container that
 * downloads its instance group's lifecycle scripts from S3 and runs {@code OnCreate}, as HyperPod
 * does on a freshly launched instance. The script's output goes to the CloudWatch log group
 * {@code /aws/sagemaker/Clusters/<name>/<id>}, stream {@code LifecycleConfig/<group>/<instance-id>}.
 * The node is {@code Running} when the script exits 0; the container is removed afterwards, so an
 * idle cluster holds no containers.
 */
@ApplicationScoped
public class SageMakerHyperPodNodeRunner implements SageMakerHyperPodNodeLauncher, ContainerTeardown, Resettable {
    private static final Logger LOG = Logger.getLogger(SageMakerHyperPodNodeRunner.class);
    static final String LIFECYCLE_DIR = "/tmp/sagemaker-lifecycle";
    static final String RESOURCE_CONFIG_PATH = "/opt/ml/config/resource_config.json";
    private static final int NODE_MEMORY_MB = 512;
    private static final int MAX_CONCURRENT_NODES = 4;
    private static final int MAX_LIFECYCLE_OBJECTS = 1000;
    private static final int EXECUTABLE_FILE_MODE = 0755;
    private static final Duration LIFECYCLE_TIMEOUT = Duration.ofMinutes(20);
    private static final String RUN_ON_CREATE =
            "cd \"$SAGEMAKER_LIFECYCLE_DIR\" && exec bash \"./$SAGEMAKER_LIFECYCLE_ON_CREATE\"";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final S3Service s3Service;
    // Replaced by afterReset() after a state reset, whose container teardown shuts this pool down.
    private volatile ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> containers = new ConcurrentHashMap<>();

    @Inject
    public SageMakerHyperPodNodeRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                       ContainerLogStreamer logStreamer, EmulatorConfig config,
                                       ContainerDetector containerDetector, S3Service s3Service) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.config = config;
        this.containerDetector = containerDetector;
        this.s3Service = s3Service;
    }

    @Override
    public void launch(NodeLaunch launch, NodeOutcome outcome) {
        inFlight.add(launch.instanceId());
        try {
            executor.submit(() -> run(launch, outcome));
        } catch (RuntimeException e) {
            LOG.warnv("Could not queue HyperPod node {0}: {1}", launch.instanceId(), e.getMessage());
            report(launch, outcome, false, "Node provisioning could not be started: " + e.getMessage());
        }
    }

    @Override
    public void cancel(String instanceId) {
        if (!inFlight.contains(instanceId)) {
            return;
        }
        cancelled.add(instanceId);
        String containerId = containers.remove(instanceId);
        if (containerId != null) {
            // Off the caller's thread: the service calls this while holding its monitor.
            CompletableFuture.runAsync(() -> lifecycleManager.stopAndRemove(containerId, null));
        }
    }

    @Override
    public boolean inFlight(String instanceId) {
        return inFlight.contains(instanceId);
    }

    private void run(NodeLaunch launch, NodeOutcome outcome) {
        String instanceId = launch.instanceId();
        String containerId = null;
        Closeable logs = null;
        try {
            if (cancelled.contains(instanceId)) {
                report(launch, outcome, false, "Node provisioning was cancelled.");
                return;
            }
            byte[] archive = nodeArchive(launch);
            String name = ContainerStorageHelper.dockerName(config,
                    "floci-sagemaker-hyperpod-" + launch.clusterId() + "-" + instanceId);
            lifecycleManager.removeIfExists(name);
            ContainerSpec spec = containerBuilder.newContainer(config.services().sagemaker().hyperpodNodeImage())
                    .withName(name)
                    .withEnv(environment(launch))
                    .withEntrypoint(List.of("/bin/bash", "-c", RUN_ON_CREATE))
                    .withMemoryMb(NODE_MEMORY_MB)
                    .withDockerNetwork(config.services().sagemaker().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation()
                    .build();
            containerId = lifecycleManager.create(spec);
            containers.put(instanceId, containerId);
            if (cancelled.contains(instanceId)) {
                containers.remove(instanceId);
                lifecycleManager.stopAndRemove(containerId, null);
                report(launch, outcome, false, "Node provisioning was cancelled.");
                return;
            }
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/")
                    .withTarInputStream(new ByteArrayInputStream(archive))
                    .exec();
            lifecycleManager.startCreated(containerId, spec);
            logs = logStreamer.attachForAccount(launch.accountId(), containerId,
                    "/aws/sagemaker/Clusters/" + launch.clusterName() + "/" + launch.clusterId(),
                    "LifecycleConfig/" + launch.instanceGroupName() + "/" + instanceId, launch.region(),
                    "sagemaker-hyperpod:" + launch.clusterName());
            Integer exit = waitForExit(containerId, instanceId);
            containers.remove(instanceId);
            lifecycleManager.stopAndRemove(containerId, logs);
            if (cancelled.contains(instanceId)) {
                report(launch, outcome, false, "Node provisioning was cancelled.");
            } else if (exit == null) {
                report(launch, outcome, false, "Lifecycle script " + launch.onCreate() + " did not complete within "
                        + LIFECYCLE_TIMEOUT.toMinutes() + " minutes.");
            } else if (exit == 0) {
                report(launch, outcome, true, null);
            } else {
                report(launch, outcome, false, "Lifecycle script " + launch.onCreate() + " exited with code " + exit + ".");
            }
        } catch (Exception e) {
            LOG.warnv("HyperPod node {0} of cluster {1} failed to provision: {2}", instanceId, launch.clusterName(),
                    e.getMessage());
            if (containerId != null && containers.remove(instanceId) != null) {
                lifecycleManager.stopAndRemove(containerId, logs);
            }
            report(launch, outcome, false, "Node provisioning failed: " + e.getMessage());
            if (e instanceof InterruptedException) {
                // Interrupted by a teardown's shutdownNow(): keep the flag for the pool thread.
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Reports unless cancelled, then leaves the in-flight set, in that order (see {@link #inFlight}). */
    private void report(NodeLaunch launch, NodeOutcome outcome, boolean succeeded, String message) {
        try {
            if (!cancelled.contains(launch.instanceId())) {
                outcome.completed(succeeded, message);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not record the outcome of HyperPod node {0}: {1}", launch.instanceId(), e.getMessage());
        } finally {
            inFlight.remove(launch.instanceId());
            cancelled.remove(launch.instanceId());
        }
    }

    /** The lifecycle scripts under {@code SourceS3Uri} plus the node's resource config, as a tar rooted at /. */
    private byte[] nodeArchive(NodeLaunch launch) throws Exception {
        S3Uri source = S3Uri.parse(launch.sourceS3Uri());
        String folder = source.key().isBlank() || source.key().endsWith("/") ? source.key() : source.key() + "/";
        String onCreate = safeRelativePath(launch.onCreate());
        boolean onCreateFound = false;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (S3Object object : s3Service.listObjects(source.bucket(), folder, null, MAX_LIFECYCLE_OBJECTS)) {
                if (!object.getKey().startsWith(folder) || object.getKey().endsWith("/")) {
                    continue;
                }
                String relative = safeRelativePath(object.getKey().substring(folder.length()));
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                onCreateFound |= relative.equals(onCreate);
                addExecutable(tar, LIFECYCLE_DIR.substring(1) + "/" + relative,
                        s3Service.getObject(source.bucket(), object.getKey()).getData());
            }
            addExecutable(tar, RESOURCE_CONFIG_PATH.substring(1),
                    launch.resourceConfigJson().getBytes(StandardCharsets.UTF_8));
        }
        if (!onCreateFound) {
            throw new IllegalStateException("Lifecycle script " + launch.onCreate() + " was not found under "
                    + launch.sourceS3Uri() + ".");
        }
        return out.toByteArray();
    }

    private static void addExecutable(TarArchiveOutputStream tar, String path, byte[] data) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(path);
        entry.setSize(data.length);
        entry.setMode(EXECUTABLE_FILE_MODE);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    /** Null for an absolute path or one that escapes the lifecycle directory via "..". */
    static String safeRelativePath(String relative) {
        Path normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            return null;
        }
        return normalized.toString();
    }

    private List<String> environment(NodeLaunch launch) {
        List<String> env = new ArrayList<>();
        env.add("SAGEMAKER_LIFECYCLE_DIR=" + LIFECYCLE_DIR);
        env.add("SAGEMAKER_LIFECYCLE_ON_CREATE=" + launch.onCreate());
        env.add("SAGEMAKER_CLUSTER_NAME=" + launch.clusterName());
        env.add("SAGEMAKER_CLUSTER_ARN=" + launch.clusterArn());
        env.add("SAGEMAKER_INSTANCE_GROUP_NAME=" + launch.instanceGroupName());
        env.add("SAGEMAKER_INSTANCE_ID=" + launch.instanceId());
        env.add("SAGEMAKER_INSTANCE_TYPE=" + launch.instanceType());
        env.add("AWS_REGION=" + launch.region());
        env.add("AWS_DEFAULT_REGION=" + launch.region());
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("AWS_SESSION_TOKEN=test");
        String endpoint = "http://" + endpointHostname() + ":" + config.port();
        env.add("AWS_ENDPOINT_URL=" + endpoint);
        env.add("FLOCI_ENDPOINT=" + endpoint);
        return env;
    }

    private String endpointHostname() {
        return containerDetector.isRunningInContainer()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : "host.docker.internal";
    }

    /** The exit code, or null when the script outlived the lifecycle timeout or the node was cancelled. */
    private Integer waitForExit(String containerId, String instanceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LIFECYCLE_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && !cancelled.contains(instanceId)) {
            try {
                InspectContainerResponse inspect = lifecycleManager.getDockerClient()
                        .inspectContainerCmd(containerId).exec();
                if (!Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    Long exit = inspect.getState().getExitCodeLong();
                    return exit == null ? 0 : exit.intValue();
                }
            } catch (NotFoundException e) {
                return 1;
            }
            Thread.sleep(500);
        }
        return null;
    }

    @Override
    public synchronized void stopManagedContainers() {
        cancelled.addAll(inFlight);
        containers.forEach((instanceId, containerId) -> lifecycleManager.stopAndRemove(containerId, null));
        containers.clear();
        executor.shutdownNow();
    }

    @Override
    public synchronized void clear() {
        inFlight.clear();
        cancelled.clear();
    }

    /**
     * Runs at the end of every state reset, never on shutdown. The teardown shut the worker pool
     * down, so without a new one every later node would stay queued until the emulator restarted.
     */
    @Override
    public synchronized void afterReset() {
        if (executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(MAX_CONCURRENT_NODES);
        }
    }

    boolean acceptsWork() {
        return !executor.isShutdown();
    }
}
