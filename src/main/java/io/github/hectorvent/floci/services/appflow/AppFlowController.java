package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appflow.model.ConnectorProfile;
import io.github.hectorvent.floci.services.appflow.model.Flow;
import io.github.hectorvent.floci.services.appflow.model.FlowExecution;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon AppFlow restJson1 — connector-profile and flow lifecycle.
 *
 * <p>Literal {@code /create-flow}, {@code /create-connector-profile} and peer
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code appflow}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppFlowController {

    private final AppFlowService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AppFlowController(
            AppFlowService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/create-connector-profile")
    public Response createConnectorProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ConnectorProfile profile = service.createConnectorProfile(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("connectorProfileArn", profile.getConnectorProfileArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describe-connector-profiles")
    @Consumes(MediaType.WILDCARD)
    public Response describeConnectorProfiles(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            AppFlowService.Page page = service.describeConnectorProfiles(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode details = response.putArray("connectorProfileDetails");
            for (ConnectorProfile profile : page.profiles()) {
                details.add(toDetail(profile));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/update-connector-profile")
    public Response updateConnectorProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ConnectorProfile profile = service.updateConnectorProfile(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("connectorProfileArn", profile.getConnectorProfileArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/delete-connector-profile")
    public Response deleteConnectorProfile(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteConnectorProfile(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/create-flow")
    public Response createFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Flow flow = service.createFlow(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("flowArn", flow.getFlowArn());
            response.put("flowStatus", flow.getFlowStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describe-flow")
    public Response describeFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                toDescribe(service.describeFlow(regionResolver.resolveRegion(headers), request))).build());
    }

    @POST
    @Path("/update-flow")
    public Response updateFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Flow flow = service.updateFlow(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("flowStatus", flow.getFlowStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/delete-flow")
    public Response deleteFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            service.deleteFlow(regionResolver.resolveRegion(headers), request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/list-flows")
    public Response listFlows(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            AppFlowService.FlowPage page = service.listFlows(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode flows = response.putArray("flows");
            for (Flow flow : page.items()) {
                flows.add(toSummary(flow));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/start-flow")
    public Response startFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            AppFlowService.StartResult result = service.startFlow(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("flowArn", result.flow().getFlowArn());
            response.put("flowStatus", result.flow().getFlowStatus());
            response.put("executionId", result.executionId());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/stop-flow")
    public Response stopFlow(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            Flow flow = service.stopFlow(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("flowArn", flow.getFlowArn());
            response.put("flowStatus", flow.getFlowStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/cancel-flow-executions")
    public Response cancelFlowExecutions(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            AppFlowService.CancelResult result = service.cancelFlowExecutions(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode invalid = response.putArray("invalidExecutions");
            result.invalidExecutions().forEach(invalid::add);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describe-flow-execution-records")
    public Response describeFlowExecutionRecords(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            AppFlowService.ExecutionPage page = service.describeFlowExecutionRecords(
                    regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode executions = response.putArray("flowExecutions");
            for (FlowExecution execution : page.items()) {
                executions.add(toExecution(execution));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    private ObjectNode toDetail(ConnectorProfile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("connectorProfileArn", profile.getConnectorProfileArn());
        node.put("connectorProfileName", profile.getConnectorProfileName());
        node.put("connectorType", profile.getConnectorType());
        if (profile.getConnectorLabel() != null) {
            node.put("connectorLabel", profile.getConnectorLabel());
        }
        node.put("connectionMode", profile.getConnectionMode());
        if (profile.getCredentialsArn() != null) {
            node.put("credentialsArn", profile.getCredentialsArn());
        }
        if (profile.getConnectorProfileProperties() != null) {
            node.set("connectorProfileProperties", profile.getConnectorProfileProperties());
        }
        node.put("createdAt", profile.getCreatedAt());
        node.put("lastUpdatedAt", profile.getLastUpdatedAt());
        return node;
    }

    private ObjectNode toDescribe(Flow flow) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowName", flow.getFlowName());
        response.put("flowArn", flow.getFlowArn());
        if (flow.getDescription() != null) {
            response.put("description", flow.getDescription());
        }
        if (flow.getKmsArn() != null) {
            response.put("kmsArn", flow.getKmsArn());
        }
        response.put("flowStatus", flow.getFlowStatus());
        if (flow.getFlowStatusMessage() != null) {
            response.put("flowStatusMessage", flow.getFlowStatusMessage());
        }
        setIfPresent(response, "sourceFlowConfig", flow.getSourceFlowConfig());
        setIfPresent(response, "destinationFlowConfigList", flow.getDestinationFlowConfigList());
        setIfPresent(response, "triggerConfig", flow.getTriggerConfig());
        setIfPresent(response, "tasks", flow.getTasks());
        setIfPresent(response, "metadataCatalogConfig", flow.getMetadataCatalogConfig());
        setIfPresent(response, "lastRunExecutionDetails", flow.getLastRunExecutionDetails());
        response.put("createdAt", flow.getCreatedAt());
        response.put("lastUpdatedAt", flow.getLastUpdatedAt());
        if (flow.getCreatedBy() != null) {
            response.put("createdBy", flow.getCreatedBy());
        }
        if (flow.getLastUpdatedBy() != null) {
            response.put("lastUpdatedBy", flow.getLastUpdatedBy());
        }
        putTags(response, flow.getTags());
        response.put("schemaVersion", flow.getSchemaVersion());
        return response;
    }

    private ObjectNode toSummary(Flow flow) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("flowArn", flow.getFlowArn());
        summary.put("flowName", flow.getFlowName());
        if (flow.getDescription() != null) {
            summary.put("description", flow.getDescription());
        }
        summary.put("flowStatus", flow.getFlowStatus());
        JsonNode source = flow.getSourceFlowConfig();
        if (source != null && source.hasNonNull("connectorType")) {
            summary.put("sourceConnectorType", source.get("connectorType").asText());
        }
        JsonNode destinations = flow.getDestinationFlowConfigList();
        if (destinations != null && destinations.isArray() && !destinations.isEmpty()) {
            JsonNode first = destinations.get(0);
            if (first != null && first.hasNonNull("connectorType")) {
                summary.put("destinationConnectorType", first.get("connectorType").asText());
            }
        }
        JsonNode trigger = flow.getTriggerConfig();
        if (trigger != null && trigger.hasNonNull("triggerType")) {
            summary.put("triggerType", trigger.get("triggerType").asText());
        }
        summary.put("createdAt", flow.getCreatedAt());
        summary.put("lastUpdatedAt", flow.getLastUpdatedAt());
        if (flow.getCreatedBy() != null) {
            summary.put("createdBy", flow.getCreatedBy());
        }
        if (flow.getLastUpdatedBy() != null) {
            summary.put("lastUpdatedBy", flow.getLastUpdatedBy());
        }
        putTags(summary, flow.getTags());
        return summary;
    }

    private ObjectNode toExecution(FlowExecution execution) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("executionId", execution.getExecutionId());
        node.put("executionStatus", execution.getExecutionStatus());
        node.put("startedAt", execution.getStartedAt());
        node.put("lastUpdatedAt", execution.getLastUpdatedAt());
        ObjectNode result = node.putObject("executionResult");
        result.put("bytesProcessed", execution.getBytesProcessed());
        result.put("bytesWritten", execution.getBytesWritten());
        result.put("recordsProcessed", execution.getRecordsProcessed());
        if (execution.getExecutionMessage() != null) {
            result.putObject("errorInfo").put("executionMessage", execution.getExecutionMessage());
        }
        return node;
    }

    private void setIfPresent(ObjectNode parent, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            parent.set(field, value);
        }
    }

    private void putTags(ObjectNode parent, java.util.Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode tagsNode = parent.putObject("tags");
        tags.forEach(tagsNode::put);
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
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
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
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
    private interface Handler {
        Response handle(JsonNode request);
    }
}
