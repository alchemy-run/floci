package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrustStore {

    private String name;
    private String trustStoreArn;
    private String status;
    private int numberOfCaCertificates;
    private int totalRevokedEntries;
    private String caCertificatesBundleS3Bucket;
    private String caCertificatesBundleS3Key;
    private String caCertificatesBundleS3ObjectVersion;
    private String region;
    private List<TrustStoreRevocation> revocations = new ArrayList<>();

    public TrustStore() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTrustStoreArn() { return trustStoreArn; }
    public void setTrustStoreArn(String trustStoreArn) { this.trustStoreArn = trustStoreArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getNumberOfCaCertificates() { return numberOfCaCertificates; }
    public void setNumberOfCaCertificates(int numberOfCaCertificates) {
        this.numberOfCaCertificates = numberOfCaCertificates;
    }

    public int getTotalRevokedEntries() { return totalRevokedEntries; }
    public void setTotalRevokedEntries(int totalRevokedEntries) {
        this.totalRevokedEntries = totalRevokedEntries;
    }

    public String getCaCertificatesBundleS3Bucket() { return caCertificatesBundleS3Bucket; }
    public void setCaCertificatesBundleS3Bucket(String caCertificatesBundleS3Bucket) {
        this.caCertificatesBundleS3Bucket = caCertificatesBundleS3Bucket;
    }

    public String getCaCertificatesBundleS3Key() { return caCertificatesBundleS3Key; }
    public void setCaCertificatesBundleS3Key(String caCertificatesBundleS3Key) {
        this.caCertificatesBundleS3Key = caCertificatesBundleS3Key;
    }

    public String getCaCertificatesBundleS3ObjectVersion() { return caCertificatesBundleS3ObjectVersion; }
    public void setCaCertificatesBundleS3ObjectVersion(String caCertificatesBundleS3ObjectVersion) {
        this.caCertificatesBundleS3ObjectVersion = caCertificatesBundleS3ObjectVersion;
    }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<TrustStoreRevocation> getRevocations() { return revocations; }
    public void setRevocations(List<TrustStoreRevocation> revocations) {
        this.revocations = revocations != null ? revocations : new ArrayList<>();
    }
}
