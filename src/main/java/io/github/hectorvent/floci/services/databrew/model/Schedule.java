package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Glue DataBrew job schedule. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Schedule {

    private String name;
    private String resourceArn;
    private String region;
    private String cronExpression;
    private List<String> jobNames = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private long createDate;
    private long lastModifiedDate;

    public Schedule() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public List<String> getJobNames() {
        if (jobNames == null) {
            jobNames = new ArrayList<>();
        }
        return jobNames;
    }

    public void setJobNames(List<String> jobNames) {
        this.jobNames = jobNames == null ? new ArrayList<>() : new ArrayList<>(jobNames);
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

    public long getCreateDate() {
        return createDate;
    }

    public void setCreateDate(long createDate) {
        this.createDate = createDate;
    }

    public long getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(long lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }
}
