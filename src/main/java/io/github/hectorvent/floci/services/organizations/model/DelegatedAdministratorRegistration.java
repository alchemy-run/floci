package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DelegatedAdministratorRegistration {

    private String accountId;
    private String servicePrincipal;
    private long delegationEnabledDate;

    public DelegatedAdministratorRegistration() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getServicePrincipal() {
        return servicePrincipal;
    }

    public void setServicePrincipal(String servicePrincipal) {
        this.servicePrincipal = servicePrincipal;
    }

    public long getDelegationEnabledDate() {
        return delegationEnabledDate;
    }

    public void setDelegationEnabledDate(long delegationEnabledDate) {
        this.delegationEnabledDate = delegationEnabledDate;
    }
}
