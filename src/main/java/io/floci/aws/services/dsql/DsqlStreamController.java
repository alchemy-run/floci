package io.floci.aws.services.dsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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

/** Aurora DSQL's change data capture stream routes ({@code /stream/...}). */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DsqlStreamController {

    private final DsqlStreamService streams;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DsqlStreamController(DsqlStreamService streams, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.streams = streams;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/stream/{clusterIdentifier}")
    public Response createStream(@Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterId,
                                 String body) {
        return Response.ok(streams.createStream(regionResolver.resolveRegion(headers), clusterId, read(body))).build();
    }

    @GET
    @Path("/stream/{clusterIdentifier}")
    public Response listStreams(@Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterId,
                                @QueryParam("max-results") Integer maxResults,
                                @QueryParam("next-token") String nextToken) {
        return Response.ok(streams.listStreams(regionResolver.resolveRegion(headers), clusterId, maxResults,
                nextToken)).build();
    }

    @GET
    @Path("/stream/{clusterIdentifier}/{streamIdentifier}")
    public Response getStream(@Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterId,
                              @PathParam("streamIdentifier") String streamId) {
        return Response.ok(streams.getStream(regionResolver.resolveRegion(headers), clusterId, streamId)).build();
    }

    @DELETE
    @Path("/stream/{clusterIdentifier}/{streamIdentifier}")
    public Response deleteStream(@Context HttpHeaders headers, @PathParam("clusterIdentifier") String clusterId,
                                 @PathParam("streamIdentifier") String streamId) {
        return Response.ok(streams.deleteStream(regionResolver.resolveRegion(headers), clusterId, streamId)).build();
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Malformed request body: " + e.getMessage(), 400);
        }
    }
}
