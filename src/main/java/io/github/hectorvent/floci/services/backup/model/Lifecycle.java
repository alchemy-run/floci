package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Lifecycle {

    @JsonProperty("MoveToColdStorageAfterDays")
    private Long moveToColdStorageAfterDays;

    @JsonProperty("DeleteAfterDays")
    private Long deleteAfterDays;

    @JsonProperty("OptInToArchiveForSupportedResources")
    private Boolean optInToArchiveForSupportedResources;

    public Lifecycle() {}

    public Long getMoveToColdStorageAfterDays() { return moveToColdStorageAfterDays; }
    public void setMoveToColdStorageAfterDays(Long moveToColdStorageAfterDays) { this.moveToColdStorageAfterDays = moveToColdStorageAfterDays; }

    public Long getDeleteAfterDays() { return deleteAfterDays; }
    public void setDeleteAfterDays(Long deleteAfterDays) { this.deleteAfterDays = deleteAfterDays; }

    public Boolean getOptInToArchiveForSupportedResources() { return optInToArchiveForSupportedResources; }
    public void setOptInToArchiveForSupportedResources(Boolean optInToArchiveForSupportedResources) {
        this.optInToArchiveForSupportedResources = optInToArchiveForSupportedResources;
    }
}
