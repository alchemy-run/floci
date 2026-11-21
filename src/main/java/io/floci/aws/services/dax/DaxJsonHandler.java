package io.floci.aws.services.dax;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** JSON 1.1 dispatch for DAX ({@code X-Amz-Target: AmazonDAXV3.<Action>}). */
@ApplicationScoped
public class DaxJsonHandler {

    private final DaxService service;

    @Inject
    public DaxJsonHandler(DaxService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateCluster" -> Response.ok(service.createCluster(request, region)).build();
            case "DescribeClusters" -> Response.ok(service.describeClusters(request, region)).build();
            case "UpdateCluster" -> Response.ok(service.updateCluster(request, region)).build();
            case "DeleteCluster" -> Response.ok(service.deleteCluster(request, region)).build();
            case "IncreaseReplicationFactor" -> Response.ok(service.increaseReplicationFactor(request, region)).build();
            case "DecreaseReplicationFactor" -> Response.ok(service.decreaseReplicationFactor(request, region)).build();
            case "RebootNode" -> Response.ok(service.rebootNode(request, region)).build();
            case "ListTags" -> Response.ok(service.listTags(request, region)).build();
            case "TagResource" -> Response.ok(service.tagResource(request, region)).build();
            case "UntagResource" -> Response.ok(service.untagResource(request, region)).build();
            case "CreateParameterGroup" -> Response.ok(service.createParameterGroup(request, region)).build();
            case "DescribeParameterGroups" -> Response.ok(service.describeParameterGroups(request, region)).build();
            case "UpdateParameterGroup" -> Response.ok(service.updateParameterGroup(request, region)).build();
            case "DeleteParameterGroup" -> Response.ok(service.deleteParameterGroup(request, region)).build();
            case "DescribeParameters" -> Response.ok(service.describeParameters(request, region)).build();
            case "DescribeDefaultParameters" -> Response.ok(service.describeDefaultParameters(request)).build();
            case "CreateSubnetGroup" -> Response.ok(service.createSubnetGroup(request, region)).build();
            case "DescribeSubnetGroups" -> Response.ok(service.describeSubnetGroups(request, region)).build();
            case "UpdateSubnetGroup" -> Response.ok(service.updateSubnetGroup(request, region)).build();
            case "DeleteSubnetGroup" -> Response.ok(service.deleteSubnetGroup(request, region)).build();
            case "DescribeEvents" -> Response.ok(service.describeEvents(request, region)).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException", "Unknown operation: AmazonDAXV3."
                            + action))
                    .build();
        };
    }
}
