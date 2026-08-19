package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

import java.util.List;

/**
 * AWS Lambda Durable Execution API endpoints (API version 2025-12-01):
 * the management plane (Get/List/Stop/History) and the checkpoint data plane
 * the Durable Execution SDK speaks from inside the function
 * (CheckpointDurableExecution / GetDurableExecutionState) plus the callback
 * completion endpoints.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaDurableController {

    private final LambdaDurableService durableService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaDurableController(LambdaDurableService durableService,
                                   RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.durableService = durableService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── GetDurableExecution ────────────────────────────

    @GET
    @Path("/2025-12-01/durable-executions/{executionArn}")
    public Response getDurableExecution(@PathParam("executionArn") String executionArn) {
        return Response.ok(durableService.getExecution(executionArn)).build();
    }

    // ──────────────────────────── GetDurableExecutionState ────────────────────────────

    @GET
    @Path("/2025-12-01/durable-executions/{executionArn}/state")
    public Response getDurableExecutionState(@PathParam("executionArn") String executionArn,
                                             @QueryParam("CheckpointToken") String checkpointToken,
                                             @QueryParam("Marker") String marker,
                                             @QueryParam("MaxItems") Integer maxItems) {
        return Response.ok(durableService.getExecutionState(executionArn, checkpointToken)).build();
    }

    // ──────────────────────────── CheckpointDurableExecution ────────────────────────────

    @POST
    @Path("/2025-12-01/durable-executions/{executionArn}/checkpoint")
    @Consumes(MediaType.WILDCARD)
    public Response checkpointDurableExecution(@PathParam("executionArn") String executionArn,
                                               String body) {
        JsonNode request = parseJson(body);
        return Response.ok(durableService.checkpoint(executionArn, request)).build();
    }

    // ──────────────────────────── GetDurableExecutionHistory ────────────────────────────

    @GET
    @Path("/2025-12-01/durable-executions/{executionArn}/history")
    public Response getDurableExecutionHistory(@PathParam("executionArn") String executionArn,
                                               @QueryParam("IncludeExecutionData") Boolean includeExecutionData,
                                               @QueryParam("MaxItems") Integer maxItems,
                                               @QueryParam("Marker") String marker,
                                               @QueryParam("ReverseOrder") Boolean reverseOrder) {
        return Response.ok(durableService.getExecutionHistory(executionArn,
                Boolean.TRUE.equals(includeExecutionData),
                Boolean.TRUE.equals(reverseOrder))).build();
    }

    // ──────────────────────────── StopDurableExecution ────────────────────────────

    @POST
    @Path("/2025-12-01/durable-executions/{executionArn}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopDurableExecution(@PathParam("executionArn") String executionArn,
                                         String body) {
        JsonNode error = body == null || body.isBlank() ? null : parseJson(body);
        return Response.ok(durableService.stopExecution(executionArn, error)).build();
    }

    // ──────────────────────────── ListDurableExecutionsByFunction ────────────────────────────

    @GET
    @Path("/2025-12-01/functions/{functionName}/durable-executions")
    public Response listDurableExecutionsByFunction(@Context HttpHeaders headers,
                                                    @PathParam("functionName") String functionName,
                                                    @QueryParam("Qualifier") String qualifier,
                                                    @QueryParam("DurableExecutionName") String durableExecutionName,
                                                    @QueryParam("Statuses") List<String> statuses,
                                                    @QueryParam("ReverseOrder") Boolean reverseOrder) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(durableService.listExecutions(region, functionName, qualifier,
                durableExecutionName, statuses, Boolean.TRUE.equals(reverseOrder))).build();
    }

    // ──────────────────────────── Durable execution callbacks ────────────────────────────

    @POST
    @Path("/2025-12-01/durable-execution-callbacks/{callbackId}/succeed")
    @Consumes(MediaType.WILDCARD)
    public Response sendDurableExecutionCallbackSuccess(@PathParam("callbackId") String callbackId,
                                                        byte[] result) {
        durableService.callbackSucceed(callbackId, result);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/2025-12-01/durable-execution-callbacks/{callbackId}/fail")
    @Consumes(MediaType.WILDCARD)
    public Response sendDurableExecutionCallbackFailure(@PathParam("callbackId") String callbackId,
                                                        String body) {
        JsonNode error = body == null || body.isBlank() ? null : parseJson(body);
        durableService.callbackFail(callbackId, error);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/2025-12-01/durable-execution-callbacks/{callbackId}/heartbeat")
    @Consumes(MediaType.WILDCARD)
    public Response sendDurableExecutionCallbackHeartbeat(@PathParam("callbackId") String callbackId) {
        durableService.callbackHeartbeat(callbackId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new AwsException("InvalidRequestContentException",
                    "Could not parse request body: " + e.getMessage(), 400);
        }
    }
}
