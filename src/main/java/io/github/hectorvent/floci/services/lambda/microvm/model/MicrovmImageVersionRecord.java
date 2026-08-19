package io.github.hectorvent.floci.services.lambda.microvm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * One immutable version of a MicroVM image: the configuration snapshot the
 * version was created with, plus its build outcome. States mirror the
 * distilled {@code MicrovmImageVersionState} / {@code BuildState} enums.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MicrovmImageVersionRecord {

    private String imageVersion;
    /** PENDING | IN_PROGRESS | SUCCESSFUL | FAILED | DELETING | DELETED | DELETE_FAILED */
    private String state = "PENDING";
    /** ACTIVE | INACTIVE */
    private String status = "INACTIVE";
    private String stateReason;
    private long createdAt;
    private Long updatedAt;

    /** Echo of the create/update request members (baseImageArn, codeArtifact, hooks, ...). */
    private Map<String, Object> config = new HashMap<>();

    /** The local Docker image tag this version built to (null until SUCCESSFUL). */
    private String dockerImageTag;

    // ── build record (one build per version; Floci builds for the host arch only) ──
    private String buildId;
    /** PENDING | IN_PROGRESS | SUCCESSFUL | FAILED */
    private String buildState = "PENDING";
    private String buildStateReason;
    private String architecture;
    private String chipset = "GRAVITON";
    private String chipsetGeneration = "4";

    public String getImageVersion() { return imageVersion; }
    public void setImageVersion(String imageVersion) { this.imageVersion = imageVersion; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public String getDockerImageTag() { return dockerImageTag; }
    public void setDockerImageTag(String dockerImageTag) { this.dockerImageTag = dockerImageTag; }

    public String getBuildId() { return buildId; }
    public void setBuildId(String buildId) { this.buildId = buildId; }

    public String getBuildState() { return buildState; }
    public void setBuildState(String buildState) { this.buildState = buildState; }

    public String getBuildStateReason() { return buildStateReason; }
    public void setBuildStateReason(String buildStateReason) { this.buildStateReason = buildStateReason; }

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    public String getChipset() { return chipset; }
    public void setChipset(String chipset) { this.chipset = chipset; }

    public String getChipsetGeneration() { return chipsetGeneration; }
    public void setChipsetGeneration(String chipsetGeneration) { this.chipsetGeneration = chipsetGeneration; }
}
