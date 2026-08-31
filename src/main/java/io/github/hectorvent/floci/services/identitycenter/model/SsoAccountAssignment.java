package io.github.hectorvent.floci.services.identitycenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsoAccountAssignment {

    private String instanceArn;
    private String permissionSetArn;
    private String principalType;
    private String principalId;
    private String accountId;
    private String targetType;

    public SsoAccountAssignment() {
    }

    public String getInstanceArn() {
        return instanceArn;
    }

    public void setInstanceArn(String instanceArn) {
        this.instanceArn = instanceArn;
    }

    public String getPermissionSetArn() {
        return permissionSetArn;
    }

    public void setPermissionSetArn(String permissionSetArn) {
        this.permissionSetArn = permissionSetArn;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }
}
