package io.floci.aws.services.entityresolution;

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
 * The AWS Entity Resolution REST API, routed here by {@link EntityResolutionRouteFilter} and matched to operations by its
 * HTTP bindings ({@link RestJsonDispatcher}).
 */
@Path(EntityResolutionRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class EntityResolutionController {

    /** Entity Resolution's request constraints, worded as its deserializer words them. */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/entityresolution-request-shapes.json", "Entity Resolution request shapes", new RequestConstraintCheck.Messages() {
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

    private final EntityResolutionService service;
    private final EntityResolutionJobs jobs;
    private final RegionResolver regions;
    private final RestJsonDispatcher dispatcher;

    @Inject
    public EntityResolutionController(EntityResolutionService service, EntityResolutionJobs jobs, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.jobs = jobs;
        this.regions = regions;
        this.dispatcher = new RestJsonDispatcher(mapper, "entityresolution");
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
        String path = uri.getRequestUri().getRawPath().substring(EntityResolutionRouteFilter.PREFIX.length());
        String region = regions.resolveRegion(headers);
        return dispatcher.dispatch(method, path, uri.getQueryParameters(), headers, body,
                (operation, input) -> handle(operation, input, region));
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return EntityResolutionStore.invalid("1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        SHAPES.check(operation, input);
        return switch (operation) {
            case "AddPolicyStatement" -> service.addPolicyStatement(input, region);
            case "BatchDeleteUniqueId" -> jobs.batchDeleteUniqueId(input, region);
            case "CreateIdMappingWorkflow" -> service.createIdMappingWorkflow(input, region);
            case "CreateIdNamespace" -> service.createIdNamespace(input, region);
            case "CreateMatchingWorkflow" -> service.createMatchingWorkflow(input, region);
            case "CreateSchemaMapping" -> service.createSchemaMapping(input, region);
            case "DeleteIdMappingWorkflow" -> service.deleteIdMappingWorkflow(input, region);
            case "DeleteIdNamespace" -> service.deleteIdNamespace(input, region);
            case "DeleteMatchingWorkflow" -> service.deleteMatchingWorkflow(input, region);
            case "DeletePolicyStatement" -> service.deletePolicyStatement(input, region);
            case "DeleteSchemaMapping" -> service.deleteSchemaMapping(input, region);
            case "GenerateMatchId" -> jobs.generateMatchId(input, region);
            case "GetIdMappingJob" -> jobs.getIdMappingJob(input, region);
            case "GetIdMappingWorkflow" -> service.getIdMappingWorkflow(input, region);
            case "GetIdNamespace" -> service.getIdNamespace(input, region);
            case "GetMatchId" -> jobs.getMatchId(input, region);
            case "GetMatchingJob" -> jobs.getMatchingJob(input, region);
            case "GetMatchingWorkflow" -> service.getMatchingWorkflow(input, region);
            case "GetPolicy" -> service.getPolicy(input, region);
            case "GetProviderService" -> service.getProviderService(input, region);
            case "GetSchemaMapping" -> service.getSchemaMapping(input, region);
            case "ListIdMappingJobs" -> jobs.listIdMappingJobs(input, region);
            case "ListIdMappingWorkflows" -> service.listIdMappingWorkflows(input, region);
            case "ListIdNamespaces" -> service.listIdNamespaces(input, region);
            case "ListMatchingJobs" -> jobs.listMatchingJobs(input, region);
            case "ListMatchingWorkflows" -> service.listMatchingWorkflows(input, region);
            case "ListProviderServices" -> service.listProviderServices(input, region);
            case "ListSchemaMappings" -> service.listSchemaMappings(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "PutPolicy" -> service.putPolicy(input, region);
            case "StartIdMappingJob" -> jobs.startIdMappingJob(input, region);
            case "StartMatchingJob" -> jobs.startMatchingJob(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateIdMappingWorkflow" -> service.updateIdMappingWorkflow(input, region);
            case "UpdateIdNamespace" -> service.updateIdNamespace(input, region);
            case "UpdateMatchingWorkflow" -> service.updateMatchingWorkflow(input, region);
            case "UpdateSchemaMapping" -> service.updateSchemaMapping(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation
                    + ".", 404);
        };
    }
}
