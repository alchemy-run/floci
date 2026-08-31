package io.github.hectorvent.floci.services.deadline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Deadline Cloud job plus nested steps and tasks. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadlineJob {

    private String farmId;
    private String queueId;
    private String jobId;
    private String name;
    private String description;
    private int priority;
    private String lifecycleStatus = "CREATE_COMPLETE";
    private String lifecycleStatusMessage = "Job created.";
    private String taskRunStatus = "READY";
    private String targetTaskRunStatus;
    private String createdAt;
    private String createdBy;
    private String updatedAt;
    private String updatedBy;
    private String clientToken;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<DeadlineStep> steps = new ArrayList<>();

    public DeadlineJob() {
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getQueueId() {
        return queueId;
    }

    public void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getLifecycleStatusMessage() {
        return lifecycleStatusMessage;
    }

    public void setLifecycleStatusMessage(String lifecycleStatusMessage) {
        this.lifecycleStatusMessage = lifecycleStatusMessage;
    }

    public String getTaskRunStatus() {
        return taskRunStatus;
    }

    public void setTaskRunStatus(String taskRunStatus) {
        this.taskRunStatus = taskRunStatus;
    }

    public String getTargetTaskRunStatus() {
        return targetTaskRunStatus;
    }

    public void setTargetTaskRunStatus(String targetTaskRunStatus) {
        this.targetTaskRunStatus = targetTaskRunStatus;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<DeadlineStep> getSteps() {
        return steps;
    }

    public void setSteps(List<DeadlineStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeadlineStep {
        private String stepId;
        private String name;
        private String lifecycleStatus = "CREATE_COMPLETE";
        private String lifecycleStatusMessage;
        private String taskRunStatus = "READY";
        private String targetTaskRunStatus;
        private String createdAt;
        private String createdBy;
        private String updatedAt;
        private String updatedBy;
        private List<DeadlineTask> tasks = new ArrayList<>();

        public DeadlineStep() {
        }

        public String getStepId() {
            return stepId;
        }

        public void setStepId(String stepId) {
            this.stepId = stepId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLifecycleStatus() {
            return lifecycleStatus;
        }

        public void setLifecycleStatus(String lifecycleStatus) {
            this.lifecycleStatus = lifecycleStatus;
        }

        public String getLifecycleStatusMessage() {
            return lifecycleStatusMessage;
        }

        public void setLifecycleStatusMessage(String lifecycleStatusMessage) {
            this.lifecycleStatusMessage = lifecycleStatusMessage;
        }

        public String getTaskRunStatus() {
            return taskRunStatus;
        }

        public void setTaskRunStatus(String taskRunStatus) {
            this.taskRunStatus = taskRunStatus;
        }

        public String getTargetTaskRunStatus() {
            return targetTaskRunStatus;
        }

        public void setTargetTaskRunStatus(String targetTaskRunStatus) {
            this.targetTaskRunStatus = targetTaskRunStatus;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }

        public List<DeadlineTask> getTasks() {
            return tasks;
        }

        public void setTasks(List<DeadlineTask> tasks) {
            this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeadlineTask {
        private String taskId;
        private String runStatus = "READY";
        private String targetRunStatus;
        private String createdAt;
        private String createdBy;
        private String updatedAt;
        private String updatedBy;

        public DeadlineTask() {
        }

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public String getRunStatus() {
            return runStatus;
        }

        public void setRunStatus(String runStatus) {
            this.runStatus = runStatus;
        }

        public String getTargetRunStatus() {
            return targetRunStatus;
        }

        public void setTargetRunStatus(String targetRunStatus) {
            this.targetRunStatus = targetRunStatus;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public void setUpdatedBy(String updatedBy) {
            this.updatedBy = updatedBy;
        }
    }
}
