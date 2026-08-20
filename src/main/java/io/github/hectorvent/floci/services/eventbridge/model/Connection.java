package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class Connection {

    private String name;
    private String connectionArn;
    private String connectionId;
    private String description;
    private String authorizationType;
    private String connectionState;
    private String secretArn;
    private String kmsKeyIdentifier;
    /** Full request AuthParameters JSON, including secret values. Never emitted as-is. */
    private String authParametersJson;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private Instant lastAuthorizedTime;

    public Connection() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getConnectionArn() { return connectionArn; }
    public void setConnectionArn(String connectionArn) { this.connectionArn = connectionArn; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthorizationType() { return authorizationType; }
    public void setAuthorizationType(String authorizationType) { this.authorizationType = authorizationType; }

    public String getConnectionState() { return connectionState; }
    public void setConnectionState(String connectionState) { this.connectionState = connectionState; }

    public String getSecretArn() { return secretArn; }
    public void setSecretArn(String secretArn) { this.secretArn = secretArn; }

    public String getKmsKeyIdentifier() { return kmsKeyIdentifier; }
    public void setKmsKeyIdentifier(String kmsKeyIdentifier) { this.kmsKeyIdentifier = kmsKeyIdentifier; }

    public String getAuthParametersJson() { return authParametersJson; }
    public void setAuthParametersJson(String authParametersJson) { this.authParametersJson = authParametersJson; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public Instant getLastAuthorizedTime() { return lastAuthorizedTime; }
    public void setLastAuthorizedTime(Instant lastAuthorizedTime) { this.lastAuthorizedTime = lastAuthorizedTime; }
}
