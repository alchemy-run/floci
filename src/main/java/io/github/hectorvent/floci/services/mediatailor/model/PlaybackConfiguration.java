package io.github.hectorvent.floci.services.mediatailor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS Elemental MediaTailor playback configuration. Nested AWS documents are stored as JSON. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlaybackConfiguration {

    private String name;
    private String arn;
    private String region;
    private String playbackEndpointPrefix;
    private String adDecisionServerUrl;
    private String videoContentSourceUrl;
    private String slateAdUrl;
    private String transcodeProfileName;
    private String insertionMode;
    private Integer personalizationThresholdSeconds;
    private JsonNode availSuppression;
    private JsonNode bumper;
    private JsonNode cdnConfiguration;
    private JsonNode dashConfiguration;
    private JsonNode livePreRollConfiguration;
    private JsonNode manifestProcessingRules;
    private JsonNode configurationAliases;
    private JsonNode adConditioningConfiguration;
    private JsonNode adDecisionServerConfiguration;
    private JsonNode functionMapping;
    private int percentEnabled;
    private List<String> enabledLoggingStrategies = new ArrayList<>();
    private JsonNode adsInteractionLog;
    private JsonNode manifestServiceInteractionLog;
    private Map<String, String> tags = new LinkedHashMap<>();

    public PlaybackConfiguration() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPlaybackEndpointPrefix() {
        return playbackEndpointPrefix;
    }

    public void setPlaybackEndpointPrefix(String playbackEndpointPrefix) {
        this.playbackEndpointPrefix = playbackEndpointPrefix;
    }

    public String getAdDecisionServerUrl() {
        return adDecisionServerUrl;
    }

    public void setAdDecisionServerUrl(String adDecisionServerUrl) {
        this.adDecisionServerUrl = adDecisionServerUrl;
    }

    public String getVideoContentSourceUrl() {
        return videoContentSourceUrl;
    }

    public void setVideoContentSourceUrl(String videoContentSourceUrl) {
        this.videoContentSourceUrl = videoContentSourceUrl;
    }

    public String getSlateAdUrl() {
        return slateAdUrl;
    }

    public void setSlateAdUrl(String slateAdUrl) {
        this.slateAdUrl = slateAdUrl;
    }

    public String getTranscodeProfileName() {
        return transcodeProfileName;
    }

    public void setTranscodeProfileName(String transcodeProfileName) {
        this.transcodeProfileName = transcodeProfileName;
    }

    public String getInsertionMode() {
        return insertionMode;
    }

    public void setInsertionMode(String insertionMode) {
        this.insertionMode = insertionMode;
    }

    public Integer getPersonalizationThresholdSeconds() {
        return personalizationThresholdSeconds;
    }

    public void setPersonalizationThresholdSeconds(Integer personalizationThresholdSeconds) {
        this.personalizationThresholdSeconds = personalizationThresholdSeconds;
    }

    public JsonNode getAvailSuppression() {
        return availSuppression;
    }

    public void setAvailSuppression(JsonNode availSuppression) {
        this.availSuppression = availSuppression;
    }

    public JsonNode getBumper() {
        return bumper;
    }

    public void setBumper(JsonNode bumper) {
        this.bumper = bumper;
    }

    public JsonNode getCdnConfiguration() {
        return cdnConfiguration;
    }

    public void setCdnConfiguration(JsonNode cdnConfiguration) {
        this.cdnConfiguration = cdnConfiguration;
    }

    public JsonNode getDashConfiguration() {
        return dashConfiguration;
    }

    public void setDashConfiguration(JsonNode dashConfiguration) {
        this.dashConfiguration = dashConfiguration;
    }

    public JsonNode getLivePreRollConfiguration() {
        return livePreRollConfiguration;
    }

    public void setLivePreRollConfiguration(JsonNode livePreRollConfiguration) {
        this.livePreRollConfiguration = livePreRollConfiguration;
    }

    public JsonNode getManifestProcessingRules() {
        return manifestProcessingRules;
    }

    public void setManifestProcessingRules(JsonNode manifestProcessingRules) {
        this.manifestProcessingRules = manifestProcessingRules;
    }

    public JsonNode getConfigurationAliases() {
        return configurationAliases;
    }

    public void setConfigurationAliases(JsonNode configurationAliases) {
        this.configurationAliases = configurationAliases;
    }

    public JsonNode getAdConditioningConfiguration() {
        return adConditioningConfiguration;
    }

    public void setAdConditioningConfiguration(JsonNode adConditioningConfiguration) {
        this.adConditioningConfiguration = adConditioningConfiguration;
    }

    public JsonNode getAdDecisionServerConfiguration() {
        return adDecisionServerConfiguration;
    }

    public void setAdDecisionServerConfiguration(JsonNode adDecisionServerConfiguration) {
        this.adDecisionServerConfiguration = adDecisionServerConfiguration;
    }

    public JsonNode getFunctionMapping() {
        return functionMapping;
    }

    public void setFunctionMapping(JsonNode functionMapping) {
        this.functionMapping = functionMapping;
    }

    public int getPercentEnabled() {
        return percentEnabled;
    }

    public void setPercentEnabled(int percentEnabled) {
        this.percentEnabled = percentEnabled;
    }

    public List<String> getEnabledLoggingStrategies() {
        return enabledLoggingStrategies;
    }

    public void setEnabledLoggingStrategies(List<String> enabledLoggingStrategies) {
        this.enabledLoggingStrategies = enabledLoggingStrategies == null
                ? new ArrayList<>()
                : new ArrayList<>(enabledLoggingStrategies);
    }

    public JsonNode getAdsInteractionLog() {
        return adsInteractionLog;
    }

    public void setAdsInteractionLog(JsonNode adsInteractionLog) {
        this.adsInteractionLog = adsInteractionLog;
    }

    public JsonNode getManifestServiceInteractionLog() {
        return manifestServiceInteractionLog;
    }

    public void setManifestServiceInteractionLog(JsonNode manifestServiceInteractionLog) {
        this.manifestServiceInteractionLog = manifestServiceInteractionLog;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
