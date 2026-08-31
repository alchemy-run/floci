package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Data Automation library ingestion job. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestionJobRecord {

    private String jobArn;
    private String libraryArn;
    private String entityType;
    private String operationType;
    private String jobStatus;
    private String s3Uri;
    private String creationTime;
    private String completionTime;

    public IngestionJobRecord() {
    }

    public String getJobArn() {
        return jobArn;
    }

    public void setJobArn(String jobArn) {
        this.jobArn = jobArn;
    }

    public String getLibraryArn() {
        return libraryArn;
    }

    public void setLibraryArn(String libraryArn) {
        this.libraryArn = libraryArn;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(String jobStatus) {
        this.jobStatus = jobStatus;
    }

    public String getS3Uri() {
        return s3Uri;
    }

    public void setS3Uri(String s3Uri) {
        this.s3Uri = s3Uri;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }
}
