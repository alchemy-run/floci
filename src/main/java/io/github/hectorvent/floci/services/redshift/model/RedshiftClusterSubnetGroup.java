package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedshiftClusterSubnetGroup {

    private String clusterSubnetGroupName;
    private String description;
    private String vpcId;
    private String subnetGroupStatus = "Complete";
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public RedshiftClusterSubnetGroup() {}

    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) {
        this.clusterSubnetGroupName = clusterSubnetGroupName;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSubnetGroupStatus() { return subnetGroupStatus; }
    public void setSubnetGroupStatus(String subnetGroupStatus) {
        this.subnetGroupStatus = subnetGroupStatus;
    }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public Map<String, String> getSubnetAvailabilityZones() { return subnetAvailabilityZones; }
    public void setSubnetAvailabilityZones(Map<String, String> subnetAvailabilityZones) {
        this.subnetAvailabilityZones = subnetAvailabilityZones != null
                ? new LinkedHashMap<>(subnetAvailabilityZones) : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
