package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiCache {
    private Long ttl;
    private String apiCachingBehavior;
    private Boolean transitEncryptionEnabled;
    private Boolean atRestEncryptionEnabled;
    private String type;
    private String status;
    private String healthMetricsConfig;

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }

    public String getApiCachingBehavior() { return apiCachingBehavior; }
    public void setApiCachingBehavior(String apiCachingBehavior) { this.apiCachingBehavior = apiCachingBehavior; }

    public Boolean getTransitEncryptionEnabled() { return transitEncryptionEnabled; }
    public void setTransitEncryptionEnabled(Boolean transitEncryptionEnabled) {
        this.transitEncryptionEnabled = transitEncryptionEnabled;
    }

    public Boolean getAtRestEncryptionEnabled() { return atRestEncryptionEnabled; }
    public void setAtRestEncryptionEnabled(Boolean atRestEncryptionEnabled) {
        this.atRestEncryptionEnabled = atRestEncryptionEnabled;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getHealthMetricsConfig() { return healthMetricsConfig; }
    public void setHealthMetricsConfig(String healthMetricsConfig) {
        this.healthMetricsConfig = healthMetricsConfig;
    }
}
