package io.floci.aws.services.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the open source Grafana containers that back Amazon Managed Grafana workspaces, one per workspace, and makes
 * the service accounts and tokens a workspace's API hands out in its Grafana, through the admin API. A workspace's
 * dashboards and settings live on a per-workspace volume, so restarts and version upgrades keep them.
 */
@ApplicationScoped
public class GrafanaContainerManager implements ContainerTeardown {

    static final String SERVICE = "grafana";
    static final int GRAFANA_PORT = 3000;
    private static final Logger LOG = Logger.getLogger(GrafanaContainerManager.class);
    private static final String DATA_PATH = "/var/lib/grafana";
    private static final int READINESS_RETRY_MS = 500;

    /** Where a workspace's Grafana answers, and the admin credentials floci manages it with. */
    public record Endpoint(String containerId, String host, int port, String adminPassword) {
        String url() {
            return "http://" + host + ":" + port;
        }
    }

    /** A service account token Grafana made: its id and secret. */
    public record Token(String id, String key) {
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    public GrafanaContainerManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
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

    /**
     * Starts (or restarts, with new settings or a new version) a workspace's Grafana and waits until it answers.
     * {@code rootUrl} is the workspace endpoint, so Grafana's links and redirects name it.
     */
    public Endpoint start(String workspaceId, String accountId, String region, String version, String rootUrl,
                          String adminPassword, Map<String, String> settings) {
        EmulatorConfig.GrafanaServiceConfig serviceConfig = config.services().grafana();
        String image = serviceConfig.imageRepository() + ":" + GrafanaVersions.release(version);
        String name = containerName(workspaceId);
        LOG.infov("Starting Grafana container {0} for workspace {1} using image {2}", name, workspaceId, image);
        lifecycleManager.removeIfExists(name);
        release(workspaceId);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(name)
                .withDockerNetwork(serviceConfig.dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, workspaceId, accountId, region))
                .withEnv("GF_SECURITY_ADMIN_USER", "admin")
                .withEnv("GF_SECURITY_ADMIN_PASSWORD", adminPassword)
                .withEnv("GF_SERVER_ROOT_URL", rootUrl)
                .withEnv("GF_ANALYTICS_REPORTING_ENABLED", "false")
                .withEnv("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
                .withEnv("GF_USERS_ALLOW_SIGN_UP", "false");
        serviceConfig.anonymousRole().filter(role -> !role.isBlank()).ifPresent(role -> builder
                .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "true").withEnv("GF_AUTH_ANONYMOUS_ORG_ROLE", role));
        settings.forEach(builder::withEnv);
        int hostPort = portAllocator.allocate(serviceConfig.hostPortBase(), serviceConfig.hostPortMax());
        builder.withPortBinding(GRAFANA_PORT, hostPort);
        ContainerInfo info;
        try {
            applyVolume(builder, workspaceId);
            info = lifecycleManager.createAndStart(builder.build());
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
        running.put(workspaceId, new Running(info.containerId(), hostPort));
        EndpointInfo endpoint = info.getEndpoint(GRAFANA_PORT);
        Endpoint started = new Endpoint(info.containerId(), endpoint.host(), endpoint.port(), adminPassword);
        waitUntilReady(started);
        LOG.infov("Grafana for workspace {0} is ready at {1}", workspaceId, started.url());
        return started;
    }

    private void waitUntilReady(Endpoint endpoint) {
        int timeoutSeconds = config.services().grafana().readinessTimeoutSeconds();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(endpoint.url()
                        + "/api/health")).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (health.statusCode() == 200 && health.body().contains("\"ok\"")) {
                    return;
                }
            } catch (IOException e) {
                LOG.debugv("Grafana at {0} is not ready: {1}", endpoint.url(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Grafana at " + endpoint.url(), e);
            }
            try {
                Thread.sleep(READINESS_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Grafana at " + endpoint.url(), e);
            }
        }
        throw new IllegalStateException("Grafana at " + endpoint.url() + " did not become healthy within "
                + timeoutSeconds + "s");
    }

    public void stop(String workspaceId, String containerId) {
        Running container = running.remove(workspaceId);
        if (container != null) {
            portAllocator.release(container.hostPort());
        }
        String id = containerId != null ? containerId : container != null ? container.containerId() : null;
        if (id != null) {
            lifecycleManager.stopAndRemove(id, null);
        } else {
            lifecycleManager.removeIfExists(containerName(workspaceId));
        }
    }

    public void removeStorage(String workspaceId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeStorage(config, lifecycleManager, SERVICE, null, workspaceId);
        }
    }

    @Override
    public void stopManagedContainers() {
        for (String workspaceId : new ArrayList<>(running.keySet())) {
            stop(workspaceId, null);
        }
    }

    private void release(String workspaceId) {
        Running previous = running.remove(workspaceId);
        if (previous != null) {
            portAllocator.release(previous.hostPort());
        }
    }

    private void applyVolume(ContainerBuilder.Builder builder, String workspaceId) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(builder, lifecycleManager, config, SERVICE, null, workspaceId,
                    DATA_PATH);
            return;
        }
        Path base = ContainerStorageHelper.hostResourcePath(config, SERVICE, workspaceId);
        String dataDir = base.resolve("data").toAbsolutePath().toString();
        if (!containerDetector.isRunningInContainer()) {
            ContainerStorageHelper.ensureHostDir(dataDir);
        }
        // The image runs as user 472, which a directory made on the host may not let it write.
        builder.withBind(dataDir, DATA_PATH).withUser("0");
    }

    static String containerName(String workspaceId) {
        return "floci-grafana-" + workspaceId;
    }

    // ---------------------------------------------------------------- Grafana's admin API

    /** Makes a service account in the workspace's Grafana; returns its id. */
    public String createServiceAccount(Endpoint endpoint, String name, String role) {
        JsonNode created = call(endpoint, "POST", "/api/serviceaccounts", mapper.createObjectNode()
                .put("name", name).put("role", role).put("isDisabled", false));
        return created.path("id").asText();
    }

    public void deleteServiceAccount(Endpoint endpoint, String serviceAccountId) {
        call(endpoint, "DELETE", "/api/serviceaccounts/" + serviceAccountId, null);
    }

    public Token createToken(Endpoint endpoint, String serviceAccountId, String name, long secondsToLive) {
        JsonNode created = call(endpoint, "POST", "/api/serviceaccounts/" + serviceAccountId + "/tokens",
                mapper.createObjectNode().put("name", name).put("secondsToLive", secondsToLive));
        return new Token(created.path("id").asText(), created.path("key").asText());
    }

    public void deleteToken(Endpoint endpoint, String serviceAccountId, String tokenId) {
        call(endpoint, "DELETE", "/api/serviceaccounts/" + serviceAccountId + "/tokens/" + tokenId, null);
    }

    private JsonNode call(Endpoint endpoint, String method, String path, JsonNode body) {
        String credentials = Base64.getEncoder().encodeToString(("admin:" + endpoint.adminPassword())
                .getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.url() + path))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body.toString()));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Grafana answered " + method + " " + path + " with "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Grafana at " + endpoint.url() + " could not be reached: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Grafana at " + endpoint.url(), e);
        }
    }

    List<String> runningWorkspaces() {
        return List.copyOf(running.keySet());
    }
}
