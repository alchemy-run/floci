package io.github.hectorvent.floci.services.dlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dlm.model.LifecyclePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon DLM restJson1. Public AWS paths are {@code /policies} and
 * {@code /policies/{PolicyId}}; {@link DlmRoutingFilter} prefixes them so they
 * do not collide with IoT's {@code /policies} routes. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path(DlmRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DlmController {

    private final DlmService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DlmController(DlmService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/policies")
    public Response createLifecyclePolicy(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        LifecyclePolicy policy = service.createLifecyclePolicy(region, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyId", policy.getPolicyId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/policies")
    @Consumes(MediaType.WILDCARD)
    public Response getLifecyclePolicies(
            @Context HttpHeaders headers,
            @QueryParam("policyIds") List<String> policyIds,
            @QueryParam("state") String state,
            @QueryParam("resourceTypes") List<String> resourceTypes,
            @QueryParam("targetTags") List<String> targetTags,
            @QueryParam("tagsToAdd") List<String> tagsToAdd) {
        String region = regionResolver.resolveRegion(headers);
        List<LifecyclePolicy> policies = service.getLifecyclePolicies(
                region, policyIds, state, resourceTypes, targetTags, tagsToAdd);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Policies");
        for (LifecyclePolicy policy : policies) {
            list.add(service.toSummary(policy));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/policies/{policyId}")
    @Consumes(MediaType.WILDCARD)
    public Response getLifecyclePolicy(
            @Context HttpHeaders headers, @PathParam("policyId") String policyId) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Policy", service.toPolicy(service.getLifecyclePolicy(region, policyId)));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/policies/{policyId}")
    public Response updateLifecyclePolicy(
            @Context HttpHeaders headers, @PathParam("policyId") String policyId, String body) {
        String region = regionResolver.resolveRegion(headers);
        service.updateLifecyclePolicy(region, policyId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/policies/{policyId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLifecyclePolicy(
            @Context HttpHeaders headers, @PathParam("policyId") String policyId) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteLifecyclePolicy(region, policyId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("InvalidRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Request body is not valid JSON.", 400);
        }
    }
}
