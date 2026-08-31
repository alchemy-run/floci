package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProtectedResource {

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("LastBackupTime")
    private Long lastBackupTime;

    @JsonProperty("LastBackupVaultArn")
    private String lastBackupVaultArn;

    @JsonProperty("LastRecoveryPointArn")
    private String lastRecoveryPointArn;

    public ProtectedResource() {}

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public Long getLastBackupTime() { return lastBackupTime; }
    public void setLastBackupTime(Long lastBackupTime) { this.lastBackupTime = lastBackupTime; }

    public String getLastBackupVaultArn() { return lastBackupVaultArn; }
    public void setLastBackupVaultArn(String lastBackupVaultArn) {
        this.lastBackupVaultArn = lastBackupVaultArn;
    }

    public String getLastRecoveryPointArn() { return lastRecoveryPointArn; }
    public void setLastRecoveryPointArn(String lastRecoveryPointArn) {
        this.lastRecoveryPointArn = lastRecoveryPointArn;
    }
}
