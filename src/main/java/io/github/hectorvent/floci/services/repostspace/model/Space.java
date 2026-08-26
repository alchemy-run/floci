package io.github.hectorvent.floci.services.repostspace.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A private re:Post space stored by the restJson1 emulator. */
@RegisterForReflection
public class Space {

    private String spaceId;
    private String arn;
    private String name;
    private String subdomain;
    private String status;
    private String configurationStatus;
    private String clientId;
    private String identityStoreId;
    private String applicationArn;
    private String description;
    private String vanityDomainStatus;
    private String vanityDomain;
    private String randomDomain;
    private String customerRoleArn;
    private String createDateTime;
    private String deleteDateTime;
    private String tier;
    private long storageLimit;
    private String userKMSKey;
    private String supportedEmailDomainsEnabled;
    private List<String> allowedDomains = new ArrayList<>();
    private List<String> userAdmins = new ArrayList<>();
    private List<String> groupAdmins = new ArrayList<>();
    private Map<String, List<String>> roles = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private int userCount;
    private long contentSize;

    public Space() {
    }

    public String getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public void setSubdomain(String subdomain) {
        this.subdomain = subdomain;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfigurationStatus() {
        return configurationStatus;
    }

    public void setConfigurationStatus(String configurationStatus) {
        this.configurationStatus = configurationStatus;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getIdentityStoreId() {
        return identityStoreId;
    }

    public void setIdentityStoreId(String identityStoreId) {
        this.identityStoreId = identityStoreId;
    }

    public String getApplicationArn() {
        return applicationArn;
    }

    public void setApplicationArn(String applicationArn) {
        this.applicationArn = applicationArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVanityDomainStatus() {
        return vanityDomainStatus;
    }

    public void setVanityDomainStatus(String vanityDomainStatus) {
        this.vanityDomainStatus = vanityDomainStatus;
    }

    public String getVanityDomain() {
        return vanityDomain;
    }

    public void setVanityDomain(String vanityDomain) {
        this.vanityDomain = vanityDomain;
    }

    public String getRandomDomain() {
        return randomDomain;
    }

    public void setRandomDomain(String randomDomain) {
        this.randomDomain = randomDomain;
    }

    public String getCustomerRoleArn() {
        return customerRoleArn;
    }

    public void setCustomerRoleArn(String customerRoleArn) {
        this.customerRoleArn = customerRoleArn;
    }

    public String getCreateDateTime() {
        return createDateTime;
    }

    public void setCreateDateTime(String createDateTime) {
        this.createDateTime = createDateTime;
    }

    public String getDeleteDateTime() {
        return deleteDateTime;
    }

    public void setDeleteDateTime(String deleteDateTime) {
        this.deleteDateTime = deleteDateTime;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public long getStorageLimit() {
        return storageLimit;
    }

    public void setStorageLimit(long storageLimit) {
        this.storageLimit = storageLimit;
    }

    public String getUserKMSKey() {
        return userKMSKey;
    }

    public void setUserKMSKey(String userKMSKey) {
        this.userKMSKey = userKMSKey;
    }

    public String getSupportedEmailDomainsEnabled() {
        return supportedEmailDomainsEnabled;
    }

    public void setSupportedEmailDomainsEnabled(String supportedEmailDomainsEnabled) {
        this.supportedEmailDomainsEnabled = supportedEmailDomainsEnabled;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains == null ? new ArrayList<>() : new ArrayList<>(allowedDomains);
    }

    public List<String> getUserAdmins() {
        return userAdmins;
    }

    public void setUserAdmins(List<String> userAdmins) {
        this.userAdmins = userAdmins == null ? new ArrayList<>() : new ArrayList<>(userAdmins);
    }

    public List<String> getGroupAdmins() {
        return groupAdmins;
    }

    public void setGroupAdmins(List<String> groupAdmins) {
        this.groupAdmins = groupAdmins == null ? new ArrayList<>() : new ArrayList<>(groupAdmins);
    }

    public Map<String, List<String>> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, List<String>> roles) {
        this.roles = copyRoleMap(roles);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public int getUserCount() {
        return userCount;
    }

    public void setUserCount(int userCount) {
        this.userCount = userCount;
    }

    public long getContentSize() {
        return contentSize;
    }

    public void setContentSize(long contentSize) {
        this.contentSize = contentSize;
    }

    static Map<String, List<String>> copyRoleMap(Map<String, List<String>> roles) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (roles == null) {
            return copy;
        }
        for (Map.Entry<String, List<String>> entry : roles.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? new ArrayList<>() : new ArrayList<>(entry.getValue()));
        }
        return copy;
    }
}
