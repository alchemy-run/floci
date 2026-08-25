package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestoreJob {

    @JsonProperty("RestoreJobId")
    private String restoreJobId;

    @JsonProperty("RecoveryPointArn")
    private String recoveryPointArn;

    @JsonProperty("SourceResourceArn")
    private String sourceResourceArn;

    @JsonProperty("BackupVaultArn")
    private String backupVaultArn;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("PercentDone")
    private String percentDone;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("CreatedResourceArn")
    private String createdResourceArn;

    @JsonProperty("ValidationStatus")
    private String validationStatus;

    @JsonProperty("ValidationStatusMessage")
    private String validationStatusMessage;

    @JsonIgnore
    private Map<String, String> metadata = new HashMap<>();

    public RestoreJob() {}

    public String getRestoreJobId() { return restoreJobId; }
    public void setRestoreJobId(String restoreJobId) { this.restoreJobId = restoreJobId; }

    public String getRecoveryPointArn() { return recoveryPointArn; }
    public void setRecoveryPointArn(String recoveryPointArn) { this.recoveryPointArn = recoveryPointArn; }

    public String getSourceResourceArn() { return sourceResourceArn; }
    public void setSourceResourceArn(String sourceResourceArn) { this.sourceResourceArn = sourceResourceArn; }

    public String getBackupVaultArn() { return backupVaultArn; }
    public void setBackupVaultArn(String backupVaultArn) { this.backupVaultArn = backupVaultArn; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public String getPercentDone() { return percentDone; }
    public void setPercentDone(String percentDone) { this.percentDone = percentDone; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getCreatedResourceArn() { return createdResourceArn; }
    public void setCreatedResourceArn(String createdResourceArn) { this.createdResourceArn = createdResourceArn; }

    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }

    public String getValidationStatusMessage() { return validationStatusMessage; }
    public void setValidationStatusMessage(String validationStatusMessage) {
        this.validationStatusMessage = validationStatusMessage;
    }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}
