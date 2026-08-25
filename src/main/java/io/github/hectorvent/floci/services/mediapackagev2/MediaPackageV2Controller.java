package io.github.hectorvent.floci.services.mediapackagev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.mediapackagev2.model.Channel;
import io.github.hectorvent.floci.services.mediapackagev2.model.ChannelGroup;
import io.github.hectorvent.floci.services.mediapackagev2.model.HarvestJob;
import io.github.hectorvent.floci.services.mediapackagev2.model.OriginEndpoint;
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
 * AWS Elemental MediaPackage v2 restJson1.
 *
 * <p>{@link MediaPackageV2RoutingFilter} prefixes {@code /channelGroup} so it
 * does not collide with S3 path-style routes. Tag APIs share {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code mediapackagev2}.
 */
@Path(MediaPackageV2RoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaPackageV2Controller {

    private final MediaPackageV2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MediaPackageV2Controller(
            MediaPackageV2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/channelGroup")
    public Response createChannelGroup(@Context HttpHeaders headers, String body) {
        ChannelGroup group = service.createChannelGroup(region(headers), parse(body));
        return Response.ok(service.toChannelGroup(group)).build();
    }

    @GET
    @Path("/channelGroup")
    @Consumes(MediaType.WILDCARD)
    public Response listChannelGroups(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (ChannelGroup group : service.listChannelGroups(region(headers))) {
            items.add(service.toListedChannelGroup(group));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}")
    @Consumes(MediaType.WILDCARD)
    public Response getChannelGroup(
            @Context HttpHeaders headers, @PathParam("channelGroupName") String channelGroupName) {
        return Response.ok(service.toChannelGroup(service.getChannelGroup(region(headers), channelGroupName))).build();
    }

    @PUT
    @Path("/channelGroup/{channelGroupName}")
    public Response updateChannelGroup(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            String body) {
        ChannelGroup group = service.updateChannelGroup(region(headers), channelGroupName, parse(body));
        return Response.ok(service.toChannelGroup(group)).build();
    }

    @DELETE
    @Path("/channelGroup/{channelGroupName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChannelGroup(
            @Context HttpHeaders headers, @PathParam("channelGroupName") String channelGroupName) {
        service.deleteChannelGroup(region(headers), channelGroupName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel")
    public Response createChannel(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            String body) {
        Channel channel = service.createChannel(region(headers), channelGroupName, parse(body));
        return Response.ok(service.toChannel(channel)).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel")
    @Consumes(MediaType.WILDCARD)
    public Response listChannels(
            @Context HttpHeaders headers, @PathParam("channelGroupName") String channelGroupName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (Channel channel : service.listChannels(region(headers), channelGroupName)) {
            items.add(service.toListedChannel(channel));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}{slash: (/)?}")
    @Consumes(MediaType.WILDCARD)
    public Response getChannel(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        return Response.ok(service.toChannel(service.getChannel(region(headers), channelGroupName, channelName)))
                .build();
    }

    @PUT
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}{slash: (/)?}")
    public Response updateChannel(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            String body) {
        Channel channel = service.updateChannel(region(headers), channelGroupName, channelName, parse(body));
        return Response.ok(service.toChannel(channel)).build();
    }

    @DELETE
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}{slash: (/)?}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChannel(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        service.deleteChannel(region(headers), channelGroupName, channelName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/policy")
    public Response putChannelPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            String body) {
        service.putChannelPolicy(region(headers), channelGroupName, channelName, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getChannelPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        String region = region(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ChannelGroupName", channelGroupName);
        response.put("ChannelName", channelName);
        response.put("Policy", service.getChannelPolicy(region, channelGroupName, channelName));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChannelPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        service.deleteChannelPolicy(region(headers), channelGroupName, channelName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/reset")
    @Consumes(MediaType.WILDCARD)
    public Response resetChannelState(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        Channel channel = service.getChannel(region(headers), channelGroupName, channelName);
        long resetAt = service.resetChannelState(region(headers), channelGroupName, channelName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ChannelGroupName", channelGroupName);
        response.put("ChannelName", channelName);
        response.put("Arn", channel.getArn());
        response.put("ResetAt", resetAt);
        return Response.ok(response).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint")
    public Response createOriginEndpoint(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            String body) {
        OriginEndpoint endpoint =
                service.createOriginEndpoint(region(headers), channelGroupName, channelName, parse(body));
        return Response.ok(service.toOriginEndpoint(endpoint)).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint")
    @Consumes(MediaType.WILDCARD)
    public Response listOriginEndpoints(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (OriginEndpoint endpoint : service.listOriginEndpoints(region(headers), channelGroupName, channelName)) {
            items.add(service.toListedOriginEndpoint(endpoint));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}")
    @Consumes(MediaType.WILDCARD)
    public Response getOriginEndpoint(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName) {
        return Response.ok(service.toOriginEndpoint(
                        service.getOriginEndpoint(region(headers), channelGroupName, channelName, originEndpointName)))
                .build();
    }

    @PUT
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}")
    public Response updateOriginEndpoint(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName,
            String body) {
        OriginEndpoint endpoint = service.updateOriginEndpoint(
                region(headers), channelGroupName, channelName, originEndpointName, parse(body));
        return Response.ok(service.toOriginEndpoint(endpoint)).build();
    }

    @DELETE
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteOriginEndpoint(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName) {
        service.deleteOriginEndpoint(region(headers), channelGroupName, channelName, originEndpointName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/policy")
    public Response putOriginEndpointPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName,
            String body) {
        service.putOriginEndpointPolicy(
                region(headers), channelGroupName, channelName, originEndpointName, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getOriginEndpointPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName) {
        OriginEndpoint endpoint = service.getOriginEndpointPolicy(
                region(headers), channelGroupName, channelName, originEndpointName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ChannelGroupName", channelGroupName);
        response.put("ChannelName", channelName);
        response.put("OriginEndpointName", originEndpointName);
        response.put("Policy", endpoint.getPolicy());
        if (endpoint.getCdnAuthConfiguration() != null) {
            response.set("CdnAuthConfiguration", endpoint.getCdnAuthConfiguration());
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteOriginEndpointPolicy(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName) {
        service.deleteOriginEndpointPolicy(region(headers), channelGroupName, channelName, originEndpointName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/reset")
    @Consumes(MediaType.WILDCARD)
    public Response resetOriginEndpointState(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName) {
        OriginEndpoint endpoint =
                service.getOriginEndpoint(region(headers), channelGroupName, channelName, originEndpointName);
        long resetAt = service.resetOriginEndpointState(
                region(headers), channelGroupName, channelName, originEndpointName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ChannelGroupName", channelGroupName);
        response.put("ChannelName", channelName);
        response.put("OriginEndpointName", originEndpointName);
        response.put("Arn", endpoint.getArn());
        response.put("ResetAt", resetAt);
        return Response.ok(response).build();
    }

    @POST
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/harvestJob")
    public Response createHarvestJob(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName,
            String body) {
        HarvestJob job = service.createHarvestJob(
                region(headers), channelGroupName, channelName, originEndpointName, parse(body));
        return Response.ok(service.toHarvestJob(job)).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/harvestJob")
    @Consumes(MediaType.WILDCARD)
    public Response listHarvestJobs(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @QueryParam("channelName") String channelName,
            @QueryParam("originEndpointName") String originEndpointName) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Items");
        for (HarvestJob job : service.listHarvestJobs(region(headers), channelGroupName, channelName,
                originEndpointName)) {
            items.add(service.toHarvestJob(job));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/harvestJob/{harvestJobName}")
    @Consumes(MediaType.WILDCARD)
    public Response getHarvestJob(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName,
            @PathParam("harvestJobName") String harvestJobName) {
        return Response.ok(service.toHarvestJob(service.getHarvestJob(
                        region(headers), channelGroupName, channelName, originEndpointName, harvestJobName)))
                .build();
    }

    @PUT
    @Path("/channelGroup/{channelGroupName}/channel/{channelName}/originEndpoint/{originEndpointName}/harvestJob/{harvestJobName}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelHarvestJob(
            @Context HttpHeaders headers,
            @PathParam("channelGroupName") String channelGroupName,
            @PathParam("channelName") String channelName,
            @PathParam("originEndpointName") String originEndpointName,
            @PathParam("harvestJobName") String harvestJobName) {
        HarvestJob job = service.cancelHarvestJob(
                region(headers), channelGroupName, channelName, originEndpointName, harvestJobName);
        return Response.ok(service.toHarvestJob(job)).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
}
