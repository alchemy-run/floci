package io.github.hectorvent.floci.services.codeconnections.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class CodeConnectionsVpcConfiguration {

    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private String tlsCertificate;

    public CodeConnectionsVpcConfiguration() {
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? subnetIds : new ArrayList<>();
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? securityGroupIds : new ArrayList<>();
    }

    public String getTlsCertificate() {
        return tlsCertificate;
    }

    public void setTlsCertificate(String tlsCertificate) {
        this.tlsCertificate = tlsCertificate;
    }
}
