package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Cloud Map service registry attached to an ECS service
 * ({@code serviceRegistries[]} on Create/Update/DescribeServices).
 */
@RegisterForReflection
public record ServiceRegistry(
        String registryArn,
        Integer port,
        String containerName,
        Integer containerPort) {}
