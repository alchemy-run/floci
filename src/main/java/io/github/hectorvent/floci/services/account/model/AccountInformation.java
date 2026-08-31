package io.github.hectorvent.floci.services.account.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Per-account display name and identity returned by {@code GetAccountInformation}.
 */
@RegisterForReflection
public class AccountInformation {

    private String accountId;
    private String accountName;
    private String accountCreatedDate;
    private String accountState;

    public AccountInformation() {
    }

    public AccountInformation(String accountId, String accountName, String accountCreatedDate, String accountState) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.accountCreatedDate = accountCreatedDate;
        this.accountState = accountState;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountCreatedDate() {
        return accountCreatedDate;
    }

    public void setAccountCreatedDate(String accountCreatedDate) {
        this.accountCreatedDate = accountCreatedDate;
    }

    public String getAccountState() {
        return accountState;
    }

    public void setAccountState(String accountState) {
        this.accountState = accountState;
    }
}
