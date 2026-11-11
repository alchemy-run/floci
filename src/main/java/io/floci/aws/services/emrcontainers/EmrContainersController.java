package io.floci.aws.services.emrcontainers;

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
import java.util.Set;

/**
 * The Amazon EMR on EKS REST API, routed here by {@link EmrContainersRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(EmrContainersRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class EmrContainersController {

    /** EMR on EKS's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/emrcontainers-request-shapes.json", "EMR on EKS request shapes", new RequestConstraintCheck.Messages() {
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

    private static final Set<String> TAGGING = Set.of("TagResource", "UntagResource", "ListTagsForResource");

    private final EmrContainersService service;
    private final EmrContainersJobRuns jobRuns;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public EmrContainersController(EmrContainersService service, EmrContainersJobRuns jobRuns, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.jobRuns = jobRuns;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "emrcontainers");
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
        String path = uri.getRequestUri().getRawPath().substring(EmrContainersRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return EmrContainersStore.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        // The model's ARN pattern for the tagging operations names virtual clusters only, which AWS does not
        // enforce: it tags job runs, endpoints and security configurations too, and refuses a job template's ARN.
        if (!TAGGING.contains(operation)) {
            SHAPES.check(operation, input);
        }
        return switch (operation) {
            case "CancelJobRun" -> jobRuns.cancelJobRun(input, region);
            case "CreateJobTemplate" -> service.createJobTemplate(input, region);
            case "CreateManagedEndpoint" -> service.createManagedEndpoint(input, region);
            case "CreateSecurityConfiguration" -> service.createSecurityConfiguration(input, region);
            case "CreateVirtualCluster" -> service.createVirtualCluster(input, region);
            case "DeleteJobTemplate" -> service.deleteJobTemplate(input, region);
            case "DeleteManagedEndpoint" -> service.deleteManagedEndpoint(input, region);
            case "DeleteSecurityConfiguration" -> service.deleteSecurityConfiguration(input, region);
            case "DeleteVirtualCluster" -> service.deleteVirtualCluster(input, region);
            case "DescribeJobRun" -> jobRuns.describeJobRun(input, region);
            case "DescribeJobTemplate" -> service.describeJobTemplate(input, region);
            case "DescribeManagedEndpoint" -> service.describeManagedEndpoint(input, region);
            case "DescribeSecurityConfiguration" -> service.describeSecurityConfiguration(input, region);
            case "DescribeVirtualCluster" -> service.describeVirtualCluster(input, region);
            case "GetManagedEndpointSessionCredentials" -> service.getManagedEndpointSessionCredentials(input, region);
            case "ListJobRuns" -> jobRuns.listJobRuns(input, region);
            case "ListJobTemplates" -> service.listJobTemplates(input, region);
            case "ListManagedEndpoints" -> service.listManagedEndpoints(input, region);
            case "ListSecurityConfigurations" -> service.listSecurityConfigurations(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "ListVirtualClusters" -> service.listVirtualClusters(input, region);
            case "StartJobRun" -> jobRuns.startJobRun(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateVirtualCluster" -> service.updateVirtualCluster(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
