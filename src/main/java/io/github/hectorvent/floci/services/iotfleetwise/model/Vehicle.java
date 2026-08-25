package io.github.hectorvent.floci.services.iotfleetwise.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An IoT FleetWise vehicle. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Vehicle {

    private String vehicleName;
    private String arn;
    private String modelManifestArn;
    private String decoderManifestArn;
    private Map<String, String> attributes;
    private String region;
    private long creationTime;
    private long lastModificationTime;
    private List<CampaignStatus> campaigns;
    private List<String> fleetIds;

    public Vehicle() {
    }

    public String getVehicleName() {
        return vehicleName;
    }

    public void setVehicleName(String vehicleName) {
        this.vehicleName = vehicleName;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getModelManifestArn() {
        return modelManifestArn;
    }

    public void setModelManifestArn(String modelManifestArn) {
        this.modelManifestArn = modelManifestArn;
    }

    public String getDecoderManifestArn() {
        return decoderManifestArn;
    }

    public void setDecoderManifestArn(String decoderManifestArn) {
        this.decoderManifestArn = decoderManifestArn;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? null : new LinkedHashMap<>(attributes);
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getLastModificationTime() {
        return lastModificationTime;
    }

    public void setLastModificationTime(long lastModificationTime) {
        this.lastModificationTime = lastModificationTime;
    }

    public List<CampaignStatus> getCampaigns() {
        return campaigns;
    }

    public void setCampaigns(List<CampaignStatus> campaigns) {
        this.campaigns = campaigns == null ? null : new ArrayList<>(campaigns);
    }

    public List<String> getFleetIds() {
        return fleetIds;
    }

    public void setFleetIds(List<String> fleetIds) {
        this.fleetIds = fleetIds == null ? null : new ArrayList<>(fleetIds);
    }
}
