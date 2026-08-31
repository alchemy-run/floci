package io.github.hectorvent.floci.services.dsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dsql.model.CdcStream;
import io.github.hectorvent.floci.services.dsql.model.Cluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Aurora DSQL restJson1. Public AWS paths are {@code /cluster} and
 * {@code /stream/{clusterIdentifier}}; {@link DsqlRoutingFilter} prefixes them so they
 * do not collide with S3's catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}.
 */
@Path(DsqlRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DsqlController {

    private final DsqlService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DsqlController(DsqlService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/cluster")
    public Response createCluster(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.createCluster(region, parse(body));
        return Response.ok(service.toCluster(cluster, false)).build();
    }

    @GET
    @Path("/cluster")
    @Consumes(MediaType.WILDCARD)
    public Response listClusters(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("clusters", service.clusterSummaries(service.listClusters(region)));
        return Response.ok(response).build();
    }

    @GET
    @Path("/cluster/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getCluster(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toCluster(service.getCluster(region, identifier), true)).build();
    }

    @POST
    @Path("/cluster/{identifier}")
    public Response updateCluster(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.updateCluster(region, identifier, parse(body));
        return Response.ok(service.toUpdateCluster(cluster)).build();
    }

    @DELETE
    @Path("/cluster/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCluster(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.deleteCluster(region, identifier);
        return Response.ok(service.toDeleteCluster(cluster)).build();
    }

    @GET
    @Path("/clusters/{identifier}/vpc-endpoint-service-name")
    @Consumes(MediaType.WILDCARD)
    public Response getVpcEndpointServiceName(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.getVpcEndpointServiceName(region, identifier)).build();
    }

    @GET
    @Path("/cluster/{identifier}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getClusterPolicy(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toClusterPolicy(service.getClusterPolicy(region, identifier))).build();
    }

    @POST
    @Path("/cluster/{identifier}/policy")
    public Response putClusterPolicy(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.putClusterPolicy(region, identifier, parse(body));
        return Response.ok(service.toPolicyVersion(cluster)).build();
    }

    @DELETE
    @Path("/cluster/{identifier}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteClusterPolicy(
            @Context HttpHeaders headers,
            @PathParam("identifier") String identifier,
            @QueryParam("expected-policy-version") String expectedPolicyVersion) {
        String region = regionResolver.resolveRegion(headers);
        Cluster cluster = service.deleteClusterPolicy(region, identifier, expectedPolicyVersion);
        return Response.ok(service.toPolicyVersion(cluster)).build();
    }

    @POST
    @Path("/stream/{clusterIdentifier}")
    public Response createStream(
            @Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterIdentifier, String body) {
        String region = regionResolver.resolveRegion(headers);
        CdcStream stream = service.createStream(region, clusterIdentifier, parse(body));
        return Response.ok(service.toCreateStream(stream)).build();
    }

    @GET
    @Path("/stream/{clusterIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response listStreams(
            @Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("streams", service.streamSummaries(service.listStreams(region, clusterIdentifier)));
        return Response.ok(response).build();
    }

    @GET
    @Path("/stream/{clusterIdentifier}/{streamIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getStream(
            @Context HttpHeaders headers,
            @PathParam("clusterIdentifier") String clusterIdentifier,
            @PathParam("streamIdentifier") String streamIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toStream(service.getStream(region, clusterIdentifier, streamIdentifier))).build();
    }

    @DELETE
    @Path("/stream/{clusterIdentifier}/{streamIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteStream(
            @Context HttpHeaders headers,
            @PathParam("clusterIdentifier") String clusterIdentifier,
            @PathParam("streamIdentifier") String streamIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        CdcStream stream = service.deleteStream(region, clusterIdentifier, streamIdentifier);
        return Response.ok(service.toDeleteStream(stream)).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException(
                        "ValidationException",
                        "Request body must be a JSON object.",
                        400,
                        java.util.Map.of("reason", "cannotParse"));
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException(
                    "ValidationException",
                    "Request body is not valid JSON.",
                    400,
                    java.util.Map.of("reason", "cannotParse"));
        }
    }
}
