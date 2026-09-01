package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RegisterForReflection
public class ReplicationGroup {

    private String replicationGroupId;
    private String description;
    private ReplicationGroupStatus status;
    private AuthMode authMode;
    private Endpoint configurationEndpoint;
    private Instant createdAt;
    private int proxyPort;
    private String authToken; // stored plain-text for PASSWORD auth validation in the proxy
    private Set<String> associatedUserIds = new HashSet<>();
    private String engine = "valkey";
    private String engineVersion = "8.0";
    private String cacheNodeType = "cache.t4g.small";
    private int nodeGroupCount = 1;
    private int replicasPerNodeGroup;
    private boolean transitEncryptionEnabled;
    private boolean automaticFailoverEnabled;
    private boolean multiAzEnabled;
    private List<String> securityGroupIds = new ArrayList<>();
    private int snapshotRetentionLimit;
    private String snapshotWindow;
    private String maintenanceWindow;
    private Map<String, String> tags = new LinkedHashMap<>();

    // Transient fields — not persisted, restored on container restart
    private transient String containerId;
    private transient String containerHost;
    private transient int containerPort;

    public ReplicationGroup() {}

    public ReplicationGroup(String replicationGroupId, String description,
                            ReplicationGroupStatus status, AuthMode authMode,
                            Endpoint configurationEndpoint, Instant createdAt, int proxyPort) {
        this.replicationGroupId = replicationGroupId;
        this.description = description;
        this.status = status;
        this.authMode = authMode;
        this.configurationEndpoint = configurationEndpoint;
        this.createdAt = createdAt;
        this.proxyPort = proxyPort;
    }

    public String getReplicationGroupId() { return replicationGroupId; }
    public void setReplicationGroupId(String replicationGroupId) { this.replicationGroupId = replicationGroupId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ReplicationGroupStatus getStatus() { return status; }
    public void setStatus(ReplicationGroupStatus status) { this.status = status; }

    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }

    public Endpoint getConfigurationEndpoint() { return configurationEndpoint; }
    public void setConfigurationEndpoint(Endpoint configurationEndpoint) { this.configurationEndpoint = configurationEndpoint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public Set<String> getAssociatedUserIds() { return associatedUserIds; }
    public void setAssociatedUserIds(Set<String> associatedUserIds) {
        this.associatedUserIds = associatedUserIds != null ? associatedUserIds : new HashSet<>();
    }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getCacheNodeType() { return cacheNodeType; }
    public void setCacheNodeType(String cacheNodeType) { this.cacheNodeType = cacheNodeType; }

    public int getNodeGroupCount() { return nodeGroupCount; }
    public void setNodeGroupCount(int nodeGroupCount) { this.nodeGroupCount = nodeGroupCount; }

    public int getReplicasPerNodeGroup() { return replicasPerNodeGroup; }
    public void setReplicasPerNodeGroup(int replicasPerNodeGroup) { this.replicasPerNodeGroup = replicasPerNodeGroup; }

    public boolean isTransitEncryptionEnabled() { return transitEncryptionEnabled; }
    public void setTransitEncryptionEnabled(boolean transitEncryptionEnabled) { this.transitEncryptionEnabled = transitEncryptionEnabled; }

    public boolean isAutomaticFailoverEnabled() { return automaticFailoverEnabled; }
    public void setAutomaticFailoverEnabled(boolean automaticFailoverEnabled) { this.automaticFailoverEnabled = automaticFailoverEnabled; }

    public boolean isMultiAzEnabled() { return multiAzEnabled; }
    public void setMultiAzEnabled(boolean multiAzEnabled) { this.multiAzEnabled = multiAzEnabled; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public int getSnapshotRetentionLimit() { return snapshotRetentionLimit; }
    public void setSnapshotRetentionLimit(int snapshotRetentionLimit) { this.snapshotRetentionLimit = snapshotRetentionLimit; }

    public String getSnapshotWindow() { return snapshotWindow; }
    public void setSnapshotWindow(String snapshotWindow) { this.snapshotWindow = snapshotWindow; }

    public String getMaintenanceWindow() { return maintenanceWindow; }
    public void setMaintenanceWindow(String maintenanceWindow) { this.maintenanceWindow = maintenanceWindow; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>(); }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
}
