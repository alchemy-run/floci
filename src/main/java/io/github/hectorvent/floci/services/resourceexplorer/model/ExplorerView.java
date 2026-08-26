package io.github.hectorvent.floci.services.resourceexplorer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resource Explorer view — a named search lens over the region's index. */
@RegisterForReflection
public class ExplorerView {
    private String viewArn;
    private String viewName;
    private String owner;
    private String lastUpdatedAt;
    private String scope;
    private String filterString;
    private List<String> includedProperties = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ExplorerView() {
    }

    public String getViewArn() {
        return viewArn;
    }

    public void setViewArn(String viewArn) {
        this.viewArn = viewArn;
    }

    public String getViewName() {
        return viewName;
    }

    public void setViewName(String viewName) {
        this.viewName = viewName;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getFilterString() {
        return filterString;
    }

    public void setFilterString(String filterString) {
        this.filterString = filterString;
    }

    public List<String> getIncludedProperties() {
        return includedProperties;
    }

    public void setIncludedProperties(List<String> includedProperties) {
        this.includedProperties = includedProperties == null
                ? new ArrayList<>()
                : new ArrayList<>(includedProperties);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
