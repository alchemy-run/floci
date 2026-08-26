package io.github.hectorvent.floci.services.mediaconvert.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Elemental MediaConvert queue. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaConvertQueue {

    private String arn;
    private String name;
    private String description;
    private String pricingPlan = "ON_DEMAND";
    private String status = "ACTIVE";
    private String type = "CUSTOM";
    private Integer concurrentJobs;
    private JsonNode reservationPlan;
    private long createdAt;
    private long lastUpdated;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();

    public MediaConvertQueue() {
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

    public String getPricingPlan() {
        return pricingPlan;
    }

    public void setPricingPlan(String pricingPlan) {
        this.pricingPlan = pricingPlan;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getConcurrentJobs() {
        return concurrentJobs;
    }

    public void setConcurrentJobs(Integer concurrentJobs) {
        this.concurrentJobs = concurrentJobs;
    }

    public JsonNode getReservationPlan() {
        return reservationPlan;
    }

    public void setReservationPlan(JsonNode reservationPlan) {
        this.reservationPlan = reservationPlan;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
