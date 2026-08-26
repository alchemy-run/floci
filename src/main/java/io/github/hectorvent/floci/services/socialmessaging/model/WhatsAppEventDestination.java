package io.github.hectorvent.floci.services.socialmessaging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WhatsAppEventDestination {

    private String eventDestinationArn;
    private String roleArn;

    public WhatsAppEventDestination() {
    }

    public String getEventDestinationArn() {
        return eventDestinationArn;
    }

    public void setEventDestinationArn(String eventDestinationArn) {
        this.eventDestinationArn = eventDestinationArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }
}
