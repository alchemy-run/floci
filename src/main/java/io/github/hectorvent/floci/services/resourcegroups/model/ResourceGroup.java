package io.github.hectorvent.floci.services.resourcegroups.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An AWS Resource Groups group (query-based or configuration-based). */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceGroup {

    private String name;
    private String arn;
    private String description;
    private JsonNode resourceQuery;
    private JsonNode configuration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> members = new ArrayList<>();

    public ResourceGroup() {
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

    public JsonNode getResourceQuery() {
        return resourceQuery == null ? null : resourceQuery.deepCopy();
    }

    public void setResourceQuery(JsonNode resourceQuery) {
        this.resourceQuery = resourceQuery == null ? null : resourceQuery.deepCopy();
    }

    public JsonNode getConfiguration() {
        return configuration == null ? null : configuration.deepCopy();
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration == null ? null : configuration.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(members));
    }

    public Set<String> memberSet() {
        return new LinkedHashSet<>(members);
    }
}
