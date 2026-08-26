package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-account Amazon Inspector (Inspector2) settings for a Region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Inspector2Account {

    private String ecrRescanDuration = "DAYS_30";
    private String ec2ScanMode = "EC2_SSM_AGENT_BASED";
    private String deepInspectionStatus = "DISABLED";
    private String status = "DISABLED";
    private Map<String, String> encryptionKeys = new LinkedHashMap<>();
    private String delegatedAdminAccountId;
    private boolean maxAccountLimitReached;

    public Inspector2Account() {
    }

    public static Inspector2Account defaults() {
        return new Inspector2Account();
    }

    public String getEcrRescanDuration() {
        return ecrRescanDuration == null ? "DAYS_30" : ecrRescanDuration;
    }

    public void setEcrRescanDuration(String ecrRescanDuration) {
        this.ecrRescanDuration = ecrRescanDuration;
    }

    public String getEc2ScanMode() {
        return ec2ScanMode == null ? "EC2_SSM_AGENT_BASED" : ec2ScanMode;
    }

    public void setEc2ScanMode(String ec2ScanMode) {
        this.ec2ScanMode = ec2ScanMode;
    }

    public String getDeepInspectionStatus() {
        return deepInspectionStatus == null ? "DISABLED" : deepInspectionStatus;
    }

    public void setDeepInspectionStatus(String deepInspectionStatus) {
        this.deepInspectionStatus = deepInspectionStatus;
    }

    public String getStatus() {
        return status == null ? "DISABLED" : status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, String> getEncryptionKeys() {
        if (encryptionKeys == null) {
            encryptionKeys = new LinkedHashMap<>();
        }
        return encryptionKeys;
    }

    public void setEncryptionKeys(Map<String, String> encryptionKeys) {
        this.encryptionKeys = encryptionKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(encryptionKeys);
    }

    public String getDelegatedAdminAccountId() {
        return delegatedAdminAccountId;
    }

    public void setDelegatedAdminAccountId(String delegatedAdminAccountId) {
        this.delegatedAdminAccountId = delegatedAdminAccountId;
    }

    public boolean isMaxAccountLimitReached() {
        return maxAccountLimitReached;
    }

    public void setMaxAccountLimitReached(boolean maxAccountLimitReached) {
        this.maxAccountLimitReached = maxAccountLimitReached;
    }
}
