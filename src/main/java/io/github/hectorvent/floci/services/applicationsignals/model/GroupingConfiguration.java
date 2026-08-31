package io.github.hectorvent.floci.services.applicationsignals.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Account-level Application Signals grouping configuration. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupingConfiguration {

    private JsonNode groupingAttributeDefinitions;
    private long updatedAt;

    public GroupingConfiguration() {
    }

    public JsonNode getGroupingAttributeDefinitions() {
        return groupingAttributeDefinitions;
    }

    public void setGroupingAttributeDefinitions(JsonNode groupingAttributeDefinitions) {
        this.groupingAttributeDefinitions = groupingAttributeDefinitions;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
