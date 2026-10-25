package io.github.hectorvent.floci.services.opensearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterConfig {

    @JsonProperty("InstanceType")
    private String instanceType = "m5.large.search";

    @JsonProperty("InstanceCount")
    private Integer instanceCount = 1;

    @JsonProperty("DedicatedMasterEnabled")
    private Boolean dedicatedMasterEnabled = false;

    @JsonProperty("ZoneAwarenessEnabled")
    private Boolean zoneAwarenessEnabled = false;

    public ClusterConfig() {}

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public Integer getInstanceCount() {
        return instanceCount;
    }

    public void setInstanceCount(Integer instanceCount) {
        this.instanceCount = instanceCount;
    }

    public Boolean isDedicatedMasterEnabled() {
        return dedicatedMasterEnabled;
    }

    public void setDedicatedMasterEnabled(Boolean dedicatedMasterEnabled) {
        this.dedicatedMasterEnabled = dedicatedMasterEnabled;
    }

    public Boolean isZoneAwarenessEnabled() {
        return zoneAwarenessEnabled;
    }

    public void setZoneAwarenessEnabled(Boolean zoneAwarenessEnabled) {
        this.zoneAwarenessEnabled = zoneAwarenessEnabled;
    }
}
