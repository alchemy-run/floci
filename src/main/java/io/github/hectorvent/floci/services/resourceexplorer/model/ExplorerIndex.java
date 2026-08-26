package io.github.hectorvent.floci.services.resourceexplorer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resource Explorer region singleton index. */
@RegisterForReflection
public class ExplorerIndex {
    private String arn;
    private String type;
    private String state;
    private String createdAt;
    private String lastUpdatedAt;
    private String defaultViewArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ExplorerIndex() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getDefaultViewArn() {
        return defaultViewArn;
    }

    public void setDefaultViewArn(String defaultViewArn) {
        this.defaultViewArn = defaultViewArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
