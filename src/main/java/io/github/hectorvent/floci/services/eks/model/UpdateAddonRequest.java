package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateAddonRequest {

    @JsonProperty("addonVersion")
    private String addonVersion;

    @JsonProperty("serviceAccountRoleArn")
    private String serviceAccountRoleArn;

    @JsonProperty("resolveConflicts")
    private String resolveConflicts;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("configurationValues")
    private String configurationValues;

    @JsonProperty("podIdentityAssociations")
    private List<Map<String, String>> podIdentityAssociations;

    public UpdateAddonRequest() {}

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

    public String getConfigurationValues() { return configurationValues; }
    public void setConfigurationValues(String configurationValues) {
        this.configurationValues = configurationValues;
    }

    public List<Map<String, String>> getPodIdentityAssociations() { return podIdentityAssociations; }
    public void setPodIdentityAssociations(List<Map<String, String>> podIdentityAssociations) {
        this.podIdentityAssociations = podIdentityAssociations;
    }
}
