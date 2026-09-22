package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheMemcachedProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ElastiCacheMemcachedService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheMemcachedService.class);
    private static final String ENGINE = "memcached";
    private static final String ENGINE_VERSION = "1.6.22";

    private final StorageBackend<String, CacheCluster> clusters;
    private final ElastiCacheMemcachedContainerManager containerManager;
    private final ElastiCacheMemcachedProxyManager proxyManager;
    private final ElastiCacheService elasticacheService;
    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final ContainerDetector containerDetector;
    private final ConcurrentHashMap<String, Object> clusterLocks = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheMemcachedService(ElastiCacheMemcachedContainerManager containerManager,
                                       ElastiCacheMemcachedProxyManager proxyManager,
                                       ElastiCacheService elasticacheService,
                                       StorageFactory storageFactory,
                                       EmulatorConfig config,
                                       DockerHostResolver dockerHostResolver,
                                       ContainerDetector containerDetector) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.elasticacheService = elasticacheService;
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.containerDetector = containerDetector;
        this.clusters = storageFactory.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
    }

    public ElastiCacheMemcachedService(ElastiCacheMemcachedContainerManager containerManager,
                                       ElastiCacheMemcachedProxyManager proxyManager,
                                       ElastiCacheService elasticacheService,
                                       StorageFactory storageFactory,
                                       EmulatorConfig config,
                                       DockerHostResolver dockerHostResolver) {
        this(containerManager, proxyManager, elasticacheService, storageFactory, config,
                dockerHostResolver, new ContainerDetector());
    }

    public CacheCluster createCacheCluster(String clusterId) {
        if (clusters.get(clusterId).isPresent()) {
            throw new AwsException("CacheClusterAlreadyExistsFault",
                    "Cache cluster " + clusterId + " already exists.", 400);
        }

        String image = config.services().elasticache().defaultMemcachedImage();
        LOG.infov("Creating Memcached cluster {0} with image {1}", clusterId, image);

        int proxyPort = elasticacheService.allocateProxyPort();
        ElastiCacheContainerHandle handle = null;
        try {
            handle = containerManager.tryStart(clusterId, image);
            if (handle != null) {
                proxyManager.startProxy(clusterId, proxyPort, handle.getHost(), handle.getPort());
            }

            Endpoint endpoint = endpointFor(handle, proxyPort);
            CacheCluster cluster = new CacheCluster(
                    clusterId, CacheClusterStatus.AVAILABLE, ENGINE, ENGINE_VERSION,
                    endpoint, Instant.now());
            cluster.setProxyPort(proxyPort);
            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
            }

            clusters.put(clusterId, cluster);
            LOG.infov("Memcached cluster {0} created, endpoint={1}:{2}",
                    clusterId, endpoint.address(), endpoint.port());
            return cluster;
        } catch (RuntimeException e) {
            proxyManager.stopProxy(clusterId);
            if (handle != null) containerManager.stop(handle);
            elasticacheService.releaseProxyPort(proxyPort);
            throw e;
        }
    }

    /**
     * Restarts the container behind every cache cluster restored from disk. Invoked from
     * {@code EmulatorLifecycle} after {@code storageFactory.loadAll()}, for the same reason
     * {@code ElastiCacheService.restorePersistedRuntime} exists: only the record is persisted,
     * the container is process-local, and a cluster left unreconciled reports {@code available}
     * with nothing behind its endpoint. A cluster whose container cannot be brought back reports
     * {@code restore-failed} instead.
     *
     * <p>Starting a container waits for a readiness probe and may pull an image, so the work runs
     * in the background and must not delay emulator readiness. Clusters are marked
     * {@code creating} synchronously and flip to {@code available} or {@code restore-failed} as
     * each restore finishes. The cache comes back empty, as on any Floci restart.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        List<CacheCluster> toRestore = new ArrayList<>();
        for (CacheCluster cluster : clusters.scan(k -> true)) {
            if (cluster.getCacheClusterStatus() == CacheClusterStatus.DELETING) {
                continue;
            }
            try {
                cluster.setProxyPort(elasticacheService.reserveOrAllocateProxyPort(cluster.getProxyPort()));
                cluster.setCacheClusterStatus(CacheClusterStatus.CREATING);
                clusters.put(cluster.getCacheClusterId(), cluster);
                toRestore.add(cluster);
            } catch (RuntimeException e) {
                failRestore(cluster, e);
            }
        }
        if (toRestore.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOG.infov("Restoring {0} Memcached cluster(s) in the background", String.valueOf(toRestore.size()));
        return CompletableFuture.runAsync(() -> toRestore.forEach(this::restoreCluster));
    }

    /**
     * The endpoint is rebuilt from the new container rather than replayed from the record: the
     * host port Docker publishes is chosen per run, so a restored cluster that kept its old
     * endpoint would advertise a port nothing listens on.
     *
     * <p>The container work runs outside the cluster's monitor, so a restore that may pull an
     * image does not hold a delete of the same cluster behind it. The write-back takes the
     * monitor and re-reads the record: a client that deleted the cluster in the first seconds
     * after boot, while it was still {@code creating}, must not see it come back
     * {@code available} with a fresh container behind it.
     */
    private void restoreCluster(CacheCluster cluster) {
        String clusterId = cluster.getCacheClusterId();
        String image = config.services().elasticache().defaultMemcachedImage();
        ElastiCacheContainerHandle handle = null;
        try {
            handle = containerManager.tryStart(clusterId, image);
            synchronized (lockFor(clusterId)) {
                if (restoreTargetLost(clusterId)) {
                    abandonRestoredContainer(clusterId, handle);
                    return;
                }
                if (handle != null) {
                    cluster.setContainerId(handle.getContainerId());
                    cluster.setContainerHost(handle.getHost());
                    cluster.setContainerPort(handle.getPort());
                    proxyManager.startProxy(clusterId, cluster.getProxyPort(), handle.getHost(), handle.getPort());
                } else {
                    // Cleared rather than left alone: whatever the record carried describes a
                    // container from the previous process, and nothing must read it as live.
                    cluster.setContainerId(null);
                    cluster.setContainerHost(null);
                    cluster.setContainerPort(0);
                    LOG.warnv("Memcached cluster {0} restored without a backing container: no Docker "
                            + "daemon is reachable. Metadata operations work; connections to the cache "
                            + "do not until a daemon appears.", clusterId);
                }
                cluster.setConfigurationEndpoint(endpointFor(handle, cluster.getProxyPort()));
                cluster.setCacheClusterStatus(CacheClusterStatus.AVAILABLE);
                clusters.put(clusterId, cluster);
                LOG.infov("Restored Memcached cluster {0}, endpoint={1}:{2}", clusterId,
                        cluster.getConfigurationEndpoint().address(),
                        String.valueOf(cluster.getConfigurationEndpoint().port()));
            }
        } catch (RuntimeException e) {
            synchronized (lockFor(clusterId)) {
                if (!restoreTargetLost(clusterId)) {
                    try {
                        proxyManager.stopProxy(clusterId);
                        elasticacheService.releaseProxyPort(cluster.getProxyPort());
                    } catch (RuntimeException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                }
                if (handle != null) {
                    try {
                        containerManager.stop(handle);
                    } catch (RuntimeException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                }
                failRestore(cluster, e);
            }
        }
    }

    /**
     * Reports a cluster whose container could not be brought back as {@code restore-failed}
     * without deleting the record: the cluster still exists on the control plane, it just has
     * nothing behind it. A cluster deleted while the failed attempt ran is not written back at
     * all, because that would resurrect it.
     */
    private void failRestore(CacheCluster cluster, RuntimeException cause) {
        String clusterId = cluster.getCacheClusterId();
        synchronized (lockFor(clusterId)) {
            if (restoreTargetLost(clusterId)) {
                LOG.warnv(cause, "Failed to restore Memcached cluster {0}, which was deleted while "
                        + "it was being restored", clusterId);
                return;
            }
            cluster.setContainerId(null);
            cluster.setContainerHost(null);
            cluster.setContainerPort(0);
            cluster.setCacheClusterStatus(CacheClusterStatus.RESTORE_FAILED);
            cluster.setConfigurationEndpoint(null);
            try {
                clusters.put(clusterId, cluster);
            } catch (RuntimeException persistFailure) {
                cause.addSuppressed(persistFailure);
            }
            LOG.warnv(cause, "Failed to restore Memcached cluster {0}", clusterId);
        }
    }

    /**
     * Whether the record a restore is about to write back is still there to write back to.
     * Callers must hold the cluster's monitor: {@link #deleteCacheCluster} removed the record
     * under that same monitor, and putting the cluster back afterwards would resurrect one the
     * caller was told had been deleted.
     */
    private boolean restoreTargetLost(String clusterId) {
        return clusters.get(clusterId)
                .map(current -> current.getCacheClusterStatus() == CacheClusterStatus.DELETING)
                .orElse(true);
    }

    /** Drops the container a restore started for a cluster that was deleted while it ran. */
    private void abandonRestoredContainer(String clusterId, ElastiCacheContainerHandle handle) {
        if (handle != null) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for deleted Memcached cluster {0}: {1}",
                        clusterId, e.getMessage());
            }
        }
        LOG.infov("Discarded the restored container for Memcached cluster {0}: it was deleted "
                + "while it was being restored", clusterId);
    }

    /**
     * One monitor per cluster id, taken by every writer that reads a record and writes it back.
     * Guarding the record itself would be too late: a restore that resolved its cluster before
     * locking would otherwise write back one a concurrent delete had already removed.
     */
    private Object lockFor(String clusterId) {
        return clusterLocks.computeIfAbsent(clusterId, key -> new Object());
    }

    public CacheCluster getCacheCluster(String clusterId) {
        return clusters.get(clusterId).orElseThrow(() ->
                new AwsException("CacheClusterNotFoundFault",
                        "Cache cluster " + clusterId + " not found.", 404));
    }

    public Collection<CacheCluster> listCacheClusters(String filterClusterId) {
        if (filterClusterId != null && !filterClusterId.isBlank()) {
            return clusters.get(filterClusterId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFoundFault",
                            "Cache cluster " + filterClusterId + " not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public CacheCluster deleteCacheCluster(String clusterId) {
        // one monitor per cluster for delete and restore: a restore's read-modify-write could
        // otherwise write the cluster back after this removed it
        synchronized (lockFor(clusterId)) {
            CacheCluster cluster = getCacheCluster(clusterId);

            cluster.setCacheClusterStatus(CacheClusterStatus.DELETING);
            clusters.put(clusterId, cluster);

            proxyManager.stopProxy(clusterId);
            if (cluster.getContainerId() != null) {
                containerManager.stop(new ElastiCacheContainerHandle(
                        cluster.getContainerId(), clusterId,
                        cluster.getContainerHost(), cluster.getContainerPort()));
            }

            elasticacheService.releaseProxyPort(cluster.getProxyPort());

            clusters.delete(clusterId);
            LOG.infov("Memcached cluster {0} deleted", clusterId);
            return cluster;
        }
    }

    public CacheCluster saveCacheCluster(CacheCluster cluster) {
        clusters.put(cluster.getCacheClusterId(), cluster);
        return cluster;
    }

    private Endpoint endpointFor(ElastiCacheContainerHandle handle, int proxyPort) {
        if (handle != null && containerDetector.isRunningInContainer()) {
            return new Endpoint(handle.getHost(), handle.getPort());
        }
        return new Endpoint(resolveEndpointHost(), proxyPort);
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }
}
