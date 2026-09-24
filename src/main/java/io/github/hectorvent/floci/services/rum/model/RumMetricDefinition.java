package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A RUM extended/custom metric definition, serialized as AWS's {@code MetricDefinition} shape. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class RumMetricDefinition {
    private String metricDefinitionId;
    private String name;
    private String valueKey;
    private String unitLabel;
    private Map<String, String> dimensionKeys;
    private String eventPattern;
    private String namespace;

    public RumMetricDefinition() {
    }

    public RumMetricDefinition(String metricDefinitionId, String name, String valueKey, String unitLabel,
                               Map<String, String> dimensionKeys, String eventPattern, String namespace) {
        this.metricDefinitionId = metricDefinitionId;
        this.name = name;
        this.valueKey = valueKey;
        this.unitLabel = unitLabel;
        setDimensionKeys(dimensionKeys);
        this.eventPattern = eventPattern;
        this.namespace = namespace;
    }

    /** True when both definitions describe the same metric, ignoring their ids. */
    public boolean sameDefinitionAs(RumMetricDefinition other) {
        return Objects.equals(name, other.name)
                && Objects.equals(valueKey, other.valueKey)
                && Objects.equals(unitLabel, other.unitLabel)
                && Objects.equals(dimensionKeysOrEmpty(), other.dimensionKeysOrEmpty())
                && Objects.equals(eventPattern, other.eventPattern)
                && Objects.equals(namespace, other.namespace);
    }

    private Map<String, String> dimensionKeysOrEmpty() {
        return dimensionKeys == null ? Map.of() : dimensionKeys;
    }

    public RumMetricDefinition withId(String id) {
        return new RumMetricDefinition(id, name, valueKey, unitLabel, dimensionKeys, eventPattern, namespace);
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
        return dimensionKeys;
    }

    public void setDimensionKeys(Map<String, String> dimensionKeys) {
        this.dimensionKeys = dimensionKeys == null || dimensionKeys.isEmpty()
                ? null
                : new LinkedHashMap<>(dimensionKeys);
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
