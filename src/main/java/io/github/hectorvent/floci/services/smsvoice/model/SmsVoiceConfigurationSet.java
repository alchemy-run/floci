package io.github.hectorvent.floci.services.smsvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsVoiceConfigurationSet {

    private String configurationSetName;
    private String configurationSetArn;
    private String region;
    private String defaultMessageType;
    private String defaultSenderId;
    private Boolean defaultMessageFeedbackEnabled;
    private String protectConfigurationId;
    private String clientToken;
    private long createdTimestamp;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<SmsVoiceEventDestination> eventDestinations = new ArrayList<>();

    public SmsVoiceConfigurationSet() {
    }

    public String getConfigurationSetName() {
        return configurationSetName;
    }

    public void setConfigurationSetName(String configurationSetName) {
        this.configurationSetName = configurationSetName;
    }

    public String getConfigurationSetArn() {
        return configurationSetArn;
    }

    public void setConfigurationSetArn(String configurationSetArn) {
        this.configurationSetArn = configurationSetArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDefaultMessageType() {
        return defaultMessageType;
    }

    public void setDefaultMessageType(String defaultMessageType) {
        this.defaultMessageType = defaultMessageType;
    }

    public String getDefaultSenderId() {
        return defaultSenderId;
    }

    public void setDefaultSenderId(String defaultSenderId) {
        this.defaultSenderId = defaultSenderId;
    }

    public Boolean getDefaultMessageFeedbackEnabled() {
        return defaultMessageFeedbackEnabled;
    }

    public void setDefaultMessageFeedbackEnabled(Boolean defaultMessageFeedbackEnabled) {
        this.defaultMessageFeedbackEnabled = defaultMessageFeedbackEnabled;
    }

    public String getProtectConfigurationId() {
        return protectConfigurationId;
    }

    public void setProtectConfigurationId(String protectConfigurationId) {
        this.protectConfigurationId = protectConfigurationId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public List<SmsVoiceEventDestination> getEventDestinations() {
        if (eventDestinations == null) {
            eventDestinations = new ArrayList<>();
        }
        return eventDestinations;
    }

    public void setEventDestinations(List<SmsVoiceEventDestination> eventDestinations) {
        this.eventDestinations = eventDestinations == null ? new ArrayList<>() : eventDestinations;
    }
}
