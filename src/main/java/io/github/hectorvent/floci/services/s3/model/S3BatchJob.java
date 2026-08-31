package io.github.hectorvent.floci.services.s3.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * S3 Batch Operations job (S3 Control CreateJob / DescribeJob / ListJobs).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3BatchJob {

    private String jobId;
    private String jobArn;
    private String clientRequestToken;
    private String status;
    private int priority;
    private boolean confirmationRequired;
    private String roleArn;
    private String operation;
    private String description;
    private String statusUpdateReason;
    private boolean reportEnabled;
    private String sourceBucket;
    private Instant creationTime;
    private Instant terminationDate;
    private Instant suspendedDate;
    private String suspendedCause;

    public S3BatchJob() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getJobArn() { return jobArn; }
    public void setJobArn(String jobArn) { this.jobArn = jobArn; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) {
        this.clientRequestToken = clientRequestToken;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isConfirmationRequired() { return confirmationRequired; }
    public void setConfirmationRequired(boolean confirmationRequired) {
        this.confirmationRequired = confirmationRequired;
    }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatusUpdateReason() { return statusUpdateReason; }
    public void setStatusUpdateReason(String statusUpdateReason) {
        this.statusUpdateReason = statusUpdateReason;
    }

    public boolean isReportEnabled() { return reportEnabled; }
    public void setReportEnabled(boolean reportEnabled) { this.reportEnabled = reportEnabled; }

    public String getSourceBucket() { return sourceBucket; }
    public void setSourceBucket(String sourceBucket) { this.sourceBucket = sourceBucket; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Instant getTerminationDate() { return terminationDate; }
    public void setTerminationDate(Instant terminationDate) { this.terminationDate = terminationDate; }

    public Instant getSuspendedDate() { return suspendedDate; }
    public void setSuspendedDate(Instant suspendedDate) { this.suspendedDate = suspendedDate; }

    public String getSuspendedCause() { return suspendedCause; }
    public void setSuspendedCause(String suspendedCause) { this.suspendedCause = suspendedCause; }
}
