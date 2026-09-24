package io.github.hectorvent.floci.services.docdb.container;


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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DocDbContainerManager {

    private static final Logger LOG = Logger.getLogger(DocDbContainerManager.class);
    static final String REPLICA_SET = "rs0";
    private static final int MEMORY_LIMIT_MB = 1024;
    private static final String WIRED_TIGER_CACHE_GB = "0.25";
    // mongod starts twice (the entrypoint creates the root user first) before the set elects it
    private static final int BACKEND_READY_DEADLINE_MS = 120_000;
    private static final int BACKEND_READY_RETRY_MS = 500;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, DocDbContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public DocDbContainerManager(ContainerBuilder containerBuilder,
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
     * Attempts {@link #start} and reports the backend as unavailable instead of propagating the
     * failure, when the cause is that no Docker daemon is reachable from Floci — Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start DocumentDB containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public DocDbContainerHandle tryStart(String clusterId, String image, String masterUsername,
                                         String masterPassword, String memberHost, int port) {
        try {
            DocDbContainerHandle handle = start(clusterId, image, masterUsername, masterPassword, memberHost, port);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). DocumentDB metadata "
                        + "operations keep working and clusters still reach 'available', but they "
                        + "have no backing MongoDB container until a daemon becomes reachable.",
                        e.getMessage());
            }
            return null;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Starts the MongoDB backend of one cluster as a single-member replica set named
     * {@code rs0}, as DocumentDB presents itself to drivers. The member is advertised as
     * {@code memberHost:port}, the cluster endpoint clients are given, so a driver that discovers
     * the set's hosts is sent back to that endpoint; inside the container the name maps to the
     * container itself, which is how mongod recognises the member as itself. The backend speaks
     * plaintext: the cluster's listener terminates TLS in front of it.
     */
    public DocDbContainerHandle start(String clusterId, String image, String masterUsername,
                                      String masterPassword, String memberHost, int port) {
        LOG.infov("Starting DocumentDB container for cluster: {0}", clusterId);

        String containerName = ContainerStorageHelper.resourceName(config, "docdb", null, clusterId);
        lifecycleManager.removeIfExists(containerName);

        List<String> envVars = List.of(
                "MONGO_INITDB_ROOT_USERNAME=" + masterUsername,
                "MONGO_INITDB_ROOT_PASSWORD=" + masterPassword,
                "FLOCI_DOCDB_HOST=" + memberHost,
                "FLOCI_DOCDB_PORT=" + port);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().docdb().dockerNetwork())
                .withLogRotation()
                .withEnv(envVars)
                .withMemoryMb(MEMORY_LIMIT_MB)
                .withExtraHost(memberHost, "127.0.0.1")
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of(replicaSetScript()))
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "docdb", clusterId, regionResolver.getAccountId(), regionResolver.getDefaultRegion()));

        if (!containerDetector.isRunningInContainer()) {
            // Clients reach the backend through the cluster proxy, never directly.
            specBuilder.withLoopbackPortBinding(port, 0);
        } else {
            specBuilder.withExposedPort(port);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(port);

        LOG.infov("DocumentDB container for cluster {0}: {1}", clusterId, endpoint);

        DocDbContainerHandle handle = new DocDbContainerHandle(
                info.containerId(), clusterId, endpoint.host(), endpoint.port());
        activeContainers.put(clusterId, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/docdb/cluster/" + clusterId + "/audit";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "docdb:" + clusterId);
        handle.setLogStream(logHandle);

        try {
            waitForBackendReady(clusterId, endpoint.host(), endpoint.port(), BACKEND_READY_DEADLINE_MS);
        } catch (RuntimeException e) {
            stop(handle);
            throw e;
        }

        return handle;
    }

    /**
     * The container command: a key file, which a replica set with authentication needs, mongod
     * started through the image's entrypoint (which creates the root user first), and the replica
     * set initiated with the advertised member once mongod accepts the root user.
     */
    static String replicaSetScript() {
        return "set -eu\n"
                + "keyfile=/tmp/floci-docdb-keyfile\n"
                + "head -c 756 /dev/urandom | base64 | tr -d '\\n' > \"$keyfile\"\n"
                + "chown mongodb:mongodb \"$keyfile\"\n"
                + "chmod 400 \"$keyfile\"\n"
                + "docker-entrypoint.sh mongod --replSet " + REPLICA_SET + " --keyFile \"$keyfile\" "
                + "--bind_ip_all --port \"$FLOCI_DOCDB_PORT\" --wiredTigerCacheSizeGB " + WIRED_TIGER_CACHE_GB + " &\n"
                + "mongod_pid=$!\n"
                + "trap 'kill -TERM \"$mongod_pid\" 2>/dev/null; wait \"$mongod_pid\"; exit 0' TERM INT\n"
                + "until mongosh --quiet --host 127.0.0.1 --port \"$FLOCI_DOCDB_PORT\" "
                + "-u \"$MONGO_INITDB_ROOT_USERNAME\" -p \"$MONGO_INITDB_ROOT_PASSWORD\" "
                + "--authenticationDatabase admin --eval "
                + "'try { rs.status() } catch (e) { rs.initiate({ _id: \"" + REPLICA_SET + "\", members: "
                + "[{ _id: 0, host: process.env.FLOCI_DOCDB_HOST + \":\" + process.env.FLOCI_DOCDB_PORT }] }) }' "
                + ">/dev/null 2>&1; do\n"
                + "  kill -0 \"$mongod_pid\" 2>/dev/null || exit 1\n"
                + "  sleep 1\n"
                + "done\n"
                + "wait \"$mongod_pid\"\n";
    }

    public void stop(DocDbContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getClusterId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopAll() {
        List<DocDbContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} DocumentDB container(s) on shutdown", handles.size());
        }
        for (DocDbContainerHandle handle : handles) {
            stop(handle);
        }
    }

    /** Waits until the backend answers {@code hello} as the replica set's writable primary. */
    static void waitForBackendReady(String clusterId, String host, int port, long deadlineMs) {
        long deadline = System.currentTimeMillis() + deadlineMs;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                if (MongoHelloProbe.isWritablePrimary(host, port, BACKEND_PROBE_CONNECT_MS)) {
                    LOG.infov("MongoDB backend ready for cluster {0} after {1} probe attempt(s)",
                            clusterId, attempt);
                    return;
                }
            } catch (IOException | RuntimeException e) {
                LOG.debugv("MongoDB probe for cluster {0} attempt {1}: {2}",
                        clusterId, attempt, e.getMessage());
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for MongoDB backend " + clusterId, ie);
            }
        }
        throw new IllegalStateException(
                "MongoDB backend for cluster " + clusterId + " did not become a writable replica set primary on "
                        + host + ":" + port + " within " + deadlineMs + "ms");
    }
}
