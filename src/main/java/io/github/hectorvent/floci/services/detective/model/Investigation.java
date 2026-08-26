package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Detective investigation of an IAM entity. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Investigation {

    private String investigationId;
    private String graphArn;
    private String entityArn;
    private String entityType;
    private String status;
    private String severity;
    private String state;
    private String createdTime;
    private String scopeStartTime;
    private String scopeEndTime;

    public Investigation() {
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public void setInvestigationId(String investigationId) {
        this.investigationId = investigationId;
    }

    public String getGraphArn() {
        return graphArn;
    }

    public void setGraphArn(String graphArn) {
        this.graphArn = graphArn;
    }

    public String getEntityArn() {
        return entityArn;
    }

    public void setEntityArn(String entityArn) {
        this.entityArn = entityArn;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(String createdTime) {
        this.createdTime = createdTime;
    }

    public String getScopeStartTime() {
        return scopeStartTime;
    }

    public void setScopeStartTime(String scopeStartTime) {
        this.scopeStartTime = scopeStartTime;
    }

    public String getScopeEndTime() {
        return scopeEndTime;
    }

    public void setScopeEndTime(String scopeEndTime) {
        this.scopeEndTime = scopeEndTime;
    }
}
