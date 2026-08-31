package io.github.hectorvent.floci.services.acmpca.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An ACM PCA audit report written to S3.
 *
 * @see <a href="https://docs.aws.amazon.com/privateca/latest/APIReference/API_CreateCertificateAuthorityAuditReport.html">CreateCertificateAuthorityAuditReport</a>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditReport {
    private String auditReportId;
    private String s3BucketName;
    private String s3Key;
    private String status;
    private String format;
    private long createdAt;

    public AuditReport() {
    }

    public String getAuditReportId() {
        return auditReportId;
    }

    public void setAuditReportId(String auditReportId) {
        this.auditReportId = auditReportId;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
