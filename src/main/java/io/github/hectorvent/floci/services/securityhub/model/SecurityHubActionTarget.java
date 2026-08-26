package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Security Hub custom action target. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecurityHubActionTarget {

    private String accountId;
    private String region;
    private String actionTargetArn;
    private String id;
    private String name;
    private String description;

    public SecurityHubActionTarget() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getActionTargetArn() {
        return actionTargetArn;
    }

    public void setActionTargetArn(String actionTargetArn) {
        this.actionTargetArn = actionTargetArn;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
