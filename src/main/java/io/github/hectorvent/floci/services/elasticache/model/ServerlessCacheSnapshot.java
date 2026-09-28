package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A serverless cache snapshot, including the keys captured from the cache it was taken from. */
@RegisterForReflection
public class ServerlessCacheSnapshot {

    private String serverlessCacheSnapshotName;
    private String arn;
    private String accountId;
    private String region;
    private String kmsKeyId;
    private String snapshotType;
    private String status;
    private Instant createTime;
    private Instant expiryTime;
    private long bytesUsedForCache;
    private String serverlessCacheName;
    private String engine;
    private String majorEngineVersion;
    private List<ServerlessCacheSnapshotEntry> entries = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServerlessCacheSnapshot() {
    }

    public ServerlessCacheSnapshot copy() {
        ServerlessCacheSnapshot copy = new ServerlessCacheSnapshot();
        copy.serverlessCacheSnapshotName = serverlessCacheSnapshotName;
        copy.arn = arn;
        copy.accountId = accountId;
        copy.region = region;
        copy.kmsKeyId = kmsKeyId;
        copy.snapshotType = snapshotType;
        copy.status = status;
        copy.createTime = createTime;
        copy.expiryTime = expiryTime;
        copy.bytesUsedForCache = bytesUsedForCache;
        copy.serverlessCacheName = serverlessCacheName;
        copy.engine = engine;
        copy.majorEngineVersion = majorEngineVersion;
        copy.entries = new ArrayList<>(entries);
        copy.tags = new LinkedHashMap<>(tags);
        return copy;
    }

    public String getServerlessCacheSnapshotName() { return serverlessCacheSnapshotName; }
    public void setServerlessCacheSnapshotName(String serverlessCacheSnapshotName) {
        this.serverlessCacheSnapshotName = serverlessCacheSnapshotName;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Instant getExpiryTime() { return expiryTime; }
    public void setExpiryTime(Instant expiryTime) { this.expiryTime = expiryTime; }

    public long getBytesUsedForCache() { return bytesUsedForCache; }
    public void setBytesUsedForCache(long bytesUsedForCache) { this.bytesUsedForCache = bytesUsedForCache; }

    public String getServerlessCacheName() { return serverlessCacheName; }
    public void setServerlessCacheName(String serverlessCacheName) { this.serverlessCacheName = serverlessCacheName; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getMajorEngineVersion() { return majorEngineVersion; }
    public void setMajorEngineVersion(String majorEngineVersion) { this.majorEngineVersion = majorEngineVersion; }

    public List<ServerlessCacheSnapshotEntry> getEntries() { return entries; }
    public void setEntries(List<ServerlessCacheSnapshotEntry> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
