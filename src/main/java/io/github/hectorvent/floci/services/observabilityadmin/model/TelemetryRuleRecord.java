package io.github.hectorvent.floci.services.observabilityadmin.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A CloudWatch Observability Admin telemetry rule. Nested Rule is stored as wire JSON. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelemetryRuleRecord {

    private String ruleName;
    private String ruleArn;
    private long createdTimeStamp;
    private long lastUpdateTimeStamp;
    private String homeRegion;
    private boolean replicated;
    private JsonNode rule;
    private Map<String, String> tags = new LinkedHashMap<>();

    public TelemetryRuleRecord() {
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleArn() {
        return ruleArn;
    }

    public void setRuleArn(String ruleArn) {
        this.ruleArn = ruleArn;
    }

    public long getCreatedTimeStamp() {
        return createdTimeStamp;
    }

    public void setCreatedTimeStamp(long createdTimeStamp) {
        this.createdTimeStamp = createdTimeStamp;
    }

    public long getLastUpdateTimeStamp() {
        return lastUpdateTimeStamp;
    }

    public void setLastUpdateTimeStamp(long lastUpdateTimeStamp) {
        this.lastUpdateTimeStamp = lastUpdateTimeStamp;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public boolean isReplicated() {
        return replicated;
    }

    public void setReplicated(boolean replicated) {
        this.replicated = replicated;
    }

    public JsonNode getRule() {
        return rule;
    }

    public void setRule(JsonNode rule) {
        this.rule = rule;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
