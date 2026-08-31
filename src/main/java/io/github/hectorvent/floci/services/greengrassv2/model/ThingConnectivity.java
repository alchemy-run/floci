package io.github.hectorvent.floci.services.greengrassv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Connectivity info stored for an IoT thing (Greengrass Get/UpdateConnectivityInfo). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThingConnectivity {

    private String thingName;
    private String region;
    private int version;
    private List<Entry> connectivityInfo = new ArrayList<>();

    public ThingConnectivity() {
    }

    public String getThingName() {
        return thingName;
    }

    public void setThingName(String thingName) {
        this.thingName = thingName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<Entry> getConnectivityInfo() {
        return connectivityInfo;
    }

    public void setConnectivityInfo(List<Entry> connectivityInfo) {
        this.connectivityInfo = connectivityInfo == null ? new ArrayList<>() : new ArrayList<>(connectivityInfo);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private String id;
        private String hostAddress;
        private Integer portNumber;
        private String metadata;

        public Entry() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getHostAddress() {
            return hostAddress;
        }

        public void setHostAddress(String hostAddress) {
            this.hostAddress = hostAddress;
        }

        public Integer getPortNumber() {
            return portNumber;
        }

        public void setPortNumber(Integer portNumber) {
            this.portNumber = portNumber;
        }

        public String getMetadata() {
            return metadata;
        }

        public void setMetadata(String metadata) {
            this.metadata = metadata;
        }
    }
}
