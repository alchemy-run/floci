package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A MemoryDB parameter group: a named collection of engine parameter
 * overrides applied to every node of any cluster that references it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParameterGroup {

    private String name;
    private String family;
    private String description;
    private String arn;
    private Instant createdAt;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ParameterGroup() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
