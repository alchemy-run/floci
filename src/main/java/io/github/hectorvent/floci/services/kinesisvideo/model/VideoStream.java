package io.github.hectorvent.floci.services.kinesisvideo.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Kinesis Video Streams stream. Wire names are UpperCamelCase
 * ({@code StreamARN}, {@code DataRetentionInHours}, …).
 */
@RegisterForReflection
public class VideoStream {

    private String streamName;
    private String streamArn;
    private String deviceName;
    private String mediaType;
    private String kmsKeyId;
    private String version;
    private String status;
    private long creationTimeEpochSeconds;
    private long creationTimeMillis;
    private int dataRetentionInHours;
    private Map<String, String> tags = new LinkedHashMap<>();

    public VideoStream() {
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public String getStreamArn() {
        return streamArn;
    }

    public void setStreamArn(String streamArn) {
        this.streamArn = streamArn;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreationTimeEpochSeconds() {
        return creationTimeEpochSeconds;
    }

    public void setCreationTimeEpochSeconds(long creationTimeEpochSeconds) {
        this.creationTimeEpochSeconds = creationTimeEpochSeconds;
    }

    public long getCreationTime() {
        return creationTimeEpochSeconds;
    }

    public void setCreationTime(long creationTimeEpochSeconds) {
        this.creationTimeEpochSeconds = creationTimeEpochSeconds;
    }

    public long getCreationTimeMillis() {
        return creationTimeMillis;
    }

    public void setCreationTimeMillis(long creationTimeMillis) {
        this.creationTimeMillis = creationTimeMillis;
    }

    public int getDataRetentionInHours() {
        return dataRetentionInHours;
    }

    public void setDataRetentionInHours(int dataRetentionInHours) {
        this.dataRetentionInHours = dataRetentionInHours;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
