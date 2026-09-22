package io.github.hectorvent.floci.services.aps;

import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.TmpfsOptions;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Owns an ephemeral, real Prometheus TSDB for each workspace that uses the data plane. */
@ApplicationScoped
public class ApsPrometheusBackend implements Resettable {

    static final String IMAGE = "prom/prometheus:v3.5.0";
    static final int PORT = 9090;
    static final int MAX_BACKENDS = 8;
    static final Duration READINESS_TIMEOUT = Duration.ofSeconds(30);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);
    private static final Logger LOG = Logger.getLogger(ApsPrometheusBackend.class);
    private static final List<String> FORWARDED_HEADERS = List.of(
            "content-type", "content-encoding", "x-prometheus-remote-write-version", "accept");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final HttpClient client;
    private final Map<String, Runtime> runtimes = new HashMap<>();
    private final ReentrantReadWriteLock requests = new ReentrantReadWriteLock(true);
    private boolean stopping;

    @Inject
    public ApsPrometheusBackend(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                EmulatorConfig config) {
        this(containerBuilder, lifecycleManager, config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ApsPrometheusBackend(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                         EmulatorConfig config, HttpClient client) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.client = client;
    }

    public BackendResponse forward(String arn, String method, String path, String rawQuery,
                                   Map<String, String> headers, byte[] body) {
        requests.readLock().lock();
        try {
            return forwardRequest(arn, method, path, rawQuery, headers, body);
        } finally {
            requests.readLock().unlock();
        }
    }

    private BackendResponse forwardRequest(String arn, String method, String path, String rawQuery,
                                           Map<String, String> headers, byte[] body) {
        Runtime runtime = ensureReady(arn);
        URI target = URI.create(runtime.url() + path + (rawQuery == null ? "" : "?" + rawQuery));
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT)
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : FORWARDED_HEADERS) {
            String value = headers.get(name);
            if (value != null) {
                request.header(name, value);
            }
        }
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new BackendResponse(response.statusCode(),
                    response.headers().firstValue("content-type").orElse("application/json"), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Prometheus request was interrupted");
        } catch (IOException e) {
            LOG.warnv(e, "AMP backend request failed for {0}", arn);
            throw unavailable("Prometheus backend is unavailable");
        }
    }

    private synchronized Runtime ensureReady(String arn) {
        if (stopping) {
            throw unavailable("Prometheus backends are shutting down");
        }
        Runtime existing = runtimes.get(arn);
        if (existing != null && existing.url() != null) {
            return existing;
        }
        if (existing != null) {
            remove(arn);
        }
        if (runtimes.size() >= MAX_BACKENDS) {
            throw new AwsException("ThrottlingException", "Local AMP supports at most " + MAX_BACKENDS
                    + " active Prometheus backends; delete an unused workspace", 429);
        }
        String name = containerName(arn);
        runtimes.put(arn, new Runtime(name, null));
        try {
            lifecycleManager.removeIfExistsStrict(name);
            ContainerInfo info = lifecycleManager.createAndStart(spec(arn, name));
            Runtime runtime = new Runtime(info.containerId(), "http://" + info.getEndpoint(PORT));
            awaitReady(runtime.url());
            runtimes.put(arn, runtime);
            LOG.infov("Started AMP Prometheus backend for {0}", arn);
            return runtime;
        } catch (RuntimeException e) {
            try {
                remove(arn);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            LOG.warnv(e, "Failed to start AMP Prometheus backend for {0}", arn);
            throw unavailable("Prometheus backend failed to start; ensure Docker and " + IMAGE + " are available");
        }
    }

    ContainerSpec spec(String arn, String name) {
        AwsArnUtils.Arn resource = AwsArnUtils.parse(arn);
        return containerBuilder.newContainer(IMAGE)
                .withName(name)
                .withLoopbackPortBinding(PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withMemoryMb(512)
                .withMount(new Mount().withType(MountType.TMPFS).withTarget("/prometheus")
                        .withTmpfsOptions(new TmpfsOptions().withSizeBytes(256L * 1024 * 1024).withMode(01777)))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("aps", resource.resource(),
                        resource.accountId(), resource.region()))
                .withLogRotation()
                .withCmd(List.of(
                        "--config.file=/dev/null",
                        "--storage.tsdb.path=/prometheus",
                        "--storage.tsdb.retention.time=24h",
                        "--storage.tsdb.retention.size=128MB",
                        "--storage.tsdb.wal-segment-size=16MB",
                        "--web.listen-address=0.0.0.0:9090",
                        "--web.enable-remote-write-receiver",
                        "--query.timeout=30s",
                        "--query.max-concurrency=4",
                        "--query.max-samples=1000000"))
                .build();
    }

    private void awaitReady(String url) {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/-/ready"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                LOG.debugv("Waiting for AMP Prometheus readiness: {0}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unavailable("Prometheus startup was interrupted");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unavailable("Prometheus startup was interrupted");
            }
        }
        throw unavailable("Prometheus did not become ready within " + READINESS_TIMEOUT.toSeconds() + " seconds");
    }

    public synchronized void remove(String arn) {
        Runtime runtime = runtimes.get(arn);
        if (runtime == null) {
            // The workspace persists ownership before startup, including across a JVM restart.
            lifecycleManager.removeIfExistsStrict(containerName(arn));
        } else {
            lifecycleManager.stopAndRemoveStrict(runtime.containerId(), null);
        }
        runtimes.remove(arn);
    }

    String containerName(String arn) {
        AwsArnUtils.Arn resource = AwsArnUtils.parse(arn);
        return ContainerStorageHelper.dockerName(config, "floci-aps-" + resource.accountId() + "-"
                + resource.region() + "-" + resource.resource().substring("workspace/".length()));
    }

    @Override
    public void beforeReset() {
        requests.writeLock().lock();
        try {
            synchronized (this) {
                stopping = true;
            }
        } finally {
            requests.writeLock().unlock();
        }
    }

    @Override
    public synchronized void clear() {
        RuntimeException failure = null;
        for (String arn : List.copyOf(runtimes.keySet())) {
            try {
                remove(arn);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized void afterReset() {
        stopping = false;
    }

    void onStop(@Observes ShutdownEvent event) {
        beforeReset();
        try {
            clear();
        } finally {
            client.close();
        }
    }

    private static AwsException unavailable(String message) {
        return new AwsException("ServiceUnavailableException", message, 503);
    }

    private record Runtime(String containerId, String url) {}

    public record BackendResponse(int status, String contentType, byte[] body) {}
}
