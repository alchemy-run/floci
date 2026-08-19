package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Container-level {@code healthCheck} on a task definition. Alchemy's
 * {@code Service} Phase-2 config sugar writes {@code command}, {@code interval},
 * {@code timeout}, {@code retries}, and {@code startPeriod} (seconds).
 */
@RegisterForReflection
public record HealthCheck(
        List<String> command,
        Integer interval,
        Integer timeout,
        Integer retries,
        Integer startPeriod) {}
