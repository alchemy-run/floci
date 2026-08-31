package io.github.hectorvent.floci.services.mediapackagev2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaPackage v2 origin endpoint. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OriginEndpoint {

    private String channelGroupName;
    private String channelName;
    private String originEndpointName;
    private String arn;
    private String containerType;
    private String description;
    private String eTag;
    private String region;
    private String policy;
    private String uriSeparator;
    private JsonNode segment;
    private JsonNode hlsManifests;
    private JsonNode lowLatencyHlsManifests;
    private JsonNode dashManifests;
    private JsonNode mssManifests;
    private JsonNode forceEndpointErrorConfiguration;
    private JsonNode cdnAuthConfiguration;
    private Integer startoverWindowSeconds;
    private long createdAt;
    private long modifiedAt;
    private Long resetAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public OriginEndpoint() {
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

    public String getOriginEndpointName() {
        return originEndpointName;
    }

    public void setOriginEndpointName(String originEndpointName) {
        this.originEndpointName = originEndpointName;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getContainerType() {
        return containerType;
    }

    public void setContainerType(String containerType) {
        this.containerType = containerType;
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

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getUriSeparator() {
        return uriSeparator;
    }

    public void setUriSeparator(String uriSeparator) {
        this.uriSeparator = uriSeparator;
    }

    public JsonNode getSegment() {
        return segment;
    }

    public void setSegment(JsonNode segment) {
        this.segment = segment;
    }

    public JsonNode getHlsManifests() {
        return hlsManifests;
    }

    public void setHlsManifests(JsonNode hlsManifests) {
        this.hlsManifests = hlsManifests;
    }

    public JsonNode getLowLatencyHlsManifests() {
        return lowLatencyHlsManifests;
    }

    public void setLowLatencyHlsManifests(JsonNode lowLatencyHlsManifests) {
        this.lowLatencyHlsManifests = lowLatencyHlsManifests;
    }

    public JsonNode getDashManifests() {
        return dashManifests;
    }

    public void setDashManifests(JsonNode dashManifests) {
        this.dashManifests = dashManifests;
    }

    public JsonNode getMssManifests() {
        return mssManifests;
    }

    public void setMssManifests(JsonNode mssManifests) {
        this.mssManifests = mssManifests;
    }

    public JsonNode getForceEndpointErrorConfiguration() {
        return forceEndpointErrorConfiguration;
    }

    public void setForceEndpointErrorConfiguration(JsonNode forceEndpointErrorConfiguration) {
        this.forceEndpointErrorConfiguration = forceEndpointErrorConfiguration;
    }

    public JsonNode getCdnAuthConfiguration() {
        return cdnAuthConfiguration;
    }

    public void setCdnAuthConfiguration(JsonNode cdnAuthConfiguration) {
        this.cdnAuthConfiguration = cdnAuthConfiguration;
    }

    public Integer getStartoverWindowSeconds() {
        return startoverWindowSeconds;
    }

    public void setStartoverWindowSeconds(Integer startoverWindowSeconds) {
        this.startoverWindowSeconds = startoverWindowSeconds;
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
