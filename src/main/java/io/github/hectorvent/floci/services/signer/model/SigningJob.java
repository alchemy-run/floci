package io.github.hectorvent.floci.services.signer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class SigningJob {

    public String accountId;
    public String region;
    public String jobId;
    public String jobOwner;
    public String jobInvoker;
    public String clientRequestToken;
    public String profileName;
    public String profileVersion;
    public String platformId;
    public String platformDisplayName;
    public String status;
    public String statusReason;
    public String sourceBucket;
    public String sourceKey;
    public String sourceVersion;
    public String destBucket;
    public String destPrefix;
    public String signedKey;
    public long createdAt;
    public Long completedAt;
    public Long signatureExpiresAt;
    public boolean revoked;
    public String revokeReason;
    public Long revokedAt;
    public String revokedBy;
    public byte[] signature;

    public SigningJob() {
    }
}
