package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbClusterEndpoint {

    private String dbClusterEndpointIdentifier;
    private String dbClusterEndpointArn;
    private String dbClusterIdentifier;
    private String endpoint;
    private String status = "available";
    private String endpointType = "CUSTOM";
    private String customEndpointType;
    private List<String> staticMembers = new ArrayList<>();
    private List<String> excludedMembers = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbClusterEndpoint() {}

    public String getDbClusterEndpointIdentifier() { return dbClusterEndpointIdentifier; }
    public void setDbClusterEndpointIdentifier(String dbClusterEndpointIdentifier) {
        this.dbClusterEndpointIdentifier = dbClusterEndpointIdentifier;
    }

    public String getDbClusterEndpointArn() { return dbClusterEndpointArn; }
    public void setDbClusterEndpointArn(String dbClusterEndpointArn) {
        this.dbClusterEndpointArn = dbClusterEndpointArn;
    }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) {
        this.dbClusterIdentifier = dbClusterIdentifier;
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEndpointType() { return endpointType; }
    public void setEndpointType(String endpointType) { this.endpointType = endpointType; }

    public String getCustomEndpointType() { return customEndpointType; }
    public void setCustomEndpointType(String customEndpointType) {
        this.customEndpointType = customEndpointType;
    }

    public List<String> getStaticMembers() { return staticMembers; }
    public void setStaticMembers(List<String> staticMembers) {
        this.staticMembers = staticMembers != null ? staticMembers : new ArrayList<>();
    }

    public List<String> getExcludedMembers() { return excludedMembers; }
    public void setExcludedMembers(List<String> excludedMembers) {
        this.excludedMembers = excludedMembers != null ? excludedMembers : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
