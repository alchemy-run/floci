package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An ingress authorization rule of a Client VPN endpoint. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnAuthorizationRule {

    private String destinationCidr;
    private String groupId;
    private boolean accessAll;
    private String description;
    private String status;
    private String clientToken;

    public ClientVpnAuthorizationRule() {}

    public String getDestinationCidr() { return destinationCidr; }
    public void setDestinationCidr(String destinationCidr) { this.destinationCidr = destinationCidr; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public boolean isAccessAll() { return accessAll; }
    public void setAccessAll(boolean accessAll) { this.accessAll = accessAll; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
