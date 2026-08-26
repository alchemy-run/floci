package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A workflow execution belonging to an image build. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowRun {

    private String workflowExecutionId;
    private String workflowBuildVersionArn;
    private String imageBuildVersionArn;
    private String type = "BUILD";
    private String status = "RUNNING";
    private String message;
    private String startTime;
    private String endTime;
    private List<WorkflowStep> steps = new ArrayList<>();

    public WorkflowRun() {
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public String getWorkflowBuildVersionArn() {
        return workflowBuildVersionArn;
    }

    public void setWorkflowBuildVersionArn(String workflowBuildVersionArn) {
        this.workflowBuildVersionArn = workflowBuildVersionArn;
    }

    public String getImageBuildVersionArn() {
        return imageBuildVersionArn;
    }

    public void setImageBuildVersionArn(String imageBuildVersionArn) {
        this.imageBuildVersionArn = imageBuildVersionArn;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public List<WorkflowStep> getSteps() {
        return steps;
    }

    public void setSteps(List<WorkflowStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }
}
