package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A CloudWatch RUM extended or custom metric definition. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class MetricDefinition {
    private String metricDefinitionId;
    private String name;
    private String valueKey;
    private String unitLabel;
    private Map<String, String> dimensionKeys;
    private String eventPattern;
    private String namespace;

    public MetricDefinition() {
    }

    public String getMetricDefinitionId() {
        return metricDefinitionId;
    }

    public void setMetricDefinitionId(String metricDefinitionId) {
        this.metricDefinitionId = metricDefinitionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValueKey() {
        return valueKey;
    }

    public void setValueKey(String valueKey) {
        this.valueKey = valueKey;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public void setUnitLabel(String unitLabel) {
        this.unitLabel = unitLabel;
    }

    public Map<String, String> getDimensionKeys() {
        return dimensionKeys == null ? null : Map.copyOf(dimensionKeys);
    }

    public void setDimensionKeys(Map<String, String> dimensionKeys) {
        this.dimensionKeys = dimensionKeys == null ? null : new LinkedHashMap<>(dimensionKeys);
    }

    public String getEventPattern() {
        return eventPattern;
    }

    public void setEventPattern(String eventPattern) {
        this.eventPattern = eventPattern;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
