package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A member account of a Detective behavior graph. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Member {

    private String accountId;
    private String emailAddress;
    private String graphArn;
    private String administratorId;
    private String status;
    private String invitationType;
    private String invitedTime;
    private String updatedTime;
    private String disabledReason;

    public Member() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getGraphArn() {
        return graphArn;
    }

    public void setGraphArn(String graphArn) {
        this.graphArn = graphArn;
    }

    public String getAdministratorId() {
        return administratorId;
    }

    public void setAdministratorId(String administratorId) {
        this.administratorId = administratorId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInvitationType() {
        return invitationType;
    }

    public void setInvitationType(String invitationType) {
        this.invitationType = invitationType;
    }

    public String getInvitedTime() {
        return invitedTime;
    }

    public void setInvitedTime(String invitedTime) {
        this.invitedTime = invitedTime;
    }

    public String getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(String updatedTime) {
        this.updatedTime = updatedTime;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }
}
