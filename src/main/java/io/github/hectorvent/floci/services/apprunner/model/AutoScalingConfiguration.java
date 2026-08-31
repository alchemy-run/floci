package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An App Runner auto scaling configuration revision.
 *
 * <p>ARN shape: {@code arn:aws:apprunner:region:account:autoscalingconfiguration/name/revision/id}.
 * Status is stored lowercase ({@code active}/{@code inactive}) to match the live wire.
 */
@RegisterForReflection
public class AutoScalingConfiguration {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";
    public static final String DEFAULT_NAME = "DefaultConfiguration";

    private String autoScalingConfigurationArn;
    private String autoScalingConfigurationName;
    private int autoScalingConfigurationRevision;
    private String configurationId;
    private boolean latest;
    private String status = STATUS_ACTIVE;
    private int maxConcurrency = 100;
    private int minSize = 1;
    private int maxSize = 25;
    private long createdAt;
    private Long deletedAt;
    private boolean hasAssociatedService;
    private boolean isDefault;
    @JsonIgnore
    private String region;
    @JsonIgnore
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getAutoScalingConfigurationArn() {
        return autoScalingConfigurationArn;
    }

    public void setAutoScalingConfigurationArn(String autoScalingConfigurationArn) {
        this.autoScalingConfigurationArn = autoScalingConfigurationArn;
    }

    public String getAutoScalingConfigurationName() {
        return autoScalingConfigurationName;
    }

    public void setAutoScalingConfigurationName(String autoScalingConfigurationName) {
        this.autoScalingConfigurationName = autoScalingConfigurationName;
    }

    public int getAutoScalingConfigurationRevision() {
        return autoScalingConfigurationRevision;
    }

    public void setAutoScalingConfigurationRevision(int autoScalingConfigurationRevision) {
        this.autoScalingConfigurationRevision = autoScalingConfigurationRevision;
    }

    public String getConfigurationId() {
        return configurationId;
    }

    public void setConfigurationId(String configurationId) {
        this.configurationId = configurationId;
    }

    public boolean isLatest() {
        return latest;
    }

    public void setLatest(boolean latest) {
        this.latest = latest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getMinSize() {
        return minSize;
    }

    public void setMinSize(int minSize) {
        this.minSize = minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isHasAssociatedService() {
        return hasAssociatedService;
    }

    public void setHasAssociatedService(boolean hasAssociatedService) {
        this.hasAssociatedService = hasAssociatedService;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(tags);
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public void putTags(Map<String, String> additional) {
        if (additional != null) {
            tags.putAll(additional);
        }
    }

    public void removeTags(Collection<String> tagKeys) {
        if (tagKeys != null) {
            tagKeys.forEach(tags::remove);
        }
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }
}
