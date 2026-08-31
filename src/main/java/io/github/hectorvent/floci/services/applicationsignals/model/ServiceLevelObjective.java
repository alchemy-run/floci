package io.github.hectorvent.floci.services.applicationsignals.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch Application Signals SLO. Nested SLI/goal payloads stay as wire JSON. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceLevelObjective {

    private String name;
    private String arn;
    private String description;
    private long createdTime;
    private long lastUpdatedTime;
    private String evaluationType;
    private String metricSourceType;
    private JsonNode sli;
    private JsonNode requestBasedSli;
    private JsonNode goal;
    private JsonNode burnRateConfigurations;
    private Boolean autoInvestigationEnabled;
    private Map<String, String> tags;
    private List<JsonNode> exclusionWindows;

    public ServiceLevelObjective() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public String getEvaluationType() {
        return evaluationType;
    }

    public void setEvaluationType(String evaluationType) {
        this.evaluationType = evaluationType;
    }

    public String getMetricSourceType() {
        return metricSourceType;
    }

    public void setMetricSourceType(String metricSourceType) {
        this.metricSourceType = metricSourceType;
    }

    public JsonNode getSli() {
        return sli;
    }

    public void setSli(JsonNode sli) {
        this.sli = sli;
    }

    public JsonNode getRequestBasedSli() {
        return requestBasedSli;
    }

    public void setRequestBasedSli(JsonNode requestBasedSli) {
        this.requestBasedSli = requestBasedSli;
    }

    public JsonNode getGoal() {
        return goal;
    }

    public void setGoal(JsonNode goal) {
        this.goal = goal;
    }

    public JsonNode getBurnRateConfigurations() {
        return burnRateConfigurations;
    }

    public void setBurnRateConfigurations(JsonNode burnRateConfigurations) {
        this.burnRateConfigurations = burnRateConfigurations;
    }

    public Boolean getAutoInvestigationEnabled() {
        return autoInvestigationEnabled;
    }

    public void setAutoInvestigationEnabled(Boolean autoInvestigationEnabled) {
        this.autoInvestigationEnabled = autoInvestigationEnabled;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<JsonNode> getExclusionWindows() {
        return exclusionWindows;
    }

    public void setExclusionWindows(List<JsonNode> exclusionWindows) {
        this.exclusionWindows = exclusionWindows == null ? new ArrayList<>() : new ArrayList<>(exclusionWindows);
    }
}
