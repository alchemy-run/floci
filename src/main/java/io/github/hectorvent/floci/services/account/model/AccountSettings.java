package io.github.hectorvent.floci.services.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted Account Management settings for one account (name, contact, region opt status).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountSettings {
    private String accountId;
    private String accountName;
    private String accountCreatedDate;
    private String accountState;
    private ContactInformation contactInformation;
    private Map<String, AlternateContact> alternateContacts = new LinkedHashMap<>();
    private Map<String, String> regionOptStatus = new LinkedHashMap<>();
    private String primaryEmail;

    public AccountSettings() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountCreatedDate() {
        return accountCreatedDate;
    }

    public void setAccountCreatedDate(String accountCreatedDate) {
        this.accountCreatedDate = accountCreatedDate;
    }

    public String getAccountState() {
        return accountState;
    }

    public void setAccountState(String accountState) {
        this.accountState = accountState;
    }

    public ContactInformation getContactInformation() {
        return contactInformation;
    }

    public void setContactInformation(ContactInformation contactInformation) {
        this.contactInformation = contactInformation;
    }

    public Map<String, AlternateContact> getAlternateContacts() {
        if (alternateContacts == null) {
            alternateContacts = new LinkedHashMap<>();
        }
        return alternateContacts;
    }

    public void setAlternateContacts(Map<String, AlternateContact> alternateContacts) {
        this.alternateContacts = alternateContacts == null ? new LinkedHashMap<>() : alternateContacts;
    }

    public Map<String, String> getRegionOptStatus() {
        if (regionOptStatus == null) {
            regionOptStatus = new LinkedHashMap<>();
        }
        return regionOptStatus;
    }

    public void setRegionOptStatus(Map<String, String> regionOptStatus) {
        this.regionOptStatus = regionOptStatus == null ? new LinkedHashMap<>() : regionOptStatus;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public void setPrimaryEmail(String primaryEmail) {
        this.primaryEmail = primaryEmail;
    }
}
