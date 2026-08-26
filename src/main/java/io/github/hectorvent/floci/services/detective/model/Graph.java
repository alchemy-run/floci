package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Detective behavior graph. One graph is allowed per account per Region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Graph {

    private String graphId;
    private String arn;
    private String createdTime;
    private String region;
    private boolean autoEnable;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, String> datasourcePackages = new LinkedHashMap<>();
    private Map<String, String> datasourceStartedAt = new LinkedHashMap<>();
    private Map<String, Member> members = new LinkedHashMap<>();
    private Map<String, Investigation> investigations = new LinkedHashMap<>();

    public Graph() {
    }

    public String getGraphId() {
        return graphId;
    }

    public void setGraphId(String graphId) {
        this.graphId = graphId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(String createdTime) {
        this.createdTime = createdTime;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isAutoEnable() {
        return autoEnable;
    }

    public void setAutoEnable(boolean autoEnable) {
        this.autoEnable = autoEnable;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, String> getDatasourcePackages() {
        return datasourcePackages;
    }

    public void setDatasourcePackages(Map<String, String> datasourcePackages) {
        this.datasourcePackages = datasourcePackages == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(datasourcePackages);
    }

    public Map<String, String> getDatasourceStartedAt() {
        return datasourceStartedAt;
    }

    public void setDatasourceStartedAt(Map<String, String> datasourceStartedAt) {
        this.datasourceStartedAt = datasourceStartedAt == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(datasourceStartedAt);
    }

    public Map<String, Member> getMembers() {
        return members;
    }

    public void setMembers(Map<String, Member> members) {
        this.members = members == null ? new LinkedHashMap<>() : new LinkedHashMap<>(members);
    }

    public Map<String, Investigation> getInvestigations() {
        return investigations;
    }

    public void setInvestigations(Map<String, Investigation> investigations) {
        this.investigations = investigations == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(investigations);
    }
}
