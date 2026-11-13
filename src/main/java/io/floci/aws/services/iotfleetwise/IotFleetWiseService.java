package io.floci.aws.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iot.IotService;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.iotfleetwise.IotFleetWiseModels.copy;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseModels.strings;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.CAMPAIGN;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.CATALOG;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.FLEET;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.VEHICLE;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.key;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.meta;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.view;

/**
 * Fleets, vehicles and campaigns, and the account's registration, encryption and logging settings. A vehicle is an
 * AWS IoT thing; approving a campaign deploys it to the vehicles it targets through {@link IotFleetWiseEdge}, and
 * suspending, resuming or deleting it, or a change of a fleet's vehicles, redeploys them.
 */
@ApplicationScoped
public class IotFleetWiseService {

    /** The far-future expiry IoT FleetWise gives a campaign without one. */
    /** IoT FleetWise's service principal, which a destination bucket's policy must allow. */
    private static final String SERVICE_PRINCIPAL = "iotfleetwise.amazonaws.com"; // partition-literal: service principals keep the commercial name

    private static final Instant NO_EXPIRY = Instant.parse("2253-12-31T23:59:59Z");

    private final IotFleetWiseStore store;
    private final IotFleetWiseModels models;
    private final IotFleetWiseEdge edge;
    private final Instance<IotService> iot;
    private final Instance<S3Service> s3;
    private final Instance<IamService> iam;

    @Inject
    public IotFleetWiseService(IotFleetWiseStore store, IotFleetWiseModels models, IotFleetWiseEdge edge,
                               Instance<IotService> iot, Instance<S3Service> s3, Instance<IamService> iam) {
        this.store = store;
        this.models = models;
        this.edge = edge;
        this.iot = iot;
        this.s3 = s3;
        this.iam = iam;
    }

    /** Refuses every operation for an account IoT FleetWise isn't open to. */
    void checkCustomer() {
        if (!store.config().existingCustomer()) {
            throw new AwsException("AccessDeniedException", "", 400);
        }
    }

    // ---------------------------------------------------------------- fleets

    public ObjectNode createFleet(JsonNode in, String region) {
        String account = store.account();
        String id = in.path("fleetId").asText();
        if (store.get(account, key(region, FLEET, id)).isPresent()) {
            throw IotFleetWiseStore.conflict(FLEET, id, "The fleet " + id + " already exists.");
        }
        ObjectNode catalog = models.catalogByArn(account, region, in.path("signalCatalogArn").asText());
        ObjectNode record = store.record(FLEET, id);
        record.put("id", id).put("arn", store.arn(account, region, FLEET, id))
                .put("signalCatalogArn", catalog.path("arn").asText());
        copy(in, record, "description");
        meta(record).putArray("vehicles");
        meta(record).set("tags", models.tagMap(in.path("tags")));
        store.put(account, key(region, FLEET, id), record);
        return store.mapper().createObjectNode().put("id", id).put("arn", record.path("arn").asText());
    }

    public ObjectNode getFleet(JsonNode in, String region) {
        return view(fleet(store.account(), region, in.path("fleetId").asText()));
    }

    public ObjectNode updateFleet(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = fleet(account, region, in.path("fleetId").asText());
        copy(in, record, "description");
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, FLEET, record.path("id").asText()), record);
        return store.mapper().createObjectNode().put("id", record.path("id").asText())
                .put("arn", record.path("arn").asText());
    }

    public ObjectNode deleteFleet(JsonNode in, String region) {
        String account = store.account();
        String id = in.path("fleetId").asText();
        ObjectNode record = store.get(account, key(region, FLEET, id)).orElse(null);
        if (record == null) {
            // Deleting a fleet that doesn't exist succeeds, as IoT FleetWise's DeleteFleet does.
            return store.mapper().createObjectNode().put("id", id)
                    .put("arn", store.arn(account, region, FLEET, id));
        }
        if (!record.path("_").path("vehicles").isEmpty()) {
            throw IotFleetWiseStore.validation("The fleet " + id + " has vehicles; disassociate them before deleting "
                    + "it.");
        }
        for (ObjectNode campaign : store.list(account, region, CAMPAIGN)) {
            if (record.path("arn").asText().equals(campaign.path("targetArn").asText())) {
                throw IotFleetWiseStore.validation("The fleet " + id + " is the target of campaign "
                        + campaign.path("name").asText() + ".");
            }
        }
        store.delete(account, key(region, FLEET, id));
        return store.mapper().createObjectNode().put("id", id).put("arn", record.path("arn").asText());
    }

    public ObjectNode listFleets(JsonNode in, String region) {
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, FLEET)) {
            ObjectNode summary = store.mapper().createObjectNode().put("id", record.path("id").asText())
                    .put("arn", record.path("arn").asText());
            if (!metadataOnly) {
                summary.put("signalCatalogArn", record.path("signalCatalogArn").asText())
                        .put("creationTime", record.path("creationTime").asDouble())
                        .put("lastModificationTime", record.path("lastModificationTime").asDouble());
                copy(record, summary, "description");
            }
            summaries.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "fleetSummaries");
        return out;
    }

    public ObjectNode associateVehicleFleet(JsonNode in, String region) {
        String account = store.account();
        ObjectNode vehicle = vehicle(account, region, in.path("vehicleName").asText());
        ObjectNode fleet = fleet(account, region, in.path("fleetId").asText());
        String name = vehicle.path("vehicleName").asText();
        ArrayNode members = (ArrayNode) meta(fleet).withArray("vehicles");
        if (strings(members).contains(name)) {
            return store.mapper().createObjectNode();
        }
        if (members.size() >= store.config().maxVehiclesPerFleet()) {
            throw IotFleetWiseStore.limit(FLEET, fleet.path("id").asText(), "The fleet has the most vehicles it can "
                    + "have.");
        }
        members.add(name);
        IotFleetWiseStore.touch(fleet);
        store.put(account, key(region, FLEET, fleet.path("id").asText()), fleet);
        edge.deploy(account, region, name);
        return store.mapper().createObjectNode();
    }

    public ObjectNode disassociateVehicleFleet(JsonNode in, String region) {
        String account = store.account();
        ObjectNode vehicle = vehicle(account, region, in.path("vehicleName").asText());
        ObjectNode fleet = fleet(account, region, in.path("fleetId").asText());
        String name = vehicle.path("vehicleName").asText();
        List<String> members = strings(fleet.path("_").path("vehicles"));
        if (!members.remove(name)) {
            throw IotFleetWiseStore.notFound(VEHICLE, name + " in fleet " + fleet.path("id").asText());
        }
        ArrayNode list = meta(fleet).putArray("vehicles");
        members.forEach(list::add);
        IotFleetWiseStore.touch(fleet);
        store.put(account, key(region, FLEET, fleet.path("id").asText()), fleet);
        edge.deploy(account, region, name);
        return store.mapper().createObjectNode();
    }

    public ObjectNode listVehiclesInFleet(JsonNode in, String region) {
        ObjectNode fleet = fleet(store.account(), region, in.path("fleetId").asText());
        List<JsonNode> vehicles = new ArrayList<>();
        fleet.path("_").path("vehicles").forEach(vehicles::add);
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(vehicles, in, 100, out, "vehicles");
        return out;
    }

    public ObjectNode listFleetsForVehicle(JsonNode in, String region) {
        String account = store.account();
        String name = vehicle(account, region, in.path("vehicleName").asText()).path("vehicleName").asText();
        List<JsonNode> fleets = new ArrayList<>();
        for (ObjectNode fleet : store.list(account, region, FLEET)) {
            if (strings(fleet.path("_").path("vehicles")).contains(name)) {
                fleets.add(fleet.path("id"));
            }
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(fleets, in, 100, out, "fleets");
        return out;
    }

    ObjectNode fleet(String account, String region, String id) {
        return store.get(account, key(region, FLEET, id)).orElseThrow(() -> IotFleetWiseStore.notFound(FLEET, id));
    }

    // ---------------------------------------------------------------- vehicles

    public ObjectNode createVehicle(JsonNode in, String region) {
        ObjectNode vehicle = newVehicle(in, region);
        return store.mapper().createObjectNode().put("vehicleName", vehicle.path("vehicleName").asText())
                .put("arn", vehicle.path("arn").asText()).put("thingArn", meta(vehicle).path("thingArn").asText());
    }

    private ObjectNode newVehicle(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("vehicleName").asText();
        if (store.get(account, key(region, VEHICLE, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(VEHICLE, name, "The vehicle " + name + " already exists.");
        }
        ObjectNode model = models.modelByArn(account, region, in.path("modelManifestArn").asText());
        ObjectNode decoder = models.decoderByArn(account, region, in.path("decoderManifestArn").asText());
        checkManifests(model, decoder);
        ObjectNode catalog = models.catalogByArn(account, region, model.path("signalCatalogArn").asText());
        checkAttributes(catalog, in.path("attributes"));
        checkStateTemplates(account, region, catalog, in.path("stateTemplates"));
        String thingArn;
        String behavior = in.path("associationBehavior").asText("CreateIotThing");
        if ("ValidateIotThingExists".equals(behavior)) {
            try {
                thingArn = iot.get().describeThing(name, region).getThingArn();
            } catch (AwsException e) {
                throw IotFleetWiseStore.notFound("thing", name);
            }
        } else {
            try {
                thingArn = iot.get().createThing(name, Map.of(), region).getThingArn();
            } catch (AwsException e) {
                throw IotFleetWiseStore.conflict(VEHICLE, name, "The AWS IoT thing " + name + " can't be created: "
                        + e.getMessage());
            }
        }
        ObjectNode record = store.record(VEHICLE, name);
        record.put("vehicleName", name).put("arn", store.arn(account, region, VEHICLE, name))
                .put("modelManifestArn", model.path("arn").asText())
                .put("decoderManifestArn", decoder.path("arn").asText());
        record.set("attributes", in.has("attributes") ? in.get("attributes").deepCopy()
                : store.mapper().createObjectNode());
        if (in.has("stateTemplates")) {
            record.set("stateTemplates", in.get("stateTemplates").deepCopy());
        }
        meta(record).put("thingArn", thingArn);
        meta(record).set("tags", models.tagMap(in.path("tags")));
        store.put(account, key(region, VEHICLE, name), record);
        edge.deploy(account, region, name);
        return record;
    }

    public ObjectNode getVehicle(JsonNode in, String region) {
        return view(vehicle(store.account(), region, in.path("vehicleName").asText()));
    }

    public ObjectNode updateVehicle(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("vehicleName").asText();
        ObjectNode record = vehicle(account, region, name);
        String modelArn = in.path("modelManifestArn").asText(record.path("modelManifestArn").asText());
        String decoderArn = in.path("decoderManifestArn").asText(record.path("decoderManifestArn").asText());
        ObjectNode model = models.modelByArn(account, region, modelArn);
        ObjectNode decoder = models.decoderByArn(account, region, decoderArn);
        checkManifests(model, decoder);
        ObjectNode catalog = models.catalogByArn(account, region, model.path("signalCatalogArn").asText());
        if (in.has("attributes")) {
            checkAttributes(catalog, in.path("attributes"));
            String mode = in.path("attributeUpdateMode").asText("Merge");
            ObjectNode attributes = "Overwrite".equals(mode) ? store.mapper().createObjectNode()
                    : (ObjectNode) record.path("attributes");
            in.path("attributes").fields().forEachRemaining(entry -> {
                if (entry.getValue().asText().isEmpty()) {
                    attributes.remove(entry.getKey());
                } else {
                    attributes.set(entry.getKey(), entry.getValue());
                }
            });
            record.set("attributes", attributes);
        }
        Map<String, JsonNode> templates = new LinkedHashMap<>();
        record.path("stateTemplates").forEach(t -> templates.put(t.path("identifier").asText(), t));
        checkStateTemplates(account, region, catalog, in.path("stateTemplatesToAdd"));
        checkStateTemplates(account, region, catalog, in.path("stateTemplatesToUpdate"));
        in.path("stateTemplatesToAdd").forEach(t -> templates.put(t.path("identifier").asText(), t));
        for (JsonNode template : in.path("stateTemplatesToUpdate")) {
            if (!templates.containsKey(template.path("identifier").asText())) {
                throw IotFleetWiseStore.validation("The state template " + template.path("identifier").asText()
                        + " isn't associated with the vehicle.");
            }
            templates.put(template.path("identifier").asText(), template);
        }
        in.path("stateTemplatesToRemove").forEach(id -> templates.remove(id.asText()));
        if (templates.isEmpty()) {
            record.remove("stateTemplates");
        } else {
            ArrayNode list = record.putArray("stateTemplates");
            templates.values().forEach(list::add);
        }
        record.put("modelManifestArn", modelArn).put("decoderManifestArn", decoderArn);
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, VEHICLE, name), record);
        edge.deploy(account, region, name);
        return store.mapper().createObjectNode().put("vehicleName", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode deleteVehicle(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("vehicleName").asText();
        ObjectNode record = store.get(account, key(region, VEHICLE, name)).orElse(null);
        String arn = record == null ? store.arn(account, region, VEHICLE, name) : record.path("arn").asText();
        if (record != null) {
            for (ObjectNode fleet : store.list(account, region, FLEET)) {
                List<String> members = strings(fleet.path("_").path("vehicles"));
                if (members.remove(name)) {
                    ArrayNode list = meta(fleet).putArray("vehicles");
                    members.forEach(list::add);
                    store.put(account, key(region, FLEET, fleet.path("id").asText()), fleet);
                }
            }
            store.delete(account, key(region, VEHICLE, name));
            // The vehicle's AWS IoT thing stays: IoT FleetWise doesn't delete it.
            edge.forget(account, region, name);
        }
        return store.mapper().createObjectNode().put("vehicleName", name).put("arn", arn);
    }

    public ObjectNode listVehicles(JsonNode in, String region) {
        String modelArn = in.path("modelManifestArn").asText(null);
        List<String> names = strings(in.path("attributeNames"));
        List<String> values = strings(in.path("attributeValues"));
        if (names.size() != values.size()) {
            throw IotFleetWiseStore.validation("attributeNames and attributeValues must be the same length.");
        }
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        vehicles:
        for (ObjectNode record : store.list(store.account(), region, VEHICLE)) {
            if (modelArn != null && !modelArn.equals(record.path("modelManifestArn").asText())) {
                continue;
            }
            for (int i = 0; i < names.size(); i++) {
                if (!values.get(i).equals(record.path("attributes").path(names.get(i)).asText(null))) {
                    continue vehicles;
                }
            }
            ObjectNode summary = store.mapper().createObjectNode()
                    .put("vehicleName", record.path("vehicleName").asText()).put("arn", record.path("arn").asText());
            if (!metadataOnly) {
                summary.put("modelManifestArn", record.path("modelManifestArn").asText())
                        .put("decoderManifestArn", record.path("decoderManifestArn").asText())
                        .put("creationTime", record.path("creationTime").asDouble())
                        .put("lastModificationTime", record.path("lastModificationTime").asDouble());
                summary.set("attributes", record.path("attributes"));
            }
            summaries.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "vehicleSummaries");
        return out;
    }

    /** Creates each vehicle on its own, reporting failures per vehicle rather than failing the batch. */
    public ObjectNode batchCreateVehicle(JsonNode in, String region) {
        ObjectNode out = store.mapper().createObjectNode();
        ArrayNode created = out.putArray("vehicles");
        ArrayNode errors = out.putArray("errors");
        for (JsonNode item : in.path("vehicles")) {
            try {
                ObjectNode vehicle = newVehicle(item, region);
                created.addObject().put("vehicleName", vehicle.path("vehicleName").asText())
                        .put("arn", vehicle.path("arn").asText())
                        .put("thingArn", meta(vehicle).path("thingArn").asText());
            } catch (AwsException e) {
                errors.addObject().put("vehicleName", item.path("vehicleName").asText())
                        .put("code", e.getErrorCode()).put("message", e.getMessage());
            }
        }
        return out;
    }

    public ObjectNode batchUpdateVehicle(JsonNode in, String region) {
        ObjectNode out = store.mapper().createObjectNode();
        ArrayNode updated = out.putArray("vehicles");
        ArrayNode errors = out.putArray("errors");
        for (JsonNode item : in.path("vehicles")) {
            try {
                ObjectNode result = updateVehicle(item, region);
                updated.addObject().put("vehicleName", result.path("vehicleName").asText())
                        .put("arn", result.path("arn").asText());
            } catch (AwsException e) {
                errors.addObject().put("vehicleName", item.path("vehicleName").asText())
                        .put("code", "ResourceNotFoundException".equals(e.getErrorCode()) ? 404 : e.getHttpStatus())
                        .put("message", e.getMessage());
            }
        }
        return out;
    }

    /** The vehicle's status in each campaign that targets it, directly or through a fleet. */
    public ObjectNode getVehicleStatus(JsonNode in, String region) {
        String account = store.account();
        String name = vehicle(account, region, in.path("vehicleName").asText()).path("vehicleName").asText();
        List<JsonNode> statuses = new ArrayList<>();
        for (ObjectNode campaign : campaignsFor(account, region, name)) {
            statuses.add(store.mapper().createObjectNode().put("campaignName", campaign.path("name").asText())
                    .put("vehicleName", name).put("status", edge.status(account, region, name, campaign)));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(statuses, in, 100, out, "campaigns");
        return out;
    }

    ObjectNode vehicle(String account, String region, String name) {
        return store.get(account, key(region, VEHICLE, name))
                .orElseThrow(() -> IotFleetWiseStore.notFound(VEHICLE, name));
    }

    List<ObjectNode> campaignsFor(String account, String region, String vehicle) {
        return edge.campaignsFor(account, region, vehicle);
    }

    private static void checkManifests(ObjectNode model, ObjectNode decoder) {
        if (!"ACTIVE".equals(model.path("status").asText())) {
            throw IotFleetWiseStore.validation("The vehicle model " + model.path("name").asText()
                    + " isn't ACTIVE.");
        }
        if (!"ACTIVE".equals(decoder.path("status").asText())) {
            throw IotFleetWiseStore.validation("The decoder manifest " + decoder.path("name").asText()
                    + " isn't ACTIVE.");
        }
        if (!model.path("arn").asText().equals(decoder.path("modelManifestArn").asText())) {
            throw IotFleetWiseStore.validation("The decoder manifest " + decoder.path("name").asText()
                    + " isn't for vehicle model " + model.path("name").asText() + ".");
        }
    }

    private void checkAttributes(ObjectNode catalog, JsonNode attributes) {
        List<String> names = new ArrayList<>();
        attributes.fieldNames().forEachRemaining(names::add);
        Map<String, ObjectNode> tree = IotFleetWiseModels.tree(catalog);
        for (String name : names) {
            ObjectNode node = tree.get(name);
            if (node == null || !"attribute".equals(IotFleetWiseModels.type(node))) {
                throw IotFleetWiseStore.validation("The attribute " + name + " isn't an attribute of the signal "
                        + "catalog.");
            }
        }
    }

    private void checkStateTemplates(String account, String region, ObjectNode catalog, JsonNode associations) {
        for (JsonNode association : associations) {
            ObjectNode template = models.stateTemplate(account, region, association.path("identifier").asText());
            if (!catalog.path("arn").asText().equals(template.path("signalCatalogArn").asText())) {
                throw IotFleetWiseStore.validation("The state template " + template.path("name").asText()
                        + " isn't of the vehicle's signal catalog.");
            }
            if (!association.path("stateTemplateUpdateStrategy").has("periodic")
                    && !association.path("stateTemplateUpdateStrategy").has("onChange")) {
                throw IotFleetWiseStore.field("stateTemplateUpdateStrategy", "A state template association needs "
                        + "a periodic or onChange update strategy.");
            }
        }
    }

    // ---------------------------------------------------------------- campaigns

    public ObjectNode createCampaign(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        if (store.get(account, key(region, CAMPAIGN, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(CAMPAIGN, name, "The campaign " + name + " already exists.");
        }
        if (store.list(account, region, CAMPAIGN).size() >= store.config().maxCampaigns()) {
            throw IotFleetWiseStore.limit(CAMPAIGN, name, "The account has the most campaigns it can have.");
        }
        ObjectNode catalog = models.catalogByArn(account, region, in.path("signalCatalogArn").asText());
        String target = in.path("targetArn").asText();
        checkTarget(account, region, catalog, target);
        Map<String, Integer> ids = IotFleetWiseModels.signalIds(catalog);
        Map<String, ObjectNode> tree = IotFleetWiseModels.tree(catalog);
        for (JsonNode signal : in.path("signalsToCollect")) {
            String signalName = signal.path("name").asText();
            if (expand(tree, signalName).isEmpty()) {
                throw IotFleetWiseStore.invalidSignals("The signals to collect aren't valid.", List.of(
                        Map.of("name", signalName, "reason", "The signal doesn't exist in the signal catalog.")));
            }
        }
        JsonNode scheme = in.path("collectionScheme");
        if (scheme.has("timeBasedCollectionScheme") == scheme.has("conditionBasedCollectionScheme")) {
            throw IotFleetWiseStore.field("collectionScheme", "A collection scheme is time based or condition "
                    + "based.");
        }
        if (scheme.has("conditionBasedCollectionScheme")) {
            IotFleetWiseExpressions.compile(scheme.path("conditionBasedCollectionScheme").path("expression").asText(),
                    ids, "collectionScheme.conditionBasedCollectionScheme.expression");
        }
        for (JsonNode fetch : in.path("signalsToFetch")) {
            if (!ids.containsKey(fetch.path("fullyQualifiedName").asText())) {
                throw IotFleetWiseStore.invalidSignals("The signals to fetch aren't valid.", List.of(Map.of("name",
                        fetch.path("fullyQualifiedName").asText(), "reason", "The signal doesn't exist.")));
            }
            JsonNode condition = fetch.path("signalFetchConfig").path("conditionBased");
            if (!condition.isMissingNode()) {
                IotFleetWiseExpressions.compile(condition.path("conditionExpression").asText(), ids,
                        "signalsToFetch.signalFetchConfig.conditionBased.conditionExpression");
            }
            for (JsonNode action : fetch.path("actions")) {
                IotFleetWiseExpressions.compile(action.asText(), ids, "signalsToFetch.actions");
            }
        }
        Set<String> partitions = new LinkedHashSet<>();
        for (JsonNode partition : in.path("dataPartitions")) {
            if (!partitions.add(partition.path("id").asText())) {
                throw IotFleetWiseStore.field("dataPartitions", "The data partition " + partition.path("id").asText()
                        + " is defined twice.");
            }
            if (partition.path("uploadOptions").has("expression")) {
                IotFleetWiseExpressions.compile(partition.path("uploadOptions").path("expression").asText(), ids,
                        "dataPartitions.uploadOptions.expression");
            }
        }
        for (JsonNode signal : in.path("signalsToCollect")) {
            String partition = signal.path("dataPartitionId").asText(null);
            if (partition != null && !"default".equals(partition) && !partitions.contains(partition)) {
                throw IotFleetWiseStore.field("signalsToCollect", "The data partition " + partition
                        + " isn't defined.");
            }
        }
        checkDestinations(account, region, in.path("dataDestinationConfigs"), partitions.isEmpty());
        for (JsonNode dimension : in.path("dataExtraDimensions")) {
            models.checkSignals(catalog, List.of(dimension.asText()), Set.of("attribute"));
        }
        Instant start = IotFleetWiseStore.instant(in.path("startTime"));
        Instant expiry = IotFleetWiseStore.instant(in.path("expiryTime"));
        Instant now = Instant.now();
        start = start == null ? now : start;
        expiry = expiry == null ? NO_EXPIRY : expiry;
        if (!expiry.isAfter(start)) {
            throw IotFleetWiseStore.field("expiryTime", "The expiry time must be after the start time.");
        }
        ObjectNode record = store.record(CAMPAIGN, name);
        record.put("name", name).put("arn", store.arn(account, region, CAMPAIGN, name))
                .put("signalCatalogArn", catalog.path("arn").asText()).put("targetArn", target)
                .put("status", "CREATING").put("startTime", IotFleetWiseStore.seconds(start))
                .put("expiryTime", IotFleetWiseStore.seconds(expiry))
                .put("postTriggerCollectionDuration", in.path("postTriggerCollectionDuration").asLong(0))
                .put("diagnosticsMode", in.path("diagnosticsMode").asText("OFF"))
                .put("spoolingMode", in.path("spoolingMode").asText("OFF"))
                .put("compression", in.path("compression").asText("SNAPPY"))
                .put("priority", in.path("priority").asInt(0));
        copy(in, record, "description", "signalsToCollect", "collectionScheme", "dataExtraDimensions",
                "dataPartitions", "signalsToFetch");
        ArrayNode destinations = record.putArray("dataDestinationConfigs");
        for (JsonNode destination : in.path("dataDestinationConfigs")) {
            ObjectNode copy = destination.deepCopy();
            if (copy.has("s3Config")) {
                ObjectNode s3Config = (ObjectNode) copy.get("s3Config");
                if (!s3Config.has("dataFormat")) {
                    s3Config.put("dataFormat", "PARQUET");
                }
                if (!s3Config.has("storageCompressionFormat")) {
                    s3Config.put("storageCompressionFormat", "GZIP");
                }
            }
            destinations.add(copy);
        }
        meta(record).put("readyAt", now.plusMillis(store.config().campaignCreationMillis()).toString());
        meta(record).set("tags", models.tagMap(in.path("tags")));
        store.put(account, key(region, CAMPAIGN, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode getCampaign(JsonNode in, String region) {
        return view(settle(campaign(store.account(), region, in.path("name").asText())));
    }

    public ObjectNode updateCampaign(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = settle(campaign(account, region, name));
        String status = record.path("status").asText();
        String action = in.path("action").asText();
        String next = switch (action) {
            case "APPROVE" -> requireStatus(record, status, "WAITING_FOR_APPROVAL", "RUNNING", action);
            case "SUSPEND" -> requireStatus(record, status, "RUNNING", "SUSPENDED", action);
            case "RESUME" -> requireStatus(record, status, "SUSPENDED", "RUNNING", action);
            case "UPDATE" -> status;
            default -> throw IotFleetWiseStore.field("action", "The action must be APPROVE, SUSPEND, RESUME or "
                    + "UPDATE.");
        };
        if (!"UPDATE".equals(action) && (in.has("description") || in.has("dataExtraDimensions"))) {
            throw IotFleetWiseStore.validation("A campaign's description and dataExtraDimensions change only with "
                    + "the UPDATE action.");
        }
        if ("UPDATE".equals(action)) {
            ObjectNode catalog = models.catalogByArn(account, region, record.path("signalCatalogArn").asText());
            for (JsonNode dimension : in.path("dataExtraDimensions")) {
                models.checkSignals(catalog, List.of(dimension.asText()), Set.of("attribute"));
            }
            copy(in, record, "description", "dataExtraDimensions");
        }
        record.put("status", next);
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, CAMPAIGN, name), record);
        if (!next.equals(status)) {
            edge.deployCampaign(account, region, record);
        }
        return store.mapper().createObjectNode().put("arn", record.path("arn").asText()).put("name", name)
                .put("status", next);
    }

    public ObjectNode deleteCampaign(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = campaign(account, region, name);
        store.delete(account, key(region, CAMPAIGN, name));
        edge.deployCampaign(account, region, record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode listCampaigns(JsonNode in, String region) {
        String status = in.path("status").asText(null);
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, CAMPAIGN)) {
            settle(record);
            if (status != null && !status.equals(record.path("status").asText())) {
                continue;
            }
            ObjectNode summary = store.mapper().createObjectNode().put("arn", record.path("arn").asText())
                    .put("name", record.path("name").asText());
            if (!metadataOnly) {
                summary.put("signalCatalogArn", record.path("signalCatalogArn").asText())
                        .put("targetArn", record.path("targetArn").asText())
                        .put("status", record.path("status").asText())
                        .put("creationTime", record.path("creationTime").asDouble())
                        .put("lastModificationTime", record.path("lastModificationTime").asDouble());
                copy(record, summary, "description");
            }
            summaries.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "campaignSummaries");
        return out;
    }

    ObjectNode campaign(String account, String region, String name) {
        return store.get(account, key(region, CAMPAIGN, name))
                .orElseThrow(() -> IotFleetWiseStore.notFound(CAMPAIGN, name));
    }

    /** A campaign that has finished CREATING waits for approval. */
    ObjectNode settle(ObjectNode campaign) {
        if ("CREATING".equals(campaign.path("status").asText())
                && !Instant.now().isBefore(Instant.parse(campaign.path("_").path("readyAt").asText()))) {
            campaign.put("status", "WAITING_FOR_APPROVAL");
            String arn = campaign.path("arn").asText();
            store.put(AwsArnUtils.parse(arn).accountId(), key(IotFleetWiseModels.regionOf(arn), CAMPAIGN,
                    campaign.path("name").asText()), campaign);
        }
        return campaign;
    }

    private static String requireStatus(ObjectNode campaign, String status, String expected, String next,
                                        String action) {
        if (!expected.equals(status)) {
            throw IotFleetWiseStore.conflict(CAMPAIGN, campaign.path("name").asText(), "The campaign "
                    + campaign.path("name").asText() + " is " + status + "; " + action + " needs it " + expected + ".");
        }
        return next;
    }

    private void checkTarget(String account, String region, ObjectNode catalog, String target) {
        if (target.contains(":" + FLEET + "/")) {
            ObjectNode fleet = fleet(account, region, IotFleetWiseModels.nameOf(target, FLEET));
            if (!catalog.path("arn").asText().equals(fleet.path("signalCatalogArn").asText())) {
                throw IotFleetWiseStore.validation("The fleet isn't of the campaign's signal catalog.");
            }
        } else if (target.contains(":" + VEHICLE + "/")) {
            ObjectNode vehicle = vehicle(account, region, IotFleetWiseModels.nameOf(target, VEHICLE));
            ObjectNode model = models.modelByArn(account, region, vehicle.path("modelManifestArn").asText());
            if (!catalog.path("arn").asText().equals(model.path("signalCatalogArn").asText())) {
                throw IotFleetWiseStore.validation("The vehicle isn't of the campaign's signal catalog.");
            }
        } else {
            throw IotFleetWiseStore.field("targetArn", "The target must be a fleet or a vehicle.");
        }
    }

    /** The catalog signals a name collects: itself, or what a {@code *} wildcard matches. */
    static List<String> expand(Map<String, ObjectNode> tree, String name) {
        List<String> signals = new ArrayList<>();
        String regex = name.replace(".", "\\.").replace("*", "[^.]*");
        for (Map.Entry<String, ObjectNode> node : tree.entrySet()) {
            String type = IotFleetWiseModels.type(node.getValue());
            if (Set.of("sensor", "actuator", "attribute").contains(type) && node.getKey().matches(regex)) {
                signals.add(node.getKey());
            }
        }
        return signals;
    }

    private void checkDestinations(String account, String region, JsonNode destinations, boolean noPartitions) {
        if (!destinations.isArray() || destinations.isEmpty()) {
            JsonNode registration = store.get(account, key(region, "settings", "registration")).orElse(null);
            if (registration == null || !registration.has("timestreamResources")) {
                throw IotFleetWiseStore.field("dataDestinationConfigs", "A campaign needs a data destination.");
            }
            throw IotFleetWiseStore.validation("The registered Timestream table doesn't exist.");
        }
        for (JsonNode destination : destinations) {
            if (destination.has("s3Config")) {
                String bucketArn = destination.path("s3Config").path("bucketArn").asText();
                String bucket = bucketArn.substring(bucketArn.lastIndexOf(':') + 1);
                RequestScopes.runAs(account, region, () -> checkBucket(bucket));
            } else if (destination.has("timestreamConfig")) {
                // Timestream for LiveAnalytics is closed to the account, so its tables don't exist.
                throw IotFleetWiseStore.validation("The Timestream table "
                        + destination.path("timestreamConfig").path("timestreamTableArn").asText()
                        + " doesn't exist.");
            } else if (destination.has("mqttTopicConfig")) {
                if (!noPartitions) {
                    throw IotFleetWiseStore.validation("Data partitions store data only to Amazon S3.");
                }
                checkRole(account, destination.path("mqttTopicConfig").path("executionRoleArn").asText());
                String topic = destination.path("mqttTopicConfig").path("mqttTopicArn").asText();
                if (!topic.contains(":iot:") || !topic.contains(":topic/")) {
                    throw IotFleetWiseStore.field("mqttTopicArn", "The MQTT topic ARN isn't valid.");
                }
            } else {
                throw IotFleetWiseStore.field("dataDestinationConfigs", "A destination is s3Config, "
                        + "timestreamConfig or mqttTopicConfig.");
            }
        }
    }

    /** The bucket must exist and its policy let IoT FleetWise put objects, as the service checks. */
    private void checkBucket(String bucket) {
        if (!s3.get().bucketExists(bucket)) {
            throw IotFleetWiseStore.validation("The S3 bucket " + bucket + " doesn't exist.");
        }
        String policy;
        try {
            policy = s3.get().getBucketPolicy(bucket);
        } catch (AwsException e) {
            policy = null;
        }
        if (policy == null || !policy.contains(SERVICE_PRINCIPAL)
                || !(policy.contains("s3:PutObject") || policy.contains("s3:*"))) {
            throw IotFleetWiseStore.validation("The S3 bucket " + bucket + "'s policy doesn't let IoT FleetWise ("
                    + SERVICE_PRINCIPAL + ") put objects.");
        }
    }

    private void checkRole(String account, String roleArn) {
        String roleName = roleArn.substring(roleArn.lastIndexOf('/') + 1);
        try {
            RequestScopes.runAs(account, () -> iam.get().getRole(roleName));
        } catch (AwsException e) {
            throw IotFleetWiseStore.validation("The execution role " + roleArn + " doesn't exist.");
        }
    }

    // ---------------------------------------------------------------- account settings

    public ObjectNode registerAccount(JsonNode in, String region) {
        String account = store.account();
        if (in.has("timestreamResources")) {
            throw IotFleetWiseStore.validation("The Timestream database "
                    + in.path("timestreamResources").path("timestreamDatabaseName").asText() + " doesn't exist.");
        }
        String role = in.path("iamResources").path("roleArn").asText(null);
        if (role != null) {
            checkRole(account, role);
        }
        ObjectNode registration = store.get(account, key(region, "settings", "registration")).orElse(null);
        double now = IotFleetWiseStore.seconds(Instant.now());
        if (registration == null) {
            registration = store.mapper().createObjectNode().put("creationTime", now);
        }
        registration.put("lastModificationTime", now).put("accountStatus", "REGISTRATION_SUCCESS");
        registration.set("iamResources", in.has("iamResources") ? in.get("iamResources").deepCopy()
                : store.mapper().createObjectNode().put("roleArn", AwsArnUtils.Arn.global(
                        AwsArnUtils.parse(store.arn(account, region, CATALOG, "x")).partition(), "iam", account,
                        "role/AWSIoTFleetwiseServiceRole").toString()));
        store.put(account, key(region, "settings", "registration"), registration);
        ObjectNode out = store.mapper().createObjectNode().put("registerAccountStatus", "REGISTRATION_SUCCESS");
        out.set("iamResources", registration.get("iamResources"));
        out.put("creationTime", registration.path("creationTime").asDouble()).put("lastModificationTime", now);
        return out;
    }

    public ObjectNode getRegisterAccountStatus(JsonNode in, String region) {
        String account = store.account();
        ObjectNode registration = store.get(account, key(region, "settings", "registration"))
                .orElseThrow(() -> IotFleetWiseStore.notFound("account", account));
        ObjectNode out = store.mapper().createObjectNode().put("customerAccountId", account)
                .put("accountStatus", registration.path("accountStatus").asText())
                .put("creationTime", registration.path("creationTime").asDouble())
                .put("lastModificationTime", registration.path("lastModificationTime").asDouble());
        ObjectNode iamResponse = out.putObject("iamRegistrationResponse");
        iamResponse.put("roleArn", registration.path("iamResources").path("roleArn").asText())
                .put("registrationStatus", "REGISTRATION_SUCCESS");
        return out;
    }

    public ObjectNode putEncryptionConfiguration(JsonNode in, String region) {
        String account = store.account();
        String type = in.path("encryptionType").asText();
        String key = in.path("kmsKeyId").asText(null);
        if ("KMS_BASED_ENCRYPTION".equals(type) && (key == null || key.isBlank())) {
            throw IotFleetWiseStore.field("kmsKeyId", "KMS_BASED_ENCRYPTION needs a kmsKeyId.");
        }
        if ("FLEETWISE_DEFAULT_ENCRYPTION".equals(type) && key != null && !key.isBlank()) {
            throw IotFleetWiseStore.field("kmsKeyId", "FLEETWISE_DEFAULT_ENCRYPTION takes no kmsKeyId.");
        }
        double now = IotFleetWiseStore.seconds(Instant.now());
        ObjectNode configuration = store.get(account, key(region, "settings", "encryption"))
                .orElseGet(() -> store.mapper().createObjectNode().put("creationTime", now));
        configuration.put("encryptionType", type).put("encryptionStatus", "SUCCESS").put("lastModificationTime", now);
        if ("KMS_BASED_ENCRYPTION".equals(type)) {
            configuration.put("kmsKeyId", key);
        } else {
            configuration.remove("kmsKeyId");
        }
        store.put(account, key(region, "settings", "encryption"), configuration);
        ObjectNode out = store.mapper().createObjectNode().put("encryptionStatus", "SUCCESS")
                .put("encryptionType", type);
        if (configuration.has("kmsKeyId")) {
            out.put("kmsKeyId", key);
        }
        return out;
    }

    public ObjectNode getEncryptionConfiguration(JsonNode in, String region) {
        return store.get(store.account(), key(region, "settings", "encryption")).map(ObjectNode::deepCopy)
                .orElseGet(() -> store.mapper().createObjectNode().put("encryptionStatus", "SUCCESS")
                        .put("encryptionType", "FLEETWISE_DEFAULT_ENCRYPTION"));
    }

    public ObjectNode putLoggingOptions(JsonNode in, String region) {
        String account = store.account();
        JsonNode delivery = in.path("cloudWatchLogDelivery");
        if ("ERROR".equals(delivery.path("logType").asText()) && !delivery.hasNonNull("logGroupName")) {
            throw IotFleetWiseStore.field("cloudWatchLogDelivery.logGroupName", "ERROR logging needs a log group.");
        }
        ObjectNode options = store.mapper().createObjectNode();
        options.set("cloudWatchLogDelivery", delivery.deepCopy());
        store.put(account, key(region, "settings", "logging"), options);
        return store.mapper().createObjectNode();
    }

    public ObjectNode getLoggingOptions(JsonNode in, String region) {
        return store.get(store.account(), key(region, "settings", "logging")).map(ObjectNode::deepCopy)
                .orElseGet(() -> {
                    ObjectNode out = store.mapper().createObjectNode();
                    out.putObject("cloudWatchLogDelivery").put("logType", "OFF");
                    return out;
                });
    }

    // ---------------------------------------------------------------- tags

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode record = tagged(in.path("ResourceARN").asText());
        ObjectNode out = store.mapper().createObjectNode();
        out.set("Tags", IotFleetWiseStore.tagList(store.mapper(), record.path("_").path("tags")));
        return out;
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        String arn = in.path("ResourceARN").asText();
        ObjectNode record = tagged(arn);
        ObjectNode tags = (ObjectNode) meta(record).with("tags");
        IotFleetWiseStore.tags(in.path("Tags")).forEach(tags::put);
        if (tags.size() > 50) {
            throw IotFleetWiseStore.validation("A resource can have at most 50 tags.");
        }
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        String arn = in.path("ResourceARN").asText();
        ObjectNode record = tagged(arn);
        ObjectNode tags = (ObjectNode) meta(record).with("tags");
        in.path("TagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        save(arn, record);
        return store.mapper().createObjectNode();
    }

    private ObjectNode tagged(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw IotFleetWiseStore.field("ResourceARN", "The ARN " + arn + " isn't valid.");
        }
        if (!IotFleetWiseStore.SERVICE.equals(parsed.service()) || !parsed.accountId().equals(store.account())) {
            throw IotFleetWiseStore.notFound("resource", arn);
        }
        return store.get(parsed.accountId(), parsed.region() + "/" + parsed.resource())
                .orElseThrow(() -> IotFleetWiseStore.notFound("resource", arn));
    }

    private void save(String arn, ObjectNode record) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        store.put(parsed.accountId(), parsed.region() + "/" + parsed.resource(), record);
    }
}
