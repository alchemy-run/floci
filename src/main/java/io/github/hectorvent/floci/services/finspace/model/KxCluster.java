package io.github.hectorvent.floci.services.finspace.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A kdb cluster in a FinSpace environment. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KxCluster {

    private String environmentId;
    private String clusterName;
    private String clusterType;
    private String status;
    private String region;
    private String availabilityZoneId;
    private String azMode;
    private String releaseLabel;
    private JsonNode vpcConfiguration;
    private List<KxNode> nodes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;

    public KxCluster() {
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getClusterType() {
        return clusterType;
    }

    public void setClusterType(String clusterType) {
        this.clusterType = clusterType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAvailabilityZoneId() {
        return availabilityZoneId;
    }

    public void setAvailabilityZoneId(String availabilityZoneId) {
        this.availabilityZoneId = availabilityZoneId;
    }

    public String getAzMode() {
        return azMode;
    }

    public void setAzMode(String azMode) {
        this.azMode = azMode;
    }

    public String getReleaseLabel() {
        return releaseLabel;
    }

    public void setReleaseLabel(String releaseLabel) {
        this.releaseLabel = releaseLabel;
    }

    public JsonNode getVpcConfiguration() {
        return vpcConfiguration;
    }

    public void setVpcConfiguration(JsonNode vpcConfiguration) {
        this.vpcConfiguration = vpcConfiguration;
    }

    public List<KxNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<KxNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
