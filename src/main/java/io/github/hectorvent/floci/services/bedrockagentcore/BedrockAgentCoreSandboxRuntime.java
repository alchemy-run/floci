package io.github.hectorvent.floci.services.bedrockagentcore;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Runs the containers behind AgentCore code interpreter and browser sessions.
 *
 * <p>One session is one container, launched with a memory limit and a one-vCPU cap and removed
 * when the session stops or expires. Containers are tracked in memory only: after a restart or a
 * state reset none are tracked, and the session services report the sessions that referenced them
 * as terminated. Command output captured from a sandbox is capped at {@link #MAX_CAPTURE_BYTES}
 * per stream, and file reads at {@link #MAX_FILE_BYTES}, so a runaway program cannot exhaust the
 * emulator's heap.
 */
@ApplicationScoped
public class BedrockAgentCoreSandboxRuntime implements ContainerTeardown, Resettable {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreSandboxRuntime.class);
    private static final String SERVICE = "bedrock-agentcore";
    static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final ImageCacheService imageCacheService;
    private final EmulatorConfig config;
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();
    private final Set<String> prewarmed = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService prewarmExecutor = Executors.newSingleThreadExecutor(daemon());

    @Inject
    public BedrockAgentCoreSandboxRuntime(ContainerBuilder containerBuilder,
                                          ContainerLifecycleManager lifecycleManager,
                                          ContainerDetector containerDetector,
                                          PortAllocator portAllocator,
                                          ImageCacheService imageCacheService,
                                          EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.imageCacheService = imageCacheService;
        this.config = config;
    }

    /**
     * What to launch for one session.
     *
     * @param kind short tool name used in the container name, e.g. {@code code-interpreter}
     * @param isolated run with no network at all, which is what a SANDBOX network mode means
     * @param servicePort container port Floci must reach, or null when none is served
     */
    public record SandboxSpec(String kind, String sessionId, String region, String accountId, String image,
                              int memoryMb, List<String> cmd, String workingDir, boolean isolated,
                              Integer servicePort) {}

    /** A running session container and where Floci reaches its service port, if it has one. */
    public record Sandbox(String containerId, String serviceHost, Integer servicePort) {}

    /** The outcome of one command run inside a sandbox. */
    public record ExecResult(String stdout, String stderr, int exitCode, long durationMillis, boolean timedOut) {}

    public Sandbox launch(SandboxSpec spec) {
        String name = ContainerStorageHelper.dockerName(config,
                "floci-agentcore-" + spec.kind() + "-" + spec.sessionId().toLowerCase(Locale.ROOT));
        ContainerBuilder.Builder builder = containerBuilder.newContainer(spec.image())
                .withName(name)
                .withMemoryMb(spec.memoryMb())
                .withCpuUnits(1024)
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        SERVICE, spec.kind() + "/" + spec.sessionId(), spec.accountId(), spec.region()));
        if (spec.cmd() != null && !spec.cmd().isEmpty()) {
            builder.withCmd(spec.cmd());
        }
        if (spec.workingDir() != null) {
            builder.withWorkingDir(spec.workingDir());
        }
        if (spec.isolated()) {
            builder.withNetworkMode("none");
        } else {
            builder.withDockerNetwork(config.services().bedrockAgentCore().dockerNetwork());
        }
        if (spec.servicePort() != null) {
            if (containerDetector.isRunningInContainer()) {
                builder.withExposedPort(spec.servicePort());
            } else {
                builder.withLoopbackPortBinding(spec.servicePort(), portAllocator.allocateAny());
            }
        }
        ContainerSpec containerSpec = builder.build();
        ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(containerSpec);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            LOG.warnv(e, "Could not start the {0} container for session {1}", spec.kind(), spec.sessionId());
            throw new AwsException("InternalServerException",
                    "Could not start the " + spec.kind() + " session container: " + e.getMessage(), 500);
        }
        tracked.add(info.containerId());
        if (spec.servicePort() == null) {
            return new Sandbox(info.containerId(), null, null);
        }
        EndpointInfo endpoint = info.getEndpoint(spec.servicePort());
        return new Sandbox(info.containerId(), endpoint.host(), endpoint.port());
    }

    /** Whether this process launched the container and has not removed it. No Docker call is made. */
    public boolean isTracked(String containerId) {
        return containerId != null && tracked.contains(containerId);
    }

    /** Whether this process launched the container and still believes it is running. */
    public boolean isLive(String containerId) {
        return containerId != null && tracked.contains(containerId) && lifecycleManager.isContainerRunning(containerId);
    }

    public void remove(String containerId) {
        if (containerId == null) {
            return;
        }
        tracked.remove(containerId);
        lifecycleManager.stopAndRemove(containerId, null, 0);
    }

    /**
     * Runs {@code command} in the container, bounded by {@code timeoutSeconds}. The command is
     * passed as positional arguments to a fixed shell script, so nothing in it is interpreted by
     * a shell, and the script applies coreutils {@code timeout} when the image has it.
     */
    public ExecResult exec(String containerId, List<String> command, String workingDir, int timeoutSeconds) {
        List<String> argv = new ArrayList<>();
        argv.add("sh");
        argv.add("-c");
        argv.add("if command -v timeout >/dev/null 2>&1; then exec timeout -k 1 " + timeoutSeconds
                + " \"$@\"; fi; exec \"$@\"");
        argv.add("sh");
        argv.addAll(command);

        DockerClient docker = lifecycleManager.getDockerClient();
        ExecCreateCmd create = docker.execCreateCmd(containerId)
                .withCmd(argv.toArray(new String[0]))
                .withAttachStdout(true)
                .withAttachStderr(true);
        if (workingDir != null) {
            create.withWorkingDir(workingDir);
        }
        String execId = create.exec().getId();
        CappedBuffer stdout = new CappedBuffer();
        CappedBuffer stderr = new CappedBuffer();
        CountDownLatch done = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        ResultCallback<Frame> callback = docker.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload != null) {
                    (frame.getStreamType() == StreamType.STDERR ? stderr : stdout).write(payload);
                }
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                stderr.write(String.valueOf(throwable.getMessage()).getBytes(StandardCharsets.UTF_8));
                done.countDown();
            }
        });
        boolean completed;
        try {
            completed = done.await(timeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(callback);
            throw new AwsException("InternalServerException", "Interrupted while executing in the sandbox", 500);
        }
        long duration = System.currentTimeMillis() - start;
        if (!completed) {
            closeQuietly(callback);
            return new ExecResult(stdout.text(), stderr.text(), 124, duration, true);
        }
        Long exit = docker.inspectExecCmd(execId).exec().getExitCodeLong();
        int exitCode = exit == null ? 1 : exit.intValue();
        return new ExecResult(stdout.text(), stderr.text(), exitCode, duration, exitCode == 124 || exitCode == 137);
    }

    /** Writes each file at its absolute path; missing parent directories are created. */
    public void writeFiles(String containerId, Map<String, byte[]> files) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey().replaceFirst("^/+", ""));
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new AwsException("InternalServerException", "Could not package files: " + e.getMessage(), 500);
        }
        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                .withRemotePath("/")
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                .exec();
    }

    /** The file's bytes, or null when it does not exist or is not a regular file. */
    public byte[] readFile(String containerId, String path) {
        try (InputStream stream = lifecycleManager.getDockerClient()
                .copyArchiveFromContainerCmd(containerId, path).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(stream)) {
            TarArchiveEntry entry = tar.getNextEntry();
            if (entry == null || !entry.isFile()) {
                return null;
            }
            if (entry.getSize() > MAX_FILE_BYTES) {
                throw new AwsException("ValidationException",
                        "File " + path + " exceeds the " + MAX_FILE_BYTES + " byte read limit", 400);
            }
            return tar.readAllBytes();
        } catch (NotFoundException e) {
            return null;
        } catch (IOException e) {
            throw new AwsException("InternalServerException", "Could not read " + path + ": " + e.getMessage(), 500);
        }
    }

    /** Pulls an image in the background so the first session does not pay for it. */
    public void prewarm(String image) {
        if (!config.services().bedrockAgentCore().prewarmImages()
                || image == null || image.isBlank() || !prewarmed.add(image)) {
            return;
        }
        try {
            prewarmExecutor.submit(() -> {
                try {
                    imageCacheService.ensureImageExists(containerBuilder.resolveImage(image));
                } catch (RuntimeException e) {
                    prewarmed.remove(image);
                    LOG.debugv("Could not prewarm AgentCore sandbox image {0}: {1}", image, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            prewarmed.remove(image);
            LOG.debugv("AgentCore image prewarm not scheduled for {0}: {1}", image, e.getMessage());
        }
    }

    @Override
    public synchronized void stopManagedContainers() {
        for (String containerId : List.copyOf(tracked)) {
            lifecycleManager.stopAndRemove(containerId, null, 0);
        }
        tracked.clear();
        prewarmExecutor.shutdownNow();
    }

    @Override
    public void clear() {
        prewarmed.clear();
    }

    @Override
    public synchronized void afterReset() {
        if (prewarmExecutor.isShutdown()) {
            prewarmExecutor = Executors.newSingleThreadExecutor(daemon());
        }
    }

    private static ThreadFactory daemon() {
        return runnable -> {
            Thread thread = new Thread(runnable, "agentcore-image-prewarm");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The exec has already been abandoned; a failed close leaves nothing further to clean up.
        }
    }

    /** Accumulates stream output up to {@link #MAX_CAPTURE_BYTES}, dropping the excess. */
    private static final class CappedBuffer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        synchronized void write(byte[] bytes) {
            int room = MAX_CAPTURE_BYTES - buffer.size();
            if (room > 0) {
                buffer.write(bytes, 0, Math.min(room, bytes.length));
            }
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
