package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityGroupMembership {

    private String securityGroupIdentifier;
    private String status;

    public SecurityGroupMembership() {
    }

    public SecurityGroupMembership(String securityGroupIdentifier, String status) {
        this.securityGroupIdentifier = securityGroupIdentifier;
        this.status = status;
    }

    public String getSecurityGroupIdentifier() {
        return securityGroupIdentifier;
    }

    public void setSecurityGroupIdentifier(String securityGroupIdentifier) {
        this.securityGroupIdentifier = securityGroupIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
