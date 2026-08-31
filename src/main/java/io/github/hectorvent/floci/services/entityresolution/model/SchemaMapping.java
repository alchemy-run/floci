package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Entity Resolution schema mapping. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchemaMapping {

    private String schemaName;
    private String schemaArn;
    private String description;
    private JsonNode mappedInputFields;
    private long createdAt;
    private long updatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SchemaMapping() {
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getSchemaArn() {
        return schemaArn;
    }

    public void setSchemaArn(String schemaArn) {
        this.schemaArn = schemaArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getMappedInputFields() {
        return mappedInputFields;
    }

    public void setMappedInputFields(JsonNode mappedInputFields) {
        this.mappedInputFields = mappedInputFields;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
