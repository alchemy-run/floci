package io.github.hectorvent.floci.services.mediatailor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.mediatailor.model.PlaybackConfiguration;
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
 * AWS Elemental MediaTailor restJson1 (playback configurations, prefetch
 * schedules, channel-assembly reads, logs).
 *
 * <p>{@link MediaTailorRoutingFilter} prefixes {@code /playbackConfiguration*} so they
 * do not collide with S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as {@code mediatailor}.
 */
@Path(MediaTailorRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaTailorController {

    private final MediaTailorService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MediaTailorController(
            MediaTailorService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/playbackConfiguration")
    public Response putPlaybackConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.toPlaybackConfiguration(service.putPlaybackConfiguration(region(headers), request)))
                .build());
    }

    @GET
    @Path("/playbackConfiguration/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getPlaybackConfiguration(
            @Context HttpHeaders headers, @PathParam("name") String name) {
        return handle(() -> Response.ok(service.toPlaybackConfiguration(
                service.getPlaybackConfiguration(region(headers), MediaTailorService.decode(name)))).build());
    }

    @DELETE
    @Path("/playbackConfiguration/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaybackConfiguration(
            @Context HttpHeaders headers, @PathParam("name") String name) {
        return handle(() -> {
            service.deletePlaybackConfiguration(region(headers), MediaTailorService.decode(name));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/playbackConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response listPlaybackConfigurations(
            @Context HttpHeaders headers,
            @QueryParam("MaxResults") Integer maxResults,
            @QueryParam("NextToken") String nextToken) {
        return handle(() -> {
            java.util.List<PlaybackConfiguration> items = service.listPlaybackConfigurations(region(headers));
            int start = 0;
            if (nextToken != null && !nextToken.isBlank()) {
                try {
                    start = Integer.parseInt(nextToken);
                } catch (NumberFormatException e) {
                    start = 0;
                }
            }
            int limit = maxResults == null || maxResults < 1 ? items.size() : maxResults;
            int end = Math.min(items.size(), start + limit);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("Items");
            for (int i = Math.max(0, start); i < end; i++) {
                array.add(service.toPlaybackConfiguration(items.get(i)));
            }
            if (end < items.size()) {
                response.put("NextToken", Integer.toString(end));
            }
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/configureLogs/playbackConfiguration")
    public Response configureLogsForPlaybackConfiguration(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.toConfigureLogsResponse(service.configureLogs(region(headers), request))).build());
    }

    @POST
    @Path("/prefetchSchedule/{PlaybackConfigurationName}/{Name}")
    public Response createPrefetchSchedule(
            @Context HttpHeaders headers,
            @PathParam("PlaybackConfigurationName") String playbackConfigurationName,
            @PathParam("Name") String name,
            String body) {
        return handle(body, request -> Response.ok(service.toPrefetchSchedule(
                service.createPrefetchSchedule(
                        region(headers),
                        MediaTailorService.decode(playbackConfigurationName),
                        MediaTailorService.decode(name),
                        request))).build());
    }

    @GET
    @Path("/prefetchSchedule/{PlaybackConfigurationName}/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response getPrefetchSchedule(
            @Context HttpHeaders headers,
            @PathParam("PlaybackConfigurationName") String playbackConfigurationName,
            @PathParam("Name") String name) {
        return handle(() -> Response.ok(service.toPrefetchSchedule(
                service.getPrefetchSchedule(
                        region(headers),
                        MediaTailorService.decode(playbackConfigurationName),
                        MediaTailorService.decode(name)))).build());
    }

    @POST
    @Path("/prefetchSchedule/{PlaybackConfigurationName}")
    public Response listPrefetchSchedules(
            @Context HttpHeaders headers,
            @PathParam("PlaybackConfigurationName") String playbackConfigurationName,
            String body) {
        return handle(body, request -> Response.ok(service.listPrefetchSchedulesResponse(
                region(headers), MediaTailorService.decode(playbackConfigurationName))).build());
    }

    @DELETE
    @Path("/prefetchSchedule/{PlaybackConfigurationName}/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePrefetchSchedule(
            @Context HttpHeaders headers,
            @PathParam("PlaybackConfigurationName") String playbackConfigurationName,
            @PathParam("Name") String name) {
        return handle(() -> {
            service.deletePrefetchSchedule(
                    region(headers),
                    MediaTailorService.decode(playbackConfigurationName),
                    MediaTailorService.decode(name));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/alerts")
    @Consumes(MediaType.WILDCARD)
    public Response listAlerts(@QueryParam("resourceArn") String resourceArn) {
        return handle(() -> Response.ok(service.listAlerts(resourceArn)).build());
    }

    @GET
    @Path("/channel/{ChannelName}/schedule")
    @Consumes(MediaType.WILDCARD)
    public Response getChannelSchedule(@PathParam("ChannelName") String channelName) {
        return handle(() -> Response.ok(service.getChannelSchedule(MediaTailorService.decode(channelName))).build());
    }

    @PUT
    @Path("/channel/{ChannelName}/start")
    @Consumes(MediaType.WILDCARD)
    public Response startChannel(@PathParam("ChannelName") String channelName) {
        return handle(() -> {
            service.startChannel(MediaTailorService.decode(channelName));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @PUT
    @Path("/channel/{ChannelName}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopChannel(@PathParam("ChannelName") String channelName) {
        return handle(() -> {
            service.stopChannel(MediaTailorService.decode(channelName));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/channel/{ChannelName}/program/{ProgramName}")
    public Response createProgram(
            @PathParam("ChannelName") String channelName,
            @PathParam("ProgramName") String programName,
            String body) {
        return handle(body, request -> Response.ok(service.createProgram(
                MediaTailorService.decode(channelName), MediaTailorService.decode(programName), request)).build());
    }

    @GET
    @Path("/channel/{ChannelName}/program/{ProgramName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeProgram(
            @PathParam("ChannelName") String channelName, @PathParam("ProgramName") String programName) {
        return handle(() -> Response.ok(service.describeProgram(
                MediaTailorService.decode(channelName), MediaTailorService.decode(programName))).build());
    }

    @PUT
    @Path("/channel/{ChannelName}/program/{ProgramName}")
    public Response updateProgram(
            @PathParam("ChannelName") String channelName,
            @PathParam("ProgramName") String programName,
            String body) {
        return handle(body, request -> Response.ok(service.updateProgram(
                MediaTailorService.decode(channelName), MediaTailorService.decode(programName), request)).build());
    }

    @DELETE
    @Path("/channel/{ChannelName}/program/{ProgramName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProgram(
            @PathParam("ChannelName") String channelName, @PathParam("ProgramName") String programName) {
        return handle(() -> {
            service.deleteProgram(MediaTailorService.decode(channelName), MediaTailorService.decode(programName));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response handle(String body, BodyHandler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response handle(NoBodyHandler handler) {
        try {
            return handler.handle();
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
                throw MediaTailorService.badRequest("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw MediaTailorService.badRequest("Request body is not valid JSON.");
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
    private interface BodyHandler {
        Response handle(JsonNode request);
    }

    @FunctionalInterface
    private interface NoBodyHandler {
        Response handle();
    }
}
