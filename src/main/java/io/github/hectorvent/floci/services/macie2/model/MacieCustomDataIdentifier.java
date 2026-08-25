package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon Macie custom data identifier. Destroy is a soft delete. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MacieCustomDataIdentifier {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String regex;
    private String accountId;
    private String region;
    private String createdAt;
    private boolean deleted;
    private Integer maximumMatchDistance;
    private List<String> keywords = new ArrayList<>();
    private List<String> ignoreWords = new ArrayList<>();
    private List<Map<String, Object>> severityLevels = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public MacieCustomDataIdentifier() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRegex() {
        return regex;
    }

    public void setRegex(String regex) {
        this.regex = regex;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Integer getMaximumMatchDistance() {
        return maximumMatchDistance;
    }

    public void setMaximumMatchDistance(Integer maximumMatchDistance) {
        this.maximumMatchDistance = maximumMatchDistance;
    }

    public List<String> getKeywords() {
        if (keywords == null) {
            keywords = new ArrayList<>();
        }
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);
    }

    public List<String> getIgnoreWords() {
        if (ignoreWords == null) {
            ignoreWords = new ArrayList<>();
        }
        return ignoreWords;
    }

    public void setIgnoreWords(List<String> ignoreWords) {
        this.ignoreWords = ignoreWords == null ? new ArrayList<>() : new ArrayList<>(ignoreWords);
    }

    public List<Map<String, Object>> getSeverityLevels() {
        if (severityLevels == null) {
            severityLevels = new ArrayList<>();
        }
        return severityLevels;
    }

    public void setSeverityLevels(List<Map<String, Object>> severityLevels) {
        this.severityLevels = severityLevels == null ? new ArrayList<>() : new ArrayList<>(severityLevels);
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
