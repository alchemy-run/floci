package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon GuardDuty detector. One per account and Region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Detector {

    private String detectorId;
    private String arn;
    private String status;
    private String findingPublishingFrequency;
    private String createdAt;
    private String updatedAt;
    private String serviceRole;
    private String ebsSnapshotPreservation = "NO_RETENTION";
    private boolean autoEnable;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, Finding> findings = new LinkedHashMap<>();
    private Map<String, Filter> filters = new LinkedHashMap<>();
    private Map<String, IpSet> ipSets = new LinkedHashMap<>();
    private Map<String, ThreatIntelSet> threatIntelSets = new LinkedHashMap<>();

    public Detector() {
    }

    public String getDetectorId() {
        return detectorId;
    }

    public void setDetectorId(String detectorId) {
        this.detectorId = detectorId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
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

    public String getServiceRole() {
        return serviceRole;
    }

    public void setServiceRole(String serviceRole) {
        this.serviceRole = serviceRole;
    }

    public String getEbsSnapshotPreservation() {
        return ebsSnapshotPreservation == null ? "NO_RETENTION" : ebsSnapshotPreservation;
    }

    public void setEbsSnapshotPreservation(String ebsSnapshotPreservation) {
        this.ebsSnapshotPreservation = ebsSnapshotPreservation;
    }

    public boolean isAutoEnable() {
        return autoEnable;
    }

    public void setAutoEnable(boolean autoEnable) {
        this.autoEnable = autoEnable;
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, Finding> getFindings() {
        if (findings == null) {
            findings = new LinkedHashMap<>();
        }
        return findings;
    }

    public void setFindings(Map<String, Finding> findings) {
        this.findings = findings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(findings);
    }

    public Map<String, Filter> getFilters() {
        if (filters == null) {
            filters = new LinkedHashMap<>();
        }
        return filters;
    }

    public void setFilters(Map<String, Filter> filters) {
        this.filters = filters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(filters);
    }

    public Map<String, IpSet> getIpSets() {
        if (ipSets == null) {
            ipSets = new LinkedHashMap<>();
        }
        return ipSets;
    }

    public void setIpSets(Map<String, IpSet> ipSets) {
        this.ipSets = ipSets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(ipSets);
    }

    public Map<String, ThreatIntelSet> getThreatIntelSets() {
        if (threatIntelSets == null) {
            threatIntelSets = new LinkedHashMap<>();
        }
        return threatIntelSets;
    }

    public void setThreatIntelSets(Map<String, ThreatIntelSet> threatIntelSets) {
        this.threatIntelSets = threatIntelSets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(threatIntelSets);
    }
}
