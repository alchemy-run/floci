package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Amazon Inspector CIS scan configuration. Serialized with the AWS member names of the
 * {@code CisScanConfiguration} structure, so it can be returned on the wire directly.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CisScanConfiguration {
    private String scanConfigurationArn;
    private String ownerId;
    private String scanName;
    private String securityLevel;
    private JsonNode schedule;
    private CisTargets targets = new CisTargets();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getScanConfigurationArn() { return scanConfigurationArn; }
    public void setScanConfigurationArn(String scanConfigurationArn) { this.scanConfigurationArn = scanConfigurationArn; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getScanName() { return scanName; }
    public void setScanName(String scanName) { this.scanName = scanName; }
    public String getSecurityLevel() { return securityLevel; }
    public void setSecurityLevel(String securityLevel) { this.securityLevel = securityLevel; }
    public JsonNode getSchedule() { return schedule; }
    public void setSchedule(JsonNode schedule) { this.schedule = schedule == null ? null : schedule.deepCopy(); }
    public CisTargets getTargets() { return targets; }
    public void setTargets(CisTargets targets) { this.targets = targets == null ? new CisTargets() : targets; }
    public Map<String, String> getTags() { return tags; }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public CisScanConfiguration copy() {
        CisScanConfiguration copy = new CisScanConfiguration();
        copy.scanConfigurationArn = scanConfigurationArn;
        copy.ownerId = ownerId;
        copy.scanName = scanName;
        copy.securityLevel = securityLevel;
        copy.setSchedule(schedule);
        copy.targets = targets.copy();
        copy.tags = new LinkedHashMap<>(tags);
        return copy;
    }
}
