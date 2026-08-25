package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * EKS managed add-on. Wire shape for CreateAddon / DescribeAddon / DeleteAddon.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Addon {

    @JsonProperty("addonName")
    private String addonName;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("status")
    private AddonStatus status;

    @JsonProperty("addonVersion")
    private String addonVersion;

    @JsonProperty("health")
    private Map<String, Object> health;

    @JsonProperty("addonArn")
    private String addonArn;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("modifiedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant modifiedAt;

    @JsonProperty("serviceAccountRoleArn")
    private String serviceAccountRoleArn;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("publisher")
    private String publisher;

    @JsonProperty("owner")
    private String owner;

    @JsonProperty("configurationValues")
    private String configurationValues;

    @JsonProperty("podIdentityAssociations")
    private List<String> podIdentityAssociations;

    @JsonProperty("namespaceConfig")
    private NamespaceConfig namespaceConfig;

    @JsonIgnore
    private String accountId;

    public Addon() {}

    public String getAddonName() { return addonName; }
    public void setAddonName(String addonName) { this.addonName = addonName; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public AddonStatus getStatus() { return status; }
    public void setStatus(AddonStatus status) { this.status = status; }

    public String getAddonVersion() { return addonVersion; }
    public void setAddonVersion(String addonVersion) { this.addonVersion = addonVersion; }

    public Map<String, Object> getHealth() { return health; }
    public void setHealth(Map<String, Object> health) { this.health = health; }

    public String getAddonArn() { return addonArn; }
    public void setAddonArn(String addonArn) { this.addonArn = addonArn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public String getServiceAccountRoleArn() { return serviceAccountRoleArn; }
    public void setServiceAccountRoleArn(String serviceAccountRoleArn) {
        this.serviceAccountRoleArn = serviceAccountRoleArn;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getConfigurationValues() { return configurationValues; }
    public void setConfigurationValues(String configurationValues) {
        this.configurationValues = configurationValues;
    }

    public List<String> getPodIdentityAssociations() { return podIdentityAssociations; }
    public void setPodIdentityAssociations(List<String> podIdentityAssociations) {
        this.podIdentityAssociations = podIdentityAssociations;
    }

    public NamespaceConfig getNamespaceConfig() { return namespaceConfig; }
    public void setNamespaceConfig(NamespaceConfig namespaceConfig) {
        this.namespaceConfig = namespaceConfig;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NamespaceConfig {
        @JsonProperty("namespace")
        private String namespace;

        public NamespaceConfig() {}

        public NamespaceConfig(String namespace) {
            this.namespace = namespace;
        }

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
    }
}
