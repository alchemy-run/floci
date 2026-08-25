package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRun {

    private String runId;
    private String jobName;
    private String state;
    private String datasetName;
    private JsonNode recipeReference;
    private JsonNode outputs;
    private JsonNode jobSample;
    private int attempt;
    private long startedOn;
    private Long completedOn;

    public JobRun() {
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getDatasetName() {
        return datasetName;
    }

    public void setDatasetName(String datasetName) {
        this.datasetName = datasetName;
    }

    public JsonNode getRecipeReference() {
        return recipeReference;
    }

    public void setRecipeReference(JsonNode recipeReference) {
        this.recipeReference = recipeReference;
    }

    public JsonNode getOutputs() {
        return outputs;
    }

    public void setOutputs(JsonNode outputs) {
        this.outputs = outputs;
    }

    public JsonNode getJobSample() {
        return jobSample;
    }

    public void setJobSample(JsonNode jobSample) {
        this.jobSample = jobSample;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public long getStartedOn() {
        return startedOn;
    }

    public void setStartedOn(long startedOn) {
        this.startedOn = startedOn;
    }

    public Long getCompletedOn() {
        return completedOn;
    }

    public void setCompletedOn(Long completedOn) {
        this.completedOn = completedOn;
    }
}
