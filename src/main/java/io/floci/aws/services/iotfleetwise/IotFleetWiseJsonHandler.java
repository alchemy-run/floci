package io.floci.aws.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

/**
 * JSON 1.0 dispatch for AWS IoT FleetWise ({@code X-Amz-Target: IoTAutobahnControlPlane.<Action>}).
 */
@ApplicationScoped
public class IotFleetWiseJsonHandler {

    /**
     * IoT FleetWise's request constraints, reported as ValidationException. The campaign and model manifest ARN
     * patterns name only the commercial partition, so ARNs are checked by the service.
     */
    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/iotfleetwise-request-shapes.json", "IoT FleetWise request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : Set.of("integer", "long").contains(type)
                            ? "value" : "size";
                    return violation(path, min != null && size < min
                            ? "Member must have " + what + " greater than or equal to " + min
                            : "Member must have " + what + " less than or equal to " + max);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: [" + String.join(", ", values)
                            + "]");
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            }).withLenientShapes(Set.of("campaignArn", "CreateDecoderManifestRequestModelManifestArnString"));

    private final IotFleetWiseService service;
    private final IotFleetWiseModels models;

    @Inject
    public IotFleetWiseJsonHandler(IotFleetWiseService service, IotFleetWiseModels models) {
        this.service = service;
        this.models = models;
    }

    private static AwsException violation(String path, String constraint) {
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        return IotFleetWiseStore.field(member, "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint);
    }

    public Response handle(String action, JsonNode request, String region) {
        service.checkCustomer();
        SHAPES.check(action, request);
        ObjectNode result = switch (action) {
            case "AssociateVehicleFleet" -> service.associateVehicleFleet(request, region);
            case "BatchCreateVehicle" -> service.batchCreateVehicle(request, region);
            case "BatchUpdateVehicle" -> service.batchUpdateVehicle(request, region);
            case "CreateCampaign" -> service.createCampaign(request, region);
            case "CreateDecoderManifest" -> models.createDecoderManifest(request, region);
            case "CreateFleet" -> service.createFleet(request, region);
            case "CreateModelManifest" -> models.createModelManifest(request, region);
            case "CreateSignalCatalog" -> models.createSignalCatalog(request, region);
            case "CreateStateTemplate" -> models.createStateTemplate(request, region);
            case "CreateVehicle" -> service.createVehicle(request, region);
            case "DeleteCampaign" -> service.deleteCampaign(request, region);
            case "DeleteDecoderManifest" -> models.deleteDecoderManifest(request, region);
            case "DeleteFleet" -> service.deleteFleet(request, region);
            case "DeleteModelManifest" -> models.deleteModelManifest(request, region);
            case "DeleteSignalCatalog" -> models.deleteSignalCatalog(request, region);
            case "DeleteStateTemplate" -> models.deleteStateTemplate(request, region);
            case "DeleteVehicle" -> service.deleteVehicle(request, region);
            case "DisassociateVehicleFleet" -> service.disassociateVehicleFleet(request, region);
            case "GetCampaign" -> service.getCampaign(request, region);
            case "GetDecoderManifest" -> models.getDecoderManifest(request, region);
            case "GetEncryptionConfiguration" -> service.getEncryptionConfiguration(request, region);
            case "GetFleet" -> service.getFleet(request, region);
            case "GetLoggingOptions" -> service.getLoggingOptions(request, region);
            case "GetModelManifest" -> models.getModelManifest(request, region);
            case "GetRegisterAccountStatus" -> service.getRegisterAccountStatus(request, region);
            case "GetSignalCatalog" -> models.getSignalCatalog(request, region);
            case "GetStateTemplate" -> models.getStateTemplate(request, region);
            case "GetVehicle" -> service.getVehicle(request, region);
            case "GetVehicleStatus" -> service.getVehicleStatus(request, region);
            case "ImportDecoderManifest" -> models.importDecoderManifest(request, region);
            case "ImportSignalCatalog" -> models.importSignalCatalog(request, region);
            case "ListCampaigns" -> service.listCampaigns(request, region);
            case "ListDecoderManifestNetworkInterfaces" -> models.listDecoderManifestNetworkInterfaces(request, region);
            case "ListDecoderManifestSignals" -> models.listDecoderManifestSignals(request, region);
            case "ListDecoderManifests" -> models.listDecoderManifests(request, region);
            case "ListFleets" -> service.listFleets(request, region);
            case "ListFleetsForVehicle" -> service.listFleetsForVehicle(request, region);
            case "ListModelManifestNodes" -> models.listModelManifestNodes(request, region);
            case "ListModelManifests" -> models.listModelManifests(request, region);
            case "ListSignalCatalogNodes" -> models.listSignalCatalogNodes(request, region);
            case "ListSignalCatalogs" -> models.listSignalCatalogs(request, region);
            case "ListStateTemplates" -> models.listStateTemplates(request, region);
            case "ListTagsForResource" -> service.listTagsForResource(request, region);
            case "ListVehicles" -> service.listVehicles(request, region);
            case "ListVehiclesInFleet" -> service.listVehiclesInFleet(request, region);
            case "PutEncryptionConfiguration" -> service.putEncryptionConfiguration(request, region);
            case "PutLoggingOptions" -> service.putLoggingOptions(request, region);
            case "RegisterAccount" -> service.registerAccount(request, region);
            case "TagResource" -> service.tagResource(request, region);
            case "UntagResource" -> service.untagResource(request, region);
            case "UpdateCampaign" -> service.updateCampaign(request, region);
            case "UpdateDecoderManifest" -> models.updateDecoderManifest(request, region);
            case "UpdateFleet" -> service.updateFleet(request, region);
            case "UpdateModelManifest" -> models.updateModelManifest(request, region);
            case "UpdateSignalCatalog" -> models.updateSignalCatalog(request, region);
            case "UpdateStateTemplate" -> models.updateStateTemplate(request, region);
            case "UpdateVehicle" -> service.updateVehicle(request, region);
            default -> null;
        };
        if (result == null) {
            return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: IoTAutobahnControlPlane." + action)).build();
        }
        return Response.ok(result).build();
    }
}
