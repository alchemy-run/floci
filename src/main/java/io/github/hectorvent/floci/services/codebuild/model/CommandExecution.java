package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandExecution {
    public CommandExecution() {}

    private String id;
    private String sandboxId;
    private String sandboxArn;
    private String status;
    private String command;
    private String type;
    private Double submitTime;
    private Double startTime;
    private Double endTime;
    private String exitCode;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSandboxId() { return sandboxId; }
    public void setSandboxId(String sandboxId) { this.sandboxId = sandboxId; }

    public String getSandboxArn() { return sandboxArn; }
    public void setSandboxArn(String sandboxArn) { this.sandboxArn = sandboxArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Double getSubmitTime() { return submitTime; }
    public void setSubmitTime(Double submitTime) { this.submitTime = submitTime; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double startTime) { this.startTime = startTime; }

    public Double getEndTime() { return endTime; }
    public void setEndTime(Double endTime) { this.endTime = endTime; }

    public String getExitCode() { return exitCode; }
    public void setExitCode(String exitCode) { this.exitCode = exitCode; }
}
