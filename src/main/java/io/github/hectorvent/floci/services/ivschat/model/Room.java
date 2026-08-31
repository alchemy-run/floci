package io.github.hectorvent.floci.services.ivschat.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Amazon IVS Chat room. Wire names are camelCase.
 */
@RegisterForReflection
public class Room {

    private String id;
    private String arn;
    private String name;
    private int maximumMessageRatePerSecond;
    private int maximumMessageLength;
    private String messageReviewHandlerUri;
    private String messageReviewHandlerFallbackResult;
    private List<String> loggingConfigurationIdentifiers = new ArrayList<>();
    private String createTime;
    private String updateTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Room() {
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

    public int getMaximumMessageRatePerSecond() {
        return maximumMessageRatePerSecond;
    }

    public void setMaximumMessageRatePerSecond(int maximumMessageRatePerSecond) {
        this.maximumMessageRatePerSecond = maximumMessageRatePerSecond;
    }

    public int getMaximumMessageLength() {
        return maximumMessageLength;
    }

    public void setMaximumMessageLength(int maximumMessageLength) {
        this.maximumMessageLength = maximumMessageLength;
    }

    public String getMessageReviewHandlerUri() {
        return messageReviewHandlerUri;
    }

    public void setMessageReviewHandlerUri(String messageReviewHandlerUri) {
        this.messageReviewHandlerUri = messageReviewHandlerUri;
    }

    public String getMessageReviewHandlerFallbackResult() {
        return messageReviewHandlerFallbackResult;
    }

    public void setMessageReviewHandlerFallbackResult(String messageReviewHandlerFallbackResult) {
        this.messageReviewHandlerFallbackResult = messageReviewHandlerFallbackResult;
    }

    public List<String> getLoggingConfigurationIdentifiers() {
        return loggingConfigurationIdentifiers;
    }

    public void setLoggingConfigurationIdentifiers(List<String> loggingConfigurationIdentifiers) {
        this.loggingConfigurationIdentifiers = loggingConfigurationIdentifiers == null
                ? new ArrayList<>()
                : new ArrayList<>(loggingConfigurationIdentifiers);
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
