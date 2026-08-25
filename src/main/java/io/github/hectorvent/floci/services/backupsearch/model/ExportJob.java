package io.github.hectorvent.floci.services.backupsearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportJob {

    private String exportJobIdentifier;
    private String exportJobArn;
    private String searchJobArn;
    private String searchJobIdentifier;
    private String status;
    private String statusMessage;
    private long creationTime;
    private Long completionTime;
    private JsonNode exportSpecification;
    private String roleArn;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String region;

    public ExportJob() {
    }

    public String getExportJobIdentifier() {
        return exportJobIdentifier;
    }

    public void setExportJobIdentifier(String exportJobIdentifier) {
        this.exportJobIdentifier = exportJobIdentifier;
    }

    public String getExportJobArn() {
        return exportJobArn;
    }

    public void setExportJobArn(String exportJobArn) {
        this.exportJobArn = exportJobArn;
    }

    public String getSearchJobArn() {
        return searchJobArn;
    }

    public void setSearchJobArn(String searchJobArn) {
        this.searchJobArn = searchJobArn;
    }

    public String getSearchJobIdentifier() {
        return searchJobIdentifier;
    }

    public void setSearchJobIdentifier(String searchJobIdentifier) {
        this.searchJobIdentifier = searchJobIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public Long getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(Long completionTime) {
        this.completionTime = completionTime;
    }

    public JsonNode getExportSpecification() {
        return exportSpecification;
    }

    public void setExportSpecification(JsonNode exportSpecification) {
        this.exportSpecification = exportSpecification;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
