package io.github.hectorvent.floci.services.kinesisvideo.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Kinesis Video signaling channel. Wire names are PascalCase.
 */
@RegisterForReflection
public class SignalingChannel {

    private String channelName;
    private String channelArn;
    private String channelType;
    private String channelStatus;
    private long creationTimeEpochSeconds;
    private long creationTimeMillis;
    private int messageTtlSeconds;
    private String version;
    private Map<String, String> tags = new LinkedHashMap<>();
    private boolean mediaStorageConfigured;

    public SignalingChannel() {
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getChannelArn() {
        return channelArn;
    }

    public void setChannelArn(String channelArn) {
        this.channelArn = channelArn;
    }

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    public String getChannelStatus() {
        return channelStatus;
    }

    public void setChannelStatus(String channelStatus) {
        this.channelStatus = channelStatus;
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

    public boolean isMediaStorageConfigured() {
        return mediaStorageConfigured;
    }

    public void setMediaStorageConfigured(boolean mediaStorageConfigured) {
        this.mediaStorageConfigured = mediaStorageConfigured;
    }

    public long getCreationTimeMillis() {
        return creationTimeMillis;
    }

    public void setCreationTimeMillis(long creationTimeMillis) {
        this.creationTimeMillis = creationTimeMillis;
    }

    public int getMessageTtlSeconds() {
        return messageTtlSeconds;
    }

    public void setMessageTtlSeconds(int messageTtlSeconds) {
        this.messageTtlSeconds = messageTtlSeconds;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
