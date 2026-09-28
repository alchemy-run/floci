package io.github.hectorvent.floci.services.elasticache.container;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine containers of ElastiCache serverless caches. AWS serves a serverless cache only over TLS
 * on its own hostname, so each container serves TLS itself with a certificate issued for that
 * hostname, and Floci's DNS answers the hostname with the container's address:
 *
 * <ul>
 *   <li>Valkey and Redis OSS: the primary on 6379 and a read replica, fed from the primary, on
 *       the reader port 6380.</li>
 *   <li>Memcached: one server listening on 11211 and on the reader port 11212.</li>
 * </ul>
 *
 * Each container also serves a plaintext port that only Floci uses, for readiness and for taking
 * and restoring snapshots; that is the address {@link ElastiCacheContainerHandle} carries.
 */
@ApplicationScoped
public class ServerlessCacheContainerManager {

    private static final Logger LOG = Logger.getLogger(ServerlessCacheContainerManager.class);

    public static final int RESP_PORT = 6379;
    public static final int RESP_READER_PORT = 6380;
    public static final int MEMCACHED_PORT = 11211;
    public static final int MEMCACHED_READER_PORT = 11212;
    static final int RESP_INTERNAL_PORT = 6390;
    static final int MEMCACHED_INTERNAL_PORT = 11290;
    static final String TLS_DIR = "/tmp/floci-tls";

    private static final int READY_DEADLINE_MS = 60_000;
    private static final int READY_RETRY_MS = 200;
    private static final int PROBE_TIMEOUT_MS = 2_000;

    /** The TLS material a container serves: the leaf chain (leaf first), its key, and the issuing CA. */
    public record TlsMaterial(String certificateChainPem, String privateKeyPem, String caPem) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public ServerlessCacheContainerManager(ContainerBuilder containerBuilder,
                                           ContainerLifecycleManager lifecycleManager,
                                           ContainerLogStreamer logStreamer,
                                           ContainerDetector containerDetector,
                                           EmulatorConfig config,
                                           RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Starts the engine container for a cache, or returns {@code null} when no Docker daemon is
     * reachable, the way the replication-group containers degrade: the cache keeps its metadata
     * and has no data plane until a daemon appears.
     */
    public ElastiCacheContainerHandle tryStart(String cacheKey, String engine, String image, String accountId,
                                               String region, TlsMaterial tls) {
        try {
            return start(cacheKey, engine, image, accountId, region, tls);
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            stopByKey(cacheKey);
            LOG.warnv("No Docker daemon is reachable from Floci ({0}); serverless cache {1} has no "
                    + "backing container.", e.getMessage(), cacheKey);
            return null;
        }
    }

    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    private ElastiCacheContainerHandle start(String cacheKey, String engine, String image, String accountId,
                                             String region, TlsMaterial tls) {
        boolean memcached = "memcached".equals(engine);
        int internalPort = memcached ? MEMCACHED_INTERNAL_PORT : RESP_INTERNAL_PORT;
        String containerName = ContainerStorageHelper.resourceName(config,
                memcached ? "memcached" : "valkey", null, cacheKey);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of(memcached ? memcachedScript() : valkeyScript()))
                .withEnv("FLOCI_TLS_CERT", tls.certificateChainPem())
                .withEnv("FLOCI_TLS_KEY", tls.privateKeyPem())
                .withEnv("FLOCI_TLS_CA", tls.caPem())
                .withDockerNetwork(config.services().elasticache().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "elasticache", cacheKey, accountId, region));
        if (!containerDetector.isRunningInContainer()) {
            builder.withDynamicPort(internalPort);
        } else {
            builder.withExposedPort(internalPort);
        }
        for (int port : memcached ? List.of(MEMCACHED_PORT, MEMCACHED_READER_PORT)
                : List.of(RESP_PORT, RESP_READER_PORT)) {
            builder.withExposedPort(port);
        }
        ContainerSpec spec = builder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(internalPort);
        ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                info.containerId(), cacheKey, endpoint.host(), endpoint.port());
        activeContainers.put(cacheKey, handle);
        try {
            handle.setNetworkIp(lifecycleManager.resolveContainerNetworkIp(
                    info.containerId(), config.services().elasticache().dockerNetwork().orElse(null)));
        } catch (RuntimeException e) {
            LOG.warnv("Could not resolve the network address of serverless cache container {0}: {1}",
                    info.containerId(), e.getMessage());
        }
        String shortId = info.containerId().length() >= 8 ? info.containerId().substring(0, 8) : info.containerId();
        Closeable logHandle = logStreamer.attachForAccount(accountId, info.containerId(),
                "/aws/elasticache/serverless/" + cacheKey + "/engine-log",
                logStreamer.generateLogStreamName(shortId), region, "elasticache:" + cacheKey);
        handle.setLogStream(logHandle);

        waitForReady(cacheKey, memcached, info.containerId(), endpoint.host(), endpoint.port());
        return handle;
    }

    /**
     * The primary serves TLS on 6379 and plaintext on the internal port; the replica serves TLS on
     * the reader port and replicates from the primary's internal port. Neither persists to disk:
     * a serverless cache's durability is its snapshots.
     */
    static String valkeyScript() {
        String tlsFlags = "--tls-cert-file " + TLS_DIR + "/server.crt --tls-key-file " + TLS_DIR + "/server.key"
                + " --tls-ca-cert-file " + TLS_DIR + "/ca.crt --tls-auth-clients no";
        return writeTlsFiles()
                + "valkey-server --port 0 --tls-port " + RESP_READER_PORT + " " + tlsFlags
                + " --replicaof 127.0.0.1 " + RESP_INTERNAL_PORT
                + " --replica-read-only yes --save '' --appendonly no --protected-mode no --dir /tmp &\n"
                + "exec valkey-server --port " + RESP_INTERNAL_PORT + " --tls-port " + RESP_PORT + " " + tlsFlags
                + " --save '' --appendonly no --protected-mode no\n";
    }

    static String memcachedScript() {
        return writeTlsFiles()
                + "exec memcached -Z -o ssl_chain_cert=" + TLS_DIR + "/server.crt,ssl_key=" + TLS_DIR + "/server.key"
                + " -l notls:0.0.0.0:" + MEMCACHED_INTERNAL_PORT + ",0.0.0.0:" + MEMCACHED_PORT
                + ",0.0.0.0:" + MEMCACHED_READER_PORT + "\n";
    }

    private static String writeTlsFiles() {
        return "set -e\n"
                + "umask 077\n"
                + "mkdir -p " + TLS_DIR + "\n"
                + "printf '%s\\n' \"$FLOCI_TLS_CERT\" > " + TLS_DIR + "/server.crt\n"
                + "printf '%s\\n' \"$FLOCI_TLS_KEY\" > " + TLS_DIR + "/server.key\n"
                + "printf '%s\\n' \"$FLOCI_TLS_CA\" > " + TLS_DIR + "/ca.crt\n";
    }

    /**
     * Probes the container's plaintext port until the engine answers. An engine that cannot start
     * (a rejected flag, an unreadable certificate) exits at once, so the container's state is
     * checked alongside and an exit fails the wait immediately instead of at the deadline.
     */
    private void waitForReady(String cacheKey, boolean memcached, String containerId, String host, int port) {
        byte[] probe = (memcached ? "version\r\n" : "*1\r\n$4\r\nPING\r\n").getBytes(StandardCharsets.US_ASCII);
        String expected = memcached ? "VERSION" : "+PONG";
        long deadline = System.currentTimeMillis() + READY_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            requireRunning(cacheKey, containerId);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
                socket.setSoTimeout(PROBE_TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write(probe);
                out.flush();
                String line = RespLineReader.readAsciiLineCrLf(socket.getInputStream());
                if (line.startsWith(expected)) {
                    return;
                }
            } catch (IOException e) {
                LOG.debugv("Serverless cache {0} not ready yet: {1}", cacheKey, e.getMessage());
            }
            try {
                Thread.sleep(READY_RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for serverless cache " + cacheKey, e);
            }
        }
        throw new IllegalStateException("Serverless cache " + cacheKey + " did not become ready on " + host + ":"
                + port + " within " + READY_DEADLINE_MS + "ms");
    }

    private void requireRunning(String cacheKey, String containerId) {
        InspectContainerResponse.ContainerState state;
        try {
            state = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState();
        } catch (RuntimeException e) {
            // An inspect that fails says nothing about the engine; the probe keeps deciding.
            LOG.debugv("Could not inspect serverless cache container {0}: {1}", containerId, e.getMessage());
            return;
        }
        if (state == null || Boolean.TRUE.equals(state.getRunning())) {
            return;
        }
        String status = state.getStatus();
        if ("exited".equals(status) || "dead".equals(status)) {
            throw new IllegalStateException("The engine of serverless cache " + cacheKey + " exited with code "
                    + state.getExitCodeLong() + " before accepting connections; see its engine log");
        }
    }

    public void stop(ElastiCacheContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getGroupId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopByKey(String cacheKey) {
        ElastiCacheContainerHandle handle = activeContainers.get(cacheKey);
        if (handle != null) {
            stop(handle);
        }
    }

    @PreDestroy
    public void stopAll() {
        for (ElastiCacheContainerHandle handle : new ArrayList<>(activeContainers.values())) {
            try {
                stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping serverless cache container {0}: {1}", handle.getContainerId(),
                        e.getMessage());
            }
        }
    }
}
