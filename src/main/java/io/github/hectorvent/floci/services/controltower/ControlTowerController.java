package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.controltower.model.ControlTowerOperation;
import io.github.hectorvent.floci.services.controltower.model.EnabledBaseline;
import io.github.hectorvent.floci.services.controltower.model.EnabledControl;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * AWS Control Tower restJson1.
 *
 * <p>Literal kebab-case paths ({@code /list-baselines}, {@code /get-landingzone},
 * …) take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code controltower}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ControlTowerController {

    private final ControlTowerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ControlTowerController(
            ControlTowerService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/list-baselines")
    @Consumes(MediaType.WILDCARD)
    public Response listBaselines(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            String region = regionResolver.resolveRegion(headers);
            ControlTowerService.Page<ControlTowerService.CatalogBaseline> page =
                    service.listBaselines(region, request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode baselines = response.putArray("baselines");
            for (ControlTowerService.CatalogBaseline baseline : page.items()) {
                ObjectNode node = baselines.addObject();
                node.put("arn", service.baselineArn(region, baseline.id()));
                node.put("name", baseline.name());
                node.put("description", baseline.description());
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/get-baseline")
    public Response getBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            String region = regionResolver.resolveRegion(headers);
            ControlTowerService.CatalogBaseline baseline = service.getBaseline(region, request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("arn", service.baselineArn(region, baseline.id()));
            response.put("name", baseline.name());
            response.put("description", baseline.description());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-enabled-baselines")
    @Consumes(MediaType.WILDCARD)
    public Response listEnabledBaselines(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.Page<EnabledBaseline> page =
                    service.listEnabledBaselines(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("enabledBaselines");
            for (EnabledBaseline enabled : page.items()) {
                items.add(toEnabledBaselineSummary(enabled));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/get-enabled-baseline")
    public Response getEnabledBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            EnabledBaseline enabled =
                    service.getEnabledBaseline(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("enabledBaselineDetails", toEnabledBaselineDetails(enabled));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/enable-baseline")
    public Response enableBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.EnableResult result =
                    service.enableBaseline(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("operationIdentifier", result.operation().getOperationIdentifier());
            response.put("arn", result.arn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/disable-baseline")
    public Response disableBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.disableBaseline(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/update-enabled-baseline")
    public Response updateEnabledBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.updateEnabledBaseline(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/reset-enabled-baseline")
    public Response resetEnabledBaseline(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.resetEnabledBaseline(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/get-baseline-operation")
    public Response getBaselineOperation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerOperation operation =
                    service.getBaselineOperation(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("baselineOperation", toBaselineOperation(operation));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-enabled-controls")
    @Consumes(MediaType.WILDCARD)
    public Response listEnabledControls(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.Page<EnabledControl> page =
                    service.listEnabledControls(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("enabledControls");
            for (EnabledControl enabled : page.items()) {
                items.add(toEnabledControlSummary(enabled));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/get-enabled-control")
    public Response getEnabledControl(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            EnabledControl enabled =
                    service.getEnabledControl(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("enabledControlDetails", toEnabledControlDetails(enabled));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/enable-control")
    public Response enableControl(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.EnableResult result =
                    service.enableControl(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("operationIdentifier", result.operation().getOperationIdentifier());
            response.put("arn", result.arn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/disable-control")
    public Response disableControl(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.disableControl(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/update-enabled-control")
    public Response updateEnabledControl(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.updateEnabledControl(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/reset-enabled-control")
    public Response resetEnabledControl(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.resetEnabledControl(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/get-control-operation")
    public Response getControlOperation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerOperation operation =
                    service.getControlOperation(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("controlOperation", toControlOperation(operation));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-control-operations")
    @Consumes(MediaType.WILDCARD)
    public Response listControlOperations(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.Page<ControlTowerOperation> page =
                    service.listControlOperations(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("controlOperations");
            for (ControlTowerOperation operation : page.items()) {
                items.add(toControlOperation(operation));
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-landingzones")
    @Consumes(MediaType.WILDCARD)
    public Response listLandingZones(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.Page<LandingZone> page =
                    service.listLandingZones(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("landingZones");
            for (LandingZone zone : page.items()) {
                ObjectNode node = items.addObject();
                node.put("arn", zone.getArn());
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/get-landingzone")
    public Response getLandingZone(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            LandingZone zone = service.getLandingZone(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("landingZone", toLandingZoneDetail(zone));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/create-landingzone")
    public Response createLandingZone(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.CreateLandingZoneResult result =
                    service.createLandingZone(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("arn", result.arn());
            response.put("operationIdentifier", result.operation().getOperationIdentifier());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/delete-landingzone")
    public Response deleteLandingZone(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.deleteLandingZone(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/update-landingzone")
    public Response updateLandingZone(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.updateLandingZone(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/reset-landingzone")
    public Response resetLandingZone(@Context HttpHeaders headers, String body) {
        return handle(body, request -> operationId(
                service.resetLandingZone(regionResolver.resolveRegion(headers), request)));
    }

    @POST
    @Path("/get-landingzone-operation")
    public Response getLandingZoneOperation(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerOperation operation =
                    service.getLandingZoneOperation(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("operationDetails", toLandingZoneOperation(operation));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-landingzone-operations")
    @Consumes(MediaType.WILDCARD)
    public Response listLandingZoneOperations(@Context HttpHeaders headers, String body) {
        return handle(body, request -> {
            ControlTowerService.Page<ControlTowerOperation> page =
                    service.listLandingZoneOperations(regionResolver.resolveRegion(headers), request);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("landingZoneOperations");
            for (ControlTowerOperation operation : page.items()) {
                ObjectNode node = items.addObject();
                node.put("operationType", operation.getOperationType());
                node.put("operationIdentifier", operation.getOperationIdentifier());
                node.put("status", operation.getStatus());
            }
            putNextToken(response, page.nextToken());
            return Response.ok(response).build();
        });
    }

    private ObjectNode toEnabledBaselineSummary(EnabledBaseline enabled) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", enabled.getArn());
        node.put("baselineIdentifier", enabled.getBaselineIdentifier());
        if (enabled.getBaselineVersion() != null) {
            node.put("baselineVersion", enabled.getBaselineVersion());
        }
        node.put("targetIdentifier", enabled.getTargetIdentifier());
        if (enabled.getParentIdentifier() != null) {
            node.put("parentIdentifier", enabled.getParentIdentifier());
        }
        node.set("statusSummary", statusSummary(enabled.getStatus(), enabled.getLastOperationIdentifier()));
        node.set("driftStatusSummary", driftStatusSummary());
        return node;
    }

    private ObjectNode toEnabledBaselineDetails(EnabledBaseline enabled) {
        ObjectNode node = toEnabledBaselineSummary(enabled);
        if (enabled.getParameters() != null) {
            node.set("parameters", enabled.getParameters());
        }
        return node;
    }

    private ObjectNode toEnabledControlSummary(EnabledControl enabled) {
        ObjectNode node = objectMapper.createObjectNode();
        if (enabled.getArn() != null) {
            node.put("arn", enabled.getArn());
        }
        if (enabled.getControlIdentifier() != null) {
            node.put("controlIdentifier", enabled.getControlIdentifier());
        }
        if (enabled.getTargetIdentifier() != null) {
            node.put("targetIdentifier", enabled.getTargetIdentifier());
        }
        if (enabled.getParentIdentifier() != null) {
            node.put("parentIdentifier", enabled.getParentIdentifier());
        }
        node.set("statusSummary", statusSummary(enabled.getStatus(), enabled.getLastOperationIdentifier()));
        ObjectNode drift = objectMapper.createObjectNode();
        if (enabled.getDriftStatus() != null) {
            drift.put("driftStatus", enabled.getDriftStatus());
        }
        node.set("driftStatusSummary", drift);
        return node;
    }

    private ObjectNode toEnabledControlDetails(EnabledControl enabled) {
        ObjectNode node = toEnabledControlSummary(enabled);
        if (enabled.getParameters() != null) {
            node.set("parameters", enabled.getParameters());
        }
        if (enabled.getTargetRegions() != null) {
            node.set("targetRegions", enabled.getTargetRegions());
        }
        return node;
    }

    private ObjectNode toLandingZoneDetail(LandingZone zone) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("version", zone.getVersion());
        if (zone.getArn() != null) {
            node.put("arn", zone.getArn());
        }
        if (zone.getStatus() != null) {
            node.put("status", zone.getStatus());
        }
        if (zone.getLatestAvailableVersion() != null) {
            node.put("latestAvailableVersion", zone.getLatestAvailableVersion());
        }
        ObjectNode drift = objectMapper.createObjectNode();
        if (zone.getDriftStatus() != null) {
            drift.put("status", zone.getDriftStatus());
        }
        node.set("driftStatus", drift);
        List<String> types = zone.getRemediationTypes();
        if (types != null && !types.isEmpty()) {
            ArrayNode array = node.putArray("remediationTypes");
            types.forEach(array::add);
        }
        if (zone.getManifest() != null) {
            node.set("manifest", zone.getManifest());
        } else {
            node.set("manifest", objectMapper.createObjectNode());
        }
        return node;
    }

    private ObjectNode toBaselineOperation(ControlTowerOperation operation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operationIdentifier", operation.getOperationIdentifier());
        node.put("operationType", operation.getOperationType());
        node.put("status", operation.getStatus());
        if (operation.getStartTime() != null) {
            node.put("startTime", operation.getStartTime());
        }
        if (operation.getEndTime() != null) {
            node.put("endTime", operation.getEndTime());
        }
        if (operation.getStatusMessage() != null) {
            node.put("statusMessage", operation.getStatusMessage());
        }
        return node;
    }

    private ObjectNode toControlOperation(ControlTowerOperation operation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operationType", operation.getOperationType());
        node.put("operationIdentifier", operation.getOperationIdentifier());
        node.put("status", operation.getStatus());
        if (operation.getStartTime() != null) {
            node.put("startTime", operation.getStartTime());
        }
        if (operation.getEndTime() != null) {
            node.put("endTime", operation.getEndTime());
        }
        if (operation.getStatusMessage() != null) {
            node.put("statusMessage", operation.getStatusMessage());
        }
        if (operation.getControlIdentifier() != null) {
            node.put("controlIdentifier", operation.getControlIdentifier());
        }
        if (operation.getTargetIdentifier() != null) {
            node.put("targetIdentifier", operation.getTargetIdentifier());
        }
        if (operation.getEnabledControlIdentifier() != null) {
            node.put("enabledControlIdentifier", operation.getEnabledControlIdentifier());
        }
        return node;
    }

    private ObjectNode toLandingZoneOperation(ControlTowerOperation operation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operationType", operation.getOperationType());
        node.put("operationIdentifier", operation.getOperationIdentifier());
        node.put("status", operation.getStatus());
        if (operation.getStartTime() != null) {
            node.put("startTime", operation.getStartTime());
        }
        if (operation.getEndTime() != null) {
            node.put("endTime", operation.getEndTime());
        }
        if (operation.getStatusMessage() != null) {
            node.put("statusMessage", operation.getStatusMessage());
        }
        return node;
    }

    private ObjectNode statusSummary(String status, String lastOperationIdentifier) {
        ObjectNode node = objectMapper.createObjectNode();
        if (status != null) {
            node.put("status", status);
        }
        if (lastOperationIdentifier != null) {
            node.put("lastOperationIdentifier", lastOperationIdentifier);
        }
        return node;
    }

    private ObjectNode driftStatusSummary() {
        ObjectNode types = objectMapper.createObjectNode();
        ObjectNode inheritance = types.putObject("inheritance");
        inheritance.put("status", "IN_SYNC");
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("types", types);
        return summary;
    }

    private Response operationId(ControlTowerOperation operation) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", operation.getOperationIdentifier());
        return Response.ok(response).build();
    }

    private static void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("nextToken", nextToken);
        }
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
