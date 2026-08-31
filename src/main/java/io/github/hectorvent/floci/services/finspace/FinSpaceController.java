package io.github.hectorvent.floci.services.finspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.finspace.model.FinSpaceEnvironment;
import io.github.hectorvent.floci.services.finspace.model.KxChangeset;
import io.github.hectorvent.floci.services.finspace.model.KxDataview;
import io.github.hectorvent.floci.services.finspace.model.KxEnvironment;
import io.github.hectorvent.floci.services.finspace.model.KxNode;
import io.github.hectorvent.floci.services.finspace.model.KxUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon FinSpace restJson1. Public AWS paths are {@code /kx/environments} and
 * {@code /environment}; {@link FinSpaceRoutingFilter} prefixes them so they do
 * not collide with S3 path-style routes. Tag APIs share {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code finspace}.
 */
@Path(FinSpaceRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FinSpaceController {

    private final FinSpaceService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public FinSpaceController(
            FinSpaceService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/kx/environments")
    public Response createKxEnvironment(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            KxEnvironment env = service.createKxEnvironment(region(headers), parse(body));
            return Response.ok(service.toCreateKxEnvironmentNode(env)).build();
        });
    }

    @GET
    @Path("/kx/environments")
    @Consumes(MediaType.WILDCARD)
    public Response listKxEnvironments(@Context HttpHeaders headers) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("environments");
            for (KxEnvironment env : service.listKxEnvironments(region(headers))) {
                items.add(service.toKxEnvironmentNode(env));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/kx/environments/{environmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxEnvironment(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId) {
        return handle(() -> Response.ok(service.toKxEnvironmentNode(
                service.getKxEnvironment(region(headers), environmentId))).build());
    }

    @PUT
    @Path("/kx/environments/{environmentId}")
    public Response updateKxEnvironment(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId, String body) {
        return handle(() -> Response.ok(service.toKxEnvironmentNode(
                service.updateKxEnvironment(region(headers), environmentId, parse(body)))).build());
    }

    @PUT
    @Path("/kx/environments/{environmentId}/network")
    public Response updateKxEnvironmentNetwork(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId, String body) {
        return handle(() -> Response.ok(service.toKxEnvironmentNode(
                service.updateKxEnvironmentNetwork(region(headers), environmentId, parse(body)))).build());
    }

    @DELETE
    @Path("/kx/environments/{environmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteKxEnvironment(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId) {
        return handle(() -> {
            service.deleteKxEnvironment(region(headers), environmentId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/kx/environments/{environmentId}/connectionString")
    @Consumes(MediaType.WILDCARD)
    public Response getKxConnectionString(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @QueryParam("userArn") String userArn,
            @QueryParam("clusterName") String clusterName) {
        return handle(() -> Response.ok(service.getKxConnectionString(
                region(headers), environmentId, userArn, clusterName)).build());
    }

    @POST
    @Path("/kx/environments/{environmentId}/databases")
    public Response createKxDatabase(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId, String body) {
        return handle(() -> Response.ok(service.toKxDatabaseNode(
                service.createKxDatabase(region(headers), environmentId, parse(body)))).build());
    }

    @GET
    @Path("/kx/environments/{environmentId}/databases/{databaseName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxDatabase(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName) {
        return handle(() -> Response.ok(service.toKxDatabaseNode(
                service.getKxDatabase(region(headers), environmentId, databaseName))).build());
    }

    @PUT
    @Path("/kx/environments/{environmentId}/databases/{databaseName}")
    public Response updateKxDatabase(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName,
            String body) {
        return handle(() -> Response.ok(service.toKxDatabaseNode(
                service.updateKxDatabase(region(headers), environmentId, databaseName, parse(body)))).build());
    }

    @DELETE
    @Path("/kx/environments/{environmentId}/databases/{databaseName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteKxDatabase(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName) {
        return handle(() -> {
            service.deleteKxDatabase(region(headers), environmentId, databaseName);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/kx/environments/{environmentId}/databases/{databaseName}/changesets")
    public Response createKxChangeset(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName,
            String body) {
        return handle(() -> Response.ok(service.toChangesetNode(
                service.createKxChangeset(region(headers), environmentId, databaseName, parse(body)))).build());
    }

    @GET
    @Path("/kx/environments/{environmentId}/databases/{databaseName}/changesets")
    @Consumes(MediaType.WILDCARD)
    public Response listKxChangesets(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("kxChangesets");
            for (KxChangeset changeset : service.listKxChangesets(region(headers), environmentId, databaseName)) {
                items.add(service.toChangesetSummary(changeset));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/kx/environments/{environmentId}/databases/{databaseName}/dataviews")
    public Response createKxDataview(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName,
            String body) {
        return handle(() -> {
            KxDataview dataview = service.createKxDataview(
                    region(headers), environmentId, databaseName, parse(body));
            return Response.ok(service.toDataviewNode(dataview)).build();
        });
    }

    @GET
    @Path("/kx/environments/{environmentId}/databases/{databaseName}/dataviews/{dataviewName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxDataview(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("databaseName") String databaseName,
            @PathParam("dataviewName") String dataviewName) {
        return handle(() -> Response.ok(service.toDataviewNode(
                service.getKxDataview(region(headers), environmentId, databaseName, dataviewName))).build());
    }

    @POST
    @Path("/kx/environments/{environmentId}/clusters")
    public Response createKxCluster(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId, String body) {
        return handle(() -> Response.ok(service.toClusterNode(
                service.createKxCluster(region(headers), environmentId, parse(body)))).build());
    }

    @GET
    @Path("/kx/environments/{environmentId}/clusters/{clusterName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxCluster(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("clusterName") String clusterName) {
        return handle(() -> Response.ok(service.toClusterNode(
                service.requireKxCluster(region(headers), environmentId, clusterName))).build());
    }

    @GET
    @Path("/kx/environments/{environmentId}/scalingGroups/{scalingGroupName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxScalingGroup(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("scalingGroupName") String scalingGroupName) {
        return handle(() -> {
            service.requireKxScalingGroup(region(headers), environmentId, scalingGroupName);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/kx/environments/{environmentId}/kxvolumes/{volumeName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxVolume(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("volumeName") String volumeName) {
        return handle(() -> {
            service.requireKxVolume(region(headers), environmentId, volumeName);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/kx/environments/{environmentId}/clusters/{clusterName}/nodes")
    @Consumes(MediaType.WILDCARD)
    public Response listKxClusterNodes(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("clusterName") String clusterName) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode nodes = response.putArray("nodes");
            for (KxNode node : service.listKxClusterNodes(region(headers), environmentId, clusterName)) {
                nodes.add(service.toNode(node));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/kx/environments/{environmentId}/users")
    public Response createKxUser(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId, String body) {
        return handle(() -> Response.ok(service.toUserNode(
                service.createKxUser(region(headers), environmentId, parse(body)))).build());
    }

    @GET
    @Path("/kx/environments/{environmentId}/users/{userName}")
    @Consumes(MediaType.WILDCARD)
    public Response getKxUser(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            @PathParam("userName") String userName) {
        return handle(() -> {
            KxUser user = service.getKxUser(region(headers), environmentId, userName);
            return Response.ok(service.toUserNode(user)).build();
        });
    }

    @POST
    @Path("/environment")
    public Response createEnvironment(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(service.toCreateClassicEnvironment(
                service.createEnvironment(region(headers), parse(body)))).build());
    }

    @GET
    @Path("/environment")
    @Consumes(MediaType.WILDCARD)
    public Response listEnvironments(@Context HttpHeaders headers) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("environments");
            for (FinSpaceEnvironment env : service.listEnvironments(region(headers))) {
                items.add(service.toEnvironmentNode(env));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/environment/{environmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response getEnvironment(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("environment", service.toEnvironmentNode(
                    service.getEnvironment(region(headers), environmentId)));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/environment/{environmentId}")
    public Response updateEnvironment(
            @Context HttpHeaders headers,
            @PathParam("environmentId") String environmentId,
            String body) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("environment", service.toEnvironmentNode(
                    service.updateEnvironment(region(headers), environmentId, parse(body))));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/environment/{environmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEnvironment(
            @Context HttpHeaders headers, @PathParam("environmentId") String environmentId) {
        return handle(() -> {
            service.deleteEnvironment(region(headers), environmentId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response handle(Action action) {
        try {
            return action.run();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Action {
        Response run();
    }
}
