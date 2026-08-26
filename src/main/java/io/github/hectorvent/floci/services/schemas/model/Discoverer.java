package io.github.hectorvent.floci.services.schemas.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class Discoverer {

    public static final String STARTED = "STARTED";
    public static final String STOPPED = "STOPPED";

    private String discovererId;
    private String discovererArn;
    private String sourceArn;
    private String description;
    private String state = STARTED;
    private boolean crossAccount = true;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public Discoverer() {
    }

    public String getDiscovererId() {
        return discovererId;
    }

    public void setDiscovererId(String discovererId) {
        this.discovererId = discovererId;
    }

    public String getDiscovererArn() {
        return discovererArn;
    }

    public void setDiscovererArn(String discovererArn) {
        this.discovererArn = discovererArn;
    }

    public String getSourceArn() {
        return sourceArn;
    }

    public void setSourceArn(String sourceArn) {
        this.sourceArn = sourceArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isCrossAccount() {
        return crossAccount;
    }

    public void setCrossAccount(boolean crossAccount) {
        this.crossAccount = crossAccount;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
