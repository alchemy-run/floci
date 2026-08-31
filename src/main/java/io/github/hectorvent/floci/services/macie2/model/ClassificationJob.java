package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Macie classification job. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClassificationJob {

    private String jobId;
    private String jobArn;
    private String name;
    private String jobType;
    private String jobStatus;
    private String description;
    private Integer samplingPercentage;
    private Boolean initialRun;
    private String managedDataIdentifierSelector;
    private String createdAt;
    private Map<String, Object> s3JobDefinition = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ClassificationJob() {
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobArn() {
        return jobArn;
    }

    public void setJobArn(String jobArn) {
        this.jobArn = jobArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(String jobStatus) {
        this.jobStatus = jobStatus;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSamplingPercentage() {
        return samplingPercentage;
    }

    public void setSamplingPercentage(Integer samplingPercentage) {
        this.samplingPercentage = samplingPercentage;
    }

    public Boolean getInitialRun() {
        return initialRun;
    }

    public void setInitialRun(Boolean initialRun) {
        this.initialRun = initialRun;
    }

    public String getManagedDataIdentifierSelector() {
        return managedDataIdentifierSelector;
    }

    public void setManagedDataIdentifierSelector(String managedDataIdentifierSelector) {
        this.managedDataIdentifierSelector = managedDataIdentifierSelector;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getS3JobDefinition() {
        if (s3JobDefinition == null) {
            s3JobDefinition = new LinkedHashMap<>();
        }
        return s3JobDefinition;
    }

    public void setS3JobDefinition(Map<String, Object> s3JobDefinition) {
        this.s3JobDefinition = s3JobDefinition == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s3JobDefinition);
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
