package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Capacity bounds applied when a scheduled action fires.
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_ScalableTargetAction.html">ScalableTargetAction</a>
 */
@RegisterForReflection
public class ScalableTargetAction {

    private Integer minCapacity;
    private Integer maxCapacity;

    public Integer getMinCapacity() { return minCapacity; }
    public void setMinCapacity(Integer v) { this.minCapacity = v; }

    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer v) { this.maxCapacity = v; }
}
