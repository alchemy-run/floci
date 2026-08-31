package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A vocabulary entity stored in a Data Automation library. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LibraryEntityRecord {

    private String libraryArn;
    private String entityType;
    private String entityId;
    private JsonNode vocabulary;
    private String lastModifiedTime;

    public LibraryEntityRecord() {
    }

    public String getLibraryArn() {
        return libraryArn;
    }

    public void setLibraryArn(String libraryArn) {
        this.libraryArn = libraryArn;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public JsonNode getVocabulary() {
        return vocabulary;
    }

    public void setVocabulary(JsonNode vocabulary) {
        this.vocabulary = vocabulary;
    }

    public String getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(String lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }
}
