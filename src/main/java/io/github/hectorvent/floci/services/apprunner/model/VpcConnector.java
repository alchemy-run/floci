package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An App Runner VPC connector revision. Wire names are PascalCase on the JSON 1.0 API.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VpcConnector {

    private String vpcConnectorName;
    private String vpcConnectorArn;
    private int vpcConnectorRevision;
    private List<String> subnets = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private String status;
    private long createdAt;
    private Long deletedAt;
    @JsonIgnore
    private String region;
    @JsonIgnore
    private Map<String, String> tags = new LinkedHashMap<>();

    public VpcConnector() {
    }

    public String getVpcConnectorName() {
        return vpcConnectorName;
    }

    public void setVpcConnectorName(String vpcConnectorName) {
        this.vpcConnectorName = vpcConnectorName;
    }

    public String getVpcConnectorArn() {
        return vpcConnectorArn;
    }

    public void setVpcConnectorArn(String vpcConnectorArn) {
        this.vpcConnectorArn = vpcConnectorArn;
    }

    public int getVpcConnectorRevision() {
        return vpcConnectorRevision;
    }

    public void setVpcConnectorRevision(int vpcConnectorRevision) {
        this.vpcConnectorRevision = vpcConnectorRevision;
    }

    public List<String> getSubnets() {
        return subnets;
    }

    public void setSubnets(List<String> subnets) {
        this.subnets = subnets == null ? new ArrayList<>() : new ArrayList<>(subnets);
    }

    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups == null ? new ArrayList<>() : new ArrayList<>(securityGroups);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public boolean isActive() {
        return status != null && "ACTIVE".equalsIgnoreCase(status);
    }
}
