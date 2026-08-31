package io.github.hectorvent.floci.services.dsql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Aurora DSQL cluster. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Cluster {

    private String identifier;
    private String arn;
    private String status;
    private long creationTime;
    private boolean deletionProtectionEnabled;
    private String kmsEncryptionKey;
    private String encryptionType;
    private String encryptionStatus;
    private String endpoint;
    private String region;
    private Map<String, String> tags;
    private String policy;
    private String policyVersion;
    private String witnessRegion;
    private List<String> linkedClusters;

    public Cluster() {
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public boolean isDeletionProtectionEnabled() {
        return deletionProtectionEnabled;
    }

    public void setDeletionProtectionEnabled(boolean deletionProtectionEnabled) {
        this.deletionProtectionEnabled = deletionProtectionEnabled;
    }

    public String getKmsEncryptionKey() {
        return kmsEncryptionKey;
    }

    public void setKmsEncryptionKey(String kmsEncryptionKey) {
        this.kmsEncryptionKey = kmsEncryptionKey;
    }

    public String getEncryptionType() {
        return encryptionType;
    }

    public void setEncryptionType(String encryptionType) {
        this.encryptionType = encryptionType;
    }

    public String getEncryptionStatus() {
        return encryptionStatus;
    }

    public void setEncryptionStatus(String encryptionStatus) {
        this.encryptionStatus = encryptionStatus;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getWitnessRegion() {
        return witnessRegion;
    }

    public void setWitnessRegion(String witnessRegion) {
        this.witnessRegion = witnessRegion;
    }

    public List<String> getLinkedClusters() {
        return linkedClusters;
    }

    public void setLinkedClusters(List<String> linkedClusters) {
        this.linkedClusters = linkedClusters == null ? null : new ArrayList<>(linkedClusters);
    }
}
