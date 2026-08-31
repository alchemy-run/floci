package io.github.hectorvent.floci.services.iotmanagedintegrations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceDiscovery {

    private String id;
    private String arn;
    private String region;
    private String discoveryType;
    private String status;
    private String controllerId;
    private String connectorAssociationId;
    private String accountAssociationId;
    private Map<String, String> tags = new LinkedHashMap<>();
    private long startedAt;
    private Long finishedAt;

    public DeviceDiscovery() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDiscoveryType() {
        return discoveryType;
    }

    public void setDiscoveryType(String discoveryType) {
        this.discoveryType = discoveryType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getControllerId() {
        return controllerId;
    }

    public void setControllerId(String controllerId) {
        this.controllerId = controllerId;
    }

    public String getConnectorAssociationId() {
        return connectorAssociationId;
    }

    public void setConnectorAssociationId(String connectorAssociationId) {
        this.connectorAssociationId = connectorAssociationId;
    }

    public String getAccountAssociationId() {
        return accountAssociationId;
    }

    public void setAccountAssociationId(String accountAssociationId) {
        this.accountAssociationId = accountAssociationId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
