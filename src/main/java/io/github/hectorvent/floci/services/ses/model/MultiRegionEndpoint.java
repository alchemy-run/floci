package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A SES v2 multi-region (global) endpoint. Provisioning is instantaneous in
 * Floci: create returns {@code CREATING} and subsequent gets report {@code READY}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultiRegionEndpoint {

    @JsonProperty("EndpointName")
    private String endpointName;

    @JsonProperty("EndpointId")
    private String endpointId;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("Regions")
    private List<String> regions = new ArrayList<>();

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("LastUpdatedTimestamp")
    private Instant lastUpdatedTimestamp;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public MultiRegionEndpoint() {}

    public String getEndpointName() { return endpointName; }
    public void setEndpointName(String endpointName) { this.endpointName = endpointName; }

    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getRegions() { return regions; }
    public void setRegions(List<String> regions) {
        this.regions = regions == null ? new ArrayList<>() : regions;
    }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Instant getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(Instant lastUpdatedTimestamp) {
        this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }
}
