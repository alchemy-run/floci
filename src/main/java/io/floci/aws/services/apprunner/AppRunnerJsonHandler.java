package io.floci.aws.services.apprunner;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** AWS App Runner JSON 1.0 handler, under the {@code AppRunner.} target prefix. */
@ApplicationScoped
public class AppRunnerJsonHandler {
    private final AppRunnerService service;

    @Inject
    public AppRunnerJsonHandler(AppRunnerService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            Object body = switch (action) {
                case "AssociateCustomDomain" -> service.associateCustomDomain(request, region);
                case "CreateAutoScalingConfiguration" -> service.createAutoScaling(request, region);
                case "CreateConnection" -> service.createConnection(request, region);
                case "CreateObservabilityConfiguration" -> service.createObservability(request, region);
                case "CreateService" -> service.createService(request, region);
                case "CreateVpcConnector" -> service.createVpcConnector(request, region);
                case "CreateVpcIngressConnection" -> service.createVpcIngress(request, region);
                case "DeleteAutoScalingConfiguration" -> service.deleteAutoScaling(request, region);
                case "DeleteConnection" -> service.deleteConnection(request, region);
                case "DeleteObservabilityConfiguration" -> service.deleteObservability(request, region);
                case "DeleteService" -> service.deleteService(request, region);
                case "DeleteVpcConnector" -> service.deleteVpcConnector(request, region);
                case "DeleteVpcIngressConnection" -> service.deleteVpcIngress(request, region);
                case "DescribeAutoScalingConfiguration" -> service.describeAutoScaling(request, region);
                case "DescribeCustomDomains" -> service.describeCustomDomains(request, region);
                case "DescribeObservabilityConfiguration" -> service.describeObservability(request, region);
                case "DescribeService" -> service.describeService(request, region);
                case "DescribeVpcConnector" -> service.describeVpcConnector(request, region);
                case "DescribeVpcIngressConnection" -> service.describeVpcIngress(request, region);
                case "DisassociateCustomDomain" -> service.disassociateCustomDomain(request, region);
                case "ListAutoScalingConfigurations" -> service.listAutoScaling(request, region);
                case "ListConnections" -> service.listConnections(request, region);
                case "ListObservabilityConfigurations" -> service.listObservability(request, region);
                case "ListOperations" -> service.listOperations(request, region);
                case "ListServices" -> service.listServices(request, region);
                case "ListServicesForAutoScalingConfiguration" -> service.listServicesForAutoScaling(request, region);
                case "ListTagsForResource" -> service.listTags(request, region);
                case "ListVpcConnectors" -> service.listVpcConnectors(request, region);
                case "ListVpcIngressConnections" -> service.listVpcIngress(request, region);
                case "PauseService" -> service.pauseService(request, region);
                case "ResumeService" -> service.resumeService(request, region);
                case "StartDeployment" -> service.startDeployment(request, region);
                case "TagResource" -> service.tag(request, region);
                case "UntagResource" -> service.untag(request, region);
                case "UpdateDefaultAutoScalingConfiguration" -> service.updateDefaultAutoScaling(request, region);
                case "UpdateService" -> service.updateService(request, region);
                case "UpdateVpcIngressConnection" -> service.updateVpcIngress(request, region);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }
}
