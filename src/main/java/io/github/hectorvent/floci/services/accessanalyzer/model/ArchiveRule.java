package io.github.hectorvent.floci.services.accessanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An archive rule attached to an IAM Access Analyzer. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArchiveRule {

    private String ruleName;
    private Map<String, Criterion> filter;
    private String createdAt;
    private String updatedAt;

    public ArchiveRule() {
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Map<String, Criterion> getFilter() {
        return filter;
    }

    public void setFilter(Map<String, Criterion> filter) {
        this.filter = filter == null ? null : new LinkedHashMap<>(filter);
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
