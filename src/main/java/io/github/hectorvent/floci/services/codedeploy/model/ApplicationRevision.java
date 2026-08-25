package io.github.hectorvent.floci.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stored CodeDeploy application revision metadata (S3/GitHub/AppSpec). */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApplicationRevision {
    public ApplicationRevision() {}

    private Map<String, Object> revision;
    private String description;
    private Double registerTime;
    private Double firstUsedTime;
    private Double lastUsedTime;
    private List<String> deploymentGroups = new ArrayList<>();

    public Map<String, Object> getRevision() { return revision; }
    public void setRevision(Map<String, Object> revision) { this.revision = revision; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getRegisterTime() { return registerTime; }
    public void setRegisterTime(Double registerTime) { this.registerTime = registerTime; }

    public Double getFirstUsedTime() { return firstUsedTime; }
    public void setFirstUsedTime(Double firstUsedTime) { this.firstUsedTime = firstUsedTime; }

    public Double getLastUsedTime() { return lastUsedTime; }
    public void setLastUsedTime(Double lastUsedTime) { this.lastUsedTime = lastUsedTime; }

    public List<String> getDeploymentGroups() { return deploymentGroups; }
    public void setDeploymentGroups(List<String> deploymentGroups) {
        this.deploymentGroups = deploymentGroups != null ? deploymentGroups : new ArrayList<>();
    }
}
