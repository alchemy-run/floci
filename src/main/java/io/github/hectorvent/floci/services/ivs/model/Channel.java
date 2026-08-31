package io.github.hectorvent.floci.services.ivs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Amazon IVS low-latency channel. Wire names are camelCase.
 */
@RegisterForReflection
public class Channel {

    private String id;
    private String arn;
    private String name;
    private String latencyMode;
    private String type;
    private String recordingConfigurationArn;
    private String ingestEndpoint;
    private String playbackUrl;
    private boolean authorized;
    private boolean insecureIngest;
    private String preset;
    private String playbackRestrictionPolicyArn;
    private String containerFormat;
    private String adConfigurationArn;
    private String srtEndpoint;
    private String srtPassphrase;
    private String streamKeyArn;
    private String streamKeyValue;
    private Map<String, String> streamKeyTags = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Channel() {
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

    public String getLatencyMode() {
        return latencyMode;
    }

    public void setLatencyMode(String latencyMode) {
        this.latencyMode = latencyMode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRecordingConfigurationArn() {
        return recordingConfigurationArn;
    }

    public void setRecordingConfigurationArn(String recordingConfigurationArn) {
        this.recordingConfigurationArn = recordingConfigurationArn;
    }

    public String getIngestEndpoint() {
        return ingestEndpoint;
    }

    public void setIngestEndpoint(String ingestEndpoint) {
        this.ingestEndpoint = ingestEndpoint;
    }

    public String getPlaybackUrl() {
        return playbackUrl;
    }

    public void setPlaybackUrl(String playbackUrl) {
        this.playbackUrl = playbackUrl;
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }

    public boolean isInsecureIngest() {
        return insecureIngest;
    }

    public void setInsecureIngest(boolean insecureIngest) {
        this.insecureIngest = insecureIngest;
    }

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public String getPlaybackRestrictionPolicyArn() {
        return playbackRestrictionPolicyArn;
    }

    public void setPlaybackRestrictionPolicyArn(String playbackRestrictionPolicyArn) {
        this.playbackRestrictionPolicyArn = playbackRestrictionPolicyArn;
    }

    public String getContainerFormat() {
        return containerFormat;
    }

    public void setContainerFormat(String containerFormat) {
        this.containerFormat = containerFormat;
    }

    public String getAdConfigurationArn() {
        return adConfigurationArn;
    }

    public void setAdConfigurationArn(String adConfigurationArn) {
        this.adConfigurationArn = adConfigurationArn;
    }

    public String getSrtEndpoint() {
        return srtEndpoint;
    }

    public void setSrtEndpoint(String srtEndpoint) {
        this.srtEndpoint = srtEndpoint;
    }

    public String getSrtPassphrase() {
        return srtPassphrase;
    }

    public void setSrtPassphrase(String srtPassphrase) {
        this.srtPassphrase = srtPassphrase;
    }

    public String getStreamKeyArn() {
        return streamKeyArn;
    }

    public void setStreamKeyArn(String streamKeyArn) {
        this.streamKeyArn = streamKeyArn;
    }

    public String getStreamKeyValue() {
        return streamKeyValue;
    }

    public void setStreamKeyValue(String streamKeyValue) {
        this.streamKeyValue = streamKeyValue;
    }

    public Map<String, String> getStreamKeyTags() {
        return streamKeyTags;
    }

    public void setStreamKeyTags(Map<String, String> streamKeyTags) {
        this.streamKeyTags = streamKeyTags == null ? new LinkedHashMap<>() : streamKeyTags;
    }

    public StreamKey getStreamKey() {
        if (streamKeyArn == null && streamKeyValue == null) {
            return null;
        }
        StreamKey key = new StreamKey();
        key.setArn(streamKeyArn);
        key.setChannelArn(arn);
        key.setValue(streamKeyValue);
        key.setTags(streamKeyTags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(streamKeyTags));
        return key;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
