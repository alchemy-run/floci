package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A VPC Lattice service-network/VPC association. Wire names are camelCase restJson1. */
@RegisterForReflection
public class ServiceNetworkVpcAssociation {

    private String id;
    private String arn;
    private String region;
    private String status = "ACTIVE";
    private String createdBy;
    private String createdAt;
    private String lastUpdatedAt;
    private String serviceNetworkId;
    private String serviceNetworkName;
    private String serviceNetworkArn;
    private String vpcId;
    private String clientToken;
    private List<String> securityGroupIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServiceNetworkVpcAssociation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getServiceNetworkId() {
        return serviceNetworkId;
    }

    public void setServiceNetworkId(String serviceNetworkId) {
        this.serviceNetworkId = serviceNetworkId;
    }

    public String getServiceNetworkName() {
        return serviceNetworkName;
    }

    public void setServiceNetworkName(String serviceNetworkName) {
        this.serviceNetworkName = serviceNetworkName;
    }

    public String getServiceNetworkArn() {
        return serviceNetworkArn;
    }

    public void setServiceNetworkArn(String serviceNetworkArn) {
        this.serviceNetworkArn = serviceNetworkArn;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds == null ? new ArrayList<>() : new ArrayList<>(securityGroupIds);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
