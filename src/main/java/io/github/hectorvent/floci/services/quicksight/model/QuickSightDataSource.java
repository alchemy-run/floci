package io.github.hectorvent.floci.services.quicksight.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class QuickSightDataSource {

    private String dataSourceId;
    private String name;
    private String type;
    private String arn;
    private String region;
    private String accountId;
    private String status;
    private long createdTime;
    private long lastUpdatedTime;
    private JsonNode dataSourceParameters;
    private JsonNode credentials;
    private JsonNode vpcConnectionProperties;
    private JsonNode sslProperties;
    private JsonNode permissions;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public JsonNode getDataSourceParameters() {
        return dataSourceParameters;
    }

    public void setDataSourceParameters(JsonNode dataSourceParameters) {
        this.dataSourceParameters = dataSourceParameters == null ? null : dataSourceParameters.deepCopy();
    }

    public JsonNode getCredentials() {
        return credentials;
    }

    public void setCredentials(JsonNode credentials) {
        this.credentials = credentials == null ? null : credentials.deepCopy();
    }

    public JsonNode getVpcConnectionProperties() {
        return vpcConnectionProperties;
    }

    public void setVpcConnectionProperties(JsonNode vpcConnectionProperties) {
        this.vpcConnectionProperties = vpcConnectionProperties == null
                ? null
                : vpcConnectionProperties.deepCopy();
    }

    public JsonNode getSslProperties() {
        return sslProperties;
    }

    public void setSslProperties(JsonNode sslProperties) {
        this.sslProperties = sslProperties == null ? null : sslProperties.deepCopy();
    }

    public JsonNode getPermissions() {
        return permissions;
    }

    public void setPermissions(JsonNode permissions) {
        this.permissions = permissions == null ? null : permissions.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
