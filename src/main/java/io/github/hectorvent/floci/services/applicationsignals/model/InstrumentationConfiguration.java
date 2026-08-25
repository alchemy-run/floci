package io.github.hectorvent.floci.services.applicationsignals.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A CloudWatch Application Signals dynamic instrumentation configuration. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstrumentationConfiguration {

    private String arn;
    private String instrumentationType;
    private String service;
    private String environment;
    private String signalType;
    private String locationHash;
    private JsonNode location;
    private JsonNode captureConfiguration;
    private JsonNode attributeFilters;
    private String description;
    private Long expiresAt;
    private long createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public InstrumentationConfiguration() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getInstrumentationType() {
        return instrumentationType;
    }

    public void setInstrumentationType(String instrumentationType) {
        this.instrumentationType = instrumentationType;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getSignalType() {
        return signalType;
    }

    public void setSignalType(String signalType) {
        this.signalType = signalType;
    }

    public String getLocationHash() {
        return locationHash;
    }

    public void setLocationHash(String locationHash) {
        this.locationHash = locationHash;
    }

    public JsonNode getLocation() {
        return copy(location);
    }

    public void setLocation(JsonNode location) {
        this.location = copy(location);
    }

    public JsonNode getCaptureConfiguration() {
        return copy(captureConfiguration);
    }

    public void setCaptureConfiguration(JsonNode captureConfiguration) {
        this.captureConfiguration = copy(captureConfiguration);
    }

    public JsonNode getAttributeFilters() {
        return copy(attributeFilters);
    }

    public void setAttributeFilters(JsonNode attributeFilters) {
        this.attributeFilters = copy(attributeFilters);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
