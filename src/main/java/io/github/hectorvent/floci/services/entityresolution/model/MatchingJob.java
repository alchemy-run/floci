package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A matching-workflow job. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchingJob {

    private String jobId;
    private String workflowName;
    private String status;
    private long startTime;
    private Long endTime;
    private JsonNode metrics;
    private JsonNode outputSourceConfig;
    private JsonNode errorDetails;

    public MatchingJob() {
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public JsonNode getMetrics() {
        return metrics;
    }

    public void setMetrics(JsonNode metrics) {
        this.metrics = metrics;
    }

    public JsonNode getOutputSourceConfig() {
        return outputSourceConfig;
    }

    public void setOutputSourceConfig(JsonNode outputSourceConfig) {
        this.outputSourceConfig = outputSourceConfig;
    }

    public JsonNode getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(JsonNode errorDetails) {
        this.errorDetails = errorDetails;
    }
}
