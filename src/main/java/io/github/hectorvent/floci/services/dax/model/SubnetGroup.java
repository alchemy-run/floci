package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubnetGroup {

    private String subnetGroupName;
    private String description;
    private String vpcId;
    private List<DaxSubnet> subnets = new ArrayList<>();

    public SubnetGroup() {}

    public String getSubnetGroupName() { return subnetGroupName; }
    public void setSubnetGroupName(String subnetGroupName) { this.subnetGroupName = subnetGroupName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public List<DaxSubnet> getSubnets() { return subnets; }
    public void setSubnets(List<DaxSubnet> subnets) {
        this.subnets = subnets != null ? subnets : new ArrayList<>();
    }
}
