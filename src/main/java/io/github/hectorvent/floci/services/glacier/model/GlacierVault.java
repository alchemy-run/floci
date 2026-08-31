package io.github.hectorvent.floci.services.glacier.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon S3 Glacier vault and its archives, jobs, and multipart uploads. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlacierVault {

    private String vaultName;
    private String vaultArn;
    private String creationDate;
    private String lastInventoryDate;
    private String region;
    private String accountId;
    private long numberOfArchives;
    private long sizeInBytes;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accessPolicy;
    private String notificationSnsTopic;
    private List<String> notificationEvents = new ArrayList<>();
    private String lockPolicy;
    private String lockState;
    private String lockId;
    private String lockCreationDate;
    private String lockExpirationDate;
    private Map<String, GlacierArchive> archives = new LinkedHashMap<>();
    private Map<String, GlacierJob> jobs = new LinkedHashMap<>();
    private Map<String, GlacierMultipartUpload> uploads = new LinkedHashMap<>();

    public GlacierVault() {
    }

    public String getVaultName() {
        return vaultName;
    }

    public void setVaultName(String vaultName) {
        this.vaultName = vaultName;
    }

    public String getVaultArn() {
        return vaultArn;
    }

    public void setVaultArn(String vaultArn) {
        this.vaultArn = vaultArn;
    }

    public String getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }

    public String getLastInventoryDate() {
        return lastInventoryDate;
    }

    public void setLastInventoryDate(String lastInventoryDate) {
        this.lastInventoryDate = lastInventoryDate;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public long getNumberOfArchives() {
        return numberOfArchives;
    }

    public void setNumberOfArchives(long numberOfArchives) {
        this.numberOfArchives = numberOfArchives;
    }

    public long getSizeInBytes() {
        return sizeInBytes;
    }

    public void setSizeInBytes(long sizeInBytes) {
        this.sizeInBytes = sizeInBytes;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getAccessPolicy() {
        return accessPolicy;
    }

    public void setAccessPolicy(String accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public String getNotificationSnsTopic() {
        return notificationSnsTopic;
    }

    public void setNotificationSnsTopic(String notificationSnsTopic) {
        this.notificationSnsTopic = notificationSnsTopic;
    }

    public List<String> getNotificationEvents() {
        return notificationEvents;
    }

    public void setNotificationEvents(List<String> notificationEvents) {
        this.notificationEvents = notificationEvents == null
                ? new ArrayList<>()
                : new ArrayList<>(notificationEvents);
    }

    public String getLockPolicy() {
        return lockPolicy;
    }

    public void setLockPolicy(String lockPolicy) {
        this.lockPolicy = lockPolicy;
    }

    public String getLockState() {
        return lockState;
    }

    public void setLockState(String lockState) {
        this.lockState = lockState;
    }

    public String getLockId() {
        return lockId;
    }

    public void setLockId(String lockId) {
        this.lockId = lockId;
    }

    public String getLockCreationDate() {
        return lockCreationDate;
    }

    public void setLockCreationDate(String lockCreationDate) {
        this.lockCreationDate = lockCreationDate;
    }

    public String getLockExpirationDate() {
        return lockExpirationDate;
    }

    public void setLockExpirationDate(String lockExpirationDate) {
        this.lockExpirationDate = lockExpirationDate;
    }

    public Map<String, GlacierArchive> getArchives() {
        return archives;
    }

    public void setArchives(Map<String, GlacierArchive> archives) {
        this.archives = archives == null ? new LinkedHashMap<>() : new LinkedHashMap<>(archives);
    }

    public Map<String, GlacierJob> getJobs() {
        return jobs;
    }

    public void setJobs(Map<String, GlacierJob> jobs) {
        this.jobs = jobs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(jobs);
    }

    public Map<String, GlacierMultipartUpload> getUploads() {
        return uploads;
    }

    public void setUploads(Map<String, GlacierMultipartUpload> uploads) {
        this.uploads = uploads == null ? new LinkedHashMap<>() : new LinkedHashMap<>(uploads);
    }
}
