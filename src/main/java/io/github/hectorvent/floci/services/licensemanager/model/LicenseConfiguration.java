package io.github.hectorvent.floci.services.licensemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * License Manager license configuration (JSON 1.1 {@code AWSLicenseManager.*}).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseConfiguration {

    private String licenseConfigurationId;
    private String licenseConfigurationArn;
    private String name;
    private String description;
    private String licenseCountingType;
    private Long licenseCount;
    private boolean licenseCountHardLimit;
    private boolean disassociateWhenNotFound;
    private List<String> licenseRules = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private String status;
    private String ownerAccountId;
    private String region;
    private long consumedLicenses;
    private Long licenseExpiry;

    public LicenseConfiguration() {
    }

    public String getLicenseConfigurationId() {
        return licenseConfigurationId;
    }

    public void setLicenseConfigurationId(String licenseConfigurationId) {
        this.licenseConfigurationId = licenseConfigurationId;
    }

    public String getLicenseConfigurationArn() {
        return licenseConfigurationArn;
    }

    public void setLicenseConfigurationArn(String licenseConfigurationArn) {
        this.licenseConfigurationArn = licenseConfigurationArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLicenseCountingType() {
        return licenseCountingType;
    }

    public void setLicenseCountingType(String licenseCountingType) {
        this.licenseCountingType = licenseCountingType;
    }

    public Long getLicenseCount() {
        return licenseCount;
    }

    public void setLicenseCount(Long licenseCount) {
        this.licenseCount = licenseCount;
    }

    public boolean isLicenseCountHardLimit() {
        return licenseCountHardLimit;
    }

    public void setLicenseCountHardLimit(boolean licenseCountHardLimit) {
        this.licenseCountHardLimit = licenseCountHardLimit;
    }

    public boolean isDisassociateWhenNotFound() {
        return disassociateWhenNotFound;
    }

    public void setDisassociateWhenNotFound(boolean disassociateWhenNotFound) {
        this.disassociateWhenNotFound = disassociateWhenNotFound;
    }

    public List<String> getLicenseRules() {
        return licenseRules;
    }

    public void setLicenseRules(List<String> licenseRules) {
        this.licenseRules = licenseRules != null ? licenseRules : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerAccountId() {
        return ownerAccountId;
    }

    public void setOwnerAccountId(String ownerAccountId) {
        this.ownerAccountId = ownerAccountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getConsumedLicenses() {
        return consumedLicenses;
    }

    public void setConsumedLicenses(long consumedLicenses) {
        this.consumedLicenses = consumedLicenses;
    }

    public Long getLicenseExpiry() {
        return licenseExpiry;
    }

    public void setLicenseExpiry(Long licenseExpiry) {
        this.licenseExpiry = licenseExpiry;
    }
}
