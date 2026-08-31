package io.github.hectorvent.floci.services.synthetics.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A CloudWatch Synthetics canary run. Timeline fields are epoch seconds. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CanaryRun {

    private String id;
    private String name;
    private String state;
    private String testResult;
    private Long started;
    private Long completed;
    private String artifactS3Location;

    public CanaryRun() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getTestResult() {
        return testResult;
    }

    public void setTestResult(String testResult) {
        this.testResult = testResult;
    }

    public Long getStarted() {
        return started;
    }

    public void setStarted(Long started) {
        this.started = started;
    }

    public Long getCompleted() {
        return completed;
    }

    public void setCompleted(Long completed) {
        this.completed = completed;
    }

    public String getArtifactS3Location() {
        return artifactS3Location;
    }

    public void setArtifactS3Location(String artifactS3Location) {
        this.artifactS3Location = artifactS3Location;
    }
}
