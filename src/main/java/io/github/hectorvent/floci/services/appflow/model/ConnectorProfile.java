package io.github.hectorvent.floci.services.appflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An AppFlow connector profile. Wire names are camelCase.
 *
 * <p>Credentials are never persisted — AWS does not return them from
 * {@code DescribeConnectorProfiles}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectorProfile {

    private String connectorProfileName;
    private String connectorProfileArn;
    private String connectorType;
    private String connectorLabel;
    private String connectionMode;
    private String credentialsArn;
    private String kmsArn;
    private JsonNode connectorProfileProperties;
    private long createdAt;
    private long lastUpdatedAt;

    public ConnectorProfile() {
    }

    public String getConnectorProfileName() {
        return connectorProfileName;
    }

    public void setConnectorProfileName(String connectorProfileName) {
        this.connectorProfileName = connectorProfileName;
    }

    public String getConnectorProfileArn() {
        return connectorProfileArn;
    }

    public void setConnectorProfileArn(String connectorProfileArn) {
        this.connectorProfileArn = connectorProfileArn;
    }

    public String getConnectorType() {
        return connectorType;
    }

    public void setConnectorType(String connectorType) {
        this.connectorType = connectorType;
    }

    public String getConnectorLabel() {
        return connectorLabel;
    }

    public void setConnectorLabel(String connectorLabel) {
        this.connectorLabel = connectorLabel;
    }

    public String getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(String connectionMode) {
        this.connectionMode = connectionMode;
    }

    public String getCredentialsArn() {
        return credentialsArn;
    }

    public void setCredentialsArn(String credentialsArn) {
        this.credentialsArn = credentialsArn;
    }

    public String getKmsArn() {
        return kmsArn;
    }

    public void setKmsArn(String kmsArn) {
        this.kmsArn = kmsArn;
    }

    public JsonNode getConnectorProfileProperties() {
        return connectorProfileProperties;
    }

    public void setConnectorProfileProperties(JsonNode connectorProfileProperties) {
        this.connectorProfileProperties = connectorProfileProperties;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(long lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}
