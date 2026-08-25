package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An App Runner observability configuration revision. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class ObservabilityConfiguration {

    private String observabilityConfigurationArn;
    private String observabilityConfigurationName;
    private TraceConfiguration traceConfiguration;
    private Integer observabilityConfigurationRevision;
    private Boolean latest;
    private String status;
    private Long createdAt;
    private Long deletedAt;

    @JsonIgnore
    private String region;

    @JsonIgnore
    private Map<String, String> tags = new LinkedHashMap<>();

    public ObservabilityConfiguration() {
    }

    public String getObservabilityConfigurationArn() {
        return observabilityConfigurationArn;
    }

    public void setObservabilityConfigurationArn(String observabilityConfigurationArn) {
        this.observabilityConfigurationArn = observabilityConfigurationArn;
    }

    public String getObservabilityConfigurationName() {
        return observabilityConfigurationName;
    }

    public void setObservabilityConfigurationName(String observabilityConfigurationName) {
        this.observabilityConfigurationName = observabilityConfigurationName;
    }

    public TraceConfiguration getTraceConfiguration() {
        return traceConfiguration;
    }

    public void setTraceConfiguration(TraceConfiguration traceConfiguration) {
        this.traceConfiguration = traceConfiguration;
    }

    public Integer getObservabilityConfigurationRevision() {
        return observabilityConfigurationRevision;
    }

    public void setObservabilityConfigurationRevision(Integer observabilityConfigurationRevision) {
        this.observabilityConfigurationRevision = observabilityConfigurationRevision;
    }

    public Boolean getLatest() {
        return latest;
    }

    public void setLatest(Boolean latest) {
        this.latest = latest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public static class TraceConfiguration {
        private String vendor;

        public TraceConfiguration() {
        }

        public TraceConfiguration(String vendor) {
            this.vendor = vendor;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor;
        }
    }
}
