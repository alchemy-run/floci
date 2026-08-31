package io.github.hectorvent.floci.services.mailmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MailManagerArchiveSearch {

    private String searchId;
    private String archiveId;
    private String region;
    private JsonNode filters;
    private long fromTimestamp;
    private long toTimestamp;
    private int maxResults;
    private String state;
    private String errorMessage;
    private long submissionTimestamp;
    private Long completionTimestamp;

    public MailManagerArchiveSearch() {
    }

    public String getSearchId() {
        return searchId;
    }

    public void setSearchId(String searchId) {
        this.searchId = searchId;
    }

    public String getArchiveId() {
        return archiveId;
    }

    public void setArchiveId(String archiveId) {
        this.archiveId = archiveId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public JsonNode getFilters() {
        return filters;
    }

    public void setFilters(JsonNode filters) {
        this.filters = filters;
    }

    public long getFromTimestamp() {
        return fromTimestamp;
    }

    public void setFromTimestamp(long fromTimestamp) {
        this.fromTimestamp = fromTimestamp;
    }

    public long getToTimestamp() {
        return toTimestamp;
    }

    public void setToTimestamp(long toTimestamp) {
        this.toTimestamp = toTimestamp;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getSubmissionTimestamp() {
        return submissionTimestamp;
    }

    public void setSubmissionTimestamp(long submissionTimestamp) {
        this.submissionTimestamp = submissionTimestamp;
    }

    public Long getCompletionTimestamp() {
        return completionTimestamp;
    }

    public void setCompletionTimestamp(Long completionTimestamp) {
        this.completionTimestamp = completionTimestamp;
    }
}
