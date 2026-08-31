package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Amazon Inspector2 scan enablement for one account in a Region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountStatus {

    private String accountId;
    private Map<String, String> resourceStatus = new LinkedHashMap<>();

    public AccountStatus() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Map<String, String> getResourceStatus() {
        return resourceStatus;
    }

    public void setResourceStatus(Map<String, String> resourceStatus) {
        this.resourceStatus = resourceStatus != null ? resourceStatus : new LinkedHashMap<>();
    }
}
