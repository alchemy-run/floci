package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS Control Tower landing zone. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LandingZone {

    private String arn;
    private String version;
    private String latestAvailableVersion;
    private String status;
    private String driftStatus;
    private List<String> remediationTypes = new ArrayList<>();
    private JsonNode manifest;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;
    private String region;

    public LandingZone() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLatestAvailableVersion() {
        return latestAvailableVersion;
    }

    public void setLatestAvailableVersion(String latestAvailableVersion) {
        this.latestAvailableVersion = latestAvailableVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDriftStatus() {
        return driftStatus;
    }

    public void setDriftStatus(String driftStatus) {
        this.driftStatus = driftStatus;
    }

    public List<String> getRemediationTypes() {
        return remediationTypes;
    }

    public void setRemediationTypes(List<String> remediationTypes) {
        this.remediationTypes = remediationTypes == null ? new ArrayList<>() : new ArrayList<>(remediationTypes);
    }

    public JsonNode getManifest() {
        return manifest == null ? null : manifest.deepCopy();
    }

    public void setManifest(JsonNode manifest) {
        this.manifest = manifest == null || manifest.isNull() ? null : manifest.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
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
}
