package io.github.hectorvent.floci.services.deadline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Deadline Cloud sessions-statistics aggregation. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadlineAggregation {

    private String aggregationId;
    private String farmId;
    private String status = "COMPLETED";

    public DeadlineAggregation() {
    }

    public String getAggregationId() {
        return aggregationId;
    }

    public void setAggregationId(String aggregationId) {
        this.aggregationId = aggregationId;
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
