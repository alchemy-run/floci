package io.github.hectorvent.floci.services.synthetics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch Synthetics canary. Timeline fields are epoch seconds. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Canary {

    private String id;
    private String name;
    private String handler;
    private String executionRoleArn;
    private String runtimeVersion;
    private String artifactS3Location;
    private String scheduleExpression;
    private Long scheduleDurationInSeconds;
    private Integer timeoutInSeconds;
    private Integer memoryInMB;
    private Boolean activeTracing;
    private Integer ephemeralStorage;
    private Integer successRetentionPeriodInDays;
    private Integer failureRetentionPeriodInDays;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private String provisionedResourceCleanup;
    private String state;
    private String stateReason;
    private String stateReasonCode;
    private Long created;
    private Long lastModified;
    private Long lastStarted;
    private Long lastStopped;
    private String engineArn;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<CanaryRun> runs = new ArrayList<>();

    public Canary() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public void setRuntimeVersion(String runtimeVersion) {
        this.runtimeVersion = runtimeVersion;
    }

    public String getArtifactS3Location() {
        return artifactS3Location;
    }

    public void setArtifactS3Location(String artifactS3Location) {
        this.artifactS3Location = artifactS3Location;
    }

    public String getScheduleExpression() {
        return scheduleExpression;
    }

    public void setScheduleExpression(String scheduleExpression) {
        this.scheduleExpression = scheduleExpression;
    }

    public Long getScheduleDurationInSeconds() {
        return scheduleDurationInSeconds;
    }

    public void setScheduleDurationInSeconds(Long scheduleDurationInSeconds) {
        this.scheduleDurationInSeconds = scheduleDurationInSeconds;
    }

    public Integer getTimeoutInSeconds() {
        return timeoutInSeconds;
    }

    public void setTimeoutInSeconds(Integer timeoutInSeconds) {
        this.timeoutInSeconds = timeoutInSeconds;
    }

    public Integer getMemoryInMB() {
        return memoryInMB;
    }

    public void setMemoryInMB(Integer memoryInMB) {
        this.memoryInMB = memoryInMB;
    }

    public Boolean getActiveTracing() {
        return activeTracing;
    }

    public void setActiveTracing(Boolean activeTracing) {
        this.activeTracing = activeTracing;
    }

    public Integer getEphemeralStorage() {
        return ephemeralStorage;
    }

    public void setEphemeralStorage(Integer ephemeralStorage) {
        this.ephemeralStorage = ephemeralStorage;
    }

    public Integer getSuccessRetentionPeriodInDays() {
        return successRetentionPeriodInDays;
    }

    public void setSuccessRetentionPeriodInDays(Integer successRetentionPeriodInDays) {
        this.successRetentionPeriodInDays = successRetentionPeriodInDays;
    }

    public Integer getFailureRetentionPeriodInDays() {
        return failureRetentionPeriodInDays;
    }

    public void setFailureRetentionPeriodInDays(Integer failureRetentionPeriodInDays) {
        this.failureRetentionPeriodInDays = failureRetentionPeriodInDays;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds == null ? new ArrayList<>() : new ArrayList<>(subnetIds);
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds == null ? new ArrayList<>() : new ArrayList<>(securityGroupIds);
    }

    public String getProvisionedResourceCleanup() {
        return provisionedResourceCleanup;
    }

    public void setProvisionedResourceCleanup(String provisionedResourceCleanup) {
        this.provisionedResourceCleanup = provisionedResourceCleanup;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }

    public String getStateReasonCode() {
        return stateReasonCode;
    }

    public void setStateReasonCode(String stateReasonCode) {
        this.stateReasonCode = stateReasonCode;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public Long getLastModified() {
        return lastModified;
    }

    public void setLastModified(Long lastModified) {
        this.lastModified = lastModified;
    }

    public Long getLastStarted() {
        return lastStarted;
    }

    public void setLastStarted(Long lastStarted) {
        this.lastStarted = lastStarted;
    }

    public Long getLastStopped() {
        return lastStopped;
    }

    public void setLastStopped(Long lastStopped) {
        this.lastStopped = lastStopped;
    }

    public String getEngineArn() {
        return engineArn;
    }

    public void setEngineArn(String engineArn) {
        this.engineArn = engineArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<CanaryRun> getRuns() {
        return runs;
    }

    public void setRuns(List<CanaryRun> runs) {
        this.runs = runs == null ? new ArrayList<>() : new ArrayList<>(runs);
    }
}
