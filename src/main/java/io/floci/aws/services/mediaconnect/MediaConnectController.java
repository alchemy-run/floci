package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The AWS Elemental MediaConnect REST API, routed here by {@link MediaConnectRouteFilter} and matched to operations by
 * its HTTP bindings ({@link RestJsonDispatcher}), which also rename its lowerCamel wire members to the model's.
 */
@Path(MediaConnectRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MediaConnectController {

    /** MediaConnect's request constraints, worded as a BadRequestException. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/mediaconnect-request-shapes.json", "MediaConnect request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return MediaConnectStore.badRequest(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return MediaConnectStore.badRequest(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return MediaConnectStore.badRequest(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return MediaConnectStore.badRequest(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return MediaConnectStore.badRequest(path + " is required.");
                }
            });

    private final MediaConnectFlows flows;
    private final MediaConnectBridges bridges;
    private final MediaConnectRouters routers;
    private final MediaConnectOfferings offerings;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public MediaConnectController(MediaConnectFlows flows, MediaConnectBridges bridges, MediaConnectRouters routers,
                                  MediaConnectOfferings offerings, RegionResolver regions, ObjectMapper mapper) {
        this.flows = flows;
        this.bridges = bridges;
        this.routers = routers;
        this.offerings = offerings;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "mediaconnect");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(MediaConnectRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "AddBridgeOutputs" -> bridges.addBridgeOutputs(input, region);
            case "AddBridgeSources" -> bridges.addBridgeSources(input, region);
            case "AddFlowMediaStreams" -> flows.addFlowMediaStreams(input, region);
            case "AddFlowOutputs" -> flows.addFlowOutputs(input, region);
            case "AddFlowSources" -> flows.addFlowSources(input, region);
            case "AddFlowVpcInterfaces" -> flows.addFlowVpcInterfaces(input, region);
            case "BatchGetRouterInput" -> routers.batchGetRouterInput(input, region);
            case "BatchGetRouterNetworkInterface" -> routers.batchGetRouterNetworkInterface(input, region);
            case "BatchGetRouterOutput" -> routers.batchGetRouterOutput(input, region);
            case "CreateBridge" -> bridges.createBridge(input, region);
            case "CreateFlow" -> flows.createFlow(input, region);
            case "CreateGateway" -> bridges.createGateway(input, region);
            case "CreateRouterInput" -> routers.createRouterInput(input, region);
            case "CreateRouterNetworkInterface" -> routers.createRouterNetworkInterface(input, region);
            case "CreateRouterOutput" -> routers.createRouterOutput(input, region);
            case "DeleteBridge" -> bridges.deleteBridge(input, region);
            case "DeleteFlow" -> flows.deleteFlow(input, region);
            case "DeleteGateway" -> bridges.deleteGateway(input, region);
            case "DeleteRouterInput" -> routers.deleteRouterInput(input, region);
            case "DeleteRouterNetworkInterface" -> routers.deleteRouterNetworkInterface(input, region);
            case "DeleteRouterOutput" -> routers.deleteRouterOutput(input, region);
            case "DeregisterGatewayInstance" -> bridges.deregisterGatewayInstance(input, region);
            case "DescribeBridge" -> bridges.describeBridge(input, region);
            case "DescribeFlow" -> flows.describeFlow(input, region);
            case "DescribeFlowSourceMetadata" -> flows.describeFlowSourceMetadata(input, region);
            case "DescribeFlowSourceThumbnail" -> flows.describeFlowSourceThumbnail(input, region);
            case "DescribeGateway" -> bridges.describeGateway(input, region);
            case "DescribeGatewayInstance" -> bridges.describeGatewayInstance(input, region);
            case "DescribeOffering" -> offerings.describeOffering(input, region);
            case "DescribeReservation" -> offerings.describeReservation(input, region);
            case "GetRouterInput" -> routers.getRouterInput(input, region);
            case "GetRouterInputSourceMetadata" -> routers.getRouterInputSourceMetadata(input, region);
            case "GetRouterInputThumbnail" -> routers.getRouterInputThumbnail(input, region);
            case "GetRouterNetworkInterface" -> routers.getRouterNetworkInterface(input, region);
            case "GetRouterOutput" -> routers.getRouterOutput(input, region);
            case "GrantFlowEntitlements" -> flows.grantFlowEntitlements(input, region);
            case "ListBridges" -> bridges.listBridges(input, region);
            case "ListEntitlements" -> flows.listEntitlements(input, region);
            case "ListFlows" -> flows.listFlows(input, region);
            case "ListGatewayInstances" -> bridges.listGatewayInstances(input, region);
            case "ListGateways" -> bridges.listGateways(input, region);
            case "ListOfferings" -> offerings.listOfferings(input, region);
            case "ListReservations" -> offerings.listReservations(input, region);
            case "ListRouterInputs" -> routers.listRouterInputs(input, region);
            case "ListRouterNetworkInterfaces" -> routers.listRouterNetworkInterfaces(input, region);
            case "ListRouterOutputs" -> routers.listRouterOutputs(input, region);
            case "ListTagsForGlobalResource" -> offerings.listTagsForGlobalResource(input, region);
            case "ListTagsForResource" -> offerings.listTagsForResource(input, region);
            case "PurchaseOffering" -> offerings.purchaseOffering(input, region);
            case "RemoveBridgeOutput" -> bridges.removeBridgeOutput(input, region);
            case "RemoveBridgeSource" -> bridges.removeBridgeSource(input, region);
            case "RemoveFlowMediaStream" -> flows.removeFlowMediaStream(input, region);
            case "RemoveFlowOutput" -> flows.removeFlowOutput(input, region);
            case "RemoveFlowSource" -> flows.removeFlowSource(input, region);
            case "RemoveFlowVpcInterface" -> flows.removeFlowVpcInterface(input, region);
            case "RestartRouterInput" -> routers.restartRouterInput(input, region);
            case "RestartRouterOutput" -> routers.restartRouterOutput(input, region);
            case "RevokeFlowEntitlement" -> flows.revokeFlowEntitlement(input, region);
            case "StartFlow" -> flows.startFlow(input, region);
            case "StartRouterInput" -> routers.startRouterInput(input, region);
            case "StartRouterOutput" -> routers.startRouterOutput(input, region);
            case "StopFlow" -> flows.stopFlow(input, region);
            case "StopRouterInput" -> routers.stopRouterInput(input, region);
            case "StopRouterOutput" -> routers.stopRouterOutput(input, region);
            case "TagGlobalResource" -> offerings.tagGlobalResource(input, region);
            case "TagResource" -> offerings.tagResource(input, region);
            case "TakeRouterInput" -> routers.takeRouterInput(input, region);
            case "UntagGlobalResource" -> offerings.untagGlobalResource(input, region);
            case "UntagResource" -> offerings.untagResource(input, region);
            case "UpdateBridge" -> bridges.updateBridge(input, region);
            case "UpdateBridgeOutput" -> bridges.updateBridgeOutput(input, region);
            case "UpdateBridgeSource" -> bridges.updateBridgeSource(input, region);
            case "UpdateBridgeState" -> bridges.updateBridgeState(input, region);
            case "UpdateFlow" -> flows.updateFlow(input, region);
            case "UpdateFlowEntitlement" -> flows.updateFlowEntitlement(input, region);
            case "UpdateFlowMediaStream" -> flows.updateFlowMediaStream(input, region);
            case "UpdateFlowOutput" -> flows.updateFlowOutput(input, region);
            case "UpdateFlowSource" -> flows.updateFlowSource(input, region);
            case "UpdateGatewayInstance" -> bridges.updateGatewayInstance(input, region);
            case "UpdateRouterInput" -> routers.updateRouterInput(input, region);
            case "UpdateRouterNetworkInterface" -> routers.updateRouterNetworkInterface(input, region);
            case "UpdateRouterOutput" -> routers.updateRouterOutput(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
