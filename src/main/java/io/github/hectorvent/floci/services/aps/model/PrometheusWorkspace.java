package io.github.hectorvent.floci.services.aps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrometheusWorkspace {

    private String workspaceId;
    private String alias;
    private String arn;
    // WorkspaceStatusCode: the emulator only ever stores ACTIVE (creation is instantaneous).
    private String status;
    private String prometheusEndpoint;
    private boolean prometheusRuntimeOwned;
    private Instant createdAt;
    private String kmsKeyArn;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private Map<String, ObjectNode> configurations = new ConcurrentHashMap<>();
    private Map<String, AnomalyDetector> anomalyDetectors = new ConcurrentHashMap<>();

    public PrometheusWorkspace() {
    }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPrometheusEndpoint() { return prometheusEndpoint; }
    public void setPrometheusEndpoint(String prometheusEndpoint) { this.prometheusEndpoint = prometheusEndpoint; }

    public boolean isPrometheusRuntimeOwned() { return prometheusRuntimeOwned; }
    public void setPrometheusRuntimeOwned(boolean prometheusRuntimeOwned) {
        this.prometheusRuntimeOwned = prometheusRuntimeOwned;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getKmsKeyArn() { return kmsKeyArn; }
    public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }

    public Map<String, ObjectNode> getConfigurations() { return configurations; }
    public void setConfigurations(Map<String, ObjectNode> configurations) {
        this.configurations = configurations != null ? new ConcurrentHashMap<>(configurations) : new ConcurrentHashMap<>();
    }

    public Map<String, AnomalyDetector> getAnomalyDetectors() { return anomalyDetectors; }
    public void setAnomalyDetectors(Map<String, AnomalyDetector> anomalyDetectors) {
        this.anomalyDetectors = anomalyDetectors != null ? new ConcurrentHashMap<>(anomalyDetectors) : new ConcurrentHashMap<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new ConcurrentHashMap<>(tags) : new ConcurrentHashMap<>();
    }
}
