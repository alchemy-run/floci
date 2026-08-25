package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetectorVersion {

    private String detectorId;
    private String detectorVersionId;
    private String description;
    private String status;
    private String ruleExecutionMode;
    private String arn;
    private String createdTime;
    private String lastUpdatedTime;
    private List<Rule> rules = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DetectorVersion() {}

    public String getDetectorId() { return detectorId; }
    public void setDetectorId(String detectorId) { this.detectorId = detectorId; }

    public String getDetectorVersionId() { return detectorVersionId; }
    public void setDetectorVersionId(String detectorVersionId) { this.detectorVersionId = detectorVersionId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRuleExecutionMode() { return ruleExecutionMode; }
    public void setRuleExecutionMode(String ruleExecutionMode) { this.ruleExecutionMode = ruleExecutionMode; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public String getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(String lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
