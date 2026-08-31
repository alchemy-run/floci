package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A VPC Lattice service. Wire names are camelCase restJson1. */
@RegisterForReflection
public class LatticeService {

    private String id;
    private String name;
    private String arn;
    private String region;
    private String authType = "NONE";
    private String customDomainName;
    private String certificateArn;
    private Integer idleTimeoutSeconds;
    private String status = "ACTIVE";
    private String dnsDomainName;
    private String dnsName;
    private String hostedZoneId;
    private String createdAt;
    private String lastUpdatedAt;
    private String clientToken;
    private String authPolicy;
    private String authPolicyCreatedAt;
    private String authPolicyUpdatedAt;
    private String resourcePolicy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public LatticeService() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getCustomDomainName() {
        return customDomainName;
    }

    public void setCustomDomainName(String customDomainName) {
        this.customDomainName = customDomainName;
    }

    public String getCertificateArn() {
        return certificateArn;
    }

    public void setCertificateArn(String certificateArn) {
        this.certificateArn = certificateArn;
    }

    public Integer getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(Integer idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDnsDomainName() {
        return dnsDomainName;
    }

    public void setDnsDomainName(String dnsDomainName) {
        this.dnsDomainName = dnsDomainName;
        this.dnsName = dnsDomainName;
    }

    public String getDnsName() {
        return dnsName != null ? dnsName : dnsDomainName;
    }

    public void setDnsName(String dnsName) {
        this.dnsName = dnsName;
        this.dnsDomainName = dnsName;
    }

    public String getHostedZoneId() {
        return hostedZoneId;
    }

    public void setHostedZoneId(String hostedZoneId) {
        this.hostedZoneId = hostedZoneId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getAuthPolicy() {
        return authPolicy;
    }

    public void setAuthPolicy(String authPolicy) {
        this.authPolicy = authPolicy;
    }

    public String getAuthPolicyCreatedAt() {
        return authPolicyCreatedAt;
    }

    public void setAuthPolicyCreatedAt(String authPolicyCreatedAt) {
        this.authPolicyCreatedAt = authPolicyCreatedAt;
    }

    public String getAuthPolicyUpdatedAt() {
        return authPolicyUpdatedAt;
    }

    public void setAuthPolicyUpdatedAt(String authPolicyUpdatedAt) {
        this.authPolicyUpdatedAt = authPolicyUpdatedAt;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
