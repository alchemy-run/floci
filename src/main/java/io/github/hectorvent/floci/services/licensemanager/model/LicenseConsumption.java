package io.github.hectorvent.floci.services.licensemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provisional/borrow checkout of a seller-issued license.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseConsumption {

    private String licenseConsumptionToken;
    private String licenseArn;
    private String checkoutType;
    private String issuedAt;
    private String expiration;
    private String nodeId;
    private String beneficiary;
    private List<Map<String, Object>> entitlements = new ArrayList<>();

    public LicenseConsumption() {
    }

    public String getLicenseConsumptionToken() {
        return licenseConsumptionToken;
    }

    public void setLicenseConsumptionToken(String licenseConsumptionToken) {
        this.licenseConsumptionToken = licenseConsumptionToken;
    }

    public String getLicenseArn() {
        return licenseArn;
    }

    public void setLicenseArn(String licenseArn) {
        this.licenseArn = licenseArn;
    }

    public String getCheckoutType() {
        return checkoutType;
    }

    public void setCheckoutType(String checkoutType) {
        this.checkoutType = checkoutType;
    }

    public String getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(String issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getExpiration() {
        return expiration;
    }

    public void setExpiration(String expiration) {
        this.expiration = expiration;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getBeneficiary() {
        return beneficiary;
    }

    public void setBeneficiary(String beneficiary) {
        this.beneficiary = beneficiary;
    }

    public List<Map<String, Object>> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<Map<String, Object>> entitlements) {
        this.entitlements = entitlements != null ? entitlements : new ArrayList<>();
    }
}
