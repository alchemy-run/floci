package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A single step inside a workflow execution. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowStep {

    private String stepExecutionId;
    private String workflowExecutionId;
    private String imageBuildVersionArn;
    private String workflowBuildVersionArn;
    private String name;
    private String action = "ExecuteBash";
    private String status = "RUNNING";
    private String startTime;
    private String endTime;

    public WorkflowStep() {
    }

    public String getStepExecutionId() {
        return stepExecutionId;
    }

    public void setStepExecutionId(String stepExecutionId) {
        this.stepExecutionId = stepExecutionId;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public String getImageBuildVersionArn() {
        return imageBuildVersionArn;
    }

    public void setImageBuildVersionArn(String imageBuildVersionArn) {
        this.imageBuildVersionArn = imageBuildVersionArn;
    }

    public String getWorkflowBuildVersionArn() {
        return workflowBuildVersionArn;
    }

    public void setWorkflowBuildVersionArn(String workflowBuildVersionArn) {
        this.workflowBuildVersionArn = workflowBuildVersionArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
}
