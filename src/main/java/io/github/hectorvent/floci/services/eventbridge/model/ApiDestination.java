package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ApiDestination {

    private String name;
    private String apiDestinationArn;
    private String destinationId;
    private String description;
    private String connectionArn;
    private String invocationEndpoint;
    private String httpMethod;
    private int invocationRateLimitPerSecond;
    private String apiDestinationState;
    private Instant creationTime;
    private Instant lastModifiedTime;

    public ApiDestination() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiDestinationArn() { return apiDestinationArn; }
    public void setApiDestinationArn(String apiDestinationArn) { this.apiDestinationArn = apiDestinationArn; }

    public String getDestinationId() { return destinationId; }
    public void setDestinationId(String destinationId) { this.destinationId = destinationId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConnectionArn() { return connectionArn; }
    public void setConnectionArn(String connectionArn) { this.connectionArn = connectionArn; }

    public String getInvocationEndpoint() { return invocationEndpoint; }
    public void setInvocationEndpoint(String invocationEndpoint) { this.invocationEndpoint = invocationEndpoint; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public int getInvocationRateLimitPerSecond() { return invocationRateLimitPerSecond; }
    public void setInvocationRateLimitPerSecond(int invocationRateLimitPerSecond) {
        this.invocationRateLimitPerSecond = invocationRateLimitPerSecond;
    }

    public String getApiDestinationState() { return apiDestinationState; }
    public void setApiDestinationState(String apiDestinationState) { this.apiDestinationState = apiDestinationState; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
