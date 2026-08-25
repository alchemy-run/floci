package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class MskCluster {

    @JsonProperty("clusterArn")
    private String clusterArn;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("state")
    private ClusterState state;

    @JsonProperty("creationTime")
    private Instant creationTime;

    @JsonProperty("currentVersion")
    private String currentVersion;

    @JsonProperty("numberOfBrokerNodes")
    private int numberOfBrokerNodes;

    @JsonProperty("tags")
    private Map<String, String> tags = new LinkedHashMap<>();

    @JsonProperty("zookeeperConnectString")
    private String zookeeperConnectString;

    @JsonProperty("currentBrokerSoftwareInfo")
    private BrokerSoftwareInfo currentBrokerSoftwareInfo;

    // Internal field, not directly in AWS response but needed for GetBootstrapBrokers
    private String bootstrapBrokers;
    
    // Docker container ID for mock=false
    private String containerId;

    @JsonIgnore
    private String accountId;

    // 6-char hex generated once at creation for stable, collision-free volume/container naming
    private String volumeId;

    @JsonProperty("clusterType")
    private String clusterType = "PROVISIONED";

    @JsonProperty("serverless")
    private Map<String, Object> serverless;

    @JsonProperty("bootstrapBrokerStringSaslIam")
    private String bootstrapBrokerStringSaslIam;

    @JsonProperty("topics")
    private Map<String, MskTopic> topics = new LinkedHashMap<>();

    public MskCluster() {}

    public MskCluster(String clusterArn, String clusterName, String kafkaVersion) {
        this.clusterArn = clusterArn;
        this.clusterName = clusterName;
        this.state = ClusterState.CREATING;
        this.creationTime = Instant.now();
        this.currentVersion = "K3V6I1"; // Example version
        this.numberOfBrokerNodes = 1;
        this.zookeeperConnectString = "localhost:2181"; // Mock ZK
        this.currentBrokerSoftwareInfo = new BrokerSoftwareInfo(kafkaVersion);
        this.clusterType = "PROVISIONED";
        this.topics = new LinkedHashMap<>();
        this.tags = new LinkedHashMap<>();
    }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public ClusterState getState() { return state; }
    public void setState(ClusterState state) { this.state = state; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }

    public int getNumberOfBrokerNodes() { return numberOfBrokerNodes; }
    public void setNumberOfBrokerNodes(int numberOfBrokerNodes) { this.numberOfBrokerNodes = numberOfBrokerNodes; }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public String getZookeeperConnectString() { return zookeeperConnectString; }
    public void setZookeeperConnectString(String zookeeperConnectString) { this.zookeeperConnectString = zookeeperConnectString; }

    public BrokerSoftwareInfo getCurrentBrokerSoftwareInfo() { return currentBrokerSoftwareInfo; }
    public void setCurrentBrokerSoftwareInfo(BrokerSoftwareInfo currentBrokerSoftwareInfo) { this.currentBrokerSoftwareInfo = currentBrokerSoftwareInfo; }

    public String getBootstrapBrokers() { return bootstrapBrokers; }
    public void setBootstrapBrokers(String bootstrapBrokers) { this.bootstrapBrokers = bootstrapBrokers; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public String getClusterType() { return clusterType == null ? "PROVISIONED" : clusterType; }
    public void setClusterType(String clusterType) { this.clusterType = clusterType; }

    public Map<String, Object> getServerless() { return serverless; }
    public void setServerless(Map<String, Object> serverless) { this.serverless = serverless; }

    public String getBootstrapBrokerStringSaslIam() { return bootstrapBrokerStringSaslIam; }
    public void setBootstrapBrokerStringSaslIam(String bootstrapBrokerStringSaslIam) {
        this.bootstrapBrokerStringSaslIam = bootstrapBrokerStringSaslIam;
    }

    public Map<String, MskTopic> getTopics() {
        if (topics == null) {
            topics = new LinkedHashMap<>();
        }
        return topics;
    }

    public void setTopics(Map<String, MskTopic> topics) {
        this.topics = topics != null ? topics : new LinkedHashMap<>();
    }

    @JsonProperty("vpcConfigs")
    private List<Map<String, Object>> vpcConfigs = new ArrayList<>();

    @JsonProperty("iamAuthEnabled")
    private boolean iamAuthEnabled;

    public List<Map<String, Object>> getVpcConfigs() {
        if (vpcConfigs == null) {
            vpcConfigs = new ArrayList<>();
        }
        return vpcConfigs;
    }

    public void setVpcConfigs(List<Map<String, Object>> vpcConfigs) {
        this.vpcConfigs = vpcConfigs != null ? new ArrayList<>(vpcConfigs) : new ArrayList<>();
    }

    public boolean isIamAuthEnabled() {
        return iamAuthEnabled;
    }

    public void setIamAuthEnabled(boolean iamAuthEnabled) {
        this.iamAuthEnabled = iamAuthEnabled;
    }
}
