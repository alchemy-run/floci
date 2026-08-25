package io.github.hectorvent.floci.services.neptunegraph.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Neptune Analytics graph snapshot. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphSnapshot {

    private String id;
    private String name;
    private String arn;
    private String sourceGraphId;
    private long snapshotCreateTime;
    private String status;
    private String kmsKeyIdentifier;
    private String region;
    private Map<String, String> tags;

    public GraphSnapshot() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getSourceGraphId() {
        return sourceGraphId;
    }

    public void setSourceGraphId(String sourceGraphId) {
        this.sourceGraphId = sourceGraphId;
    }

    public long getSnapshotCreateTime() {
        return snapshotCreateTime;
    }

    public void setSnapshotCreateTime(long snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getKmsKeyIdentifier() {
        return kmsKeyIdentifier;
    }

    public void setKmsKeyIdentifier(String kmsKeyIdentifier) {
        this.kmsKeyIdentifier = kmsKeyIdentifier;
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
}
