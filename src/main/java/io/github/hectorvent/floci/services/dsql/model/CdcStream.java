package io.github.hectorvent.floci.services.dsql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Aurora DSQL change-data-capture stream that delivers into Kinesis. */
@RegisterForReflection
public class CdcStream {

    private String clusterIdentifier;
    private String streamIdentifier;
    private String arn;
    private String status;
    private long creationTime;
    private String ordering;
    private String format;
    private String kinesisStreamArn;
    private String roleArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CdcStream() {
    }

    public String getClusterIdentifier() {
        return clusterIdentifier;
    }

    public void setClusterIdentifier(String clusterIdentifier) {
        this.clusterIdentifier = clusterIdentifier;
    }

    public String getStreamIdentifier() {
        return streamIdentifier;
    }

    public void setStreamIdentifier(String streamIdentifier) {
        this.streamIdentifier = streamIdentifier;
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

    public String getOrdering() {
        return ordering;
    }

    public void setOrdering(String ordering) {
        this.ordering = ordering;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getKinesisStreamArn() {
        return kinesisStreamArn;
    }

    public void setKinesisStreamArn(String kinesisStreamArn) {
        this.kinesisStreamArn = kinesisStreamArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
