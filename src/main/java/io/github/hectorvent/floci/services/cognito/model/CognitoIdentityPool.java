package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CognitoIdentityPool {
    private String identityPoolId;
    private String identityPoolName;
    private boolean allowUnauthenticatedIdentities;
    private Boolean allowClassicFlow;
    private Map<String, String> supportedLoginProviders = new LinkedHashMap<>();
    private String developerProviderName;
    private List<String> openIdConnectProviderARNs = new ArrayList<>();
    private List<Map<String, Object>> cognitoIdentityProviders = new ArrayList<>();
    private List<String> samlProviderARNs = new ArrayList<>();
    private Map<String, String> identityPoolTags = new LinkedHashMap<>();
    private Map<String, String> roles = new LinkedHashMap<>();
    private Map<String, Object> roleMappings = new LinkedHashMap<>();

    public String getIdentityPoolId() { return identityPoolId; }
    public void setIdentityPoolId(String identityPoolId) { this.identityPoolId = identityPoolId; }

    public String getIdentityPoolName() { return identityPoolName; }
    public void setIdentityPoolName(String identityPoolName) { this.identityPoolName = identityPoolName; }

    public boolean isAllowUnauthenticatedIdentities() { return allowUnauthenticatedIdentities; }
    public void setAllowUnauthenticatedIdentities(boolean allowUnauthenticatedIdentities) {
        this.allowUnauthenticatedIdentities = allowUnauthenticatedIdentities;
    }

    public Boolean getAllowClassicFlow() { return allowClassicFlow; }
    public void setAllowClassicFlow(Boolean allowClassicFlow) { this.allowClassicFlow = allowClassicFlow; }

    public Map<String, String> getSupportedLoginProviders() { return supportedLoginProviders; }
    public void setSupportedLoginProviders(Map<String, String> supportedLoginProviders) {
        this.supportedLoginProviders = supportedLoginProviders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(supportedLoginProviders);
    }

    public String getDeveloperProviderName() { return developerProviderName; }
    public void setDeveloperProviderName(String developerProviderName) { this.developerProviderName = developerProviderName; }

    public List<String> getOpenIdConnectProviderARNs() { return openIdConnectProviderARNs; }
    public void setOpenIdConnectProviderARNs(List<String> openIdConnectProviderARNs) {
        this.openIdConnectProviderARNs = openIdConnectProviderARNs == null ? new ArrayList<>() : new ArrayList<>(openIdConnectProviderARNs);
    }

    public List<Map<String, Object>> getCognitoIdentityProviders() { return cognitoIdentityProviders; }
    public void setCognitoIdentityProviders(List<Map<String, Object>> cognitoIdentityProviders) {
        this.cognitoIdentityProviders = cognitoIdentityProviders == null ? new ArrayList<>() : new ArrayList<>(cognitoIdentityProviders);
    }

    public List<String> getSamlProviderARNs() { return samlProviderARNs; }
    public void setSamlProviderARNs(List<String> samlProviderARNs) {
        this.samlProviderARNs = samlProviderARNs == null ? new ArrayList<>() : new ArrayList<>(samlProviderARNs);
    }

    public Map<String, String> getIdentityPoolTags() { return identityPoolTags; }
    public void setIdentityPoolTags(Map<String, String> identityPoolTags) {
        this.identityPoolTags = identityPoolTags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(identityPoolTags);
    }

    public Map<String, String> getRoles() { return roles; }
    public void setRoles(Map<String, String> roles) {
        this.roles = roles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roles);
    }

    public Map<String, Object> getRoleMappings() { return roleMappings; }
    public void setRoleMappings(Map<String, Object> roleMappings) {
        this.roleMappings = roleMappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(roleMappings);
    }
}
