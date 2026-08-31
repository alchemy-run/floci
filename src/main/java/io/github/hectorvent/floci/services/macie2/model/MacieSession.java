package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Amazon Macie session — one per account and Region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MacieSession {

    private String accountId;
    private String region;
    private String status;
    private String findingPublishingFrequency;
    private String serviceRole;
    private String createdAt;
    private String updatedAt;
    private String automatedDiscoveryStatus = "DISABLED";
    private boolean revealConfigured;
    private Map<String, MacieFinding> findings = new LinkedHashMap<>();

    public MacieSession() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFindingPublishingFrequency() {
        return findingPublishingFrequency;
    }

    public void setFindingPublishingFrequency(String findingPublishingFrequency) {
        this.findingPublishingFrequency = findingPublishingFrequency;
    }

    public String getServiceRole() {
        return serviceRole;
    }

    public void setServiceRole(String serviceRole) {
        this.serviceRole = serviceRole;
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

    public String getAutomatedDiscoveryStatus() {
        return automatedDiscoveryStatus == null ? "DISABLED" : automatedDiscoveryStatus;
    }

    public void setAutomatedDiscoveryStatus(String automatedDiscoveryStatus) {
        this.automatedDiscoveryStatus = automatedDiscoveryStatus;
    }

    public boolean isRevealConfigured() {
        return revealConfigured;
    }

    public void setRevealConfigured(boolean revealConfigured) {
        this.revealConfigured = revealConfigured;
    }

    public Map<String, MacieFinding> getFindings() {
        if (findings == null) {
            findings = new LinkedHashMap<>();
        }
        return findings;
    }

    public void setFindings(Map<String, MacieFinding> findings) {
        this.findings = findings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(findings);
    }
}
