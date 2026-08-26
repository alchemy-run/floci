package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoicePhoneNumber {

    private String phoneNumberId;
    private String phoneNumberArn;
    private String phoneNumber;
    private String status;
    private String isoCountryCode;
    private String messageType;
    private List<String> numberCapabilities = new ArrayList<>();
    private String numberType;
    private String monthlyLeasingPrice;
    private boolean twoWayEnabled;
    private String twoWayChannelArn;
    private String twoWayChannelRole;
    private boolean selfManagedOptOutsEnabled;
    private String optOutListName;
    private boolean internationalSendingEnabled;
    private boolean deletionProtectionEnabled;
    private String poolId;
    private String registrationId;
    private String region;
    private String clientToken;
    private long createdTimestamp;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, SmsVoiceKeyword> keywords = new LinkedHashMap<>();

    public SmsVoicePhoneNumber() {
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public String getPhoneNumberArn() {
        return phoneNumberArn;
    }

    public void setPhoneNumberArn(String phoneNumberArn) {
        this.phoneNumberArn = phoneNumberArn;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getIsoCountryCode() {
        return isoCountryCode;
    }

    public void setIsoCountryCode(String isoCountryCode) {
        this.isoCountryCode = isoCountryCode;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public List<String> getNumberCapabilities() {
        return numberCapabilities;
    }

    public void setNumberCapabilities(List<String> numberCapabilities) {
        this.numberCapabilities = numberCapabilities == null ? new ArrayList<>() : new ArrayList<>(numberCapabilities);
    }

    public String getNumberType() {
        return numberType;
    }

    public void setNumberType(String numberType) {
        this.numberType = numberType;
    }

    public String getMonthlyLeasingPrice() {
        return monthlyLeasingPrice;
    }

    public void setMonthlyLeasingPrice(String monthlyLeasingPrice) {
        this.monthlyLeasingPrice = monthlyLeasingPrice;
    }

    public boolean isTwoWayEnabled() {
        return twoWayEnabled;
    }

    public void setTwoWayEnabled(boolean twoWayEnabled) {
        this.twoWayEnabled = twoWayEnabled;
    }

    public String getTwoWayChannelArn() {
        return twoWayChannelArn;
    }

    public void setTwoWayChannelArn(String twoWayChannelArn) {
        this.twoWayChannelArn = twoWayChannelArn;
    }

    public String getTwoWayChannelRole() {
        return twoWayChannelRole;
    }

    public void setTwoWayChannelRole(String twoWayChannelRole) {
        this.twoWayChannelRole = twoWayChannelRole;
    }

    public boolean isSelfManagedOptOutsEnabled() {
        return selfManagedOptOutsEnabled;
    }

    public void setSelfManagedOptOutsEnabled(boolean selfManagedOptOutsEnabled) {
        this.selfManagedOptOutsEnabled = selfManagedOptOutsEnabled;
    }

    public String getOptOutListName() {
        return optOutListName;
    }

    public void setOptOutListName(String optOutListName) {
        this.optOutListName = optOutListName;
    }

    public boolean isInternationalSendingEnabled() {
        return internationalSendingEnabled;
    }

    public void setInternationalSendingEnabled(boolean internationalSendingEnabled) {
        this.internationalSendingEnabled = internationalSendingEnabled;
    }

    public boolean isDeletionProtectionEnabled() {
        return deletionProtectionEnabled;
    }

    public void setDeletionProtectionEnabled(boolean deletionProtectionEnabled) {
        this.deletionProtectionEnabled = deletionProtectionEnabled;
    }

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public Map<String, SmsVoiceKeyword> getKeywords() {
        if (keywords == null) {
            keywords = new LinkedHashMap<>();
        }
        return keywords;
    }

    public void setKeywords(Map<String, SmsVoiceKeyword> keywords) {
        this.keywords = keywords == null ? new LinkedHashMap<>() : keywords;
    }
}
