package io.github.hectorvent.floci.services.b2bi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS B2BI capability. Wire names are camelCase awsJson1_0. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2biCapability {

    private String capabilityId;
    private String capabilityArn;
    private String name;
    private String type;
    private JsonNode configuration;
    private JsonNode instructionsDocuments;
    private String createdAt;
    private String modifiedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public B2biCapability() {}

    public String getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(String capabilityId) {
        this.capabilityId = capabilityId;
    }

    public String getCapabilityArn() {
        return capabilityArn;
    }

    public void setCapabilityArn(String capabilityArn) {
        this.capabilityArn = capabilityArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration;
    }

    public JsonNode getInstructionsDocuments() {
        return instructionsDocuments;
    }

    public void setInstructionsDocuments(JsonNode instructionsDocuments) {
        this.instructionsDocuments = instructionsDocuments;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
