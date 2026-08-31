package io.github.hectorvent.floci.services.ivs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon IVS recording configuration. Wire names are camelCase.
 */
@RegisterForReflection
public class RecordingConfiguration {

    private String id;
    private String arn;
    private String name;
    private String state;
    private String bucketName;
    private int recordingReconnectWindowSeconds;
    private String thumbnailRecordingMode;
    private Integer thumbnailTargetIntervalSeconds;
    private String thumbnailResolution;
    private List<String> thumbnailStorage = new ArrayList<>();
    private String renditionSelection;
    private List<String> renditions = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public RecordingConfiguration() {
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public int getRecordingReconnectWindowSeconds() {
        return recordingReconnectWindowSeconds;
    }

    public void setRecordingReconnectWindowSeconds(int recordingReconnectWindowSeconds) {
        this.recordingReconnectWindowSeconds = recordingReconnectWindowSeconds;
    }

    public String getThumbnailRecordingMode() {
        return thumbnailRecordingMode;
    }

    public void setThumbnailRecordingMode(String thumbnailRecordingMode) {
        this.thumbnailRecordingMode = thumbnailRecordingMode;
    }

    public Integer getThumbnailTargetIntervalSeconds() {
        return thumbnailTargetIntervalSeconds;
    }

    public void setThumbnailTargetIntervalSeconds(Integer thumbnailTargetIntervalSeconds) {
        this.thumbnailTargetIntervalSeconds = thumbnailTargetIntervalSeconds;
    }

    public String getThumbnailResolution() {
        return thumbnailResolution;
    }

    public void setThumbnailResolution(String thumbnailResolution) {
        this.thumbnailResolution = thumbnailResolution;
    }

    public List<String> getThumbnailStorage() {
        return thumbnailStorage;
    }

    public void setThumbnailStorage(List<String> thumbnailStorage) {
        this.thumbnailStorage = thumbnailStorage == null ? new ArrayList<>() : new ArrayList<>(thumbnailStorage);
    }

    public String getRenditionSelection() {
        return renditionSelection;
    }

    public void setRenditionSelection(String renditionSelection) {
        this.renditionSelection = renditionSelection;
    }

    public List<String> getRenditions() {
        return renditions;
    }

    public void setRenditions(List<String> renditions) {
        this.renditions = renditions == null ? new ArrayList<>() : new ArrayList<>(renditions);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
