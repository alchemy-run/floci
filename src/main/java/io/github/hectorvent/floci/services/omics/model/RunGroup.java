package io.github.hectorvent.floci.services.omics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon HealthOmics run group. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunGroup {

    private String id;
    private String arn;
    private String name;
    private Integer maxCpus;
    private Integer maxRuns;
    private Integer maxDuration;
    private Integer maxGpus;
    private String creationTime;
    private String region;
    private String requestId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public RunGroup() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMaxCpus() {
        return maxCpus;
    }

    public void setMaxCpus(Integer maxCpus) {
        this.maxCpus = maxCpus;
    }

    public Integer getMaxRuns() {
        return maxRuns;
    }

    public void setMaxRuns(Integer maxRuns) {
        this.maxRuns = maxRuns;
    }

    public Integer getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Integer maxDuration) {
        this.maxDuration = maxDuration;
    }

    public Integer getMaxGpus() {
        return maxGpus;
    }

    public void setMaxGpus(Integer maxGpus) {
        this.maxGpus = maxGpus;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
