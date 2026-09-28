package io.github.hectorvent.floci.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A cluster's managed scaling policy: the {@code ComputeLimits} bounds plus the optional
 * scaling strategy and utilization-performance index.
 */
@RegisterForReflection
public class EmrManagedScalingPolicy {

    private String unitType;
    private Integer minimumCapacityUnits;
    private Integer maximumCapacityUnits;
    private Integer maximumOnDemandCapacityUnits;
    private Integer maximumCoreCapacityUnits;
    private Integer utilizationPerformanceIndex;
    private String scalingStrategy;

    public EmrManagedScalingPolicy() {}

    public String getUnitType() { return unitType; }
    public void setUnitType(String unitType) { this.unitType = unitType; }

    public Integer getMinimumCapacityUnits() { return minimumCapacityUnits; }
    public void setMinimumCapacityUnits(Integer minimumCapacityUnits) {
        this.minimumCapacityUnits = minimumCapacityUnits;
    }

    public Integer getMaximumCapacityUnits() { return maximumCapacityUnits; }
    public void setMaximumCapacityUnits(Integer maximumCapacityUnits) {
        this.maximumCapacityUnits = maximumCapacityUnits;
    }

    public Integer getMaximumOnDemandCapacityUnits() { return maximumOnDemandCapacityUnits; }
    public void setMaximumOnDemandCapacityUnits(Integer maximumOnDemandCapacityUnits) {
        this.maximumOnDemandCapacityUnits = maximumOnDemandCapacityUnits;
    }

    public Integer getMaximumCoreCapacityUnits() { return maximumCoreCapacityUnits; }
    public void setMaximumCoreCapacityUnits(Integer maximumCoreCapacityUnits) {
        this.maximumCoreCapacityUnits = maximumCoreCapacityUnits;
    }

    public Integer getUtilizationPerformanceIndex() { return utilizationPerformanceIndex; }
    public void setUtilizationPerformanceIndex(Integer utilizationPerformanceIndex) {
        this.utilizationPerformanceIndex = utilizationPerformanceIndex;
    }

    public String getScalingStrategy() { return scalingStrategy; }
    public void setScalingStrategy(String scalingStrategy) { this.scalingStrategy = scalingStrategy; }
}
