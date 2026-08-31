package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeConnectionsRepositoryLink {

    private String repositoryLinkId;
    private String repositoryLinkArn;
    private String connectionArn;
    private String ownerId;
    private String repositoryName;
    private String providerType;
    private String encryptionKeyArn;
    private String region;
    private String accountId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CodeConnectionsRepositoryLink() {
    }

    public String getRepositoryLinkId() {
        return repositoryLinkId;
    }

    public void setRepositoryLinkId(String repositoryLinkId) {
        this.repositoryLinkId = repositoryLinkId;
    }

    public String getRepositoryLinkArn() {
        return repositoryLinkArn;
    }

    public void setRepositoryLinkArn(String repositoryLinkArn) {
        this.repositoryLinkArn = repositoryLinkArn;
    }

    public String getConnectionArn() {
        return connectionArn;
    }

    public void setConnectionArn(String connectionArn) {
        this.connectionArn = connectionArn;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getEncryptionKeyArn() {
        return encryptionKeyArn;
    }

    public void setEncryptionKeyArn(String encryptionKeyArn) {
        this.encryptionKeyArn = encryptionKeyArn;
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
