package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssociateAccessPolicyRequest {

    @JsonProperty("policyArn")
    private String policyArn;

    @JsonProperty("accessScope")
    private AccessEntry.AccessScope accessScope;

    public AssociateAccessPolicyRequest() {}

    public String getPolicyArn() { return policyArn; }
    public void setPolicyArn(String policyArn) { this.policyArn = policyArn; }

    public AccessEntry.AccessScope getAccessScope() { return accessScope; }
    public void setAccessScope(AccessEntry.AccessScope accessScope) { this.accessScope = accessScope; }
}
