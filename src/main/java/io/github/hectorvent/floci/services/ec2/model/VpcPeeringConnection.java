package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcPeeringConnection {

    private String vpcPeeringConnectionId;
    private String status = "pending-acceptance";
    private String requesterVpcId;
    private String requesterOwnerId;
    private String requesterCidrBlock;
    private String requesterRegion;
    private String accepterVpcId;
    private String accepterOwnerId;
    private String accepterCidrBlock;
    private String accepterRegion;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public VpcPeeringConnection() {}

    public String getVpcPeeringConnectionId() { return vpcPeeringConnectionId; }
    public void setVpcPeeringConnectionId(String vpcPeeringConnectionId) { this.vpcPeeringConnectionId = vpcPeeringConnectionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRequesterVpcId() { return requesterVpcId; }
    public void setRequesterVpcId(String requesterVpcId) { this.requesterVpcId = requesterVpcId; }

    public String getRequesterOwnerId() { return requesterOwnerId; }
    public void setRequesterOwnerId(String requesterOwnerId) { this.requesterOwnerId = requesterOwnerId; }

    public String getRequesterCidrBlock() { return requesterCidrBlock; }
    public void setRequesterCidrBlock(String requesterCidrBlock) { this.requesterCidrBlock = requesterCidrBlock; }

    public String getRequesterRegion() { return requesterRegion; }
    public void setRequesterRegion(String requesterRegion) { this.requesterRegion = requesterRegion; }

    public String getAccepterVpcId() { return accepterVpcId; }
    public void setAccepterVpcId(String accepterVpcId) { this.accepterVpcId = accepterVpcId; }

    public String getAccepterOwnerId() { return accepterOwnerId; }
    public void setAccepterOwnerId(String accepterOwnerId) { this.accepterOwnerId = accepterOwnerId; }

    public String getAccepterCidrBlock() { return accepterCidrBlock; }
    public void setAccepterCidrBlock(String accepterCidrBlock) { this.accepterCidrBlock = accepterCidrBlock; }

    public String getAccepterRegion() { return accepterRegion; }
    public void setAccepterRegion(String accepterRegion) { this.accepterRegion = accepterRegion; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
