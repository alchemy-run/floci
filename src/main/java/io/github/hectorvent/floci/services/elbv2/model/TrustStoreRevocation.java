package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrustStoreRevocation {

    private long revocationId;
    private String revocationType;
    private String s3Bucket;
    private String s3Key;
    private String s3ObjectVersion;
    private int numberOfRevokedEntries;

    public TrustStoreRevocation() {}

    public long getRevocationId() { return revocationId; }
    public void setRevocationId(long revocationId) { this.revocationId = revocationId; }

    public String getRevocationType() { return revocationType; }
    public void setRevocationType(String revocationType) { this.revocationType = revocationType; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }

    public String getS3ObjectVersion() { return s3ObjectVersion; }
    public void setS3ObjectVersion(String s3ObjectVersion) { this.s3ObjectVersion = s3ObjectVersion; }

    public int getNumberOfRevokedEntries() { return numberOfRevokedEntries; }
    public void setNumberOfRevokedEntries(int numberOfRevokedEntries) {
        this.numberOfRevokedEntries = numberOfRevokedEntries;
    }
}
