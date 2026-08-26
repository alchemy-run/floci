package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryDbEvent {

    private String sourceName;
    private String sourceType;
    private String message;
    private long date;

    public MemoryDbEvent() {}

    public MemoryDbEvent(String sourceName, String sourceType, String message, long date) {
        this.sourceName = sourceName;
        this.sourceType = sourceType;
        this.message = message;
        this.date = date;
    }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getDate() { return date; }
    public void setDate(long date) { this.date = date; }
}
