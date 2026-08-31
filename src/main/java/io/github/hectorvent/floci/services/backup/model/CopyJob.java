package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopyJob {

    @JsonProperty("CopyJobId")
    private String copyJobId;

    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("SourceBackupVaultArn")
    private String sourceBackupVaultArn;

    @JsonProperty("SourceRecoveryPointArn")
    private String sourceRecoveryPointArn;

    @JsonProperty("DestinationBackupVaultArn")
    private String destinationBackupVaultArn;

    @JsonProperty("DestinationRecoveryPointArn")
    private String destinationRecoveryPointArn;

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("State")
    private String state;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    public CopyJob() {}

    public String getCopyJobId() { return copyJobId; }
    public void setCopyJobId(String copyJobId) { this.copyJobId = copyJobId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getSourceBackupVaultArn() { return sourceBackupVaultArn; }
    public void setSourceBackupVaultArn(String sourceBackupVaultArn) {
        this.sourceBackupVaultArn = sourceBackupVaultArn;
    }

    public String getSourceRecoveryPointArn() { return sourceRecoveryPointArn; }
    public void setSourceRecoveryPointArn(String sourceRecoveryPointArn) {
        this.sourceRecoveryPointArn = sourceRecoveryPointArn;
    }

    public String getDestinationBackupVaultArn() { return destinationBackupVaultArn; }
    public void setDestinationBackupVaultArn(String destinationBackupVaultArn) {
        this.destinationBackupVaultArn = destinationBackupVaultArn;
    }

    public String getDestinationRecoveryPointArn() { return destinationRecoveryPointArn; }
    public void setDestinationRecoveryPointArn(String destinationRecoveryPointArn) {
        this.destinationRecoveryPointArn = destinationRecoveryPointArn;
    }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }
}
