package io.github.hectorvent.floci.services.chatbot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Pair linking a resource (custom action) to a channel configuration. */
@RegisterForReflection
public class Association {

    private String chatConfigurationArn;
    private String resourceArn;

    public Association() {
    }

    public Association(String chatConfigurationArn, String resourceArn) {
        this.chatConfigurationArn = chatConfigurationArn;
        this.resourceArn = resourceArn;
    }

    public String getChatConfigurationArn() {
        return chatConfigurationArn;
    }

    public void setChatConfigurationArn(String chatConfigurationArn) {
        this.chatConfigurationArn = chatConfigurationArn;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }
}
