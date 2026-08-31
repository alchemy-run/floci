package io.github.hectorvent.floci.services.auditmanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Audit Manager assessment. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Assessment {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String status;
    private String frameworkId;
    private String frameworkArn;
    private JsonNode assessmentReportsDestination;
    private JsonNode scope;
    private JsonNode roles;
    private long createdAt;
    private long lastUpdated;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Assessment() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFrameworkId() {
        return frameworkId;
    }

    public void setFrameworkId(String frameworkId) {
        this.frameworkId = frameworkId;
    }

    public String getFrameworkArn() {
        return frameworkArn;
    }

    public void setFrameworkArn(String frameworkArn) {
        this.frameworkArn = frameworkArn;
    }

    public JsonNode getAssessmentReportsDestination() {
        return assessmentReportsDestination;
    }

    public void setAssessmentReportsDestination(JsonNode assessmentReportsDestination) {
        this.assessmentReportsDestination = assessmentReportsDestination;
    }

    public JsonNode getScope() {
        return scope;
    }

    public void setScope(JsonNode scope) {
        this.scope = scope;
    }

    public JsonNode getRoles() {
        return roles;
    }

    public void setRoles(JsonNode roles) {
        this.roles = roles;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
