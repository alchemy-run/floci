package io.github.hectorvent.floci.services.osis.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A VPC endpoint for an OpenSearch Ingestion pipeline. */
@RegisterForReflection
public class OsisPipelineEndpoint {
    private String pipelineArn;
    private String endpointId;
    private String status;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private String ingestEndpointUrl;

    public OsisPipelineEndpoint() {
    }

    public String getPipelineArn() {
        return pipelineArn;
    }

    public void setPipelineArn(String pipelineArn) {
        this.pipelineArn = pipelineArn;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(String endpointId) {
        this.endpointId = endpointId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        this.subnetIds = subnetIds == null ? new ArrayList<>() : new ArrayList<>(subnetIds);
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds == null ? new ArrayList<>() : new ArrayList<>(securityGroupIds);
    }

    public String getIngestEndpointUrl() {
        return ingestEndpointUrl;
    }

    public void setIngestEndpointUrl(String ingestEndpointUrl) {
        this.ingestEndpointUrl = ingestEndpointUrl;
    }
}
