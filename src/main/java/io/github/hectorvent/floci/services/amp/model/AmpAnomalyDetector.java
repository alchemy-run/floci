package io.github.hectorvent.floci.services.amp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Random Cut Forest anomaly detector attached to an AMP workspace. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmpAnomalyDetector {

    private String anomalyDetectorId;
    private String alias;
    private String arn;
    private Integer evaluationIntervalInSeconds;
    private JsonNode missingDataAction;
    private JsonNode configuration;
    private Map<String, String> labels = new LinkedHashMap<>();
    private String statusCode;
    private long createdAt;
    private long modifiedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AmpAnomalyDetector() {
    }

    public String getAnomalyDetectorId() {
        return anomalyDetectorId;
    }

    public void setAnomalyDetectorId(String anomalyDetectorId) {
        this.anomalyDetectorId = anomalyDetectorId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public Integer getEvaluationIntervalInSeconds() {
        return evaluationIntervalInSeconds;
    }

    public void setEvaluationIntervalInSeconds(Integer evaluationIntervalInSeconds) {
        this.evaluationIntervalInSeconds = evaluationIntervalInSeconds;
    }

    public JsonNode getMissingDataAction() {
        return missingDataAction;
    }

    public void setMissingDataAction(JsonNode missingDataAction) {
        this.missingDataAction = missingDataAction == null ? null : missingDataAction.deepCopy();
    }

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration == null ? null : configuration.deepCopy();
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(labels);
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
