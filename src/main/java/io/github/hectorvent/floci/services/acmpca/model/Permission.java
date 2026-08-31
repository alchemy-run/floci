package io.github.hectorvent.floci.services.acmpca.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Permission granted on a private CA to a service principal.
 *
 * @see <a href="https://docs.aws.amazon.com/privateca/latest/APIReference/API_Permission.html">Permission</a>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Permission {
    private String certificateAuthorityArn;
    private long createdAt;
    private String principal;
    private String sourceAccount;
    private List<String> actions = new ArrayList<>();
    private String policy;

    public Permission() {
    }

    public String getCertificateAuthorityArn() {
        return certificateAuthorityArn;
    }

    public void setCertificateAuthorityArn(String certificateAuthorityArn) {
        this.certificateAuthorityArn = certificateAuthorityArn;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public void setSourceAccount(String sourceAccount) {
        this.sourceAccount = sourceAccount;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }
}
