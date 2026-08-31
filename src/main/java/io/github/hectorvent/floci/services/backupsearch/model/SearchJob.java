package io.github.hectorvent.floci.services.backupsearch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchJob {

    private String searchJobIdentifier;
    private String searchJobArn;
    private String name;
    private String encryptionKeyArn;
    private String status;
    private String statusMessage;
    private long creationTime;
    private Long completionTime;
    private JsonNode searchScope;
    private JsonNode itemFilters;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String region;

    public SearchJob() {
    }

    public String getSearchJobIdentifier() {
        return searchJobIdentifier;
    }

    public void setSearchJobIdentifier(String searchJobIdentifier) {
        this.searchJobIdentifier = searchJobIdentifier;
    }

    public String getSearchJobArn() {
        return searchJobArn;
    }

    public void setSearchJobArn(String searchJobArn) {
        this.searchJobArn = searchJobArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEncryptionKeyArn() {
        return encryptionKeyArn;
    }

    public void setEncryptionKeyArn(String encryptionKeyArn) {
        this.encryptionKeyArn = encryptionKeyArn;
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

    public JsonNode getSearchScope() {
        return searchScope;
    }

    public void setSearchScope(JsonNode searchScope) {
        this.searchScope = searchScope;
    }

    public JsonNode getItemFilters() {
        return itemFilters;
    }

    public void setItemFilters(JsonNode itemFilters) {
        this.itemFilters = itemFilters;
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
