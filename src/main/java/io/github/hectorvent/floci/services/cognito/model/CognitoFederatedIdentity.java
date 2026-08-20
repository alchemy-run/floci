package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CognitoFederatedIdentity {
    private String identityId;
    private String identityPoolId;
    private List<String> logins = new ArrayList<>();
    private long creationDate;
    private long lastModifiedDate;

    public CognitoFederatedIdentity() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getIdentityId() { return identityId; }
    public void setIdentityId(String identityId) { this.identityId = identityId; }

    public String getIdentityPoolId() { return identityPoolId; }
    public void setIdentityPoolId(String identityPoolId) { this.identityPoolId = identityPoolId; }

    public List<String> getLogins() { return logins; }
    public void setLogins(List<String> logins) {
        this.logins = logins == null ? new ArrayList<>() : new ArrayList<>(logins);
    }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
