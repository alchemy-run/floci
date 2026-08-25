package io.github.hectorvent.floci.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.iotfleetwise.model.CampaignStatus;
import io.github.hectorvent.floci.services.iotfleetwise.model.Fleet;
import io.github.hectorvent.floci.services.iotfleetwise.model.Vehicle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local AWS IoT FleetWise control plane (JSON 1.0, {@code IoTAutobahnControlPlane.*}).
 *
 * <p>Signal catalogs, state templates, model/decoder manifests, fleets, vehicles,
 * and campaigns are in-memory; provisioning is instantaneous.
 */
@ApplicationScoped
public class IotFleetWiseService implements Resettable {

    static final String SERVICE = "iotfleetwise";

    public record Page<T>(List<T> items, String nextToken) {
    }

    static final class NamedResource {
        String name;
        String arn;
        String id;
        String description;
        String status;
        String signalCatalogArn;
        String modelManifestArn;
        String decoderManifestArn;
        String targetArn;
        long creationTime;
        long lastModificationTime;
        final List<JsonNode> nodes = new ArrayList<>();
        final List<String> nodePaths = new ArrayList<>();
        final List<JsonNode> networkInterfaces = new ArrayList<>();
        final List<JsonNode> signalDecoders = new ArrayList<>();
        final List<String> stateTemplateProperties = new ArrayList<>();
        final List<String> dataExtraDimensions = new ArrayList<>();
        final List<String> metadataExtraDimensions = new ArrayList<>();
        final Map<String, String> attributes = new LinkedHashMap<>();
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<String> fleetIds = new ArrayList<>();
        final List<String> vehicleNames = new ArrayList<>();
        final List<CampaignStatus> campaignStatuses = new ArrayList<>();
        JsonNode collectionScheme;
        JsonNode signalsToCollect;
        JsonNode dataDestinationConfigs;
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, NamedResource> catalogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> stateTemplates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> models = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> fleets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> vehicles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> campaigns = new ConcurrentHashMap<>();

    @Inject
    public IotFleetWiseService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        catalogs.clear();
        stateTemplates.clear();
        models.clear();
        decoders.clear();
        fleets.clear();
        vehicles.clear();
        campaigns.clear();
    }

    public ObjectNode createSignalCatalog(JsonNode request, String region) {
        String name = requireText(request, "name");
        if (catalogs.containsKey(name)) {
            throw conflict(name, "signalCatalog");
        }
        NamedResource catalog = newResource(name, arn(region, "signal-catalog/" + name));
        catalog.description = textOrNull(request, "description");
        catalog.nodes.addAll(copyArray(request.get("nodes")));
        catalog.tags.putAll(readTags(request));
        catalogs.put(name, catalog);
        return nameArn(catalog);
    }

    public ObjectNode getSignalCatalog(JsonNode request) {
        NamedResource catalog = requireCatalog(requireText(request, "name"));
        ObjectNode response = nameArn(catalog);
        putOptional(response, "description", catalog.description);
        response.set("nodeCounts", nodeCounts(catalog.nodes));
        response.put("creationTime", catalog.creationTime);
        response.put("lastModificationTime", catalog.lastModificationTime);
        return response;
    }

    public ObjectNode updateSignalCatalog(JsonNode request) {
        NamedResource catalog = requireCatalog(requireText(request, "name"));
        if (request.hasNonNull("description")) {
            catalog.description = request.get("description").asText();
        }
        applyNodeDelta(catalog.nodes, request.get("nodesToAdd"), request.get("nodesToUpdate"),
                request.get("nodesToRemove"));
        touch(catalog);
        return nameArn(catalog);
    }

    public ObjectNode deleteSignalCatalog(JsonNode request) {
        String name = requireText(request, "name");
        NamedResource catalog = catalogs.remove(name);
        if (catalog == null) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("name", name);
            response.put("arn", arn(regionResolver.getRegion(), "signal-catalog/" + name));
            return response;
        }
        return nameArn(catalog);
    }

    public ObjectNode listSignalCatalogs() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("summaries");
        for (NamedResource catalog : catalogs.values()) {
            ObjectNode summary = nameArn(catalog);
            summary.put("creationTime", catalog.creationTime);
            summary.put("lastModificationTime", catalog.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode listSignalCatalogNodes(JsonNode request) {
        NamedResource catalog = requireCatalog(requireText(request, "name"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode nodes = response.putArray("nodes");
        for (JsonNode node : catalog.nodes) {
            nodes.add(node.deepCopy());
        }
        return response;
    }

    public ObjectNode createStateTemplate(JsonNode request, String region) {
        String name = requireText(request, "name");
        if (findStateTemplate(name) != null) {
            throw conflict(name, "stateTemplate");
        }
        NamedResource template = newResource(name, arn(region, "state-template/" + name));
        template.id = newId();
        template.description = textOrNull(request, "description");
        template.signalCatalogArn = requireText(request, "signalCatalogArn");
        template.stateTemplateProperties.addAll(copyStrings(request.get("stateTemplateProperties")));
        template.dataExtraDimensions.addAll(copyStrings(request.get("dataExtraDimensions")));
        template.metadataExtraDimensions.addAll(copyStrings(request.get("metadataExtraDimensions")));
        template.tags.putAll(readTags(request));
        stateTemplates.put(name, template);
        return stateTemplateId(template);
    }

    public ObjectNode getStateTemplate(JsonNode request) {
        NamedResource template = requireStateTemplate(requireText(request, "identifier"));
        ObjectNode response = stateTemplateId(template);
        putOptional(response, "description", template.description);
        putOptional(response, "signalCatalogArn", template.signalCatalogArn);
        response.set("stateTemplateProperties", stringArray(template.stateTemplateProperties));
        response.set("dataExtraDimensions", stringArray(template.dataExtraDimensions));
        response.set("metadataExtraDimensions", stringArray(template.metadataExtraDimensions));
        response.put("creationTime", template.creationTime);
        response.put("lastModificationTime", template.lastModificationTime);
        return response;
    }

    public ObjectNode updateStateTemplate(JsonNode request) {
        NamedResource template = requireStateTemplate(requireText(request, "identifier"));
        if (request.hasNonNull("description")) {
            template.description = request.get("description").asText();
        }
        for (String fqn : copyStrings(request.get("stateTemplatePropertiesToAdd"))) {
            if (!template.stateTemplateProperties.contains(fqn)) {
                template.stateTemplateProperties.add(fqn);
            }
        }
        template.stateTemplateProperties.removeAll(copyStrings(request.get("stateTemplatePropertiesToRemove")));
        if (request.has("dataExtraDimensions")) {
            template.dataExtraDimensions.clear();
            template.dataExtraDimensions.addAll(copyStrings(request.get("dataExtraDimensions")));
        }
        if (request.has("metadataExtraDimensions")) {
            template.metadataExtraDimensions.clear();
            template.metadataExtraDimensions.addAll(copyStrings(request.get("metadataExtraDimensions")));
        }
        touch(template);
        return stateTemplateId(template);
    }

    public ObjectNode deleteStateTemplate(JsonNode request) {
        String identifier = requireText(request, "identifier");
        NamedResource template = findStateTemplate(identifier);
        if (template != null) {
            stateTemplates.remove(template.name);
            return stateTemplateId(template);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", identifier);
        return response;
    }

    public ObjectNode listStateTemplates() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("summaries");
        for (NamedResource template : stateTemplates.values()) {
            ObjectNode summary = stateTemplateId(template);
            putOptional(summary, "signalCatalogArn", template.signalCatalogArn);
            putOptional(summary, "description", template.description);
            summary.put("creationTime", template.creationTime);
            summary.put("lastModificationTime", template.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode createModelManifest(JsonNode request, String region) {
        String name = requireText(request, "name");
        if (models.containsKey(name)) {
            throw conflict(name, "modelManifest");
        }
        NamedResource model = newResource(name, arn(region, "model-manifest/" + name));
        model.description = textOrNull(request, "description");
        model.signalCatalogArn = requireText(request, "signalCatalogArn");
        model.status = "DRAFT";
        model.nodePaths.addAll(copyStrings(request.get("nodes")));
        model.tags.putAll(readTags(request));
        models.put(name, model);
        return nameArn(model);
    }

    public ObjectNode getModelManifest(JsonNode request) {
        NamedResource model = requireModel(requireText(request, "name"));
        ObjectNode response = nameArn(model);
        putOptional(response, "description", model.description);
        putOptional(response, "signalCatalogArn", model.signalCatalogArn);
        response.put("status", model.status);
        response.put("creationTime", model.creationTime);
        response.put("lastModificationTime", model.lastModificationTime);
        return response;
    }

    public ObjectNode updateModelManifest(JsonNode request) {
        NamedResource model = requireModel(requireText(request, "name"));
        if (request.hasNonNull("description")) {
            model.description = request.get("description").asText();
        }
        for (String fqn : copyStrings(request.get("nodesToAdd"))) {
            if (!model.nodePaths.contains(fqn)) {
                model.nodePaths.add(fqn);
            }
        }
        model.nodePaths.removeAll(copyStrings(request.get("nodesToRemove")));
        if (request.hasNonNull("status")) {
            model.status = request.get("status").asText();
        }
        touch(model);
        return nameArn(model);
    }

    public ObjectNode deleteModelManifest(JsonNode request) {
        String name = requireText(request, "name");
        NamedResource model = models.remove(name);
        if (model == null) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("name", name);
            return response;
        }
        return nameArn(model);
    }

    public ObjectNode listModelManifests() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("summaries");
        for (NamedResource model : models.values()) {
            ObjectNode summary = nameArn(model);
            putOptional(summary, "signalCatalogArn", model.signalCatalogArn);
            putOptional(summary, "description", model.description);
            summary.put("status", model.status);
            summary.put("creationTime", model.creationTime);
            summary.put("lastModificationTime", model.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode listModelManifestNodes(JsonNode request) {
        NamedResource model = requireModel(requireText(request, "name"));
        NamedResource catalog = model.signalCatalogArn == null
                ? null
                : findCatalogByArn(model.signalCatalogArn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode nodes = response.putArray("nodes");
        for (String fqn : model.nodePaths) {
            JsonNode catalogNode = catalog == null ? null : findNode(catalog.nodes, fqn);
            if (catalogNode != null) {
                nodes.add(catalogNode.deepCopy());
            } else {
                ObjectNode branch = objectMapper.createObjectNode();
                ObjectNode inner = objectMapper.createObjectNode();
                inner.put("fullyQualifiedName", fqn);
                branch.set("branch", inner);
                nodes.add(branch);
            }
        }
        return response;
    }

    public ObjectNode createDecoderManifest(JsonNode request, String region) {
        String name = requireText(request, "name");
        if (decoders.containsKey(name)) {
            throw conflict(name, "decoderManifest");
        }
        NamedResource decoder = newResource(name, arn(region, "decoder-manifest/" + name));
        decoder.description = textOrNull(request, "description");
        decoder.modelManifestArn = requireText(request, "modelManifestArn");
        decoder.status = "DRAFT";
        decoder.networkInterfaces.addAll(copyArray(request.get("networkInterfaces")));
        decoder.signalDecoders.addAll(copyArray(request.get("signalDecoders")));
        decoder.tags.putAll(readTags(request));
        decoders.put(name, decoder);
        return nameArn(decoder);
    }

    public ObjectNode getDecoderManifest(JsonNode request) {
        NamedResource decoder = requireDecoder(requireText(request, "name"));
        ObjectNode response = nameArn(decoder);
        putOptional(response, "description", decoder.description);
        putOptional(response, "modelManifestArn", decoder.modelManifestArn);
        response.put("status", decoder.status);
        response.put("creationTime", decoder.creationTime);
        response.put("lastModificationTime", decoder.lastModificationTime);
        return response;
    }

    public ObjectNode updateDecoderManifest(JsonNode request) {
        NamedResource decoder = requireDecoder(requireText(request, "name"));
        if (request.hasNonNull("description")) {
            decoder.description = request.get("description").asText();
        }
        applyKeyedDelta(decoder.networkInterfaces, request.get("networkInterfacesToAdd"),
                request.get("networkInterfacesToUpdate"), request.get("networkInterfacesToRemove"),
                "interfaceId");
        applyKeyedDelta(decoder.signalDecoders, request.get("signalDecodersToAdd"),
                request.get("signalDecodersToUpdate"), request.get("signalDecodersToRemove"),
                "fullyQualifiedName");
        if (request.hasNonNull("status")) {
            decoder.status = request.get("status").asText();
        }
        touch(decoder);
        return nameArn(decoder);
    }

    public ObjectNode deleteDecoderManifest(JsonNode request) {
        String name = requireText(request, "name");
        NamedResource decoder = decoders.remove(name);
        if (decoder == null) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("name", name);
            return response;
        }
        return nameArn(decoder);
    }

    public ObjectNode listDecoderManifests() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("summaries");
        for (NamedResource decoder : decoders.values()) {
            ObjectNode summary = nameArn(decoder);
            putOptional(summary, "modelManifestArn", decoder.modelManifestArn);
            putOptional(summary, "description", decoder.description);
            summary.put("status", decoder.status);
            summary.put("creationTime", decoder.creationTime);
            summary.put("lastModificationTime", decoder.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode listDecoderManifestNetworkInterfaces(JsonNode request) {
        NamedResource decoder = requireDecoder(requireText(request, "name"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("networkInterfaces");
        for (JsonNode item : decoder.networkInterfaces) {
            items.add(item.deepCopy());
        }
        return response;
    }

    public ObjectNode listDecoderManifestSignals(JsonNode request) {
        NamedResource decoder = requireDecoder(requireText(request, "name"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("signalDecoders");
        for (JsonNode item : decoder.signalDecoders) {
            items.add(item.deepCopy());
        }
        return response;
    }

    public ObjectNode createFleet(JsonNode request, String region) {
        String fleetId = requireText(request, "fleetId");
        if (fleets.containsKey(fleetId)) {
            throw conflict(fleetId, "fleet");
        }
        NamedResource fleet = newResource(fleetId, arn(region, "fleet/" + fleetId));
        fleet.description = textOrNull(request, "description");
        fleet.signalCatalogArn = requireText(request, "signalCatalogArn");
        fleet.tags.putAll(readTags(request));
        fleets.put(fleetId, fleet);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleet.name);
        response.put("arn", fleet.arn);
        return response;
    }

    public ObjectNode getFleet(JsonNode request) {
        NamedResource fleet = requireFleet(requireText(request, "fleetId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleet.name);
        response.put("arn", fleet.arn);
        putOptional(response, "description", fleet.description);
        response.put("signalCatalogArn", fleet.signalCatalogArn);
        response.put("creationTime", fleet.creationTime);
        response.put("lastModificationTime", fleet.lastModificationTime);
        return response;
    }

    public ObjectNode updateFleet(JsonNode request) {
        NamedResource fleet = requireFleet(requireText(request, "fleetId"));
        if (request.hasNonNull("description")) {
            fleet.description = request.get("description").asText();
        }
        touch(fleet);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleet.name);
        response.put("arn", fleet.arn);
        return response;
    }

    public ObjectNode deleteFleet(JsonNode request) {
        String fleetId = requireText(request, "fleetId");
        NamedResource fleet = fleets.remove(fleetId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleetId);
        if (fleet != null) {
            response.put("arn", fleet.arn);
        }
        return response;
    }

    public ObjectNode listFleets() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("fleetSummaries");
        for (NamedResource fleet : fleets.values()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("id", fleet.name);
            summary.put("arn", fleet.arn);
            putOptional(summary, "description", fleet.description);
            summary.put("signalCatalogArn", fleet.signalCatalogArn);
            summary.put("creationTime", fleet.creationTime);
            summary.put("lastModificationTime", fleet.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode createVehicle(JsonNode request, String region) {
        String vehicleName = requireText(request, "vehicleName");
        if (vehicles.containsKey(vehicleName)) {
            throw conflict(vehicleName, "vehicle");
        }
        NamedResource vehicle = newResource(vehicleName, arn(region, "vehicle/" + vehicleName));
        vehicle.modelManifestArn = requireText(request, "modelManifestArn");
        vehicle.decoderManifestArn = requireText(request, "decoderManifestArn");
        vehicle.attributes.putAll(readStringMap(request.get("attributes")));
        vehicle.tags.putAll(readTags(request));
        vehicles.put(vehicleName, vehicle);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicle.name);
        response.put("arn", vehicle.arn);
        return response;
    }

    public ObjectNode getVehicle(JsonNode request) {
        NamedResource vehicle = requireVehicle(requireText(request, "vehicleName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicle.name);
        response.put("arn", vehicle.arn);
        putOptional(response, "modelManifestArn", vehicle.modelManifestArn);
        putOptional(response, "decoderManifestArn", vehicle.decoderManifestArn);
        if (!vehicle.attributes.isEmpty()) {
            ObjectNode attributes = objectMapper.createObjectNode();
            vehicle.attributes.forEach(attributes::put);
            response.set("attributes", attributes);
        }
        response.put("creationTime", vehicle.creationTime);
        response.put("lastModificationTime", vehicle.lastModificationTime);
        return response;
    }

    public ObjectNode updateVehicle(JsonNode request) {
        NamedResource vehicle = requireVehicle(requireText(request, "vehicleName"));
        if (request.hasNonNull("modelManifestArn")) {
            vehicle.modelManifestArn = request.get("modelManifestArn").asText();
        }
        if (request.hasNonNull("decoderManifestArn")) {
            vehicle.decoderManifestArn = request.get("decoderManifestArn").asText();
        }
        if (request.has("attributes")) {
            String mode = textOrNull(request, "attributeUpdateMode");
            if (mode == null || "Overwrite".equals(mode)) {
                vehicle.attributes.clear();
            }
            vehicle.attributes.putAll(readStringMap(request.get("attributes")));
        }
        touch(vehicle);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicle.name);
        response.put("arn", vehicle.arn);
        return response;
    }

    public ObjectNode deleteVehicle(JsonNode request) {
        String vehicleName = requireText(request, "vehicleName");
        NamedResource vehicle = vehicles.remove(vehicleName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicleName);
        if (vehicle != null) {
            response.put("arn", vehicle.arn);
        }
        return response;
    }

    public ObjectNode listVehicles() {
        return listVehicles(objectMapper.createObjectNode());
    }

    public ObjectNode listVehicles(JsonNode request) {
        String modelManifestArn = textOrNull(request, "modelManifestArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("vehicleSummaries");
        for (NamedResource vehicle : vehicles.values()) {
            if (modelManifestArn != null && !modelManifestArn.equals(vehicle.modelManifestArn)) {
                continue;
            }
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("vehicleName", vehicle.name);
            summary.put("arn", vehicle.arn);
            summary.put("modelManifestArn", vehicle.modelManifestArn);
            summary.put("decoderManifestArn", vehicle.decoderManifestArn);
            summary.put("creationTime", vehicle.creationTime);
            summary.put("lastModificationTime", vehicle.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode batchCreateVehicle(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode created = response.putArray("vehicles");
        ArrayNode errors = response.putArray("errors");
        JsonNode items = request == null ? null : request.get("vehicles");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                try {
                    created.add(createVehicle(item, region));
                } catch (AwsException e) {
                    ObjectNode error = errors.addObject();
                    putOptional(error, "vehicleName", textOrNull(item, "vehicleName"));
                    error.put("code", e.getErrorCode());
                    putOptional(error, "message", e.getMessage());
                }
            }
        }
        return response;
    }

    public ObjectNode batchUpdateVehicle(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode updated = response.putArray("vehicles");
        ArrayNode errors = response.putArray("errors");
        JsonNode items = request == null ? null : request.get("vehicles");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                try {
                    updated.add(updateVehicle(item));
                } catch (AwsException e) {
                    ObjectNode error = errors.addObject();
                    putOptional(error, "vehicleName", textOrNull(item, "vehicleName"));
                    error.put("code", 400);
                    putOptional(error, "message", e.getMessage());
                }
            }
        }
        return response;
    }

    public ObjectNode createCampaign(JsonNode request, String region) {
        String name = requireText(request, "name");
        if (campaigns.containsKey(name)) {
            throw conflict(name, "campaign");
        }
        NamedResource campaign = newResource(name, arn(region, "campaign/" + name));
        campaign.description = textOrNull(request, "description");
        campaign.signalCatalogArn = requireText(request, "signalCatalogArn");
        campaign.targetArn = requireText(request, "targetArn");
        campaign.status = "WAITING_FOR_APPROVAL";
        campaign.collectionScheme = copy(request.get("collectionScheme"));
        campaign.signalsToCollect = copy(request.get("signalsToCollect"));
        campaign.dataDestinationConfigs = copy(request.get("dataDestinationConfigs"));
        campaign.tags.putAll(readTags(request));
        campaigns.put(name, campaign);
        return nameArn(campaign);
    }

    public ObjectNode getCampaign(JsonNode request) {
        NamedResource campaign = requireCampaign(requireText(request, "name"));
        ObjectNode response = nameArn(campaign);
        putOptional(response, "description", campaign.description);
        putOptional(response, "signalCatalogArn", campaign.signalCatalogArn);
        putOptional(response, "targetArn", campaign.targetArn);
        response.put("status", campaign.status);
        if (campaign.collectionScheme != null) {
            response.set("collectionScheme", campaign.collectionScheme.deepCopy());
        }
        if (campaign.signalsToCollect != null) {
            response.set("signalsToCollect", campaign.signalsToCollect.deepCopy());
        }
        if (campaign.dataDestinationConfigs != null) {
            response.set("dataDestinationConfigs", campaign.dataDestinationConfigs.deepCopy());
        }
        response.put("creationTime", campaign.creationTime);
        response.put("lastModificationTime", campaign.lastModificationTime);
        return response;
    }

    public ObjectNode updateCampaign(JsonNode request) {
        NamedResource campaign = requireCampaign(requireText(request, "name"));
        if (request.hasNonNull("description")) {
            campaign.description = request.get("description").asText();
        }
        if (request.has("dataExtraDimensions")) {
            campaign.dataExtraDimensions.clear();
            campaign.dataExtraDimensions.addAll(copyStrings(request.get("dataExtraDimensions")));
        }
        String action = textOrNull(request, "action");
        if ("APPROVE".equals(action) || "RESUME".equals(action)) {
            campaign.status = "RUNNING";
        } else if ("SUSPEND".equals(action)) {
            campaign.status = "SUSPENDED";
        }
        touch(campaign);
        return nameArn(campaign);
    }

    public ObjectNode deleteCampaign(JsonNode request) {
        String name = requireText(request, "name");
        NamedResource campaign = campaigns.remove(name);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", name);
        if (campaign != null) {
            response.put("arn", campaign.arn);
        }
        return response;
    }

    public ObjectNode listCampaigns() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("campaignSummaries");
        for (NamedResource campaign : campaigns.values()) {
            ObjectNode summary = nameArn(campaign);
            putOptional(summary, "description", campaign.description);
            putOptional(summary, "signalCatalogArn", campaign.signalCatalogArn);
            putOptional(summary, "targetArn", campaign.targetArn);
            summary.put("status", campaign.status);
            summary.put("creationTime", campaign.creationTime);
            summary.put("lastModificationTime", campaign.lastModificationTime);
            summaries.add(summary);
        }
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        NamedResource resource = requireByArn(requireText(request, "ResourceARN"));
        resource.tags.putAll(readTags(request));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        NamedResource resource = requireByArn(requireText(request, "ResourceARN"));
        for (String key : copyStrings(request.get("TagKeys"))) {
            resource.tags.remove(key);
        }
        return objectMapper.createObjectNode();
    }

    public Vehicle createVehicle(String region, String vehicleName, JsonNode request) {
        ObjectNode body = request == null || !request.isObject()
                ? objectMapper.createObjectNode()
                : (ObjectNode) request.deepCopy();
        body.put("vehicleName", vehicleName);
        createVehicle(body, region);
        return toVehicleModel(requireVehicle(vehicleName));
    }

    public Vehicle getVehicle(String region, String vehicleName) {
        return toVehicleModel(requireVehicle(vehicleName));
    }

    public Page<CampaignStatus> getVehicleStatus(
            String region, String vehicleName, String nextToken, String maxResults) {
        NamedResource vehicle = requireVehicle(vehicleName);
        return new Page<>(List.copyOf(vehicle.campaignStatuses), null);
    }

    public void associateVehicleFleet(String region, String vehicleName, JsonNode request) {
        NamedResource vehicle = requireVehicle(vehicleName);
        String fleetId = requireText(request, "fleetId");
        NamedResource fleet = requireFleet(fleetId);
        if (!vehicle.fleetIds.contains(fleetId)) {
            vehicle.fleetIds.add(fleetId);
        }
        if (!fleet.vehicleNames.contains(vehicleName)) {
            fleet.vehicleNames.add(vehicleName);
        }
    }

    public Fleet createFleet(String region, String fleetId, JsonNode request) {
        ObjectNode body = request == null || !request.isObject()
                ? objectMapper.createObjectNode()
                : (ObjectNode) request.deepCopy();
        body.put("fleetId", fleetId);
        createFleet(body, region);
        return toFleetModel(requireFleet(fleetId));
    }

    public Fleet getFleet(String region, String fleetId) {
        return toFleetModel(requireFleet(fleetId));
    }

    public Page<String> listVehiclesInFleet(
            String region, String fleetId, String nextToken, String maxResults) {
        NamedResource fleet = requireFleet(fleetId);
        return new Page<>(List.copyOf(fleet.vehicleNames), null);
    }

    public ObjectNode getVehicleStatus(JsonNode request) {
        NamedResource vehicle = requireVehicle(requireText(request, "vehicleName"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode campaigns = response.putArray("campaigns");
        for (CampaignStatus status : vehicle.campaignStatuses) {
            ObjectNode node = campaigns.addObject();
            if (status.getCampaignName() != null) {
                node.put("campaignName", status.getCampaignName());
            }
            node.put("vehicleName",
                    status.getVehicleName() == null ? vehicle.name : status.getVehicleName());
            if (status.getStatus() != null) {
                node.put("status", status.getStatus());
            }
        }
        for (NamedResource campaign : this.campaigns.values()) {
            if (!campaignTargetsVehicle(campaign, vehicle)) {
                continue;
            }
            boolean already = false;
            for (CampaignStatus existing : vehicle.campaignStatuses) {
                if (campaign.name.equals(existing.getCampaignName())) {
                    already = true;
                    break;
                }
            }
            if (already) {
                continue;
            }
            ObjectNode node = campaigns.addObject();
            node.put("campaignName", campaign.name);
            node.put("vehicleName", vehicle.name);
            node.put("status", vehicleState(campaign.status));
        }
        return response;
    }

    public ObjectNode listVehiclesInFleet(JsonNode request) {
        NamedResource fleet = requireFleet(requireText(request, "fleetId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode vehiclesNode = response.putArray("vehicles");
        for (String name : fleet.vehicleNames) {
            vehiclesNode.add(name);
        }
        return response;
    }

    public ObjectNode associateVehicleFleet(JsonNode request) {
        associateVehicleFleet(regionResolver.getRegion(), requireText(request, "vehicleName"), request);
        return objectMapper.createObjectNode();
    }

    public ObjectNode disassociateVehicleFleet(JsonNode request) {
        String vehicleName = requireText(request, "vehicleName");
        String fleetId = requireText(request, "fleetId");
        NamedResource vehicle = requireVehicle(vehicleName);
        NamedResource fleet = requireFleet(fleetId);
        vehicle.fleetIds.remove(fleetId);
        fleet.vehicleNames.remove(vehicleName);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listFleetsForVehicle(JsonNode request) {
        NamedResource vehicle = requireVehicle(requireText(request, "vehicleName"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode fleetsNode = response.putArray("fleets");
        for (String fleetId : vehicle.fleetIds) {
            fleetsNode.add(fleetId);
        }
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        NamedResource resource = requireByArn(requireText(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        resource.tags.forEach((key, value) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", key);
            tag.put("Value", value);
            tags.add(tag);
        });
        return response;
    }

    private Vehicle toVehicleModel(NamedResource resource) {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleName(resource.name);
        vehicle.setArn(resource.arn);
        vehicle.setModelManifestArn(resource.modelManifestArn);
        vehicle.setDecoderManifestArn(resource.decoderManifestArn);
        vehicle.setAttributes(resource.attributes);
        vehicle.setCreationTime(resource.creationTime);
        vehicle.setLastModificationTime(resource.lastModificationTime);
        vehicle.setCampaigns(resource.campaignStatuses);
        vehicle.setFleetIds(resource.fleetIds);
        return vehicle;
    }

    private Fleet toFleetModel(NamedResource resource) {
        Fleet fleet = new Fleet();
        fleet.setFleetId(resource.name);
        fleet.setArn(resource.arn);
        fleet.setSignalCatalogArn(resource.signalCatalogArn);
        fleet.setDescription(resource.description);
        fleet.setCreationTime(resource.creationTime);
        fleet.setLastModificationTime(resource.lastModificationTime);
        fleet.setVehicles(resource.vehicleNames);
        return fleet;
    }

    private NamedResource newResource(String name, String arn) {
        long now = Instant.now().getEpochSecond();
        NamedResource resource = new NamedResource();
        resource.name = name;
        resource.arn = arn;
        resource.creationTime = now;
        resource.lastModificationTime = now;
        return resource;
    }

    private ObjectNode nameArn(NamedResource resource) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", resource.name);
        response.put("arn", resource.arn);
        return response;
    }

    private ObjectNode stateTemplateId(NamedResource template) {
        ObjectNode response = nameArn(template);
        putOptional(response, "id", template.id);
        return response;
    }

    private NamedResource requireCatalog(String name) {
        NamedResource catalog = catalogs.get(name);
        if (catalog == null) {
            throw notFound(name, "signalCatalog");
        }
        return catalog;
    }

    private NamedResource requireStateTemplate(String identifier) {
        NamedResource template = findStateTemplate(identifier);
        if (template == null) {
            throw notFound(identifier, "stateTemplate");
        }
        return template;
    }

    private NamedResource findStateTemplate(String identifier) {
        NamedResource byName = stateTemplates.get(identifier);
        if (byName != null) {
            return byName;
        }
        for (NamedResource template : stateTemplates.values()) {
            if (identifier.equals(template.id) || identifier.equals(template.arn)) {
                return template;
            }
        }
        return null;
    }

    private NamedResource requireModel(String name) {
        NamedResource model = models.get(name);
        if (model == null) {
            throw notFound(name, "modelManifest");
        }
        return model;
    }

    private NamedResource requireDecoder(String name) {
        NamedResource decoder = decoders.get(name);
        if (decoder == null) {
            throw notFound(name, "decoderManifest");
        }
        return decoder;
    }

    private NamedResource requireFleet(String fleetId) {
        NamedResource fleet = fleets.get(fleetId);
        if (fleet == null) {
            throw notFound(fleetId, "fleet");
        }
        return fleet;
    }

    private NamedResource requireVehicle(String vehicleName) {
        NamedResource vehicle = vehicles.get(vehicleName);
        if (vehicle == null) {
            throw notFound(vehicleName, "vehicle");
        }
        return vehicle;
    }

    private NamedResource requireCampaign(String name) {
        NamedResource campaign = campaigns.get(name);
        if (campaign == null) {
            throw notFound(name, "campaign");
        }
        return campaign;
    }

    private NamedResource requireByArn(String arn) {
        for (ConcurrentHashMap<String, NamedResource> store : List.of(
                catalogs, stateTemplates, models, decoders, fleets, vehicles, campaigns)) {
            for (NamedResource resource : store.values()) {
                if (arn.equals(resource.arn)) {
                    return resource;
                }
            }
        }
        throw notFound(arn, "resource");
    }

    private NamedResource findCatalogByArn(String arn) {
        for (NamedResource catalog : catalogs.values()) {
            if (arn.equals(catalog.arn)) {
                return catalog;
            }
        }
        return null;
    }

    private static JsonNode findNode(List<JsonNode> nodes, String fqn) {
        for (JsonNode node : nodes) {
            if (fqn.equals(nodeFqn(node))) {
                return node;
            }
        }
        return null;
    }

    private static String nodeFqn(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String kind : List.of("branch", "sensor", "actuator", "attribute", "struct", "property")) {
            JsonNode inner = node.get(kind);
            if (inner != null && inner.hasNonNull("fullyQualifiedName")) {
                return inner.get("fullyQualifiedName").asText();
            }
        }
        return node.hasNonNull("fullyQualifiedName") ? node.get("fullyQualifiedName").asText() : "";
    }

    private void applyNodeDelta(List<JsonNode> nodes, JsonNode add, JsonNode update, JsonNode remove) {
        for (String fqn : copyStrings(remove)) {
            nodes.removeIf(node -> fqn.equals(nodeFqn(node)));
        }
        for (JsonNode node : copyArray(update)) {
            String fqn = nodeFqn(node);
            nodes.removeIf(existing -> fqn.equals(nodeFqn(existing)));
            nodes.add(node);
        }
        for (JsonNode node : copyArray(add)) {
            String fqn = nodeFqn(node);
            nodes.removeIf(existing -> fqn.equals(nodeFqn(existing)));
            nodes.add(node);
        }
    }

    private void applyKeyedDelta(List<JsonNode> items, JsonNode add, JsonNode update, JsonNode remove, String key) {
        for (String id : copyStrings(remove)) {
            items.removeIf(item -> id.equals(textOrNull(item, key)));
        }
        for (JsonNode item : copyArray(update)) {
            String id = textOrNull(item, key);
            items.removeIf(existing -> id != null && id.equals(textOrNull(existing, key)));
            items.add(item);
        }
        for (JsonNode item : copyArray(add)) {
            String id = textOrNull(item, key);
            items.removeIf(existing -> id != null && id.equals(textOrNull(existing, key)));
            items.add(item);
        }
    }

    private ObjectNode nodeCounts(List<JsonNode> nodes) {
        int branches = 0;
        int sensors = 0;
        int attributes = 0;
        int actuators = 0;
        int structs = 0;
        int properties = 0;
        for (JsonNode node : nodes) {
            if (node.has("branch")) {
                branches++;
            } else if (node.has("sensor")) {
                sensors++;
            } else if (node.has("attribute")) {
                attributes++;
            } else if (node.has("actuator")) {
                actuators++;
            } else if (node.has("struct")) {
                structs++;
            } else if (node.has("property")) {
                properties++;
            }
        }
        ObjectNode counts = objectMapper.createObjectNode();
        counts.put("totalNodes", nodes.size());
        counts.put("totalBranches", branches);
        counts.put("totalSensors", sensors);
        counts.put("totalAttributes", attributes);
        counts.put("totalActuators", actuators);
        counts.put("totalStructs", structs);
        counts.put("totalProperties", properties);
        return counts;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private List<JsonNode> copyArray(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                out.add(item.deepCopy());
            }
        }
        return out;
    }

    private static List<String> copyStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                JsonNode value = node.get(key);
                if (value != null && !value.isNull()) {
                    out.put(key, value.asText());
                }
            }
        }
        return out;
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("tags");
        if (tagsNode == null || tagsNode.isNull() || tagsNode.isMissingNode()) {
            tagsNode = request.get("Tags");
        }
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = textOrNull(tag, "Key");
                if (key == null) {
                    key = textOrNull(tag, "key");
                }
                String value = textOrNull(tag, "Value");
                if (value == null) {
                    value = textOrNull(tag, "value");
                }
                if (key != null) {
                    tags.put(key, value != null ? value : "");
                }
            }
        }
        return tags;
    }

    private JsonNode copy(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.deepCopy();
    }

    private void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void touch(NamedResource resource) {
        resource.lastModificationTime = Instant.now().getEpochSecond();
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean campaignTargetsVehicle(NamedResource campaign, NamedResource vehicle) {
        if (campaign.targetArn == null) {
            return false;
        }
        if (campaign.targetArn.equals(vehicle.arn)) {
            return true;
        }
        for (String fleetId : vehicle.fleetIds) {
            if (campaign.targetArn.contains(":fleet/" + fleetId)) {
                return true;
            }
        }
        return false;
    }

    private static String vehicleState(String campaignStatus) {
        if ("RUNNING".equals(campaignStatus)) {
            return "HEALTHY";
        }
        if ("SUSPENDED".equals(campaignStatus)) {
            return "SUSPENDED";
        }
        return "CREATED";
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " not found",
                404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resource, String resourceType) {
        return new AwsException(
                "ConflictException",
                resourceType + " " + resource + " already exists",
                409,
                Map.of("resource", resource, "resourceType", resourceType));
    }
}
