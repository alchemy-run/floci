package io.github.hectorvent.floci.services.repostspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.repostspace.model.Channel;
import io.github.hectorvent.floci.services.repostspace.model.Space;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
 * AWS re:Post Private restJson1. Public AWS paths are {@code /spaces} and
 * {@code /spaces/{spaceId}}; {@link RepostspaceRoutingFilter} prefixes them so
 * they do not collide with S3 path-style routes. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path(RepostspaceRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepostspaceController {

    private final RepostspaceService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RepostspaceController(
            RepostspaceService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/spaces")
    public Response createSpace(@Context HttpHeaders headers, String body) {
        Space space = service.createSpace(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("spaceId", space.getSpaceId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/spaces")
    @Consumes(MediaType.WILDCARD)
    public Response listSpaces(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        RepostspaceService.Page page = service.listSpaces(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode spaces = response.putArray("spaces");
        for (Space space : page.spaces()) {
            spaces.add(service.toSpaceData(space));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/spaces/{spaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSpace(@Context HttpHeaders headers, @PathParam("spaceId") String spaceId) {
        Space space = service.getSpace(regionResolver.resolveRegion(headers), spaceId);
        return Response.ok(service.toGetSpace(space)).build();
    }

    @PUT
    @Path("/spaces/{spaceId}")
    public Response updateSpace(
            @Context HttpHeaders headers, @PathParam("spaceId") String spaceId, String body) {
        service.updateSpace(regionResolver.resolveRegion(headers), spaceId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/spaces/{spaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSpace(@Context HttpHeaders headers, @PathParam("spaceId") String spaceId) {
        service.deleteSpace(regionResolver.resolveRegion(headers), spaceId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/spaces/{spaceId}/channels")
    public Response createChannel(
            @Context HttpHeaders headers, @PathParam("spaceId") String spaceId, String body) {
        Channel channel = service.createChannel(regionResolver.resolveRegion(headers), spaceId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("channelId", channel.getChannelId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/spaces/{spaceId}/channels")
    @Consumes(MediaType.WILDCARD)
    public Response listChannels(@Context HttpHeaders headers, @PathParam("spaceId") String spaceId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("channels");
        for (Channel channel : service.listChannels(regionResolver.resolveRegion(headers), spaceId)) {
            items.add(service.toChannelData(channel));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/spaces/{spaceId}/channels/{channelId}")
    @Consumes(MediaType.WILDCARD)
    public Response getChannel(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("channelId") String channelId) {
        return Response.ok(service.toChannel(
                service.getChannel(regionResolver.resolveRegion(headers), spaceId, channelId))).build();
    }

    @PUT
    @Path("/spaces/{spaceId}/channels/{channelId}")
    public Response updateChannel(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("channelId") String channelId,
            String body) {
        service.updateChannel(regionResolver.resolveRegion(headers), spaceId, channelId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/spaces/{spaceId}/roles")
    public Response batchAddRole(
            @Context HttpHeaders headers, @PathParam("spaceId") String spaceId, String body) {
        return Response.ok(service.toBatchResult(
                service.batchAddRole(regionResolver.resolveRegion(headers), spaceId, parse(body)),
                "addedAccessorIds")).build();
    }

    @PATCH
    @Path("/spaces/{spaceId}/roles")
    public Response batchRemoveRole(
            @Context HttpHeaders headers, @PathParam("spaceId") String spaceId, String body) {
        return Response.ok(service.toBatchResult(
                service.batchRemoveRole(regionResolver.resolveRegion(headers), spaceId, parse(body)),
                "removedAccessorIds")).build();
    }

    @POST
    @Path("/spaces/{spaceId}/channels/{channelId}/roles")
    public Response batchAddChannelRole(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("channelId") String channelId,
            String body) {
        return Response.ok(service.toBatchResult(
                service.batchAddChannelRole(
                        regionResolver.resolveRegion(headers), spaceId, channelId, parse(body)),
                "addedAccessorIds")).build();
    }

    @PATCH
    @Path("/spaces/{spaceId}/channels/{channelId}/roles")
    public Response batchRemoveChannelRole(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("channelId") String channelId,
            String body) {
        return Response.ok(service.toBatchResult(
                service.batchRemoveChannelRole(
                        regionResolver.resolveRegion(headers), spaceId, channelId, parse(body)),
                "removedAccessorIds")).build();
    }

    @POST
    @Path("/spaces/{spaceId}/admins/{adminId}")
    @Consumes(MediaType.WILDCARD)
    public Response registerAdmin(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("adminId") String adminId) {
        service.registerAdmin(regionResolver.resolveRegion(headers), spaceId, adminId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/spaces/{spaceId}/admins/{adminId}")
    @Consumes(MediaType.WILDCARD)
    public Response deregisterAdmin(
            @Context HttpHeaders headers,
            @PathParam("spaceId") String spaceId,
            @PathParam("adminId") String adminId) {
        service.deregisterAdmin(regionResolver.resolveRegion(headers), spaceId, adminId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/spaces/{spaceId}/invite")
    public Response sendInvites(
            @Context HttpHeaders headers, @PathParam("spaceId") String spaceId, String body) {
        service.sendInvites(regionResolver.resolveRegion(headers), spaceId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw RepostspaceService.validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw RepostspaceService.validation("Request body is not valid JSON.");
        }
    }
}
