package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A GuardDuty threat intelligence IP set hosted in S3. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThreatIntelSet {

    private String threatIntelSetId;
    private String name;
    private String format;
    private String location;
    private String status;
    private String expectedBucketOwner;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ThreatIntelSet() {
    }

    public String getThreatIntelSetId() {
        return threatIntelSetId;
    }

    public void setThreatIntelSetId(String threatIntelSetId) {
        this.threatIntelSetId = threatIntelSetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExpectedBucketOwner() {
        return expectedBucketOwner;
    }

    public void setExpectedBucketOwner(String expectedBucketOwner) {
        this.expectedBucketOwner = expectedBucketOwner;
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
