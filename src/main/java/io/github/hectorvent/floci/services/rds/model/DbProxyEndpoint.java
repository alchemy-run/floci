package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbProxyEndpoint {

    private String dbProxyEndpointName;
    private String dbProxyEndpointArn;
    private String dbProxyName;
    private String status = "available";
    private String endpoint;
    private Instant createdDate;
    private String vpcId;
    private List<String> vpcSubnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private String targetRole = "READ_WRITE";
    private boolean defaultEndpoint;
    private String endpointNetworkType = "IPV4";
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbProxyEndpoint() {}

    public DbProxyEndpoint(DbProxyEndpoint source) {
        dbProxyEndpointName = source.dbProxyEndpointName;
        dbProxyEndpointArn = source.dbProxyEndpointArn;
        dbProxyName = source.dbProxyName;
        status = source.status;
        endpoint = source.endpoint;
        createdDate = source.createdDate;
        vpcId = source.vpcId;
        setVpcSubnetIds(source.vpcSubnetIds);
        setVpcSecurityGroupIds(source.vpcSecurityGroupIds);
        targetRole = source.targetRole;
        defaultEndpoint = source.defaultEndpoint;
        endpointNetworkType = source.endpointNetworkType;
        setTags(source.tags);
    }

    public String getDbProxyEndpointName() { return dbProxyEndpointName; }
    public void setDbProxyEndpointName(String name) { dbProxyEndpointName = name; }
    public String getDbProxyEndpointArn() { return dbProxyEndpointArn; }
    public void setDbProxyEndpointArn(String arn) { dbProxyEndpointArn = arn; }
    public String getDbProxyName() { return dbProxyName; }
    public void setDbProxyName(String name) { dbProxyName = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Instant getCreatedDate() { return createdDate; }
    public void setCreatedDate(Instant createdDate) { this.createdDate = createdDate; }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }
    public List<String> getVpcSubnetIds() { return vpcSubnetIds; }
    public void setVpcSubnetIds(List<String> ids) {
        vpcSubnetIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> ids) {
        vpcSecurityGroupIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }
    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    public boolean isDefault() { return defaultEndpoint; }
    public void setDefault(boolean defaultEndpoint) { this.defaultEndpoint = defaultEndpoint; }
    public String getEndpointNetworkType() { return endpointNetworkType; }
    public void setEndpointNetworkType(String type) { endpointNetworkType = type; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
