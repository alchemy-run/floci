package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreatePodIdentityAssociationRequest {

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("serviceAccount")
    private String serviceAccount;

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("disableSessionTags")
    private Boolean disableSessionTags;

    @JsonProperty("targetRoleArn")
    private String targetRoleArn;

    @JsonProperty("policy")
    private String policy;

    public CreatePodIdentityAssociationRequest() {}

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getServiceAccount() { return serviceAccount; }
    public void setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Boolean getDisableSessionTags() { return disableSessionTags; }
    public void setDisableSessionTags(Boolean disableSessionTags) {
        this.disableSessionTags = disableSessionTags;
    }

    public String getTargetRoleArn() { return targetRoleArn; }
    public void setTargetRoleArn(String targetRoleArn) { this.targetRoleArn = targetRoleArn; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
