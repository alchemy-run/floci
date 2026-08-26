package io.github.hectorvent.floci.services.shield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shield Advanced protection group.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShieldProtectionGroup {

    private String protectionGroupId;
    private String protectionGroupArn;
    private String aggregation;
    private String pattern;
    private String resourceType;
    private List<String> members = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ShieldProtectionGroup() {
    }

    public String getProtectionGroupId() {
        return protectionGroupId;
    }

    public void setProtectionGroupId(String protectionGroupId) {
        this.protectionGroupId = protectionGroupId;
    }

    public String getProtectionGroupArn() {
        return protectionGroupArn;
    }

    public void setProtectionGroupArn(String protectionGroupArn) {
        this.protectionGroupArn = protectionGroupArn;
    }

    public String getAggregation() {
        return aggregation;
    }

    public void setAggregation(String aggregation) {
        this.aggregation = aggregation;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members != null ? members : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
