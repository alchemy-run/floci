package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stored DMS replication subnet group. The AWS describe shape does not
 * include the ARN; tags are returned only via the tagging APIs.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsReplicationSubnetGroup {

    private String identifier;
    private String description;
    private String vpcId;
    private String status = "Complete";
    private String arn;
    private List<DmsSubnetMembership> subnets = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DmsReplicationSubnetGroup() {
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "Complete";
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public List<DmsSubnetMembership> getSubnets() {
        return subnets;
    }

    public void setSubnets(List<DmsSubnetMembership> subnets) {
        this.subnets = subnets != null ? subnets : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
