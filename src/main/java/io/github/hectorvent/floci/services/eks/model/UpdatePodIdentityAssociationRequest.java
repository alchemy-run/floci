package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdatePodIdentityAssociationRequest {

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("disableSessionTags")
    private Boolean disableSessionTags;

    @JsonProperty("targetRoleArn")
    private String targetRoleArn;

    @JsonProperty("policy")
    private String policy;

    public UpdatePodIdentityAssociationRequest() {}

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public Boolean getDisableSessionTags() { return disableSessionTags; }
    public void setDisableSessionTags(Boolean disableSessionTags) {
        this.disableSessionTags = disableSessionTags;
    }

    public String getTargetRoleArn() { return targetRoleArn; }
    public void setTargetRoleArn(String targetRoleArn) { this.targetRoleArn = targetRoleArn; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
