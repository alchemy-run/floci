package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class ServerlessCacheSnapshot {

    private String serverlessCacheSnapshotName;
    private String arn;
    private String kmsKeyId;
    private String snapshotType;
    private String status;
    private Instant createTime;
    private String serverlessCacheName;
    private String engine;
    private String majorEngineVersion;
    private String bytesUsedForCache;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServerlessCacheSnapshot() {}

    public String getServerlessCacheSnapshotName() { return serverlessCacheSnapshotName; }
    public void setServerlessCacheSnapshotName(String serverlessCacheSnapshotName) {
        this.serverlessCacheSnapshotName = serverlessCacheSnapshotName;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getServerlessCacheName() { return serverlessCacheName; }
    public void setServerlessCacheName(String serverlessCacheName) { this.serverlessCacheName = serverlessCacheName; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getMajorEngineVersion() { return majorEngineVersion; }
    public void setMajorEngineVersion(String majorEngineVersion) { this.majorEngineVersion = majorEngineVersion; }

    public String getBytesUsedForCache() { return bytesUsedForCache; }
    public void setBytesUsedForCache(String bytesUsedForCache) { this.bytesUsedForCache = bytesUsedForCache; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
