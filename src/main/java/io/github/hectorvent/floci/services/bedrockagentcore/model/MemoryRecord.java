package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One AgentCore Memory long-term record, as written by {@code BatchCreateMemoryRecords}.
 *
 * <p>Timestamps are epoch seconds with a fractional part, the restJson1 encoding the SDK sends
 * and expects back. Metadata values keep their wire union shape ({@code stringValue},
 * {@code stringListValue}, {@code numberValue} or {@code dateTimeValue}) so they round-trip
 * unchanged.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryRecord {
    private String memoryId;
    private String memoryRecordId;
    private String text;
    private String memoryStrategyId;
    private List<String> namespaces;
    private Double createdAt;
    private Double updatedAt;
    private Map<String, JsonNode> metadata;
    private String requestIdentifier;
    private String clientToken;

    public MemoryRecord() {}

    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public String getMemoryRecordId() { return memoryRecordId; }
    public void setMemoryRecordId(String memoryRecordId) { this.memoryRecordId = memoryRecordId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getMemoryStrategyId() { return memoryStrategyId; }
    public void setMemoryStrategyId(String memoryStrategyId) { this.memoryStrategyId = memoryStrategyId; }
    public List<String> getNamespaces() { return namespaces == null ? null : new ArrayList<>(namespaces); }
    public void setNamespaces(List<String> namespaces) {
        this.namespaces = namespaces == null ? null : new ArrayList<>(namespaces);
    }
    public Double getCreatedAt() { return createdAt; }
    public void setCreatedAt(Double createdAt) { this.createdAt = createdAt; }
    public Double getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Double updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, JsonNode> getMetadata() { return metadata == null ? null : new LinkedHashMap<>(metadata); }
    public void setMetadata(Map<String, JsonNode> metadata) {
        this.metadata = metadata == null ? null : new LinkedHashMap<>(metadata);
    }
    public String getRequestIdentifier() { return requestIdentifier; }
    public void setRequestIdentifier(String requestIdentifier) { this.requestIdentifier = requestIdentifier; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
