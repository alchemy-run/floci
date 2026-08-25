package io.github.hectorvent.floci.services.licensemanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seller-issued License Manager license (JSON 1.1 {@code AWSLicenseManager.*}).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SellerLicense {

    private String licenseArn;
    private String licenseName;
    private String productName;
    private String productSku;
    private String homeRegion;
    private String status;
    private String version;
    private String beneficiary;
    private String createTime;
    private String issuerName;
    private String issuerSignKey;
    private String keyFingerprint;
    private String validityBegin;
    private String validityEnd;
    private String clientToken;
    private List<Map<String, Object>> entitlements = new ArrayList<>();
    private Map<String, Object> consumptionConfiguration = new LinkedHashMap<>();
    private List<Map<String, Object>> licenseMetadata = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public SellerLicense() {
    }

    public String getLicenseArn() {
        return licenseArn;
    }

    public void setLicenseArn(String licenseArn) {
        this.licenseArn = licenseArn;
    }

    public String getLicenseName() {
        return licenseName;
    }

    public void setLicenseName(String licenseName) {
        this.licenseName = licenseName;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getProductSku() {
        return productSku;
    }

    public void setProductSku(String productSku) {
        this.productSku = productSku;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBeneficiary() {
        return beneficiary;
    }

    public void setBeneficiary(String beneficiary) {
        this.beneficiary = beneficiary;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getIssuerName() {
        return issuerName;
    }

    public void setIssuerName(String issuerName) {
        this.issuerName = issuerName;
    }

    public String getIssuerSignKey() {
        return issuerSignKey;
    }

    public void setIssuerSignKey(String issuerSignKey) {
        this.issuerSignKey = issuerSignKey;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }

    public void setKeyFingerprint(String keyFingerprint) {
        this.keyFingerprint = keyFingerprint;
    }

    public String getValidityBegin() {
        return validityBegin;
    }

    public void setValidityBegin(String validityBegin) {
        this.validityBegin = validityBegin;
    }

    public String getValidityEnd() {
        return validityEnd;
    }

    public void setValidityEnd(String validityEnd) {
        this.validityEnd = validityEnd;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public List<Map<String, Object>> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<Map<String, Object>> entitlements) {
        this.entitlements = entitlements != null ? entitlements : new ArrayList<>();
    }

    public Map<String, Object> getConsumptionConfiguration() {
        return consumptionConfiguration;
    }

    public void setConsumptionConfiguration(Map<String, Object> consumptionConfiguration) {
        this.consumptionConfiguration = consumptionConfiguration != null
                ? consumptionConfiguration
                : new LinkedHashMap<>();
    }

    public List<Map<String, Object>> getLicenseMetadata() {
        return licenseMetadata;
    }

    public void setLicenseMetadata(List<Map<String, Object>> licenseMetadata) {
        this.licenseMetadata = licenseMetadata != null ? licenseMetadata : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
