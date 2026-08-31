package io.github.hectorvent.floci.services.datasync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DataSyncTask {

    private String taskArn;
    private String name;
    private String status;
    private String sourceLocationArn;
    private String destinationLocationArn;
    private String cloudWatchLogGroupArn;
    private String taskMode;
    private String currentTaskExecutionArn;
    private Map<String, Object> options;
    private List<Map<String, Object>> excludes = new ArrayList<>();
    private List<Map<String, Object>> includes = new ArrayList<>();
    private Map<String, Object> schedule;
    private long creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DataSyncTask() {}

    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String taskArn) { this.taskArn = taskArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSourceLocationArn() { return sourceLocationArn; }
    public void setSourceLocationArn(String sourceLocationArn) { this.sourceLocationArn = sourceLocationArn; }

    public String getDestinationLocationArn() { return destinationLocationArn; }
    public void setDestinationLocationArn(String destinationLocationArn) {
        this.destinationLocationArn = destinationLocationArn;
    }

    public String getCloudWatchLogGroupArn() { return cloudWatchLogGroupArn; }
    public void setCloudWatchLogGroupArn(String cloudWatchLogGroupArn) {
        this.cloudWatchLogGroupArn = cloudWatchLogGroupArn;
    }

    public String getTaskMode() { return taskMode; }
    public void setTaskMode(String taskMode) { this.taskMode = taskMode; }

    public String getCurrentTaskExecutionArn() { return currentTaskExecutionArn; }
    public void setCurrentTaskExecutionArn(String currentTaskExecutionArn) {
        this.currentTaskExecutionArn = currentTaskExecutionArn;
    }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public List<Map<String, Object>> getExcludes() { return excludes; }
    public void setExcludes(List<Map<String, Object>> excludes) {
        this.excludes = excludes != null ? excludes : new ArrayList<>();
    }

    public List<Map<String, Object>> getIncludes() { return includes; }
    public void setIncludes(List<Map<String, Object>> includes) {
        this.includes = includes != null ? includes : new ArrayList<>();
    }

    public Map<String, Object> getSchedule() { return schedule; }
    public void setSchedule(Map<String, Object> schedule) { this.schedule = schedule; }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
