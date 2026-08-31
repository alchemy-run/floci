package io.github.hectorvent.floci.services.appintegrations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon AppIntegrations event integration. Wire JSON is PascalCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class EventIntegration {

    private String name;
    private String description;
    private String eventIntegrationArn;
    private String eventBridgeBus;
    private JsonNode eventFilter;
    private Map<String, String> tags = new LinkedHashMap<>();

    public EventIntegration() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEventIntegrationArn() {
        return eventIntegrationArn;
    }

    public void setEventIntegrationArn(String eventIntegrationArn) {
        this.eventIntegrationArn = eventIntegrationArn;
    }

    public String getEventBridgeBus() {
        return eventBridgeBus;
    }

    public void setEventBridgeBus(String eventBridgeBus) {
        this.eventBridgeBus = eventBridgeBus;
    }

    public JsonNode getEventFilter() {
        return eventFilter == null ? null : eventFilter.deepCopy();
    }

    public void setEventFilter(JsonNode eventFilter) {
        this.eventFilter = eventFilter == null ? null : eventFilter.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
