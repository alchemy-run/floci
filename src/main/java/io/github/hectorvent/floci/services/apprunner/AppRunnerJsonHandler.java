package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * JSON 1.0 handler for App Runner. Dispatched from {@code AwsJsonController}
 * under the {@code AppRunner.} target prefix.
 */
@ApplicationScoped
public class AppRunnerJsonHandler {

    private final AppRunnerService service;
    private final AppRunnerVpcConnectorService vpcConnectors;
    private final AppRunnerAutoScalingConfigurationService autoScaling;
    private final AppRunnerObservabilityConfigurationService observability;

    @Inject
    public AppRunnerJsonHandler(
            AppRunnerService service,
            AppRunnerVpcConnectorService vpcConnectors,
            AppRunnerAutoScalingConfigurationService autoScaling,
            AppRunnerObservabilityConfigurationService observability) {
        this.service = service;
        this.vpcConnectors = vpcConnectors;
        this.autoScaling = autoScaling;
        this.observability = observability;
    }

    public Response handle(String action, JsonNode request, String region) {
        if (AppRunnerVpcConnectorService.isVpcConnectorAction(action, request)) {
            return Response.ok(vpcConnectors.handle(action, request, region)).build();
        }
        if (AppRunnerAutoScalingConfigurationService.isAutoScalingAction(action, request)) {
            return Response.ok(autoScaling.handle(action, request, region)).build();
        }
        if (AppRunnerObservabilityConfigurationService.isObservabilityAction(action, request)) {
            return Response.ok(observability.handle(action, request, region)).build();
        }
        return Response.ok(service.handle(action, request, region)).build();
    }
}
