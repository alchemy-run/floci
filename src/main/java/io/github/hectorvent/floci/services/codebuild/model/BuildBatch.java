package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BuildBatch {
    public BuildBatch() {}

    private String id;
    private String arn;
    private String projectName;
    private String buildBatchStatus;
    private Boolean complete;
    private Double startTime;
    private Double endTime;
    private String currentPhase;
    private Long buildBatchNumber;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getBuildBatchStatus() { return buildBatchStatus; }
    public void setBuildBatchStatus(String buildBatchStatus) { this.buildBatchStatus = buildBatchStatus; }

    public Boolean getComplete() { return complete; }
    public void setComplete(Boolean complete) { this.complete = complete; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double startTime) { this.startTime = startTime; }

    public Double getEndTime() { return endTime; }
    public void setEndTime(Double endTime) { this.endTime = endTime; }

    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }

    public Long getBuildBatchNumber() { return buildBatchNumber; }
    public void setBuildBatchNumber(Long buildBatchNumber) { this.buildBatchNumber = buildBatchNumber; }
}
