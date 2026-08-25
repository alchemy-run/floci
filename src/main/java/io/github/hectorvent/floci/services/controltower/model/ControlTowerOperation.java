package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An asynchronous Control Tower landing-zone, baseline, or control operation. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ControlTowerOperation {

    public static final String FAMILY_LANDING_ZONE = "LANDING_ZONE";
    public static final String FAMILY_BASELINE = "BASELINE";
    public static final String FAMILY_CONTROL = "CONTROL";

    private String operationIdentifier;
    private String family;
    private String operationType;
    private String status;
    private String startTime;
    private String endTime;
    private String statusMessage;
    private String controlIdentifier;
    private String targetIdentifier;
    private String enabledControlIdentifier;
    private String accountId;
    private String region;

    public ControlTowerOperation() {
    }

    public String getOperationIdentifier() {
        return operationIdentifier;
    }

    public void setOperationIdentifier(String operationIdentifier) {
        this.operationIdentifier = operationIdentifier;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getControlIdentifier() {
        return controlIdentifier;
    }

    public void setControlIdentifier(String controlIdentifier) {
        this.controlIdentifier = controlIdentifier;
    }

    public String getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setTargetIdentifier(String targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public String getEnabledControlIdentifier() {
        return enabledControlIdentifier;
    }

    public void setEnabledControlIdentifier(String enabledControlIdentifier) {
        this.enabledControlIdentifier = enabledControlIdentifier;
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
}
