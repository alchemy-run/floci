package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DMS endpoint: connection metadata for a migration source or target. {@code password} is kept
 * so TestConnection and RefreshSchemas can log in to the database; it is never rendered in a
 * response. {@code settings} holds each engine-specific settings structure (for example
 * {@code MySQLSettings}) keyed by its wire member name.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsEndpoint {

    private String endpointIdentifier;
    private String endpointArn;
    private String endpointType;
    private String engineName;
    private String username;
    private String password;
    private String serverName;
    private Integer port;
    private String databaseName;
    private String extraConnectionAttributes;
    private String kmsKeyId;
    private String certificateArn;
    private String sslMode;
    private String serviceAccessRoleArn;
    private String externalTableDefinition;
    private String status;
    private Map<String, JsonNode> settings = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DmsEndpoint() {
    }

    public DmsEndpoint(DmsEndpoint other) {
        this.endpointIdentifier = other.endpointIdentifier;
        this.endpointArn = other.endpointArn;
        this.endpointType = other.endpointType;
        this.engineName = other.engineName;
        this.username = other.username;
        this.password = other.password;
        this.serverName = other.serverName;
        this.port = other.port;
        this.databaseName = other.databaseName;
        this.extraConnectionAttributes = other.extraConnectionAttributes;
        this.kmsKeyId = other.kmsKeyId;
        this.certificateArn = other.certificateArn;
        this.sslMode = other.sslMode;
        this.serviceAccessRoleArn = other.serviceAccessRoleArn;
        this.externalTableDefinition = other.externalTableDefinition;
        this.status = other.status;
        setSettings(other.settings);
        setTags(other.tags);
    }

    public String getEndpointIdentifier() {
        return endpointIdentifier;
    }

    public void setEndpointIdentifier(String endpointIdentifier) {
        this.endpointIdentifier = endpointIdentifier;
    }

    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(String endpointArn) {
        this.endpointArn = endpointArn;
    }

    public String getEndpointType() {
        return endpointType;
    }

    public void setEndpointType(String endpointType) {
        this.endpointType = endpointType;
    }

    public String getEngineName() {
        return engineName;
    }

    public void setEngineName(String engineName) {
        this.engineName = engineName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getExtraConnectionAttributes() {
        return extraConnectionAttributes;
    }

    public void setExtraConnectionAttributes(String extraConnectionAttributes) {
        this.extraConnectionAttributes = extraConnectionAttributes;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getCertificateArn() {
        return certificateArn;
    }

    public void setCertificateArn(String certificateArn) {
        this.certificateArn = certificateArn;
    }

    public String getSslMode() {
        return sslMode;
    }

    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }

    public String getServiceAccessRoleArn() {
        return serviceAccessRoleArn;
    }

    public void setServiceAccessRoleArn(String serviceAccessRoleArn) {
        this.serviceAccessRoleArn = serviceAccessRoleArn;
    }

    public String getExternalTableDefinition() {
        return externalTableDefinition;
    }

    public void setExternalTableDefinition(String externalTableDefinition) {
        this.externalTableDefinition = externalTableDefinition;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, JsonNode> getSettings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    public void setSettings(Map<String, JsonNode> settings) {
        this.settings = settings != null ? new LinkedHashMap<>(settings) : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
