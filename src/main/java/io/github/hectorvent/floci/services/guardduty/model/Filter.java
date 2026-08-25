package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A GuardDuty findings filter on a detector. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Filter {

    private String name;
    private String description;
    private String action;
    private Integer rank;
    private Map<String, Object> findingCriteria = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Filter() {
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

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public Map<String, Object> getFindingCriteria() {
        if (findingCriteria == null) {
            findingCriteria = new LinkedHashMap<>();
        }
        return findingCriteria;
    }

    public void setFindingCriteria(Map<String, Object> findingCriteria) {
        this.findingCriteria = findingCriteria == null ? new LinkedHashMap<>() : new LinkedHashMap<>(findingCriteria);
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
