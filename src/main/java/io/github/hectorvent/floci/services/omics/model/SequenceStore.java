package io.github.hectorvent.floci.services.omics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon HealthOmics sequence store. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SequenceStore {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String sseType;
    private String sseKeyArn;
    private String creationTime;
    private String updateTime;
    private String fallbackLocation;
    private String eTagAlgorithmFamily;
    private String status;
    private String region;
    private String clientToken;
    private List<String> propagatedSetLevelTags = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public SequenceStore() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSseType() {
        return sseType;
    }

    public void setSseType(String sseType) {
        this.sseType = sseType;
    }

    public String getSseKeyArn() {
        return sseKeyArn;
    }

    public void setSseKeyArn(String sseKeyArn) {
        this.sseKeyArn = sseKeyArn;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getFallbackLocation() {
        return fallbackLocation;
    }

    public void setFallbackLocation(String fallbackLocation) {
        this.fallbackLocation = fallbackLocation;
    }

    public String getETagAlgorithmFamily() {
        return eTagAlgorithmFamily;
    }

    public void setETagAlgorithmFamily(String eTagAlgorithmFamily) {
        this.eTagAlgorithmFamily = eTagAlgorithmFamily;
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

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public List<String> getPropagatedSetLevelTags() {
        return propagatedSetLevelTags;
    }

    public void setPropagatedSetLevelTags(List<String> propagatedSetLevelTags) {
        this.propagatedSetLevelTags = propagatedSetLevelTags == null
                ? new ArrayList<>()
                : new ArrayList<>(propagatedSetLevelTags);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
