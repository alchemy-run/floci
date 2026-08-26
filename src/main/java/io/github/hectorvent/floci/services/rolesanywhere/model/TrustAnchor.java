package io.github.hectorvent.floci.services.rolesanywhere.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IAM Roles Anywhere trust anchor. */
@RegisterForReflection
public class TrustAnchor {
    private String trustAnchorId;
    private String trustAnchorArn;
    private String name;
    private JsonNode source;
    private boolean enabled = true;
    private String createdAt;
    private String updatedAt;
    private List<JsonNode> notificationSettings = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public TrustAnchor() {
    }

    public String getTrustAnchorId() {
        return trustAnchorId;
    }

    public void setTrustAnchorId(String trustAnchorId) {
        this.trustAnchorId = trustAnchorId;
    }

    public String getTrustAnchorArn() {
        return trustAnchorArn;
    }

    public void setTrustAnchorArn(String trustAnchorArn) {
        this.trustAnchorArn = trustAnchorArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getSource() {
        return source;
    }

    public void setSource(JsonNode source) {
        this.source = source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<JsonNode> getNotificationSettings() {
        return notificationSettings;
    }

    public void setNotificationSettings(List<JsonNode> notificationSettings) {
        this.notificationSettings = notificationSettings == null
                ? new ArrayList<>()
                : new ArrayList<>(notificationSettings);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
