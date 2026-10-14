package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One key captured from a serverless cache: the key and value bytes (Base64), the absolute expiry
 * in epoch milliseconds ({@code null} when the key never expires) and, for Memcached, the item's
 * client flags. For Valkey and Redis OSS the value is the engine's {@code DUMP} payload.
 */
@RegisterForReflection
public class ServerlessCacheSnapshotEntry {

    private String key;
    private String value;
    private Long expiresAtMillis;
    private long flags;

    public ServerlessCacheSnapshotEntry() {
    }

    public ServerlessCacheSnapshotEntry(String key, String value, Long expiresAtMillis, long flags) {
        this.key = key;
        this.value = value;
        this.expiresAtMillis = expiresAtMillis;
        this.flags = flags;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Long getExpiresAtMillis() { return expiresAtMillis; }
    public void setExpiresAtMillis(Long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }

    public long getFlags() { return flags; }
    public void setFlags(long flags) { this.flags = flags; }
}
