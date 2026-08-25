package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A long-term memory record. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryRecordItem {

    private String memoryRecordId;
    private JsonNode content;
    private String memoryStrategyId;
    private List<String> namespaces = new ArrayList<>();
    private long createdAt;
    private JsonNode metadata;
    private String requestIdentifier;

    public MemoryRecordItem() {
    }

    public String getMemoryRecordId() {
        return memoryRecordId;
    }

    public void setMemoryRecordId(String memoryRecordId) {
        this.memoryRecordId = memoryRecordId;
    }

    public JsonNode getContent() {
        return content == null ? null : content.deepCopy();
    }

    public void setContent(JsonNode content) {
        this.content = content == null ? null : content.deepCopy();
    }

    public String getMemoryStrategyId() {
        return memoryStrategyId;
    }

    public void setMemoryStrategyId(String memoryStrategyId) {
        this.memoryStrategyId = memoryStrategyId;
    }

    public List<String> getNamespaces() {
        return namespaces;
    }

    public void setNamespaces(List<String> namespaces) {
        this.namespaces = namespaces == null ? new ArrayList<>() : new ArrayList<>(namespaces);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public JsonNode getMetadata() {
        return metadata == null ? null : metadata.deepCopy();
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata == null ? null : metadata.deepCopy();
    }

    public String getRequestIdentifier() {
        return requestIdentifier;
    }

    public void setRequestIdentifier(String requestIdentifier) {
        this.requestIdentifier = requestIdentifier;
    }
}
