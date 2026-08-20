package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRun {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("JobName")
    private String jobName;
    @JsonProperty("JobRunState")
    private String jobRunState;
    @JsonProperty("StartedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startedOn;
    @JsonProperty("CompletedOn")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant completedOn;
    @JsonProperty("Arguments")
    private Map<String, String> arguments;
    @JsonProperty("ErrorMessage")
    private String errorMessage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public String getJobRunState() { return jobRunState; }
    public void setJobRunState(String jobRunState) { this.jobRunState = jobRunState; }
    public Instant getStartedOn() { return startedOn; }
    public void setStartedOn(Instant startedOn) { this.startedOn = startedOn; }
    public Instant getCompletedOn() { return completedOn; }
    public void setCompletedOn(Instant completedOn) { this.completedOn = completedOn; }
    public Map<String, String> getArguments() { return arguments; }
    public void setArguments(Map<String, String> arguments) { this.arguments = arguments; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
