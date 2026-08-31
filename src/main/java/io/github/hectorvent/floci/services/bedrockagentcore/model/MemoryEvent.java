package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A short-term memory event. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryEvent {

    private String memoryId;
    private String actorId;
    private String sessionId;
    private String eventId;
    private long eventTimestamp;
    private JsonNode payload;
    private JsonNode branch;
    private JsonNode metadata;

    public MemoryEvent() {
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public long getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(long eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public JsonNode getPayload() {
        return payload == null ? null : payload.deepCopy();
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload == null ? null : payload.deepCopy();
    }

    public JsonNode getBranch() {
        return branch == null ? null : branch.deepCopy();
    }

    public void setBranch(JsonNode branch) {
        this.branch = branch == null ? null : branch.deepCopy();
    }

    public JsonNode getMetadata() {
        return metadata == null ? null : metadata.deepCopy();
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata == null ? null : metadata.deepCopy();
    }
}
