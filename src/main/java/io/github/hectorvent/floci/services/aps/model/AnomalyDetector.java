package io.github.hectorvent.floci.services.aps.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Persisted detector metadata, without a training or evaluation runtime. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyDetector {

    private ObjectNode description;
    private ObjectNode creationRequest;
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public AnomalyDetector() {}

    public ObjectNode getDescription() { return description; }
    public void setDescription(ObjectNode description) { this.description = description; }

    public ObjectNode getCreationRequest() { return creationRequest; }
    public void setCreationRequest(ObjectNode creationRequest) { this.creationRequest = creationRequest; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new ConcurrentHashMap<>(tags) : new ConcurrentHashMap<>();
    }
}
