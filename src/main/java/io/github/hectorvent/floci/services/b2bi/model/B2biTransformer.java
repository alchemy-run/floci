package io.github.hectorvent.floci.services.b2bi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS B2BI transformer. Wire names are camelCase awsJson1_0. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2biTransformer {

    private String transformerId;
    private String transformerArn;
    private String name;
    private String status;
    private String createdAt;
    private String modifiedAt;
    private String fileFormat;
    private String mappingTemplate;
    private JsonNode ediType;
    private String sampleDocument;
    private JsonNode inputConversion;
    private JsonNode mapping;
    private JsonNode outputConversion;
    private JsonNode sampleDocuments;
    private Map<String, String> tags = new LinkedHashMap<>();

    public B2biTransformer() {}

    public String getTransformerId() {
        return transformerId;
    }

    public void setTransformerId(String transformerId) {
        this.transformerId = transformerId;
    }

    public String getTransformerArn() {
        return transformerArn;
    }

    public void setTransformerArn(String transformerArn) {
        this.transformerArn = transformerArn;
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

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public String getMappingTemplate() {
        return mappingTemplate;
    }

    public void setMappingTemplate(String mappingTemplate) {
        this.mappingTemplate = mappingTemplate;
    }

    public JsonNode getEdiType() {
        return ediType;
    }

    public void setEdiType(JsonNode ediType) {
        this.ediType = ediType;
    }

    public String getSampleDocument() {
        return sampleDocument;
    }

    public void setSampleDocument(String sampleDocument) {
        this.sampleDocument = sampleDocument;
    }

    public JsonNode getInputConversion() {
        return inputConversion;
    }

    public void setInputConversion(JsonNode inputConversion) {
        this.inputConversion = inputConversion;
    }

    public JsonNode getMapping() {
        return mapping;
    }

    public void setMapping(JsonNode mapping) {
        this.mapping = mapping;
    }

    public JsonNode getOutputConversion() {
        return outputConversion;
    }

    public void setOutputConversion(JsonNode outputConversion) {
        this.outputConversion = outputConversion;
    }

    public JsonNode getSampleDocuments() {
        return sampleDocuments;
    }

    public void setSampleDocuments(JsonNode sampleDocuments) {
        this.sampleDocuments = sampleDocuments;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
