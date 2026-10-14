package io.github.hectorvent.floci.services.cloudformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;

@RegisterForReflection
public record StackDriftDetection(String detectionId, String stackId, String detectionStatus,
                                  String stackDriftStatus, int driftedResourceCount, Instant timestamp,
                                  String statusReason, List<ResourceDrift> resources) {
    @RegisterForReflection
    public record ResourceDrift(String logicalId, String physicalId, String resourceType, String status,
                                String expectedProperties, String actualProperties, Instant timestamp,
                                List<PropertyDifference> differences) {}

    @RegisterForReflection
    public record PropertyDifference(String path, String expectedValue, String actualValue, String type) {}
}
