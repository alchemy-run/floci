package io.github.hectorvent.floci.services.macie2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Macie findings filter. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MacieFindingsFilter {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String action;
    private Integer position;
    private String accountId;
    private String region;
    private Map<String, Object> findingCriteria = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public MacieFindingsFilter() {
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
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

    public Map<String, Object> getFindingCriteria() {
        if (findingCriteria == null) {
            findingCriteria = new LinkedHashMap<>();
        }
        return findingCriteria;
    }

    public void setFindingCriteria(Map<String, Object> findingCriteria) {
        this.findingCriteria = findingCriteria == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(findingCriteria);
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
