package io.floci.aws.services.healthlake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the HAPI FHIR R4 servers that back HealthLake data stores, one per data store, and reads and writes their
 * resources through the FHIR REST API. A server's database lives on a per-data-store volume, and its links name the
 * data store's endpoint, so what a client reads through the endpoint points back at it.
 */
@ApplicationScoped
public class HealthLakeFhirServers implements ContainerTeardown {

    static final String SERVICE = "healthlake";
    static final int FHIR_PORT = 8080;
    private static final Logger LOG = Logger.getLogger(HealthLakeFhirServers.class);
    private static final String DATA_PATH = "/data";
    private static final int PAGE = 500;

    /** Where a data store's FHIR server answers: its base URL, {@code http://host:port/fhir}. */
    public record Server(String containerId, String host, int port) {
        String base() {
            return "http://" + host + ":" + port + "/fhir";
        }
    }

    private record Running(String containerId, int hostPort) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    public HealthLakeFhirServers(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                 ContainerDetector containerDetector, PortAllocator portAllocator,
                                 EmulatorConfig config, ObjectMapper mapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
        this.mapper = mapper;
    }

    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    /** Starts a data store's FHIR server and waits until it serves its capability statement. */
    public Server start(String datastoreId, String accountId, String region, String endpoint) {
        EmulatorConfig.HealthLakeServiceConfig serviceConfig = config.services().healthlake();
        String name = containerName(datastoreId);
        LOG.infov("Starting FHIR server {0} for data store {1} using image {2}", name, datastoreId,
                serviceConfig.image());
        lifecycleManager.removeIfExists(name);
        release(datastoreId);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(serviceConfig.image())
                .withName(name)
                .withDockerNetwork(serviceConfig.dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, datastoreId, accountId, region))
                .withEnv("HAPI_FHIR_FHIR_VERSION", "R4")
                // HealthLake keeps the ids an import or a client gives, numeric ones included.
                .withEnv("HAPI_FHIR_CLIENT_ID_STRATEGY", "ANY")
                .withEnv("HAPI_FHIR_SERVER_ADDRESS", endpoint)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:h2:file:" + DATA_PATH + "/hapi");
        int hostPort = portAllocator.allocate(serviceConfig.hostPortBase(), serviceConfig.hostPortMax());
        builder.withPortBinding(FHIR_PORT, hostPort);
        ContainerInfo info;
        try {
            applyVolume(builder, datastoreId);
            info = lifecycleManager.createAndStart(builder.build());
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
        running.put(datastoreId, new Running(info.containerId(), hostPort));
        EndpointInfo published = info.getEndpoint(FHIR_PORT);
        Server server = new Server(info.containerId(), published.host(), published.port());
        waitUntilReady(server);
        LOG.infov("FHIR server for data store {0} is ready at {1}", datastoreId, server.base());
        return server;
    }

    private void waitUntilReady(Server server) {
        int timeoutSeconds = config.services().healthlake().readinessTimeoutSeconds();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            exitedEarly(server.containerId());
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(server.base()
                        + "/metadata")).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                LOG.debugv("FHIR server at {0} is not ready: {1}", server.base(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the FHIR server at " + server.base(), e);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the FHIR server at " + server.base(), e);
            }
        }
        throw new IllegalStateException("The FHIR server at " + server.base() + " did not answer within "
                + timeoutSeconds + "s");
    }

    /** Fails when the container has stopped: it will never answer, and its exit code says why. */
    private void exitedEarly(String containerId) {
        try {
            InspectContainerResponse.ContainerState state = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
            if (state != null && Boolean.FALSE.equals(state.getRunning())) {
                throw new IllegalStateException("The FHIR server container exited with code " + state.getExitCodeLong()
                        + " before it was ready.");
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            LOG.debugv("Could not inspect the FHIR server container: {0}", e.getMessage());
        }
    }

    public void stop(String datastoreId, String containerId) {
        Running container = running.remove(datastoreId);
        if (container != null) {
            portAllocator.release(container.hostPort());
        }
        String id = containerId != null ? containerId : container != null ? container.containerId() : null;
        if (id != null) {
            lifecycleManager.stopAndRemove(id, null);
        } else {
            lifecycleManager.removeIfExists(containerName(datastoreId));
        }
    }

    public void removeStorage(String datastoreId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeStorage(config, lifecycleManager, SERVICE, null, datastoreId);
        }
    }

    @Override
    public void stopManagedContainers() {
        for (String datastoreId : new ArrayList<>(running.keySet())) {
            stop(datastoreId, null);
        }
    }

    private void release(String datastoreId) {
        Running previous = running.remove(datastoreId);
        if (previous != null) {
            portAllocator.release(previous.hostPort());
        }
    }

    private void applyVolume(ContainerBuilder.Builder builder, String datastoreId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(builder, lifecycleManager, config, SERVICE, null, datastoreId,
                    DATA_PATH);
            // The image runs as a non-root user, which a volume Docker makes for root cannot be written by.
            builder.withUser("0");
            return;
        }
        Path base = ContainerStorageHelper.hostResourcePath(config, SERVICE, datastoreId);
        String dataDir = base.resolve("data").toAbsolutePath().toString();
        if (!containerDetector.isRunningInContainer()) {
            ContainerStorageHelper.ensureHostDir(dataDir);
        }
        builder.withBind(dataDir, DATA_PATH).withUser("0");
    }

    static String containerName(String datastoreId) {
        return "floci-healthlake-" + datastoreId;
    }

    // ---------------------------------------------------------------- the FHIR REST API

    /**
     * Writes a resource: with an id, to that id (created or updated); without one, created with an id the server
     * assigns. Returns the stored resource, or throws with the server's OperationOutcome when it is rejected.
     */
    public JsonNode write(Server server, JsonNode resource) {
        String type = resource.path("resourceType").asText();
        boolean identified = resource.hasNonNull("id");
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.base() + "/" + type
                        + (identified ? "/" + resource.get("id").asText() : "")))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/fhir+json")
                .method(identified ? "PUT" : "POST", HttpRequest.BodyPublishers.ofString(resource.toString()))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new IllegalArgumentException(outcome(response.body(), response.statusCode()));
        }
        return read(response.body());
    }

    /** Every current resource of the server, by type: the latest version of each, deleted ones left out. */
    public Map<String, List<JsonNode>> all(Server server) {
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        Set<String> deleted = new LinkedHashSet<>();
        String url = server.base() + "/_history?_count=" + PAGE;
        while (url != null) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60)).GET().build());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(outcome(response.body(), response.statusCode()));
            }
            JsonNode bundle = read(response.body());
            for (JsonNode entry : bundle.path("entry")) {
                String key = key(entry);
                if (key == null || latest.containsKey(key) || deleted.contains(key)) {
                    continue;
                }
                if ("DELETE".equals(entry.path("request").path("method").asText()) || !entry.has("resource")) {
                    deleted.add(key);
                } else {
                    latest.put(key, entry.get("resource"));
                }
            }
            url = null;
            for (JsonNode link : bundle.path("link")) {
                if ("next".equals(link.path("relation").asText())) {
                    url = local(server, link.path("url").asText());
                }
            }
        }
        Map<String, List<JsonNode>> byType = new LinkedHashMap<>();
        latest.values().forEach(resource -> byType.computeIfAbsent(resource.path("resourceType").asText(),
                type -> new ArrayList<>()).add(resource));
        return byType;
    }

    private static String key(JsonNode entry) {
        if (entry.has("resource")) {
            return entry.get("resource").path("resourceType").asText() + "/" + entry.get("resource").path("id")
                    .asText();
        }
        String fullUrl = entry.path("fullUrl").asText(entry.path("request").path("url").asText(""));
        String[] parts = fullUrl.split("/");
        for (int i = parts.length - 1; i > 0; i--) {
            if ("_history".equals(parts[i]) && i >= 2) {
                return parts[i - 2] + "/" + parts[i - 1];
            }
        }
        return parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : null;
    }

    /** A paging link HAPI writes with the data store endpoint as its base, pointed back at the server itself. */
    private static String local(Server server, String link) {
        int at = link.indexOf("?");
        return at < 0 ? null : server.base() + link.substring(at);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("The FHIR server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the FHIR server", e);
        }
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("The FHIR server answered with something that is not JSON: "
                    + e.getMessage());
        }
    }

    /** The diagnostics of an OperationOutcome, or the status when there is none. */
    private String outcome(String body, int status) {
        try {
            JsonNode outcome = mapper.readTree(body);
            List<String> messages = new ArrayList<>();
            outcome.path("issue").forEach(issue -> messages.add(issue.path("diagnostics").asText(issue
                    .path("details").path("text").asText(""))));
            messages.removeIf(String::isBlank);
            if (!messages.isEmpty()) {
                return String.join("; ", messages);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugv("The FHIR server's error is not an OperationOutcome: {0}", e.getMessage());
        }
        return "The FHIR server answered " + status + ".";
    }
}
