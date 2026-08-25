package io.github.hectorvent.floci.services.bedrockagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An ingested knowledge-base document. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnowledgeBaseDocument {

    private String knowledgeBaseId;
    private String dataSourceId;
    private String documentId;
    private String status;
    private String text;
    private JsonNode identifier;
    private String updatedAt;

    public KnowledgeBaseDocument() {
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public JsonNode getIdentifier() {
        return identifier;
    }

    public void setIdentifier(JsonNode identifier) {
        this.identifier = identifier;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
