package io.github.hectorvent.floci.services.ssmcontacts.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RotationRecord {

    private String rotationArn;
    private String name;
    private List<String> contactIds = new ArrayList<>();
    private Long startTime;
    private String timeZoneId;
    private JsonNode recurrence;
    private Map<String, String> tags = new LinkedHashMap<>();

    public RotationRecord() {
    }

    public String getRotationArn() {
        return rotationArn;
    }

    public void setRotationArn(String rotationArn) {
        this.rotationArn = rotationArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getContactIds() {
        return contactIds;
    }

    public void setContactIds(List<String> contactIds) {
        this.contactIds = contactIds != null ? contactIds : new ArrayList<>();
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public String getTimeZoneId() {
        return timeZoneId;
    }

    public void setTimeZoneId(String timeZoneId) {
        this.timeZoneId = timeZoneId;
    }

    public JsonNode getRecurrence() {
        return recurrence;
    }

    public void setRecurrence(JsonNode recurrence) {
        this.recurrence = recurrence;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
