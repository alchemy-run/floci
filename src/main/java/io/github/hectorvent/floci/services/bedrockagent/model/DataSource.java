package io.github.hectorvent.floci.services.bedrockagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A data source attached to a Bedrock knowledge base. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSource {

    private String knowledgeBaseId;
    private String dataSourceId;
    private String name;
    private String status;
    private String description;
    private JsonNode dataSourceConfiguration;
    private JsonNode vectorIngestionConfiguration;
    private String dataDeletionPolicy;
    private String createdAt;
    private String updatedAt;

    public DataSource() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getDataSourceConfiguration() {
        return dataSourceConfiguration;
    }

    public void setDataSourceConfiguration(JsonNode dataSourceConfiguration) {
        this.dataSourceConfiguration = dataSourceConfiguration;
    }

    public JsonNode getVectorIngestionConfiguration() {
        return vectorIngestionConfiguration;
    }

    public void setVectorIngestionConfiguration(JsonNode vectorIngestionConfiguration) {
        this.vectorIngestionConfiguration = vectorIngestionConfiguration;
    }

    public String getDataDeletionPolicy() {
        return dataDeletionPolicy;
    }

    public void setDataDeletionPolicy(String dataDeletionPolicy) {
        this.dataDeletionPolicy = dataDeletionPolicy;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
