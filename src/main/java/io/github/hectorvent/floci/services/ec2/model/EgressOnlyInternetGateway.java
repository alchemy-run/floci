package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EgressOnlyInternetGateway {

    private String egressOnlyInternetGatewayId;
    private String vpcId;
    private String attachmentState = "attached";
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public EgressOnlyInternetGateway() {}

    public String getEgressOnlyInternetGatewayId() { return egressOnlyInternetGatewayId; }
    public void setEgressOnlyInternetGatewayId(String egressOnlyInternetGatewayId) {
        this.egressOnlyInternetGatewayId = egressOnlyInternetGatewayId;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAttachmentState() { return attachmentState; }
    public void setAttachmentState(String attachmentState) { this.attachmentState = attachmentState; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
