package io.floci.aws.services.opensearchserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The search engine behind OpenSearch Serverless collections: an OpenSearch container per collection, with its
 * security plugin off, as Floci authorizes requests with the collection's data access policies before they reach it.
 */
@ApplicationScoped
public class AossEngine implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(AossEngine.class);
    private static final int PORT = 9200;

    /** A collection's running container. */
    record Backend(String containerId, String host, int port) {
        String url(String path) {
            return "http://" + host + ":" + port + path;
        }
    }

    /** An answer from a collection's OpenSearch. */
    record Answer(int status, String contentType, byte[] body) {
    }

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ContainerDetector detector;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Backend> backends = new ConcurrentHashMap<>();

    @Inject
    public AossEngine(ContainerBuilder containers, ContainerLifecycleManager lifecycle, ContainerDetector detector,
                      EmulatorConfig config) {
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.detector = detector;
        this.config = config;
    }

    private EmulatorConfig.OpenSearchServerlessServiceConfig settings() {
        return config.services().opensearchServerless();
    }

    boolean enabled() {
        return !"none".equalsIgnoreCase(settings().engine());
    }

    private static String key(String account, String region, String id) {
        return account + "/" + region + "/" + id;
    }

    Backend backend(String account, String region, String id) {
        return backends.get(key(account, region, id));
    }

    /** Starts a collection's OpenSearch and waits until it answers. */
    synchronized Backend start(String account, String region, String id) {
        Backend running = backend(account, region, id);
        if (running != null && ready(running)) {
            return running;
        }
        String name = ContainerStorageHelper.resourceName(config, "aoss", null, account + "-" + region + "-" + id);
        lifecycle.removeIfExists(name, true);
        String heap = settings().heapMb() + "m";
        ContainerBuilder.Builder builder = containers.newContainer(settings().image()).withName(name)
                .withEnv(List.of("discovery.type=single-node", "DISABLE_SECURITY_PLUGIN=true",
                        "DISABLE_INSTALL_DEMO_CONFIG=true", "OPENSEARCH_JAVA_OPTS=-Xms" + heap + " -Xmx" + heap,
                        "bootstrap.memory_lock=false"))
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(AossStore.SERVICE, id, account, region));
        builder.withDockerNetwork(config.services().dockerNetwork());
        if (!detector.isRunningInContainer()) {
            builder.withDynamicPort(PORT);
        } else {
            builder.withExposedPort(PORT);
        }
        ContainerSpec spec = builder.build();
        ContainerInfo info = lifecycle.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(PORT);
        Backend backend = new Backend(info.containerId(), endpoint.host(), endpoint.port());
        backends.put(key(account, region, id), backend);
        long deadline = System.currentTimeMillis() + settings().readySeconds() * 1000L;
        while (!ready(backend)) {
            if (System.currentTimeMillis() > deadline) {
                remove(account, region, id);
                throw new IllegalStateException("The collection's OpenSearch didn't start within "
                        + settings().readySeconds() + " seconds.");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the collection started.", e);
            }
        }
        LOG.infov("OpenSearch Serverless collection {0} runs on OpenSearch at {1}:{2}", id, endpoint.host(),
                String.valueOf(endpoint.port()));
        return backend;
    }

    private boolean ready(Backend backend) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(backend.url(
                    "/_cluster/health"))).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && !response.body().contains("\"red\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Sends a request to a collection's OpenSearch. */
    Answer send(Backend backend, String method, String path, String contentType, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(backend.url(path)))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Answer(response.statusCode(), response.headers().firstValue("Content-Type")
                    .orElse("application/json"), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("The collection's OpenSearch didn't answer: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The request was interrupted.");
        }
    }

    /** Removes a collection's container and its data. */
    synchronized void remove(String account, String region, String id) {
        Backend backend = backends.remove(key(account, region, id));
        if (backend != null) {
            lifecycle.stopAndRemove(backend.containerId(), null, 5, true);
        } else {
            lifecycle.removeIfExists(ContainerStorageHelper.resourceName(config, "aoss", null, account + "-" + region
                    + "-" + id), true);
        }
    }

    @Override
    public void stopManagedContainers() {
        for (String key : new ArrayList<>(backends.keySet())) {
            Backend backend = backends.remove(key);
            if (backend != null) {
                lifecycle.stopAndRemove(backend.containerId(), null, 5, true);
            }
        }
    }
}
