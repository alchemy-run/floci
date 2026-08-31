package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * EKS pod identity association. Wire shape for Create/Describe/Update/Delete.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PodIdentityAssociation {

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("serviceAccount")
    private String serviceAccount;

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("associationArn")
    private String associationArn;

    @JsonProperty("associationId")
    private String associationId;

    @JsonProperty("tags")
    private Map<String, String> tags = new HashMap<>();

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("modifiedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant modifiedAt;

    @JsonProperty("ownerArn")
    private String ownerArn;

    @JsonProperty("disableSessionTags")
    private Boolean disableSessionTags;

    @JsonProperty("targetRoleArn")
    private String targetRoleArn;

    @JsonProperty("externalId")
    private String externalId;

    @JsonProperty("policy")
    private String policy;

    @JsonIgnore
    private String accountId;

    public PodIdentityAssociation() {}

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getServiceAccount() { return serviceAccount; }
    public void setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getAssociationArn() { return associationArn; }
    public void setAssociationArn(String associationArn) { this.associationArn = associationArn; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public String getOwnerArn() { return ownerArn; }
    public void setOwnerArn(String ownerArn) { this.ownerArn = ownerArn; }

    public Boolean getDisableSessionTags() { return disableSessionTags; }
    public void setDisableSessionTags(Boolean disableSessionTags) {
        this.disableSessionTags = disableSessionTags;
    }

    public String getTargetRoleArn() { return targetRoleArn; }
    public void setTargetRoleArn(String targetRoleArn) { this.targetRoleArn = targetRoleArn; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
