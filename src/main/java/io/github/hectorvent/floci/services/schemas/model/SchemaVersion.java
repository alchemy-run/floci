package io.github.hectorvent.floci.services.schemas.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One published version of an EventBridge schema. */
@RegisterForReflection
public class SchemaVersion {
    private String version;
    private String content;
    private String type;
    private String createdDate;

    public SchemaVersion() {
    }

    public SchemaVersion(String version, String content, String type, String createdDate) {
        this.version = version;
        this.content = content;
        this.type = type;
        this.createdDate = createdDate;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }
}
