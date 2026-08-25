package io.github.hectorvent.floci.services.mediapackagev2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaPackage v2 channel. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Channel {

    private String channelGroupName;
    private String channelName;
    private String arn;
    private String description;
    private String inputType;
    private String eTag;
    private String region;
    private String policy;
    private JsonNode ingestEndpoints;
    private JsonNode inputSwitchConfiguration;
    private JsonNode outputHeaderConfiguration;
    private long createdAt;
    private long modifiedAt;
    private Long resetAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Channel() {
    }

    public String getChannelGroupName() {
        return channelGroupName;
    }

    public void setChannelGroupName(String channelGroupName) {
        this.channelGroupName = channelGroupName;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getETag() {
        return eTag;
    }

    public void setETag(String eTag) {
        this.eTag = eTag;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public JsonNode getIngestEndpoints() {
        return ingestEndpoints;
    }

    public void setIngestEndpoints(JsonNode ingestEndpoints) {
        this.ingestEndpoints = ingestEndpoints;
    }

    public JsonNode getInputSwitchConfiguration() {
        return inputSwitchConfiguration;
    }

    public void setInputSwitchConfiguration(JsonNode inputSwitchConfiguration) {
        this.inputSwitchConfiguration = inputSwitchConfiguration;
    }

    public JsonNode getOutputHeaderConfiguration() {
        return outputHeaderConfiguration;
    }

    public void setOutputHeaderConfiguration(JsonNode outputHeaderConfiguration) {
        this.outputHeaderConfiguration = outputHeaderConfiguration;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(long modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public Long getResetAt() {
        return resetAt;
    }

    public void setResetAt(Long resetAt) {
        this.resetAt = resetAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
