package io.floci.aws.services.appflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The Amazon AppFlow REST API: one {@code POST /<operation-name>} route per operation. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AppFlowController {
    private final AppFlowService service;
    private final AppFlowRuns runs;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public AppFlowController(AppFlowService service, AppFlowRuns runs, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.runs = runs;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/cancel-flow-executions")
    public Response cancelFlowExecutions(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(runs.cancel(in, region)).build();
    }

    @POST
    @Path("/create-connector-profile")
    public Response createConnectorProfile(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.createProfile(in, region)).build();
    }

    @POST
    @Path("/create-flow")
    public Response createFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.createFlow(in, region)).build();
    }

    @POST
    @Path("/delete-connector-profile")
    public Response deleteConnectorProfile(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        service.deleteProfile(in, region);
        return Response.ok(mapper.createObjectNode()).build();
    }

    @POST
    @Path("/delete-flow")
    public Response deleteFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        service.deleteFlow(in, region, runs);
        return Response.ok(mapper.createObjectNode()).build();
    }

    @POST
    @Path("/describe-connector")
    public Response describeConnector(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.describeConnector(in, region)).build();
    }

    @POST
    @Path("/describe-connector-entity")
    public Response describeConnectorEntity(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.describeEntity(in, region)).build();
    }

    @POST
    @Path("/describe-connector-profiles")
    public Response describeConnectorProfiles(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.describeProfiles(in, region)).build();
    }

    @POST
    @Path("/describe-connectors")
    public Response describeConnectors(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.describeConnectors(in, region)).build();
    }

    @POST
    @Path("/describe-flow")
    public Response describeFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.describeFlow(in, region)).build();
    }

    @POST
    @Path("/describe-flow-execution-records")
    public Response describeFlowExecutionRecords(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(runs.records(in, region)).build();
    }

    @POST
    @Path("/list-connector-entities")
    public Response listConnectorEntities(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.listEntities(in, region)).build();
    }

    @POST
    @Path("/list-connectors")
    public Response listConnectors(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.listConnectors(in, region)).build();
    }

    @POST
    @Path("/list-flows")
    public Response listFlows(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.listFlows(in, region)).build();
    }

    @POST
    @Path("/register-connector")
    public Response registerConnector(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.registerConnector(in, region)).build();
    }

    @POST
    @Path("/reset-connector-metadata-cache")
    public Response resetConnectorMetadataCache(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        service.resetMetadataCache(in, region);
        return Response.ok(mapper.createObjectNode()).build();
    }

    @POST
    @Path("/start-flow")
    public Response startFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(runs.startFlow(in, region)).build();
    }

    @POST
    @Path("/stop-flow")
    public Response stopFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(runs.stopFlow(in, region)).build();
    }

    @POST
    @Path("/unregister-connector")
    public Response unregisterConnector(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        service.unregisterConnector(in, region);
        return Response.ok(mapper.createObjectNode()).build();
    }

    @POST
    @Path("/update-connector-profile")
    public Response updateConnectorProfile(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.updateProfile(in, region)).build();
    }

    @POST
    @Path("/update-connector-registration")
    public Response updateConnectorRegistration(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.updateRegistration(in, region)).build();
    }

    @POST
    @Path("/update-flow")
    public Response updateFlow(@Context HttpHeaders headers, String body) {
        JsonNode in = parse(body);
        String region = regions.resolveRegion(headers);
        return Response.ok(service.updateFlow(in, region, runs)).build();
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
