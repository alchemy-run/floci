package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record DeploymentConfiguration(
        Integer minimumHealthyPercent,
        Integer maximumPercent,
        DeploymentCircuitBreaker deploymentCircuitBreaker) {

    @RegisterForReflection
    public record DeploymentCircuitBreaker(Boolean enable, Boolean rollback) {}
}
