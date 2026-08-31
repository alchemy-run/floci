package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rule {

    private String detectorId;
    private String ruleId;
    private String ruleVersion;
    private String description;
    private String expression;
    private String language;
    private String arn;
    private String createdTime;
    private String lastUpdatedTime;
    private List<String> outcomes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Rule() {}

    public String getDetectorId() { return detectorId; }
    public void setDetectorId(String detectorId) { this.detectorId = detectorId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public String getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(String lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public List<String> getOutcomes() { return outcomes; }
    public void setOutcomes(List<String> outcomes) {
        this.outcomes = outcomes != null ? outcomes : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
