package io.floci.aws.services.osis;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
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
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs OpenSearch Ingestion pipelines on Data Prepper: one container per pipeline, running the pipeline's
 * configuration with a session for its role. Push sources are published so Floci's ingest endpoint can forward to
 * them, and with logging on, the container's log goes to the pipeline's CloudWatch Logs group.
 */
@ApplicationScoped
public class OsisEngine implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(OsisEngine.class);
    private static final int CORE_PORT = 4900;
    private static final String HOME = "/usr/share/data-prepper";

    /** A pipeline's running container: where its push sources and core API answer. */
    record Backend(String containerId, String host, int corePort, Map<Integer, EndpointInfo> sources,
                   Optional<SessionCreds> session, Closeable logs) {
    }

    /** What a pipeline runs: its rendered configuration, the role its container holds, and where it logs. */
    record Launch(String account, String region, String pipeline, String configuration, List<Integer> ports,
                  String role, String logGroup, Set<String> buckets) {
    }

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ContainerDetector detector;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roles;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Backend> backends = new ConcurrentHashMap<>();

    @Inject
    public OsisEngine(ContainerBuilder containers, ContainerLifecycleManager lifecycle, ContainerDetector detector,
                      LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roles,
                      ContainerLogStreamer logStreamer, EmulatorConfig config) {
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.detector = detector;
        this.awsEnv = awsEnv;
        this.roles = roles;
        this.logStreamer = logStreamer;
        this.config = config;
    }

    private EmulatorConfig.OsisServiceConfig settings() {
        return config.services().osis();
    }

    boolean enabled() {
        return "data-prepper".equalsIgnoreCase(settings().engine());
    }

    private static String key(String account, String region, String pipeline) {
        return account + "/" + region + "/" + pipeline;
    }

    private String containerName(String account, String region, String pipeline) {
        return ContainerStorageHelper.resourceName(config, "osis", null, account + "-" + region + "-" + pipeline);
    }

    Backend backend(String account, String region, String pipeline) {
        return backends.get(key(account, region, pipeline));
    }

    /** Starts (or restarts) a pipeline's container and waits until Data Prepper runs its pipelines. */
    synchronized Backend start(Launch launch) {
        remove(launch.account(), launch.region(), launch.pipeline());
        String name = containerName(launch.account(), launch.region(), launch.pipeline());
        lifecycle.removeIfExists(name, true);
        Optional<SessionCreds> session = Optional.empty();
        if (launch.role() != null && !launch.role().isBlank()) {
            session = roles.forRole(launch.role(), launch.account(), "osis-" + launch.pipeline());
        }
        List<String> env = new ArrayList<>(awsEnv.sdkBaselineEnv(launch.region(), Optional.empty(), session,
                launch.account()));
        env.add("JAVA_OPTS=-Xms128m -Xmx" + settings().heapMb() + "m");
        ContainerBuilder.Builder builder = containers.newContainer(settings().image()).withName(name)
                .withEnv(env)
                .withDockerNetwork(config.services().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(OsisStore.SERVICE, launch.pipeline(),
                        launch.account(), launch.region()));
        bucketHosts(builder, launch.buckets());
        List<Integer> ports = new ArrayList<>(launch.ports());
        ports.add(CORE_PORT);
        for (int port : ports) {
            if (!detector.isRunningInContainer()) {
                builder.withDynamicPort(port);
            } else {
                builder.withExposedPort(port);
            }
        }
        ContainerSpec spec = builder.build();
        String id = lifecycle.create(spec);
        Backend backend;
        try {
            copyIn(id, HOME + "/pipelines", Map.of("pipelines.yaml", launch.configuration()));
            copyIn(id, HOME + "/config", Map.of("data-prepper-config.yaml", "ssl: false\n"));
            ContainerInfo info = lifecycle.startCreated(id, spec);
            Map<Integer, EndpointInfo> sources = new LinkedHashMap<>();
            launch.ports().forEach(port -> sources.put(port, info.getEndpoint(port)));
            EndpointInfo core = info.getEndpoint(CORE_PORT);
            Closeable logs = null;
            if (launch.logGroup() != null) {
                logs = logStreamer.attachForAccount(launch.account(), id, launch.logGroup(),
                        "pipeline/" + launch.pipeline(), launch.region(), "osis:" + launch.pipeline());
            }
            backend = new Backend(id, core.host(), core.port(), sources, session, logs);
        } catch (RuntimeException e) {
            lifecycle.stopAndRemove(id, null, 5, true);
            session.ifPresent(creds -> roles.unregister(launch.account(), creds.accessKeyId()));
            throw e;
        }
        backends.put(key(launch.account(), launch.region(), launch.pipeline()), backend);
        try {
            await(backend, launch.pipeline());
        } catch (RuntimeException e) {
            remove(launch.account(), launch.region(), launch.pipeline());
            throw e;
        }
        LOG.infov("OpenSearch Ingestion pipeline {0} runs on Data Prepper ({1})", launch.pipeline(), id);
        return backend;
    }

    /**
     * The SDK in Data Prepper addresses buckets virtual-host style, {@code <bucket>.<Floci's host>}. Floci's DNS answers
     * those names; where a container doesn't get it, as when Floci runs outside Docker, each bucket the configuration
     * names is mapped to Floci's host instead.
     */
    private void bucketHosts(ContainerBuilder.Builder builder, Set<String> buckets) {
        String host = URI.create(awsEnv.flociEndpoint()).getHost();
        if (host == null || host.endsWith(EmbeddedDnsServer.DEFAULT_SUFFIX)
                || config.hostname().map(host::equals).orElse(false)) {
            return;
        }
        String address = host.matches("[0-9.]+") ? host : "host.docker.internal".equals(host) ? "host-gateway" : null;
        if (address == null) {
            try {
                address = InetAddress.getByName(host).getHostAddress();
            } catch (UnknownHostException e) {
                LOG.debugv("Pipeline buckets can't be mapped to {0}: {1}", host, e.getMessage());
                return;
            }
        }
        for (String bucket : buckets) {
            builder.withExtraHost(bucket + "." + host, address);
        }
    }

    private void copyIn(String id, String directory, Map<String, String> files) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not package the pipeline configuration.", e);
        }
        lifecycle.getDockerClient().copyArchiveToContainerCmd(id).withRemotePath(directory)
                .withTarInputStream(new ByteArrayInputStream(archive.toByteArray())).exec();
    }

    private void await(Backend backend, String pipeline) {
        long deadline = System.currentTimeMillis() + settings().readySeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ready(backend)) {
                return;
            }
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient()
                    .inspectContainerCmd(backend.containerId()).exec().getState();
            if (state != null && Boolean.FALSE.equals(state.getRunning())) {
                throw new IllegalStateException("Data Prepper stopped while starting the pipeline: "
                        + failure(backend.containerId()));
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the pipeline started.", e);
            }
        }
        throw new IllegalStateException("The pipeline " + pipeline + "'s Data Prepper didn't start within "
                + settings().readySeconds() + " seconds.");
    }

    /** Data Prepper's core API lists the pipelines once they run. */
    private boolean ready(Backend backend) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://" + backend.host()
                    + ":" + backend.corePort() + "/list")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().contains("\"name\"")) {
                return false;
            }
            // Docker's port proxy accepts connections before the source listens, so ask the source itself: any
            // HTTP answer means it's up.
            for (EndpointInfo source : backend.sources().values()) {
                http.send(HttpRequest.newBuilder(URI.create("http://" + source.host() + ":" + source.port() + "/"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            }
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The last error lines Data Prepper logged. */
    private String failure(String containerId) {
        StringBuilder log = new StringBuilder();
        try {
            lifecycle.getDockerClient().logContainerCmd(containerId).withStdOut(true).withStdErr(true).withTail(200)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            log.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            return "its log can't be read";
        }
        List<String> errors = new ArrayList<>();
        for (String line : log.toString().split("\n")) {
            if ((line.contains(" ERROR ") || line.startsWith("Caused by:")) && errors.size() < 3) {
                errors.add(line.trim());
            }
        }
        return errors.isEmpty() ? "it exited" : String.join(" ", errors);
    }

    /** Whether a running pipeline's container is still up. */
    boolean running(String account, String region, String pipeline) {
        Backend backend = backend(account, region, pipeline);
        if (backend == null) {
            return false;
        }
        try {
            InspectContainerResponse.ContainerState state = lifecycle.getDockerClient()
                    .inspectContainerCmd(backend.containerId()).exec().getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Removes a pipeline's container and revokes its role session. */
    synchronized void remove(String account, String region, String pipeline) {
        Backend backend = backends.remove(key(account, region, pipeline));
        if (backend == null) {
            return;
        }
        if (backend.logs() != null) {
            try {
                backend.logs().close();
            } catch (IOException e) {
                LOG.debugv("Could not close pipeline {0}'s log stream: {1}", pipeline, e.getMessage());
            }
        }
        lifecycle.stopAndRemove(backend.containerId(), null, 5, true);
        backend.session().ifPresent(creds -> roles.unregister(account, creds.accessKeyId()));
    }

    @Override
    public void stopManagedContainers() {
        for (String key : new ArrayList<>(backends.keySet())) {
            String[] parts = key.split("/", 3);
            remove(parts[0], parts[1], parts[2]);
        }
    }
}
