package io.github.hectorvent.floci.services.medialive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.medialive.model.Channel;
import io.github.hectorvent.floci.services.medialive.model.Input;
import io.github.hectorvent.floci.services.medialive.model.Input.Destination;
import io.github.hectorvent.floci.services.medialive.model.Input.Source;
import io.github.hectorvent.floci.services.medialive.model.InputSecurityGroup;
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

import java.util.List;
import java.util.Map;

/**
 * AWS Elemental MediaLive restJson1 (input security groups, inputs, channels, tags).
 *
 * <p>{@link MediaLiveRoutingFilter} prefixes {@code /prod/*} so they do not collide
 * with API Gateway stage paths. Requests are signed as {@code medialive}.
 */
@Path(MediaLiveRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaLiveController {

    private final MediaLiveService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MediaLiveController(
            MediaLiveService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/prod/inputSecurityGroups")
    public Response createInputSecurityGroup(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            InputSecurityGroup group = service.createInputSecurityGroup(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("securityGroup", toGroup(group));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/inputSecurityGroups")
    @Consumes(MediaType.WILDCARD)
    public Response listInputSecurityGroups(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            MediaLiveService.Page<InputSecurityGroup> page =
                    service.listInputSecurityGroups(region(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("inputSecurityGroups");
            for (InputSecurityGroup group : page.items()) {
                array.add(toGroup(group));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/inputSecurityGroups/{inputSecurityGroupId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeInputSecurityGroup(
            @Context HttpHeaders headers,
            @PathParam("inputSecurityGroupId") String inputSecurityGroupId) {
        return handle(() -> Response.ok(
                toGroup(service.describeInputSecurityGroup(region(headers), inputSecurityGroupId))).build());
    }

    @PUT
    @Path("/prod/inputSecurityGroups/{inputSecurityGroupId}")
    public Response updateInputSecurityGroup(
            @Context HttpHeaders headers,
            @PathParam("inputSecurityGroupId") String inputSecurityGroupId,
            String body) {
        return handle(body, request -> {
            InputSecurityGroup group =
                    service.updateInputSecurityGroup(region(headers), inputSecurityGroupId, request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("securityGroup", toGroup(group));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/prod/inputSecurityGroups/{inputSecurityGroupId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInputSecurityGroup(
            @Context HttpHeaders headers,
            @PathParam("inputSecurityGroupId") String inputSecurityGroupId) {
        return handle(() -> {
            service.deleteInputSecurityGroup(region(headers), inputSecurityGroupId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/prod/inputs")
    public Response createInput(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Input input = service.createInput(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("input", toInput(input));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/inputs")
    @Consumes(MediaType.WILDCARD)
    public Response listInputs(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            MediaLiveService.Page<Input> page = service.listInputs(region(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("inputs");
            for (Input input : page.items()) {
                array.add(toInput(input));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/inputs/{inputId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeInput(@Context HttpHeaders headers, @PathParam("inputId") String inputId) {
        return handle(() -> Response.ok(toInput(service.describeInput(region(headers), inputId))).build());
    }

    @PUT
    @Path("/prod/inputs/{inputId}")
    public Response updateInput(
            @Context HttpHeaders headers, @PathParam("inputId") String inputId, String body) {
        return handle(body, request -> {
            Input input = service.updateInput(region(headers), inputId, request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("input", toInput(input));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/prod/inputs/{inputId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInput(@Context HttpHeaders headers, @PathParam("inputId") String inputId) {
        return handle(() -> {
            service.deleteInput(region(headers), inputId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/prod/channels")
    public Response createChannel(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Channel channel = service.createChannel(region(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(channel));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/channels")
    @Consumes(MediaType.WILDCARD)
    public Response listChannels(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            MediaLiveService.Page<Channel> page = service.listChannels(region(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("channels");
            for (Channel channel : page.items()) {
                array.add(toChannelSummary(channel));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/channels/{channelId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeChannel(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> Response.ok(toChannel(service.describeChannel(region(headers), channelId))).build());
    }

    @PUT
    @Path("/prod/channels/{channelId}")
    public Response updateChannel(
            @Context HttpHeaders headers, @PathParam("channelId") String channelId, String body) {
        return handle(body, request -> {
            Channel channel = service.updateChannel(region(headers), channelId, request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(channel));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/prod/channels/{channelId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChannel(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            service.deleteChannel(region(headers), channelId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/prod/channels/{channelId}/start")
    @Consumes(MediaType.WILDCARD)
    public Response startChannel(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(service.startChannel(region(headers), channelId)));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/prod/channels/{channelId}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopChannel(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(service.stopChannel(region(headers), channelId)));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/channels/{channelId}/schedule")
    @Consumes(MediaType.WILDCARD)
    public Response describeSchedule(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("scheduleActions", service.describeSchedule(region(headers), channelId));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/prod/channels/{channelId}/schedule")
    public Response batchUpdateSchedule(
            @Context HttpHeaders headers, @PathParam("channelId") String channelId, String body) {
        return handle(body, request ->
                Response.ok(service.batchUpdateSchedule(region(headers), channelId, request)).build());
    }

    @DELETE
    @Path("/prod/channels/{channelId}/schedule")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSchedule(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            service.deleteSchedule(region(headers), channelId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/prod/channels/{channelId}/alerts")
    @Consumes(MediaType.WILDCARD)
    public Response listAlerts(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            service.describeChannel(region(headers), channelId);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("alerts");
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/channels/{channelId}/thumbnails")
    @Consumes(MediaType.WILDCARD)
    public Response describeThumbnails(@Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            service.describeChannel(region(headers), channelId);
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("thumbnailDetails");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/prod/channels/{channelId}/restartChannelPipelines")
    @Consumes(MediaType.WILDCARD)
    public Response restartChannelPipelines(
            @Context HttpHeaders headers, @PathParam("channelId") String channelId) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("channel", toChannel(service.restartChannelPipelines(region(headers), channelId)));
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/prod/tags/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn) {
        return handle(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode tags = response.putObject("tags");
            for (Map.Entry<String, String> entry :
                    service.listTagsForResource(region(headers), resourceArn).entrySet()) {
                tags.put(entry.getKey(), entry.getValue());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/prod/tags/{resourceArn:.+}")
    public Response createTags(
            @Context HttpHeaders headers, @PathParam("resourceArn") String resourceArn, String body) {
        return handle(body, request -> {
            service.createTags(region(headers), resourceArn, request);
            return Response.ok().build();
        });
    }

    @DELETE
    @Path("/prod/tags/{resourceArn:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTags(
            @Context HttpHeaders headers,
            @PathParam("resourceArn") String resourceArn,
            @QueryParam("tagKeys") List<String> tagKeys) {
        return handle(() -> {
            service.deleteTags(region(headers), resourceArn, tagKeys);
            return Response.ok().build();
        });
    }

    private ObjectNode toGroup(InputSecurityGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", group.getArn());
        node.put("id", group.getId());
        node.put("state", group.getState());
        ArrayNode inputs = node.putArray("inputs");
        for (String inputId : group.getInputs()) {
            inputs.add(inputId);
        }
        ArrayNode channels = node.putArray("channels");
        for (String channelId : group.getChannels()) {
            channels.add(channelId);
        }
        ArrayNode rules = node.putArray("whitelistRules");
        for (String cidr : group.getWhitelistRules()) {
            rules.addObject().put("cidr", cidr);
        }
        node.set("tags", tagsNode(group.getTags()));
        return node;
    }

    private ObjectNode toInput(Input input) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", input.getArn());
        node.put("id", input.getId());
        putText(node, "name", input.getName());
        putText(node, "type", input.getType());
        putText(node, "state", input.getState());
        putText(node, "inputClass", input.getInputClass());
        putText(node, "roleArn", input.getRoleArn());
        ArrayNode attached = node.putArray("attachedChannels");
        for (String channelId : input.getAttachedChannels()) {
            attached.add(channelId);
        }
        ArrayNode destinations = node.putArray("destinations");
        for (Destination destination : input.getDestinations()) {
            ObjectNode dest = destinations.addObject();
            putText(dest, "url", destination.getUrl());
            putText(dest, "ip", destination.getIp());
            putText(dest, "port", destination.getPort());
        }
        ArrayNode sources = node.putArray("sources");
        for (Source source : input.getSources()) {
            ObjectNode src = sources.addObject();
            putText(src, "url", source.getUrl());
            putText(src, "username", source.getUsername());
            putText(src, "passwordParam", source.getPasswordParam());
        }
        ArrayNode securityGroups = node.putArray("securityGroups");
        for (String groupId : input.getSecurityGroups()) {
            securityGroups.add(groupId);
        }
        ArrayNode flows = node.putArray("mediaConnectFlows");
        for (String flowArn : input.getMediaConnectFlows()) {
            flows.addObject().put("flowArn", flowArn);
        }
        node.set("tags", tagsNode(input.getTags()));
        return node;
    }

    private ObjectNode toChannel(Channel channel) {
        ObjectNode node = toChannelSummary(channel);
        node.put("pipelinesRunningCount", channel.getPipelinesRunningCount());
        node.putArray("egressEndpoints");
        if (channel.getInputAttachments() != null && !channel.getInputAttachments().isNull()) {
            node.set("inputAttachments", channel.getInputAttachments());
        }
        if (channel.getEncoderSettings() != null && !channel.getEncoderSettings().isNull()) {
            node.set("encoderSettings", channel.getEncoderSettings());
        }
        if (channel.getDestinations() != null && !channel.getDestinations().isNull()) {
            node.set("destinations", channel.getDestinations());
        }
        if (channel.getInputSpecification() != null && !channel.getInputSpecification().isNull()) {
            node.set("inputSpecification", channel.getInputSpecification());
        }
        if (channel.getCdiInputSpecification() != null && !channel.getCdiInputSpecification().isNull()) {
            node.set("cdiInputSpecification", channel.getCdiInputSpecification());
        }
        if (channel.getMaintenance() != null && !channel.getMaintenance().isNull()) {
            node.set("maintenance", channel.getMaintenance());
        }
        return node;
    }

    private ObjectNode toChannelSummary(Channel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", channel.getArn());
        node.put("id", channel.getId());
        putText(node, "name", channel.getName());
        putText(node, "state", channel.getState());
        putText(node, "channelClass", channel.getChannelClass());
        putText(node, "roleArn", channel.getRoleArn());
        putText(node, "logLevel", channel.getLogLevel());
        node.set("tags", tagsNode(channel.getTags()));
        return node;
    }

    private ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
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
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
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
