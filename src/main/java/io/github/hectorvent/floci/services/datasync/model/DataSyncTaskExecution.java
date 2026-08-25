package io.github.hectorvent.floci.services.datasync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DataSyncTaskExecution {

    private String taskExecutionArn;
    private String taskArn;
    private String status;
    private String taskMode;
    private long bytesTransferred;
    private Map<String, Object> options;
    private long startTime;
    private Long endTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DataSyncTaskExecution() {}

    public String getTaskExecutionArn() { return taskExecutionArn; }
    public void setTaskExecutionArn(String taskExecutionArn) { this.taskExecutionArn = taskExecutionArn; }

    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String taskArn) { this.taskArn = taskArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTaskMode() { return taskMode; }
    public void setTaskMode(String taskMode) { this.taskMode = taskMode; }

    public long getBytesTransferred() { return bytesTransferred; }
    public void setBytesTransferred(long bytesTransferred) { this.bytesTransferred = bytesTransferred; }

    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
