package io.github.hectorvent.floci.services.grafana.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon Managed Grafana workspace. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GrafanaWorkspace {

    private String id;
    private String region;
    private String accountId;
    private String name;
    private String description;
    private String accountAccessType;
    private String permissionType;
    private List<String> authenticationProviders = new ArrayList<>();
    private List<String> dataSources = new ArrayList<>();
    private String grafanaVersion;
    private String workspaceRoleArn;
    private String status;
    private long created;
    private long modified;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String configuration;
    private String licenseType;
    private String grafanaToken;
    private String samlStatus;
    private JsonNode samlConfiguration;
    private List<PermissionEntry> permissions = new ArrayList<>();
    private Map<String, ServiceAccount> serviceAccounts = new LinkedHashMap<>();

    public GrafanaWorkspace() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
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

    public String getAccountAccessType() {
        return accountAccessType;
    }

    public void setAccountAccessType(String accountAccessType) {
        this.accountAccessType = accountAccessType;
    }

    public String getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(String permissionType) {
        this.permissionType = permissionType;
    }

    public List<String> getAuthenticationProviders() {
        return authenticationProviders;
    }

    public void setAuthenticationProviders(List<String> authenticationProviders) {
        this.authenticationProviders = authenticationProviders == null
                ? new ArrayList<>()
                : new ArrayList<>(authenticationProviders);
    }

    public List<String> getDataSources() {
        return dataSources;
    }

    public void setDataSources(List<String> dataSources) {
        this.dataSources = dataSources == null ? new ArrayList<>() : new ArrayList<>(dataSources);
    }

    public String getGrafanaVersion() {
        return grafanaVersion;
    }

    public void setGrafanaVersion(String grafanaVersion) {
        this.grafanaVersion = grafanaVersion;
    }

    public String getWorkspaceRoleArn() {
        return workspaceRoleArn;
    }

    public void setWorkspaceRoleArn(String workspaceRoleArn) {
        this.workspaceRoleArn = workspaceRoleArn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public long getModified() {
        return modified;
    }

    public void setModified(long modified) {
        this.modified = modified;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(String configuration) {
        this.configuration = configuration;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public String getGrafanaToken() {
        return grafanaToken;
    }

    public void setGrafanaToken(String grafanaToken) {
        this.grafanaToken = grafanaToken;
    }

    public String getSamlStatus() {
        return samlStatus;
    }

    public void setSamlStatus(String samlStatus) {
        this.samlStatus = samlStatus;
    }

    public JsonNode getSamlConfiguration() {
        return samlConfiguration;
    }

    public void setSamlConfiguration(JsonNode samlConfiguration) {
        this.samlConfiguration = samlConfiguration;
    }

    public List<PermissionEntry> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<PermissionEntry> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : new ArrayList<>(permissions);
    }

    public Map<String, ServiceAccount> getServiceAccounts() {
        return serviceAccounts;
    }

    public void setServiceAccounts(Map<String, ServiceAccount> serviceAccounts) {
        this.serviceAccounts = serviceAccounts == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(serviceAccounts);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PermissionEntry {
        private String userId;
        private String userType;
        private String role;

        public PermissionEntry() {
        }

        public PermissionEntry(String userId, String userType, String role) {
            this.userId = userId;
            this.userType = userType;
            this.role = role;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUserType() {
            return userType;
        }

        public void setUserType(String userType) {
            this.userType = userType;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServiceAccount {
        private String id;
        private String name;
        private String grafanaRole;
        private String isDisabled = "false";
        private Map<String, Token> tokens = new LinkedHashMap<>();

        public ServiceAccount() {
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

        public String getGrafanaRole() {
            return grafanaRole;
        }

        public void setGrafanaRole(String grafanaRole) {
            this.grafanaRole = grafanaRole;
        }

        public String getIsDisabled() {
            return isDisabled;
        }

        public void setIsDisabled(String isDisabled) {
            this.isDisabled = isDisabled;
        }

        public Map<String, Token> getTokens() {
            return tokens;
        }

        public void setTokens(Map<String, Token> tokens) {
            this.tokens = tokens == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tokens);
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Token {
        private String id;
        private String name;
        private long createdAt;
        private long expiresAt;
        private String key;

        public Token() {
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

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }
}
