package io.github.hectorvent.floci.services.osis.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An OpenSearch Ingestion pipeline. */
@RegisterForReflection
public class OsisPipeline {
    private String pipelineName;
    private String pipelineArn;
    private int minUnits;
    private int maxUnits;
    private String status;
    private String pipelineConfigurationBody;
    private long createdAt;
    private long lastUpdatedAt;
    private List<String> ingestEndpointUrls = new ArrayList<>();
    private JsonNode logPublishingOptions;
    private JsonNode vpcOptions;
    private JsonNode bufferOptions;
    private JsonNode encryptionAtRestOptions;
    private String pipelineRoleArn;
    private String policy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public OsisPipeline() {
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public String getPipelineArn() {
        return pipelineArn;
    }

    public void setPipelineArn(String pipelineArn) {
        this.pipelineArn = pipelineArn;
    }

    public int getMinUnits() {
        return minUnits;
    }

    public void setMinUnits(int minUnits) {
        this.minUnits = minUnits;
    }

    public int getMaxUnits() {
        return maxUnits;
    }

    public void setMaxUnits(int maxUnits) {
        this.maxUnits = maxUnits;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPipelineConfigurationBody() {
        return pipelineConfigurationBody;
    }

    public void setPipelineConfigurationBody(String pipelineConfigurationBody) {
        this.pipelineConfigurationBody = pipelineConfigurationBody;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(long lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public List<String> getIngestEndpointUrls() {
        return ingestEndpointUrls;
    }

    public void setIngestEndpointUrls(List<String> ingestEndpointUrls) {
        this.ingestEndpointUrls = ingestEndpointUrls == null ? new ArrayList<>() : new ArrayList<>(ingestEndpointUrls);
    }

    public JsonNode getLogPublishingOptions() {
        return logPublishingOptions;
    }

    public void setLogPublishingOptions(JsonNode logPublishingOptions) {
        this.logPublishingOptions = logPublishingOptions;
    }

    public JsonNode getVpcOptions() {
        return vpcOptions;
    }

    public void setVpcOptions(JsonNode vpcOptions) {
        this.vpcOptions = vpcOptions;
    }

    public JsonNode getBufferOptions() {
        return bufferOptions;
    }

    public void setBufferOptions(JsonNode bufferOptions) {
        this.bufferOptions = bufferOptions;
    }

    public JsonNode getEncryptionAtRestOptions() {
        return encryptionAtRestOptions;
    }

    public void setEncryptionAtRestOptions(JsonNode encryptionAtRestOptions) {
        this.encryptionAtRestOptions = encryptionAtRestOptions;
    }

    public String getPipelineRoleArn() {
        return pipelineRoleArn;
    }

    public void setPipelineRoleArn(String pipelineRoleArn) {
        this.pipelineRoleArn = pipelineRoleArn;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
