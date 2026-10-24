package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

/** Resolves the current reachable endpoint of the container owned by an RDS runtime. */
@FunctionalInterface
public interface RdsBackendEndpointResolver {

    EndpointInfo resolveBackendEndpoint(String runtimeId, DatabaseEngine engine);
}
