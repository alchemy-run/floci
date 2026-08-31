package io.github.hectorvent.floci.services.rbin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.rbin.model.RetentionRule;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon Recycle Bin restJson1. Public AWS paths are {@code /rules},
 * {@code /rules/{Identifier}}, {@code /list-rules}, and lock/unlock suffixes;
 * {@link RbinRoutingFilter} prefixes them so they do not collide with IoT's
 * {@code /rules} routes. Tag APIs share {@code /tags/{arn}} and are dispatched
 * by {@code SharedTagsController}.
 */
@Path(RbinRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RbinController {

    private final RbinService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RbinController(RbinService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/rules")
    public Response createRule(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        RetentionRule rule = service.createRule(region, parse(body));
        return Response.ok(service.toCreateRule(rule)).build();
    }

    @GET
    @Path("/rules/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getRule(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toRule(service.getRule(region, identifier))).build();
    }

    @PATCH
    @Path("/rules/{identifier}")
    public Response updateRule(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        String region = regionResolver.resolveRegion(headers);
        RetentionRule rule = service.updateRule(region, identifier, parse(body));
        return Response.ok(service.toRule(rule)).build();
    }

    @DELETE
    @Path("/rules/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRule(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRule(region, identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/list-rules")
    public Response listRules(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        List<RetentionRule> rules = service.listRules(region, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Rules");
        for (RetentionRule rule : rules) {
            list.add(service.toSummary(rule));
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/rules/{identifier}/lock")
    public Response lockRule(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        String region = regionResolver.resolveRegion(headers);
        RetentionRule rule = service.lockRule(region, identifier, parse(body));
        return Response.ok(service.toRule(rule)).build();
    }

    @PATCH
    @Path("/rules/{identifier}/unlock")
    @Consumes(MediaType.WILDCARD)
    public Response unlockRule(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        RetentionRule rule = service.unlockRule(region, identifier);
        return Response.ok(service.toRule(rule)).build();
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
