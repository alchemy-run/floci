package io.github.hectorvent.floci.services.licensemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Seller-issued License Manager grant (JSON 1.1 {@code AWSLicenseManager.*}).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SellerGrant {

    private String grantArn;
    private String grantName;
    private String licenseArn;
    private String parentArn;
    private String granteePrincipalArn;
    private String homeRegion;
    private String grantStatus;
    private String statusReason;
    private String version;
    private String clientToken;
    private List<String> grantedOperations = new ArrayList<>();

    public SellerGrant() {
    }

    public String getGrantArn() {
        return grantArn;
    }

    public void setGrantArn(String grantArn) {
        this.grantArn = grantArn;
    }

    public String getGrantName() {
        return grantName;
    }

    public void setGrantName(String grantName) {
        this.grantName = grantName;
    }

    public String getLicenseArn() {
        return licenseArn;
    }

    public void setLicenseArn(String licenseArn) {
        this.licenseArn = licenseArn;
    }

    public String getParentArn() {
        return parentArn;
    }

    public void setParentArn(String parentArn) {
        this.parentArn = parentArn;
    }

    public String getGranteePrincipalArn() {
        return granteePrincipalArn;
    }

    public void setGranteePrincipalArn(String granteePrincipalArn) {
        this.granteePrincipalArn = granteePrincipalArn;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public String getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(String grantStatus) {
        this.grantStatus = grantStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public List<String> getGrantedOperations() {
        return grantedOperations;
    }

    public void setGrantedOperations(List<String> grantedOperations) {
        this.grantedOperations = grantedOperations != null ? grantedOperations : new ArrayList<>();
    }
}
