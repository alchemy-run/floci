package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A managed login branding style: the per-app-client visual configuration of the
 * Cognito managed login (hosted UI v2) pages. Exactly one style may be assigned to
 * an app client.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedLoginBranding {
    private String userPoolId;
    private String managedLoginBrandingId;
    private String clientId;
    private boolean useCognitoProvidedValues;
    /** The designer settings document, as sent by the caller (null when using provided values). */
    private Map<String, Object> settings;
    /** Asset entries as sent by the caller: Category, ColorMode, Extension, Bytes (base64), ResourceId. */
    private List<Map<String, Object>> assets = new ArrayList<>();
    private long creationDate;
    private long lastModifiedDate;

    public ManagedLoginBranding() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getManagedLoginBrandingId() { return managedLoginBrandingId; }
    public void setManagedLoginBrandingId(String managedLoginBrandingId) { this.managedLoginBrandingId = managedLoginBrandingId; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public boolean isUseCognitoProvidedValues() { return useCognitoProvidedValues; }
    public void setUseCognitoProvidedValues(boolean useCognitoProvidedValues) { this.useCognitoProvidedValues = useCognitoProvidedValues; }

    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = settings; }

    public List<Map<String, Object>> getAssets() { return assets; }
    public void setAssets(List<Map<String, Object>> assets) { this.assets = assets != null ? assets : new ArrayList<>(); }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
