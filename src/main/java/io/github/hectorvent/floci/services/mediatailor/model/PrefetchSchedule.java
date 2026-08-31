package io.github.hectorvent.floci.services.mediatailor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaTailor prefetch schedule. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrefetchSchedule {

    private String name;
    private String playbackConfigurationName;
    private String region;
    private String arn;
    private JsonNode consumption;
    private JsonNode retrieval;
    private JsonNode recurringPrefetchConfiguration;
    private String scheduleType;
    private String streamId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public PrefetchSchedule() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlaybackConfigurationName() {
        return playbackConfigurationName;
    }

    public void setPlaybackConfigurationName(String playbackConfigurationName) {
        this.playbackConfigurationName = playbackConfigurationName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public JsonNode getConsumption() {
        return consumption;
    }

    public void setConsumption(JsonNode consumption) {
        this.consumption = consumption;
    }

    public JsonNode getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(JsonNode retrieval) {
        this.retrieval = retrieval;
    }

    public JsonNode getRecurringPrefetchConfiguration() {
        return recurringPrefetchConfiguration;
    }

    public void setRecurringPrefetchConfiguration(JsonNode recurringPrefetchConfiguration) {
        this.recurringPrefetchConfiguration = recurringPrefetchConfiguration;
    }

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
