package io.github.hectorvent.floci.services.b2bi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS B2BI partnership. Wire names are camelCase awsJson1_0. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2biPartnership {

    private String partnershipId;
    private String partnershipArn;
    private String profileId;
    private String name;
    private String email;
    private String phone;
    private List<String> capabilities = new ArrayList<>();
    private JsonNode capabilityOptions;
    private String tradingPartnerId;
    private String createdAt;
    private String modifiedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public B2biPartnership() {}

    public String getPartnershipId() {
        return partnershipId;
    }

    public void setPartnershipId(String partnershipId) {
        this.partnershipId = partnershipId;
    }

    public String getPartnershipArn() {
        return partnershipArn;
    }

    public void setPartnershipArn(String partnershipArn) {
        this.partnershipArn = partnershipArn;
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities != null ? capabilities : new ArrayList<>();
    }

    public JsonNode getCapabilityOptions() {
        return capabilityOptions;
    }

    public void setCapabilityOptions(JsonNode capabilityOptions) {
        this.capabilityOptions = capabilityOptions;
    }

    public String getTradingPartnerId() {
        return tradingPartnerId;
    }

    public void setTradingPartnerId(String tradingPartnerId) {
        this.tradingPartnerId = tradingPartnerId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
