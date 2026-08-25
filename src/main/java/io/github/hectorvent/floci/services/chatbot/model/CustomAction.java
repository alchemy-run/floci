package io.github.hectorvent.floci.services.chatbot.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Account-level Chatbot custom action. ARNs are global (empty region). */
@RegisterForReflection
public class CustomAction {

    private String customActionArn;
    private String actionName;
    private String commandText;
    private String aliasName;
    private JsonNode attachments;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CustomAction() {
    }

    public String getCustomActionArn() {
        return customActionArn;
    }

    public void setCustomActionArn(String customActionArn) {
        this.customActionArn = customActionArn;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getCommandText() {
        return commandText;
    }

    public void setCommandText(String commandText) {
        this.commandText = commandText;
    }

    public String getAliasName() {
        return aliasName;
    }

    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }

    public JsonNode getAttachments() {
        return attachments == null ? null : attachments.deepCopy();
    }

    public void setAttachments(JsonNode attachments) {
        this.attachments = attachments == null || attachments.isNull() ? null : attachments.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
