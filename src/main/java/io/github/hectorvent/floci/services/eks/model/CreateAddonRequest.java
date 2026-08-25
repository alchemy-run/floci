package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAddonRequest {

    @JsonProperty("addonName")
    private String addonName;

    @JsonProperty("addonVersion")
    private String addonVersion;

    @JsonProperty("serviceAccountRoleArn")
    private String serviceAccountRoleArn;

    @JsonProperty("resolveConflicts")
    private String resolveConflicts;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("configurationValues")
    private String configurationValues;

    @JsonProperty("podIdentityAssociations")
    private List<Map<String, String>> podIdentityAssociations;

    @JsonProperty("namespaceConfig")
    private Addon.NamespaceConfig namespaceConfig;

    public CreateAddonRequest() {}

    public String getAddonName() { return addonName; }
    public void setAddonName(String addonName) { this.addonName = addonName; }

    public String getAddonVersion() { return addonVersion; }
    public void setAddonVersion(String addonVersion) { this.addonVersion = addonVersion; }

    public String getServiceAccountRoleArn() { return serviceAccountRoleArn; }
    public void setServiceAccountRoleArn(String serviceAccountRoleArn) {
        this.serviceAccountRoleArn = serviceAccountRoleArn;
    }

    public String getResolveConflicts() { return resolveConflicts; }
    public void setResolveConflicts(String resolveConflicts) { this.resolveConflicts = resolveConflicts; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getConfigurationValues() { return configurationValues; }
    public void setConfigurationValues(String configurationValues) {
        this.configurationValues = configurationValues;
    }

    public List<Map<String, String>> getPodIdentityAssociations() { return podIdentityAssociations; }
    public void setPodIdentityAssociations(List<Map<String, String>> podIdentityAssociations) {
        this.podIdentityAssociations = podIdentityAssociations;
    }

    public Addon.NamespaceConfig getNamespaceConfig() { return namespaceConfig; }
    public void setNamespaceConfig(Addon.NamespaceConfig namespaceConfig) {
        this.namespaceConfig = namespaceConfig;
    }
}
