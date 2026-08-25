package io.github.hectorvent.floci.services.ce.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted Cost Explorer cost category definition.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_CostCategory.html">CostCategory</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CostCategoryDefinition {

    private String costCategoryArn;
    private String name;
    private String effectiveStart;
    private String effectiveEnd;
    private String ruleVersion;
    private JsonNode rules;
    private JsonNode splitChargeRules;
    private String defaultValue;
    private Map<String, String> resourceTags = new LinkedHashMap<>();

    public CostCategoryDefinition() {
    }

    public String getCostCategoryArn() {
        return costCategoryArn;
    }

    public void setCostCategoryArn(String costCategoryArn) {
        this.costCategoryArn = costCategoryArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEffectiveStart() {
        return effectiveStart;
    }

    public void setEffectiveStart(String effectiveStart) {
        this.effectiveStart = effectiveStart;
    }

    public String getEffectiveEnd() {
        return effectiveEnd;
    }

    public void setEffectiveEnd(String effectiveEnd) {
        this.effectiveEnd = effectiveEnd;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public JsonNode getRules() {
        return rules;
    }

    public void setRules(JsonNode rules) {
        this.rules = rules;
    }

    public JsonNode getSplitChargeRules() {
        return splitChargeRules;
    }

    public void setSplitChargeRules(JsonNode splitChargeRules) {
        this.splitChargeRules = splitChargeRules;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Map<String, String> getResourceTags() {
        if (resourceTags == null) {
            resourceTags = new LinkedHashMap<>();
        }
        return resourceTags;
    }

    public void setResourceTags(Map<String, String> resourceTags) {
        this.resourceTags = resourceTags == null ? new LinkedHashMap<>() : resourceTags;
    }
}
