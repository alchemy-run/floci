package io.github.hectorvent.floci.services.finspace.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A FinSpace managed kdb environment. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KxEnvironment {

    private String environmentId;
    private String name;
    private String description;
    private String environmentArn;
    private String kmsKeyId;
    private String status;
    private String tgwStatus;
    private String dnsStatus;
    private String awsAccountId;
    private String dedicatedServiceAccountId;
    private String certificateAuthorityArn;
    private String region;
    private JsonNode transitGatewayConfiguration;
    private JsonNode customDNSConfiguration;
    private List<String> availabilityZoneIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private long creationTimestamp;
    private long updateTimestamp;

    public KxEnvironment() {
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
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

    public String getEnvironmentArn() {
        return environmentArn;
    }

    public void setEnvironmentArn(String environmentArn) {
        this.environmentArn = environmentArn;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTgwStatus() {
        return tgwStatus;
    }

    public void setTgwStatus(String tgwStatus) {
        this.tgwStatus = tgwStatus;
    }

    public String getDnsStatus() {
        return dnsStatus;
    }

    public void setDnsStatus(String dnsStatus) {
        this.dnsStatus = dnsStatus;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getDedicatedServiceAccountId() {
        return dedicatedServiceAccountId;
    }

    public void setDedicatedServiceAccountId(String dedicatedServiceAccountId) {
        this.dedicatedServiceAccountId = dedicatedServiceAccountId;
    }

    public String getCertificateAuthorityArn() {
        return certificateAuthorityArn;
    }

    public void setCertificateAuthorityArn(String certificateAuthorityArn) {
        this.certificateAuthorityArn = certificateAuthorityArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public JsonNode getTransitGatewayConfiguration() {
        return transitGatewayConfiguration;
    }

    public void setTransitGatewayConfiguration(JsonNode transitGatewayConfiguration) {
        this.transitGatewayConfiguration = transitGatewayConfiguration;
    }

    public JsonNode getCustomDNSConfiguration() {
        return customDNSConfiguration;
    }

    public void setCustomDNSConfiguration(JsonNode customDNSConfiguration) {
        this.customDNSConfiguration = customDNSConfiguration;
    }

    public List<String> getAvailabilityZoneIds() {
        return availabilityZoneIds;
    }

    public void setAvailabilityZoneIds(List<String> availabilityZoneIds) {
        this.availabilityZoneIds = availabilityZoneIds == null ? new ArrayList<>() : new ArrayList<>(availabilityZoneIds);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(long creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public long getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(long updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }
}
