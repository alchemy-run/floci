package io.github.hectorvent.floci.services.chatbot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An AWS Chatbot Slack channel configuration. Chat-configuration ARNs are global
 * ({@code arn:aws:chatbot::<account>:chat-configuration/slack-channel/<name>}).
 */
@RegisterForReflection
public class SlackChannelConfiguration {

    private String configurationName;
    private String slackTeamId;
    private String slackTeamName;
    private String slackChannelId;
    private String slackChannelName;
    private String chatConfigurationArn;
    private String iamRoleArn;
    private List<String> snsTopicArns = new ArrayList<>();
    private String loggingLevel;
    private List<String> guardrailPolicyArns = new ArrayList<>();
    private boolean userAuthorizationRequired;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String state;
    private String stateReason;
    private boolean tombstone;

    public SlackChannelConfiguration() {
    }

    public String getConfigurationName() {
        return configurationName;
    }

    public void setConfigurationName(String configurationName) {
        this.configurationName = configurationName;
    }

    public String getSlackTeamId() {
        return slackTeamId;
    }

    public void setSlackTeamId(String slackTeamId) {
        this.slackTeamId = slackTeamId;
    }

    public String getSlackTeamName() {
        return slackTeamName;
    }

    public void setSlackTeamName(String slackTeamName) {
        this.slackTeamName = slackTeamName;
    }

    public String getSlackChannelId() {
        return slackChannelId;
    }

    public void setSlackChannelId(String slackChannelId) {
        this.slackChannelId = slackChannelId;
    }

    public String getSlackChannelName() {
        return slackChannelName;
    }

    public void setSlackChannelName(String slackChannelName) {
        this.slackChannelName = slackChannelName;
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

    public boolean isTombstone() {
        return tombstone;
    }

    public void setTombstone(boolean tombstone) {
        this.tombstone = tombstone;
    }
}
