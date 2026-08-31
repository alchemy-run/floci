package io.github.hectorvent.floci.services.mediapackagev2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaPackage v2 channel group. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelGroup {

    private String channelGroupName;
    private String arn;
    private String egressDomain;
    private String description;
    private String eTag;
    private String region;
    private long createdAt;
    private long modifiedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ChannelGroup() {
    }

    public String getChannelGroupName() {
        return channelGroupName;
    }

    public void setChannelGroupName(String channelGroupName) {
        this.channelGroupName = channelGroupName;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getEgressDomain() {
        return egressDomain;
    }

    public void setEgressDomain(String egressDomain) {
        this.egressDomain = egressDomain;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
