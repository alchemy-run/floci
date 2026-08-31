package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Macie allow list. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MacieAllowList {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String accountId;
    private String region;
    private String createdAt;
    private String updatedAt;
    private String statusCode = "OK";
    private Map<String, Object> criteria = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public MacieAllowList() {
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

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public Map<String, Object> getCriteria() {
        if (criteria == null) {
            criteria = new LinkedHashMap<>();
        }
        return criteria;
    }

    public void setCriteria(Map<String, Object> criteria) {
        this.criteria = criteria == null ? new LinkedHashMap<>() : new LinkedHashMap<>(criteria);
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
