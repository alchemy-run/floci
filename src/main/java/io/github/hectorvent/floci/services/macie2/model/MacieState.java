package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MacieState {
    private String adminAccountId;
    private boolean enabled;
    private boolean autoEnable;
    private String status = "ENABLED";
    private String findingPublishingFrequency = "SIX_HOURS";
    private String createdAt;
    private String updatedAt;

    private Map<String, ObjectNode> documents = new LinkedHashMap<>();
    private Map<String, ObjectNode> createRequests = new LinkedHashMap<>();

    public MacieState() {}
    public Map<String, ObjectNode> getDocuments() { return documents; }
    public void setDocuments(Map<String, ObjectNode> documents) { this.documents = documents; }
    public Map<String, ObjectNode> getCreateRequests() { return createRequests; }
    public void setCreateRequests(Map<String, ObjectNode> createRequests) { this.createRequests = createRequests; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFindingPublishingFrequency() { return findingPublishingFrequency; }
    public void setFindingPublishingFrequency(String frequency) { this.findingPublishingFrequency = frequency; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoEnable() { return autoEnable; }
    public void setAutoEnable(boolean autoEnable) { this.autoEnable = autoEnable; }
}
