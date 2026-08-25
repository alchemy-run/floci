package io.github.hectorvent.floci.services.iotfleetwise.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A campaign deployment status for a vehicle. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CampaignStatus {

    private String campaignName;
    private String vehicleName;
    private String status;

    public CampaignStatus() {
    }

    public CampaignStatus(String campaignName, String vehicleName, String status) {
        this.campaignName = campaignName;
        this.vehicleName = vehicleName;
        this.status = status;
    }

    public String getCampaignName() {
        return campaignName;
    }

    public void setCampaignName(String campaignName) {
        this.campaignName = campaignName;
    }

    public String getVehicleName() {
        return vehicleName;
    }

    public void setVehicleName(String vehicleName) {
        this.vehicleName = vehicleName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
