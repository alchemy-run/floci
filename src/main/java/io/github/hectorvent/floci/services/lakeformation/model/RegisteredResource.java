package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An S3 location registered with Lake Formation.
 *
 * <p>Wire names are PascalCase ({@code ResourceArn}, {@code RoleArn}, …).
 */
@RegisterForReflection
public class RegisteredResource {

    private String resourceArn;
    private String roleArn;
    private long lastModifiedEpochSeconds;
    private boolean withFederation;
    private boolean hybridAccessEnabled;
    private boolean withPrivilegedAccess;
    private String expectedResourceOwnerAccount;
    private boolean serviceLinkedRole;

    public RegisteredResource() {
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public long getLastModifiedEpochSeconds() {
        return lastModifiedEpochSeconds;
    }

    public void setLastModifiedEpochSeconds(long lastModifiedEpochSeconds) {
        this.lastModifiedEpochSeconds = lastModifiedEpochSeconds;
    }

    public boolean isWithFederation() {
        return withFederation;
    }

    public void setWithFederation(boolean withFederation) {
        this.withFederation = withFederation;
    }

    public boolean isHybridAccessEnabled() {
        return hybridAccessEnabled;
    }

    public void setHybridAccessEnabled(boolean hybridAccessEnabled) {
        this.hybridAccessEnabled = hybridAccessEnabled;
    }

    public boolean isWithPrivilegedAccess() {
        return withPrivilegedAccess;
    }

    public void setWithPrivilegedAccess(boolean withPrivilegedAccess) {
        this.withPrivilegedAccess = withPrivilegedAccess;
    }

    public String getExpectedResourceOwnerAccount() {
        return expectedResourceOwnerAccount;
    }

    public void setExpectedResourceOwnerAccount(String expectedResourceOwnerAccount) {
        this.expectedResourceOwnerAccount = expectedResourceOwnerAccount;
    }

    public boolean isServiceLinkedRole() {
        return serviceLinkedRole;
    }

    public void setServiceLinkedRole(boolean serviceLinkedRole) {
        this.serviceLinkedRole = serviceLinkedRole;
    }
}
