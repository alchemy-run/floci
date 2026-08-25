package io.github.hectorvent.floci.services.accessanalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An IAM Access Analyzer. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Analyzer {

    private String name;
    private String arn;
    private String type;
    private String status;
    private String createdAt;
    private JsonNode configuration;
    private Map<String, String> tags;
    private Map<String, ArchiveRule> archiveRules;

    public Analyzer() {
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration;
    }

    public Integer getUnusedAccessAge() {
        if (configuration == null || !configuration.isObject() || !configuration.has("unusedAccess")) {
            return null;
        }
        JsonNode unusedAccess = configuration.get("unusedAccess");
        if (unusedAccess == null || !unusedAccess.has("unusedAccessAge")) {
            return null;
        }
        JsonNode age = unusedAccess.get("unusedAccessAge");
        return age != null && age.isNumber() ? age.intValue() : null;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public Map<String, ArchiveRule> getArchiveRules() {
        return archiveRules;
    }

    public void setArchiveRules(Map<String, ArchiveRule> archiveRules) {
        this.archiveRules = archiveRules == null ? null : new LinkedHashMap<>(archiveRules);
    }
}
