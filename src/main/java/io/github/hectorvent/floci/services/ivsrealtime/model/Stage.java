package io.github.hectorvent.floci.services.ivsrealtime.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon IVS Real-Time stage. Wire names are camelCase.
 */
@RegisterForReflection
public class Stage {

    private String id;
    private String arn;
    private String name;
    private String whipEndpoint;
    private String eventsEndpoint;
    private String rtmpEndpoint;
    private String rtmpsEndpoint;
    private String activeSessionId;
    private String storageConfigurationArn;
    private List<String> mediaTypes = new ArrayList<>();
    private Integer recordingReconnectWindowSeconds;
    private Boolean recordParticipantReplicas;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Stage() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWhipEndpoint() {
        return whipEndpoint;
    }

    public void setWhipEndpoint(String whipEndpoint) {
        this.whipEndpoint = whipEndpoint;
    }

    public String getEventsEndpoint() {
        return eventsEndpoint;
    }

    public void setEventsEndpoint(String eventsEndpoint) {
        this.eventsEndpoint = eventsEndpoint;
    }

    public String getRtmpEndpoint() {
        return rtmpEndpoint;
    }

    public void setRtmpEndpoint(String rtmpEndpoint) {
        this.rtmpEndpoint = rtmpEndpoint;
    }

    public String getRtmpsEndpoint() {
        return rtmpsEndpoint;
    }

    public void setRtmpsEndpoint(String rtmpsEndpoint) {
        this.rtmpsEndpoint = rtmpsEndpoint;
    }

    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId;
    }

    public String getStorageConfigurationArn() {
        return storageConfigurationArn;
    }

    public void setStorageConfigurationArn(String storageConfigurationArn) {
        this.storageConfigurationArn = storageConfigurationArn;
    }

    public String getRecordingStorageConfigurationArn() {
        return storageConfigurationArn;
    }

    public void setRecordingStorageConfigurationArn(String storageConfigurationArn) {
        this.storageConfigurationArn = storageConfigurationArn;
    }

    public List<String> getMediaTypes() {
        return mediaTypes;
    }

    public void setMediaTypes(List<String> mediaTypes) {
        this.mediaTypes = mediaTypes == null ? new ArrayList<>() : new ArrayList<>(mediaTypes);
    }

    public List<String> getRecordingMediaTypes() {
        return mediaTypes;
    }

    public void setRecordingMediaTypes(List<String> mediaTypes) {
        setMediaTypes(mediaTypes);
    }

    public Integer getRecordingReconnectWindowSeconds() {
        return recordingReconnectWindowSeconds;
    }

    public void setRecordingReconnectWindowSeconds(Integer recordingReconnectWindowSeconds) {
        this.recordingReconnectWindowSeconds = recordingReconnectWindowSeconds;
    }

    public Boolean getRecordParticipantReplicas() {
        return recordParticipantReplicas;
    }

    public void setRecordParticipantReplicas(Boolean recordParticipantReplicas) {
        this.recordParticipantReplicas = recordParticipantReplicas;
    }

    public boolean hasRecordingConfiguration() {
        return storageConfigurationArn != null && !storageConfigurationArn.isBlank();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
