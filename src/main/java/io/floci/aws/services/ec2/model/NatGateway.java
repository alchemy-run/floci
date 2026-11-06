package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGateway {

    private String natGatewayId;
    private String subnetId;
    private String vpcId;
    private String allocationId;
    private String state = "available";
    private String connectivityType = "public";
    private String failureCode;
    private String failureMessage;
    private Instant createTime;
    private String region;
    private String availabilityMode;  // null or "zonal" for a gateway in one subnet, "regional" for one spanning its VPC
    private String autoProvisionZones; // regional only: "enabled" when AWS picks the zones, "disabled" when the request named them
    private List<NatGatewayAddress> natGatewayAddresses = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public NatGateway() {}

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public String getConnectivityType() { return connectivityType; }
    public void setConnectivityType(String connectivityType) { this.connectivityType = connectivityType; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<NatGatewayAddress> getNatGatewayAddresses() { return natGatewayAddresses; }
    public void setNatGatewayAddresses(List<NatGatewayAddress> natGatewayAddresses) { this.natGatewayAddresses = natGatewayAddresses; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public String getAvailabilityMode() { return availabilityMode; }
    public void setAvailabilityMode(String availabilityMode) { this.availabilityMode = availabilityMode; }

    public String getAutoProvisionZones() { return autoProvisionZones; }
    public void setAutoProvisionZones(String autoProvisionZones) { this.autoProvisionZones = autoProvisionZones; }

    /** Whether the gateway spans its VPC rather than sitting in one subnet. */
    public boolean isRegional() { return "regional".equals(availabilityMode); }
}
