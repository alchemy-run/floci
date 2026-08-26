package io.github.hectorvent.floci.services.ssmincidents.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Incident Manager response plan. ARNs omit region. */
@RegisterForReflection
public class ResponsePlan {

    private String arn;
    private String name;
    private String displayName;
    private String title;
    private int impact = 5;
    private String summary;
    private String dedupeString;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ResponsePlan() {
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getImpact() {
        return impact;
    }

    public void setImpact(int impact) {
        this.impact = impact;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDedupeString() {
        return dedupeString;
    }

    public void setDedupeString(String dedupeString) {
        this.dedupeString = dedupeString;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
