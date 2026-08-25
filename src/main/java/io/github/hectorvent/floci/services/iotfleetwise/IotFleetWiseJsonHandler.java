package io.github.hectorvent.floci.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * JSON 1.0 handler for AWS IoT FleetWise. Dispatched from {@code AwsJsonController}
 * under the {@code IoTAutobahnControlPlane.} target prefix.
 */
@ApplicationScoped
public class IotFleetWiseJsonHandler {

    static final String TARGET_PREFIX = "IoTAutobahnControlPlane.";

    private final IotFleetWiseService service;
    private final ObjectMapper objectMapper;

    @Inject
    public IotFleetWiseJsonHandler(IotFleetWiseService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateSignalCatalog" -> ok(service.createSignalCatalog(body, region));
                case "GetSignalCatalog" -> ok(service.getSignalCatalog(body));
                case "UpdateSignalCatalog" -> ok(service.updateSignalCatalog(body));
                case "DeleteSignalCatalog" -> ok(service.deleteSignalCatalog(body));
                case "ListSignalCatalogs" -> ok(service.listSignalCatalogs());
                case "ListSignalCatalogNodes" -> ok(service.listSignalCatalogNodes(body));
                case "CreateStateTemplate" -> ok(service.createStateTemplate(body, region));
                case "GetStateTemplate" -> ok(service.getStateTemplate(body));
                case "UpdateStateTemplate" -> ok(service.updateStateTemplate(body));
                case "DeleteStateTemplate" -> ok(service.deleteStateTemplate(body));
                case "ListStateTemplates" -> ok(service.listStateTemplates());
                case "CreateModelManifest" -> ok(service.createModelManifest(body, region));
                case "GetModelManifest" -> ok(service.getModelManifest(body));
                case "UpdateModelManifest" -> ok(service.updateModelManifest(body));
                case "DeleteModelManifest" -> ok(service.deleteModelManifest(body));
                case "ListModelManifests" -> ok(service.listModelManifests());
                case "ListModelManifestNodes" -> ok(service.listModelManifestNodes(body));
                case "CreateDecoderManifest" -> ok(service.createDecoderManifest(body, region));
                case "GetDecoderManifest" -> ok(service.getDecoderManifest(body));
                case "UpdateDecoderManifest" -> ok(service.updateDecoderManifest(body));
                case "DeleteDecoderManifest" -> ok(service.deleteDecoderManifest(body));
                case "ListDecoderManifests" -> ok(service.listDecoderManifests());
                case "ListDecoderManifestNetworkInterfaces" -> ok(service.listDecoderManifestNetworkInterfaces(body));
                case "ListDecoderManifestSignals" -> ok(service.listDecoderManifestSignals(body));
                case "CreateFleet" -> ok(service.createFleet(body, region));
                case "GetFleet" -> ok(service.getFleet(body));
                case "UpdateFleet" -> ok(service.updateFleet(body));
                case "DeleteFleet" -> ok(service.deleteFleet(body));
                case "ListFleets" -> ok(service.listFleets());
                case "CreateVehicle" -> ok(service.createVehicle(body, region));
                case "GetVehicle" -> ok(service.getVehicle(body));
                case "UpdateVehicle" -> ok(service.updateVehicle(body));
                case "DeleteVehicle" -> ok(service.deleteVehicle(body));
                case "ListVehicles" -> ok(service.listVehicles(body));
                case "GetVehicleStatus" -> ok(service.getVehicleStatus(body));
                case "ListVehiclesInFleet" -> ok(service.listVehiclesInFleet(body));
                case "ListFleetsForVehicle" -> ok(service.listFleetsForVehicle(body));
                case "AssociateVehicleFleet" -> ok(service.associateVehicleFleet(body));
                case "DisassociateVehicleFleet" -> ok(service.disassociateVehicleFleet(body));
                case "BatchCreateVehicle" -> ok(service.batchCreateVehicle(body, region));
                case "BatchUpdateVehicle" -> ok(service.batchUpdateVehicle(body));
                case "CreateCampaign" -> ok(service.createCampaign(body, region));
                case "GetCampaign" -> ok(service.getCampaign(body));
                case "UpdateCampaign" -> ok(service.updateCampaign(body));
                case "DeleteCampaign" -> ok(service.deleteCampaign(body));
                case "ListCampaigns" -> ok(service.listCampaigns());
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response error(AwsException e) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("__type", e.jsonType());
        if (e.getMessage() != null) {
            body.put("message", e.getMessage());
        }
        Map<String, Object> extra = e.getExtendedData();
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (value instanceof String s) {
                    body.put(key, s);
                }
            });
        }
        return Response.status(e.getHttpStatus()).entity(body).build();
    }
}
