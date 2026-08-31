package io.github.hectorvent.floci.services.greengrassv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A client device associated with a Greengrass core device. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientAssociation {

    private String coreDeviceThingName;
    private String thingName;
    private long associationTimestamp;
    private String region;

    public ClientAssociation() {
    }

    public String getCoreDeviceThingName() {
        return coreDeviceThingName;
    }

    public void setCoreDeviceThingName(String coreDeviceThingName) {
        this.coreDeviceThingName = coreDeviceThingName;
    }

    public String getThingName() {
        return thingName;
    }

    public void setThingName(String thingName) {
        this.thingName = thingName;
    }

    public long getAssociationTimestamp() {
        return associationTimestamp;
    }

    public void setAssociationTimestamp(long associationTimestamp) {
        this.associationTimestamp = associationTimestamp;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
