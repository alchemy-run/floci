package io.github.hectorvent.floci.services.chatbot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Account-level Chatbot preferences used by Get/UpdateAccountPreferences. */
@RegisterForReflection
public class ChatbotState {

    private boolean userAuthorizationRequired;
    private boolean trainingDataCollectionEnabled;

    public ChatbotState() {
    }

    public boolean isUserAuthorizationRequired() {
        return userAuthorizationRequired;
    }

    public void setUserAuthorizationRequired(boolean userAuthorizationRequired) {
        this.userAuthorizationRequired = userAuthorizationRequired;
    }

    public boolean isTrainingDataCollectionEnabled() {
        return trainingDataCollectionEnabled;
    }

    public void setTrainingDataCollectionEnabled(boolean trainingDataCollectionEnabled) {
        this.trainingDataCollectionEnabled = trainingDataCollectionEnabled;
    }
}
