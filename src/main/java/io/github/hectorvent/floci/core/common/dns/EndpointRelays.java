package io.github.hectorvent.floci.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-endpoint relay containers: each gives one endpoint hostname an address of its own on the
 * container network, listening on the endpoint's port and relaying to a listener of Floci's
 * process. Used for an endpoint that must be served by Floci (an authenticating proxy, say) on a
 * port other endpoints also use: AWS gives every endpoint its own name and address, so any number
 * of them share a port, while Floci's own address can hold each port only once.
 */
@ApplicationScoped
public class EndpointRelays {

    private static final Logger LOG = Logger.getLogger(EndpointRelays.class);
    private static final String LABEL = "floci.endpoint-relay";

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final SourceNetworkHelper sourceHelper;
    /** Relay container id by endpoint hostname. */
    private final Map<String, String> relays = new ConcurrentHashMap<>();

    @Inject
    public EndpointRelays(EmulatorConfig config, ContainerBuilder containerBuilder,
                          ContainerLifecycleManager lifecycleManager, SourceNetworkHelper sourceHelper) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.sourceHelper = sourceHelper;
    }

    /**
     * Starts the relay for {@code hostname}, replacing any it had: connections to {@code port} on
     * the relay's address are relayed to {@code targetHost:targetPort}.
     *
     * @return the relay's address on the container network
     */
    public synchronized String start(String hostname, int port, String targetHost, int targetPort) {
        stop(hostname);
        sourceHelper.ensureImage();
        String network = config.services().lambda().dockerNetwork().orElse(null);
        String name = ContainerStorageHelper.dockerName(config, "floci-endpoint-relay-" + containerNameOf(hostname));
        lifecycleManager.removeIfExists(name);
        ContainerSpec spec = containerBuilder.newContainer(config.dns().sourceHelperImage())
                .withName(name)
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of(relayScript(port, targetHost, targetPort)))
                .withLogRotation()
                .withLabels(ContainerStorageHelper.defaultLabels(config))
                .withLabels(Map.of(LABEL, hostname))
                .build();
        String containerId = lifecycleManager.createAndStart(spec).containerId();
        relays.put(hostname, containerId);
        try {
            String address = EmbeddedDnsServer.requireBridgeAddress(
                    lifecycleManager.resolveContainerNetworkIp(containerId, network));
            awaitListener(containerId, port);
            LOG.infov("Endpoint {0} relays port {1} to {2}:{3} from {4}", hostname, String.valueOf(port),
                    targetHost, String.valueOf(targetPort), address);
            return address;
        } catch (RuntimeException e) {
            stop(hostname);
            throw e;
        }
    }

    /** Stops the relay for {@code hostname}, if it has one. */
    public void stop(String hostname) {
        if (hostname == null) {
            return;
        }
        String containerId = relays.remove(hostname);
        if (containerId == null) {
            return;
        }
        try {
            lifecycleManager.stopAndRemove(containerId, null);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the relay of endpoint {0}: {1}", hostname, e.getMessage());
        }
    }

    public boolean hasRelay(String hostname) {
        return hostname != null && relays.containsKey(hostname);
    }

    @PreDestroy
    void stopAll() {
        for (String hostname : List.copyOf(relays.keySet())) {
            stop(hostname);
        }
    }

    static String relayScript(int port, String targetHost, int targetPort) {
        if (port < 1 || port > 65535 || targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("Not a TCP port: " + port + " -> " + targetPort);
        }
        if (!targetHost.matches("[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("Not a relay target host: " + targetHost);
        }
        return "exec socat TCP4-LISTEN:" + port + ",reuseaddr,fork TCP4:" + targetHost + ":" + targetPort;
    }

    /** A Docker-safe container name component for a hostname, bounded in length. */
    static String containerNameOf(String hostname) {
        String name = hostname.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private void awaitListener(String containerId, int port) {
        String probe = "for attempt in $(seq 1 50); do "
                + "if [ -n \"$(ss -H -ltn 'sport = :" + port + "')\" ]; then exit 0; fi; "
                + "sleep 0.1; done; exit 1";
        DockerClient docker = lifecycleManager.getDockerClient();
        String execId = docker.execCreateCmd(containerId).withCmd("sh", "-c", probe)
                .withAttachStdout(true).withAttachStderr(true).exec().getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ExecStartResultCallback callback = new ExecStartResultCallback(output, output)) {
            if (!docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Endpoint relay did not start listening on port " + port);
            }
            Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
            if (exitCode == null || exitCode != 0) {
                throw new IllegalStateException("Endpoint relay is not listening on port " + port + ": "
                        + output.toString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for an endpoint relay", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect an endpoint relay", e);
        }
    }
}
