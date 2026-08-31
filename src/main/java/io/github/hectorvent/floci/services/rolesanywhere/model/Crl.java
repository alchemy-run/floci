package io.github.hectorvent.floci.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** IAM Roles Anywhere certificate revocation list. */
@RegisterForReflection
public class Crl {
    private String crlId;
    private String crlArn;
    private String name;
    private boolean enabled = true;
    private String crlData;
    private String trustAnchorArn;
    private String createdAt;
    private String updatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Crl() {
    }

    public String getCrlId() {
        return crlId;
    }

    public void setCrlId(String crlId) {
        this.crlId = crlId;
    }

    public String getCrlArn() {
        return crlArn;
    }

    public void setCrlArn(String crlArn) {
        this.crlArn = crlArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCrlData() {
        return crlData;
    }

    public void setCrlData(String crlData) {
        this.crlData = crlData;
    }

    public String getTrustAnchorArn() {
        return trustAnchorArn;
    }

    public void setTrustAnchorArn(String trustAnchorArn) {
        this.trustAnchorArn = trustAnchorArn;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
