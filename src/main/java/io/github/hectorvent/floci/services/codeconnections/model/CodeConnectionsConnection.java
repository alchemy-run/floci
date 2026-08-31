package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeConnectionsConnection {

    private String connectionArn;
    private String connectionName;
    private String providerType;
    private String ownerAccountId;
    private String connectionStatus;
    private String hostArn;
    private String region;
    private String accountId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CodeConnectionsConnection() {
    }

    public String getConnectionArn() {
        return connectionArn;
    }

    public void setConnectionArn(String connectionArn) {
        this.connectionArn = connectionArn;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getOwnerAccountId() {
        return ownerAccountId;
    }

    public void setOwnerAccountId(String ownerAccountId) {
        this.ownerAccountId = ownerAccountId;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public String getHostArn() {
        return hostArn;
    }

    public void setHostArn(String hostArn) {
        this.hostArn = hostArn;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
