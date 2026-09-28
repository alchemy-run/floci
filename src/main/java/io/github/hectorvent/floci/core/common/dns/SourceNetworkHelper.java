package io.github.hectorvent.floci.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A private DNS/TLS bridge for a source-mode process; never publishes or claims a host port.
 *
 * <p>Besides DNS, HTTPS and the gateway port, it relays any port a Floci listener serves an
 * advertised endpoint on ({@link #forwardPort}), so a container that resolves that endpoint's
 * hostname to this helper reaches the listener on the host, on the port the endpoint names.
 */
@ApplicationScoped
public class SourceNetworkHelper {

    private static final Logger LOG = Logger.getLogger(SourceNetworkHelper.class);
    static final String DEFAULT_IMAGE = "floci/source-network-helper:local";
    static final String HOST_GATEWAY = "host.docker.internal";
    private static final Pattern PID = Pattern.compile("\\d+");
    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    /** Ports relayed to the host and how many listeners asked for each. */
    private final Map<Integer, Integer> forwardedPorts = new HashMap<>();
    /** The helper-side relay process of each forwarded port. */
    private final Map<Integer, String> forwarderPids = new HashMap<>();
    private String containerId;

    private record ExecOutput(long exitCode, String stdout, String stderr) {}

    @Inject
    public SourceNetworkHelper(EmulatorConfig config, ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
    }

    public synchronized String start(int dnsPort) {
        if (containerId != null) {
            throw new IllegalStateException("Source network helper already started");
        }
        requireUnprivilegedPort(dnsPort);
        requireUnprivilegedPort(config.port());
        String owner = UUID.randomUUID().toString();
        String name = ContainerStorageHelper.dockerName(config, "floci-source-network-" + owner);
        ContainerSpec spec = containerBuilder.newContainer(config.dns().sourceHelperImage())
                .withName(name)
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of(forwardingScript(dnsPort, config.port())))
                .withLogRotation()
                .withLabels(ContainerStorageHelper.defaultLabels(config))
                .withLabels(Map.of("floci.source-network-helper", "true", "floci.source-network-owner", owner))
                .build();
        String network = spec.networkMode() == null ? "bridge" : spec.networkMode();
        if (network.equals("host") || network.equals("none") || network.startsWith("container:")) {
            throw new IllegalStateException("Source network helper requires a Docker bridge network");
        }
        ensureImage();
        try {
            containerId = lifecycleManager.create(spec);
            lifecycleManager.startCreated(containerId, spec);
            var networks = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec()
                    .getNetworkSettings().getNetworks();
            var endpoint = networks.get(network);
            if (endpoint == null && networks.size() == 1) {
                endpoint = networks.values().iterator().next();
            }
            if (endpoint == null) {
                throw new IllegalStateException("Source network helper has no address on " + network);
            }
            String address = EmbeddedDnsServer.requireBridgeAddress(endpoint.getIpAddress());
            awaitListeners();
            for (int port : new ArrayList<>(forwardedPorts.keySet())) {
                startForwarder(port);
            }
            return address;
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static void requireUnprivilegedPort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Source networking requires unprivileged host ports: " + port);
        }
    }

    static String forwardingScript(int dnsPort, int gatewayPort) {
        requireUnprivilegedPort(dnsPort);
        requireUnprivilegedPort(gatewayPort);
        return "set -eu\n"
                + "trap 'kill $(jobs -p) 2>/dev/null || true' EXIT\n"
                + "trap 'exit 0' TERM INT\n"
                + "socat -T 5 UDP4-RECVFROM:53,reuseaddr,fork UDP4-SENDTO:" + HOST_GATEWAY + ":" + dnsPort + " &\ndns_pid=$!\n"
                + "socat TCP4-LISTEN:443,reuseaddr,fork TCP4:" + HOST_GATEWAY + ":" + gatewayPort + " &\ntls_pid=$!\n"
                + "socat TCP4-LISTEN:" + gatewayPort + ",reuseaddr,fork TCP4:" + HOST_GATEWAY + ":" + gatewayPort + " &\ngateway_pid=$!\n"
                // PID 1 also reaps exec-probe watchdogs; only listener exits stop the relay.
                + "wait -n \"$dns_pid\" \"$tls_pid\" \"$gateway_pid\"\nexit 1\n";
    }

    /**
     * Relays TCP connections to {@code port} on the helper's address to the same port on the
     * host. Each call must be paired with one {@link #releaseForwardedPort}; the relay runs while
     * any caller holds the port. Ports the helper already serves (443 and the gateway port) are
     * left alone. A port asked for before the helper starts is relayed once it has.
     */
    public synchronized void forwardPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a TCP port: " + port);
        }
        if (isBuiltInListener(port)) {
            return;
        }
        int holders = forwardedPorts.merge(port, 1, Integer::sum);
        if (holders == 1 && containerId != null) {
            startForwarder(port);
        }
    }

    /** Releases one {@link #forwardPort} hold, stopping the relay with the last one. */
    public synchronized void releaseForwardedPort(int port) {
        Integer holders = forwardedPorts.get(port);
        if (holders == null) {
            return;
        }
        if (holders > 1) {
            forwardedPorts.put(port, holders - 1);
            return;
        }
        forwardedPorts.remove(port);
        String pid = forwarderPids.remove(port);
        if (pid == null || containerId == null) {
            return;
        }
        try {
            ExecOutput output = exec("kill " + pid + " 2>/dev/null || true");
            if (output.exitCode() != 0) {
                LOG.warnv("Source network helper could not stop the relay for port {0}: {1}",
                        String.valueOf(port), output.stderr());
            }
        } catch (RuntimeException e) {
            LOG.warnv("Source network helper could not stop the relay for port {0}: {1}",
                    String.valueOf(port), e.getMessage());
        }
    }

    /** Ports currently relayed to the host, for diagnostics and tests. */
    synchronized Set<Integer> forwardedPorts() {
        return Set.copyOf(forwardedPorts.keySet());
    }

    private boolean isBuiltInListener(int port) {
        return port == 443 || port == config.port();
    }

    /**
     * Starts the helper-side relay for {@code port}. A relay that cannot start is logged rather
     * than thrown: the resource behind it exists either way, and only its container-side
     * reachability is missing, which a client then observes as a refused connection.
     */
    private void startForwarder(int port) {
        try {
            ExecOutput output = exec(forwarderScript(port));
            String pid = output.stdout().trim();
            if (output.exitCode() != 0 || !PID.matcher(pid).matches()) {
                LOG.warnv("Source network helper could not relay port {0} to the host (exit {1}): {2}",
                        String.valueOf(port), String.valueOf(output.exitCode()), output.stderr());
                return;
            }
            forwarderPids.put(port, pid);
            LOG.infov("Source network helper relays container port {0} to the host", String.valueOf(port));
        } catch (RuntimeException e) {
            LOG.warnv("Source network helper could not relay port {0} to the host: {1}",
                    String.valueOf(port), e.getMessage());
        }
    }

    /** Starts one relay in the background, waits for its listener and prints its pid. */
    static String forwarderScript(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a TCP port: " + port);
        }
        return "set -u\n"
                + "socat TCP4-LISTEN:" + port + ",reuseaddr,fork TCP4:" + HOST_GATEWAY + ":" + port
                + " </dev/null >/dev/null 2>&1 &\n"
                + "pid=$!\n"
                + "for attempt in $(seq 1 50); do\n"
                + "  if [ -n \"$(ss -H -ltn 'sport = :" + port + "')\" ]; then echo \"$pid\"; exit 0; fi\n"
                + "  if ! kill -0 \"$pid\" 2>/dev/null; then echo 'relay exited' >&2; exit 1; fi\n"
                + "  sleep 0.1\n"
                + "done\n"
                + "kill \"$pid\" 2>/dev/null || true\n"
                + "echo 'relay did not listen' >&2\n"
                + "exit 1\n";
    }

    private ExecOutput exec(String script) {
        DockerClient docker = lifecycleManager.getDockerClient();
        String execId = docker.execCreateCmd(containerId).withCmd("sh", "-c", script)
                .withAttachStdout(true).withAttachStderr(true).exec().getId();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
            if (!docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Source network helper command timed out");
            }
            Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecOutput(exitCode == null ? -1 : exitCode,
                    stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running a source network helper command", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run a source network helper command", e);
        }
    }

    private void awaitListeners() {
        String probe = "set -eu; for attempt in $(seq 1 50); do "
                + "if [ -n \"$(ss -H -lun 'sport = :53')\" ] "
                + "&& [ -n \"$(ss -H -ltn 'sport = :443')\" ] "
                + "&& [ -n \"$(ss -H -ltn 'sport = :" + config.port() + "')\" ]; then exit 0; fi; "
                + "sleep 0.1; done; exit 1";
        var docker = lifecycleManager.getDockerClient();
        String execId = docker.execCreateCmd(containerId).withCmd("sh", "-c", probe)
                .withAttachStdout(true).withAttachStderr(true).exec().getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ExecStartResultCallback callback = new ExecStartResultCallback(output, output)) {
            if (!docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Source network helper listeners did not become ready");
            }
            Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
            if (exitCode == null || exitCode != 0) {
                throw new IllegalStateException("Source network helper listeners failed readiness (exit "
                        + exitCode + "): " + output.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for source network helper", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect source network helper readiness", e);
        }
    }

    /** Called after the host DNS handler has its answer address, before advertising it to containers. */
    public synchronized void awaitDns(String address) {
        if (containerId == null) {
            throw new IllegalStateException("Source network helper is not started");
        }
        try {
            EmbeddedDnsServer.requireBridgeAddress(address);
            byte[] query = readinessQuery((short) UUID.randomUUID().hashCode());
            StringBuilder octal = new StringBuilder();
            for (byte value : query) {
                octal.append(String.format("\\0%03o", Byte.toUnsignedInt(value)));
            }
            String probe = "set -eu; set -o pipefail; printf '%b' '" + octal
                    + "' | timeout 8 socat -T 2 -t 2 - UDP4:" + address + ":53,shut-none | base64";
            DockerClient docker = lifecycleManager.getDockerClient();
            String execId = docker.execCreateCmd(containerId).withCmd("sh", "-c", probe)
                    .withAttachStdout(true).withAttachStderr(true).exec().getId();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
                if (!docker.execStartCmd(execId).exec(callback).awaitCompletion(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Source network helper DNS roundtrip timed out");
                }
                Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
                if (exitCode == null || exitCode != 0) {
                    throw new IllegalStateException("Source network helper DNS probe failed (exit " + exitCode
                            + "): " + stderr.toString(StandardCharsets.UTF_8));
                }
                byte[] response = Base64.getDecoder().decode(
                        stdout.toString(StandardCharsets.US_ASCII).replaceAll("\\s", ""));
                validateDnsResponse(query, response, address);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for source network helper DNS roundtrip", e);
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Invalid source network helper DNS probe response", e);
            }
        } catch (RuntimeException failure) {
            try {
                stop();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static byte[] readinessQuery(short transactionId) {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort(transactionId).putShort((short) 0x0100).putShort((short) 1);
        query.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : ("source-readiness." + EmbeddedDnsServer.DEFAULT_SUFFIX).split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) bytes.length).put(bytes);
        }
        query.put((byte) 0).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(query.array(), query.position());
    }

    static void validateDnsResponse(byte[] query, byte[] response, String address) {
        // The owned listener echoes the question and returns one compressed IN A answer.
        if (response.length != query.length + 16) {
            throw new IllegalStateException("Source network helper DNS response has an invalid length");
        }
        ByteBuffer packet = ByteBuffer.wrap(response);
        int flags = Short.toUnsignedInt(packet.getShort(2));
        if (packet.getShort(0) != ByteBuffer.wrap(query).getShort(0)
                || (flags & 0xfa0f) != 0x8000
                || packet.getShort(4) != 1 || packet.getShort(6) != 1
                || packet.getShort(8) != 0 || packet.getShort(10) != 0
                || !Arrays.equals(query, 12, query.length, response, 12, query.length)) {
            throw new IllegalStateException("Source network helper DNS response does not match the query");
        }
        packet.position(query.length);
        if (Short.toUnsignedInt(packet.getShort()) != 0xc00c || packet.getShort() != 1 || packet.getShort() != 1) {
            throw new IllegalStateException("Source network helper DNS response is not an IN A answer for the query");
        }
        packet.getInt(); // TTL does not affect readiness.
        if (packet.getShort() != 4) {
            throw new IllegalStateException("Source network helper DNS response has an invalid A record length");
        }
        for (String octet : address.split("\\.")) {
            if (Byte.toUnsignedInt(packet.get()) != Integer.parseInt(octet)) {
                throw new IllegalStateException("Source network helper DNS answer does not match its bridge address");
            }
        }
    }

    synchronized void ensureImage() {
        if (!DEFAULT_IMAGE.equals(config.dns().sourceHelperImage())) {
            return;
        }
        String image = containerBuilder.resolveImage(DEFAULT_IMAGE);
        var docker = lifecycleManager.getDockerClient();
        try {
            docker.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException missing) {
            // Build the checked-in recipe, never rely on a separately prepared daemon image.
        }
        try (InputStream dockerfile = getClass().getResourceAsStream("/docker/network-helper.Dockerfile")) {
            if (dockerfile == null) {
                throw new IllegalStateException("Floci network helper Dockerfile is missing");
            }
            byte[] content = dockerfile.readAllBytes();
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                TarArchiveEntry entry = new TarArchiveEntry("Dockerfile");
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
                String built = docker.buildImageCmd(new ByteArrayInputStream(archive.toByteArray()))
                        .withTags(Set.of(image)).exec(callback).awaitImageId(2, TimeUnit.MINUTES);
                if (built == null || built.isBlank()) {
                    throw new IllegalStateException("Docker did not build the source network helper image");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the source network helper image", e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (containerId != null) {
            lifecycleManager.stopAndRemoveStrict(containerId, null);
            containerId = null;
            forwarderPids.clear();
        }
    }
}
