package io.github.hectorvent.floci.services.chatbot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An AWS Chatbot Microsoft Teams channel configuration. ARNs are global
 * ({@code arn:aws:chatbot::<account>:chat-configuration/microsoft-teams-channel/<name>}).
 */
@RegisterForReflection
public class TeamsChannelConfiguration {

    private String configurationName;
    private String channelId;
    private String channelName;
    private String teamId;
    private String teamName;
    private String tenantId;
    private String chatConfigurationArn;
    private String iamRoleArn;
    private List<String> snsTopicArns = new ArrayList<>();
    private String loggingLevel;
    private List<String> guardrailPolicyArns = new ArrayList<>();
    private boolean userAuthorizationRequired;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String state;
    private String stateReason;

    public TeamsChannelConfiguration() {
    }

    public String getConfigurationName() {
        return configurationName;
    }

    public void setConfigurationName(String configurationName) {
        this.configurationName = configurationName;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getChatConfigurationArn() {
        return chatConfigurationArn;
    }

    public void setChatConfigurationArn(String chatConfigurationArn) {
        this.chatConfigurationArn = chatConfigurationArn;
    }

    public String getIamRoleArn() {
        return iamRoleArn;
    }

    public void setIamRoleArn(String iamRoleArn) {
        this.iamRoleArn = iamRoleArn;
    }

    public List<String> getSnsTopicArns() {
        return snsTopicArns;
    }

    public void setSnsTopicArns(List<String> snsTopicArns) {
        this.snsTopicArns = snsTopicArns == null ? new ArrayList<>() : new ArrayList<>(snsTopicArns);
    }

    public String getLoggingLevel() {
        return loggingLevel;
    }

    public void setLoggingLevel(String loggingLevel) {
        this.loggingLevel = loggingLevel;
    }

    public List<String> getGuardrailPolicyArns() {
        return guardrailPolicyArns;
    }

    public void setGuardrailPolicyArns(List<String> guardrailPolicyArns) {
        this.guardrailPolicyArns = guardrailPolicyArns == null
                ? new ArrayList<>()
                : new ArrayList<>(guardrailPolicyArns);
    }

    public boolean isUserAuthorizationRequired() {
        return userAuthorizationRequired;
    }

    public void setUserAuthorizationRequired(boolean userAuthorizationRequired) {
        this.userAuthorizationRequired = userAuthorizationRequired;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }
}
