package io.floci.aws.services.devopsguru;

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
import jakarta.ws.rs.PATCH;
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
 * The Amazon DevOps Guru REST API, routed here by {@link DevOpsGuruRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(DevOpsGuruRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DevOpsGuruController {

    /** DevOps Guru's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/devopsguru-request-shapes.json", "DevOps Guru request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = switch (type) {
                        case "string" -> "length";
                        case "integer", "long", "double", "float" -> "value";
                        default -> "size";
                    };
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private final DevOpsGuruService service;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public DevOpsGuruController(DevOpsGuruService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "devopsguru");
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

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String path = uri.getRequestUri().getRawPath().substring(DevOpsGuruRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "GetResourceCollection" -> service.getResourceCollection(input, region);
            case "UpdateResourceCollection" -> service.updateResourceCollection(input, region);
            case "ListMonitoredResources" -> service.listMonitoredResources(input, region);
            case "DescribeInsight" -> service.describeInsight(input, region);
            case "DeleteInsight" -> service.deleteInsight(input, region);
            case "ListInsights" -> service.listInsights(input, region);
            case "SearchInsights" -> service.searchInsights(input, region);
            case "DescribeAnomaly" -> service.describeAnomaly(input, region);
            case "ListAnomaliesForInsight" -> service.listAnomaliesForInsight(input, region);
            case "ListRecommendations" -> service.listRecommendations(input, region);
            case "ListEvents" -> service.listEvents(input, region);
            case "ListAnomalousLogGroups" -> service.listAnomalousLogGroups(input, region);
            case "DescribeAccountHealth" -> service.describeAccountHealth(input, region);
            case "DescribeAccountOverview" -> service.describeAccountOverview(input, region);
            case "DescribeResourceCollectionHealth" -> service.describeResourceCollectionHealth(input, region);
            case "DescribeOrganizationHealth" -> service.describeOrganizationHealth(input, region);
            case "DescribeOrganizationOverview" -> service.describeOrganizationOverview(input, region);
            case "DescribeOrganizationResourceCollectionHealth" ->
                    service.describeOrganizationResourceCollectionHealth(input, region);
            case "ListOrganizationInsights" -> service.listOrganizationInsights(input, region);
            case "SearchOrganizationInsights" -> service.searchOrganizationInsights(input, region);
            case "DescribeServiceIntegration" -> service.describeServiceIntegration(input, region);
            case "UpdateServiceIntegration" -> service.updateServiceIntegration(input, region);
            case "DescribeEventSourcesConfig" -> service.describeEventSourcesConfig(input, region);
            case "UpdateEventSourcesConfig" -> service.updateEventSourcesConfig(input, region);
            case "AddNotificationChannel" -> service.addNotificationChannel(input, region);
            case "RemoveNotificationChannel" -> service.removeNotificationChannel(input, region);
            case "ListNotificationChannels" -> service.listNotificationChannels(input, region);
            case "PutFeedback" -> service.putFeedback(input, region);
            case "DescribeFeedback" -> service.describeFeedback(input, region);
            case "StartCostEstimation" -> service.startCostEstimation(input, region);
            case "GetCostEstimation" -> service.getCostEstimation(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
