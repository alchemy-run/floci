package io.floci.aws.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An EventBridge global endpoint: a name in its home Region that routes PutEvents to the primary
 * event bus and, on failover, to the secondary one. RoutingConfig, ReplicationConfig and EventBuses
 * are kept as the JSON the request carried, so they read back exactly as written.
 */
@RegisterForReflection
public class GlobalEndpoint {

    private String name;
    private String arn;
    private String endpointId;
    private String endpointUrl;
    private String description;
    private String routingConfig;
    private String replicationConfig;
    private String eventBuses;
    private String roleArn;
    private String state;
    private Instant creationTime;
    private Instant lastModifiedTime;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRoutingConfig() { return routingConfig; }
    public void setRoutingConfig(String routingConfig) { this.routingConfig = routingConfig; }

    public String getReplicationConfig() { return replicationConfig; }
    public void setReplicationConfig(String replicationConfig) { this.replicationConfig = replicationConfig; }

    public String getEventBuses() { return eventBuses; }
    public void setEventBuses(String eventBuses) { this.eventBuses = eventBuses; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
