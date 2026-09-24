package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of a {@code capacityProviderStrategy}:
 * {@code {"capacityProvider": "FARGATE_SPOT", "weight": 1, "base": 0}}.
 *
 * <p>{@code base} is the number of tasks placed on the provider before any weighting applies, and
 * at most one entry of a strategy may set it. {@code weight} is that provider's share of whatever
 * is left.
 */
@RegisterForReflection
public record CapacityProviderStrategyItem(String capacityProvider, Integer weight, Integer base) {

    public CapacityProviderStrategyItem {
        weight = weight != null ? weight : 0;
        base = base != null ? base : 0;
    }

    public CapacityProviderStrategyItem(String capacityProvider) {
        this(capacityProvider, 0, 0);
    }
}
