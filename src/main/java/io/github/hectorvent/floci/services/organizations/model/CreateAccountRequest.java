package io.github.hectorvent.floci.services.organizations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Asynchronous {@code CreateAccount} handshake. Local creates settle to
 * {@code SUCCEEDED} immediately so Alchemy's poll loop does not stall.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAccountRequest {

    private String id;
    private String accountName;
    private String state;
    private String accountId;
    private String failureReason;
    private long requestedTimestamp;
    private long completedTimestamp;

    public CreateAccountRequest() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public long getRequestedTimestamp() {
        return requestedTimestamp;
    }

    public void setRequestedTimestamp(long requestedTimestamp) {
        this.requestedTimestamp = requestedTimestamp;
    }

    public long getCompletedTimestamp() {
        return completedTimestamp;
    }

    public void setCompletedTimestamp(long completedTimestamp) {
        this.completedTimestamp = completedTimestamp;
    }
}
