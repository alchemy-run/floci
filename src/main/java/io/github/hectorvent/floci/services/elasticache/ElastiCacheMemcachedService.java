package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
            handle = containerManager.start(clusterId, image);
            proxyManager.startProxy(clusterId, proxyPort, handle.getHost(), handle.getPort());

            Endpoint endpoint = endpointFor(handle, proxyPort);
            CacheCluster cluster = new CacheCluster(
                    clusterId, CacheClusterStatus.AVAILABLE, ENGINE, ENGINE_VERSION,
                    endpoint, Instant.now());
            cluster.setProxyPort(proxyPort);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());

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

    public CacheCluster saveCacheCluster(CacheCluster cluster) {
        clusters.put(cluster.getCacheClusterId(), cluster);
        return cluster;
    }

    private Endpoint endpointFor(ElastiCacheContainerHandle handle, int proxyPort) {
        if (containerDetector.isRunningInContainer()) {
            return new Endpoint(handle.getHost(), handle.getPort());
        }
        return new Endpoint(resolveEndpointHost(), proxyPort);
    }

    private String resolveEndpointHost() {
        return config.hostname().orElseGet(dockerHostResolver::resolve);
    }
}
