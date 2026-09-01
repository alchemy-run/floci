package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class CacheCluster {

    private String cacheClusterId;
    private CacheClusterStatus cacheClusterStatus;
    private String engine;
    private String engineVersion;
    private Endpoint configurationEndpoint;
    private Instant cacheClusterCreateTime;
    private int proxyPort;
    private int numCacheNodes = 1;
    private String cacheNodeType = "cache.t4g.small";
    private List<String> securityGroupIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    // Transient — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public CacheCluster() {}

    public CacheCluster(String cacheClusterId, CacheClusterStatus cacheClusterStatus,
                        String engine, String engineVersion,
                        Endpoint configurationEndpoint, Instant cacheClusterCreateTime) {
        this.cacheClusterId = cacheClusterId;
        this.cacheClusterStatus = cacheClusterStatus;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.configurationEndpoint = configurationEndpoint;
        this.cacheClusterCreateTime = cacheClusterCreateTime;
    }

    public String getCacheClusterId() { return cacheClusterId; }
    public void setCacheClusterId(String cacheClusterId) { this.cacheClusterId = cacheClusterId; }

    public CacheClusterStatus getCacheClusterStatus() { return cacheClusterStatus; }
    public void setCacheClusterStatus(CacheClusterStatus cacheClusterStatus) { this.cacheClusterStatus = cacheClusterStatus; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public Endpoint getConfigurationEndpoint() { return configurationEndpoint; }
    public void setConfigurationEndpoint(Endpoint configurationEndpoint) { this.configurationEndpoint = configurationEndpoint; }

    public Instant getCacheClusterCreateTime() { return cacheClusterCreateTime; }
    public void setCacheClusterCreateTime(Instant cacheClusterCreateTime) { this.cacheClusterCreateTime = cacheClusterCreateTime; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public int getNumCacheNodes() { return numCacheNodes; }
    public void setNumCacheNodes(int numCacheNodes) { this.numCacheNodes = numCacheNodes; }

    public String getCacheNodeType() { return cacheNodeType; }
    public void setCacheNodeType(String cacheNodeType) { this.cacheNodeType = cacheNodeType; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>(); }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
