package io.github.hectorvent.floci.services.medialive.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaLive channel. Nested encoder documents are stored as JSON. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Channel {

    private String id;
    private String arn;
    private String name;
    private String state;
    private String channelClass;
    private String roleArn;
    private String logLevel;
    private String region;
    private JsonNode inputAttachments;
    private JsonNode encoderSettings;
    private JsonNode destinations;
    private JsonNode inputSpecification;
    private JsonNode cdiInputSpecification;
    private JsonNode maintenance;
    private JsonNode scheduleActions;
    private int pipelinesRunningCount;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getChannelClass() {
        return channelClass;
    }

    public void setChannelClass(String channelClass) {
        this.channelClass = channelClass;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public JsonNode getInputAttachments() {
        return inputAttachments;
    }

    public void setInputAttachments(JsonNode inputAttachments) {
        this.inputAttachments = inputAttachments;
    }

    public JsonNode getEncoderSettings() {
        return encoderSettings;
    }

    public void setEncoderSettings(JsonNode encoderSettings) {
        this.encoderSettings = encoderSettings;
    }

    public JsonNode getDestinations() {
        return destinations;
    }

    public void setDestinations(JsonNode destinations) {
        this.destinations = destinations;
    }

    public JsonNode getInputSpecification() {
        return inputSpecification;
    }

    public void setInputSpecification(JsonNode inputSpecification) {
        this.inputSpecification = inputSpecification;
    }

    public JsonNode getCdiInputSpecification() {
        return cdiInputSpecification;
    }

    public void setCdiInputSpecification(JsonNode cdiInputSpecification) {
        this.cdiInputSpecification = cdiInputSpecification;
    }

    public JsonNode getMaintenance() {
        return maintenance;
    }

    public void setMaintenance(JsonNode maintenance) {
        this.maintenance = maintenance;
    }

    public JsonNode getScheduleActions() {
        return scheduleActions;
    }

    public void setScheduleActions(JsonNode scheduleActions) {
        this.scheduleActions = scheduleActions;
    }

    public int getPipelinesRunningCount() {
        return pipelinesRunningCount;
    }

    public void setPipelinesRunningCount(int pipelinesRunningCount) {
        this.pipelinesRunningCount = pipelinesRunningCount;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
