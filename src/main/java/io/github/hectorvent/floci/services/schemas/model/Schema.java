package io.github.hectorvent.floci.services.schemas.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** EventBridge schema (all versions) in a registry. */
@RegisterForReflection
public class Schema {
    private String registryName;
    private String schemaName;
    private String schemaArn;
    private String description;
    private String lastModified;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<SchemaVersion> versions = new ArrayList<>();
    private Map<String, CodeBinding> codeBindings = new LinkedHashMap<>();

    public Schema() {
    }

    public String getRegistryName() {
        return registryName;
    }

    public void setRegistryName(String registryName) {
        this.registryName = registryName;
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

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<SchemaVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<SchemaVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
    }

    public SchemaVersion latestVersion() {
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.get(versions.size() - 1);
    }

    public Map<String, CodeBinding> getCodeBindings() {
        if (codeBindings == null) {
            codeBindings = new LinkedHashMap<>();
        }
        return codeBindings;
    }

    public void setCodeBindings(Map<String, CodeBinding> codeBindings) {
        this.codeBindings = codeBindings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(codeBindings);
    }
}
