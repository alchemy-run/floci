package io.github.hectorvent.floci.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.mediaconnect.model.Flow;
import io.github.hectorvent.floci.services.mediaconnect.model.FlowEntitlement;
import io.github.hectorvent.floci.services.mediaconnect.model.FlowOutput;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Elemental MediaConnect restJson1. Public AWS paths are {@code /v1/flows}
 * and peers; {@link MediaConnectRoutingFilter} prefixes them so they do not
 * collide with S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code mediaconnect}.
 */
@Path(MediaConnectRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaConnectController {

    private final MediaConnectService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MediaConnectController(
            MediaConnectService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v1/flows")
    public Response createFlow(@Context HttpHeaders headers, String body) {
        Flow flow = service.createFlow(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("flow", service.toFlow(flow));
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/flows")
    @Consumes(MediaType.WILDCARD)
    public Response listFlows(@Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode flows = response.putArray("flows");
        for (Flow flow : service.listFlows(region(headers))) {
            flows.add(service.toListedFlow(flow));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/flows/{flowArn}")
    @Consumes(MediaType.WILDCARD)
    public Response describeFlow(@Context HttpHeaders headers, @PathParam("flowArn") String flowArn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("flow", service.toFlow(service.describeFlow(region(headers), flowArn)));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/v1/flows/{flowArn}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteFlow(@Context HttpHeaders headers, @PathParam("flowArn") String flowArn) {
        Flow flow = service.deleteFlow(region(headers), flowArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.put("status", "DELETING");
        return Response.ok(response).build();
    }

    @PUT
    @Path("/v1/flows/{flowArn}/source/{sourceArn}")
    public Response updateFlowSource(
            @Context HttpHeaders headers,
            @PathParam("flowArn") String flowArn,
            @PathParam("sourceArn") String sourceArn,
            String body) {
        Flow flow = service.updateFlowSource(region(headers), flowArn, sourceArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.set("source", service.toSource(flow.getSource()));
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/flows/{flowArn}/outputs")
    public Response addFlowOutputs(
            @Context HttpHeaders headers, @PathParam("flowArn") String flowArn, String body) {
        java.util.List<FlowOutput> added = service.addFlowOutputs(region(headers), flowArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        Flow flow = service.describeFlow(region(headers), flowArn);
        response.put("flowArn", flow.getFlowArn());
        ArrayNode outputs = response.putArray("outputs");
        for (FlowOutput output : added) {
            outputs.add(service.toOutput(output));
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/v1/flows/{flowArn}/outputs/{outputArn}")
    public Response updateFlowOutput(
            @Context HttpHeaders headers,
            @PathParam("flowArn") String flowArn,
            @PathParam("outputArn") String outputArn,
            String body) {
        FlowOutput output = service.updateFlowOutput(region(headers), flowArn, outputArn, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", service.describeFlow(region(headers), flowArn).getFlowArn());
        response.set("output", service.toOutput(output));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/v1/flows/{flowArn}/outputs/{outputArn}")
    @Consumes(MediaType.WILDCARD)
    public Response removeFlowOutput(
            @Context HttpHeaders headers,
            @PathParam("flowArn") String flowArn,
            @PathParam("outputArn") String outputArn) {
        FlowOutput output = service.removeFlowOutput(region(headers), flowArn, outputArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", service.describeFlow(region(headers), flowArn).getFlowArn());
        response.put("outputArn", output.getOutputArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/flows/start/{flowArn}")
    @Consumes(MediaType.WILDCARD)
    public Response startFlow(@Context HttpHeaders headers, @PathParam("flowArn") String flowArn) {
        Flow flow = service.startFlow(region(headers), flowArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.put("status", flow.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/v1/flows/stop/{flowArn}")
    @Consumes(MediaType.WILDCARD)
    public Response stopFlow(@Context HttpHeaders headers, @PathParam("flowArn") String flowArn) {
        Flow flow = service.stopFlow(region(headers), flowArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.put("status", flow.getStatus());
        return Response.ok(response).build();
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
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }
}
