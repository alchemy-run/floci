package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeConnectionsHost {

    private String name;
    private String hostArn;
    private String providerType;
    private String providerEndpoint;
    private String status;
    private String statusMessage;
    private String region;
    private String accountId;
    private CodeConnectionsVpcConfiguration vpcConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CodeConnectionsHost() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostArn() {
        return hostArn;
    }

    public void setHostArn(String hostArn) {
        this.hostArn = hostArn;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getProviderEndpoint() {
        return providerEndpoint;
    }

    public void setProviderEndpoint(String providerEndpoint) {
        this.providerEndpoint = providerEndpoint;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
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

    public CodeConnectionsVpcConfiguration getVpcConfiguration() {
        return vpcConfiguration;
    }

    public void setVpcConfiguration(CodeConnectionsVpcConfiguration vpcConfiguration) {
        this.vpcConfiguration = vpcConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
