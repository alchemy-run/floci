package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PredictionRecord {

    private String eventId;
    private String eventTypeName;
    private String eventTimestamp;
    private String predictionTimestamp;
    private String detectorId;
    private String detectorVersionId;
    private String detectorVersionStatus;
    private String ruleExecutionMode;
    private String region;
    private List<String> outcomes = new ArrayList<>();
    private List<Rule> evaluatedRules = new ArrayList<>();

    public PredictionRecord() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventTypeName() { return eventTypeName; }
    public void setEventTypeName(String eventTypeName) { this.eventTypeName = eventTypeName; }

    public String getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(String eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    public String getPredictionTimestamp() { return predictionTimestamp; }
    public void setPredictionTimestamp(String predictionTimestamp) { this.predictionTimestamp = predictionTimestamp; }

    public String getDetectorId() { return detectorId; }
    public void setDetectorId(String detectorId) { this.detectorId = detectorId; }

    public String getDetectorVersionId() { return detectorVersionId; }
    public void setDetectorVersionId(String detectorVersionId) { this.detectorVersionId = detectorVersionId; }

    public String getDetectorVersionStatus() { return detectorVersionStatus; }
    public void setDetectorVersionStatus(String detectorVersionStatus) {
        this.detectorVersionStatus = detectorVersionStatus;
    }

    public String getRuleExecutionMode() { return ruleExecutionMode; }
    public void setRuleExecutionMode(String ruleExecutionMode) { this.ruleExecutionMode = ruleExecutionMode; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<String> getOutcomes() { return outcomes; }
    public void setOutcomes(List<String> outcomes) {
        this.outcomes = outcomes != null ? outcomes : new ArrayList<>();
    }

    public List<Rule> getEvaluatedRules() { return evaluatedRules; }
    public void setEvaluatedRules(List<Rule> evaluatedRules) {
        this.evaluatedRules = evaluatedRules != null ? evaluatedRules : new ArrayList<>();
    }
}
