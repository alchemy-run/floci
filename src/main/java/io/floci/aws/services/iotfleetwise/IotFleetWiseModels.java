package io.floci.aws.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.CATALOG;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.DECODER;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.MODEL;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.STATE_TEMPLATE;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.key;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.meta;
import static io.floci.aws.services.iotfleetwise.IotFleetWiseStore.view;

/**
 * Vehicle modeling: the signal catalog (one per account and Region), vehicle models (model manifests), decoder
 * manifests and state templates, with the validation IoT FleetWise applies to each and the dependencies that keep
 * one from being deleted while another uses it. Every signal gets a numeric ID, stable for the catalog's life, that
 * the decoder manifests and collection schemes sent to vehicles refer to.
 */
@ApplicationScoped
public class IotFleetWiseModels {

    static final List<String> NODE_TYPES = List.of("branch", "sensor", "actuator", "attribute", "struct",
            "property");
    private static final Map<String, String> SIGNAL_NODE_TYPES = Map.of("branch", "BRANCH", "sensor", "SENSOR",
            "actuator", "ACTUATOR", "attribute", "ATTRIBUTE", "struct", "CUSTOM_STRUCT", "property",
            "CUSTOM_PROPERTY");
    private static final Map<String, String> INTERFACE_PAYLOAD = Map.of("CAN_INTERFACE", "canInterface",
            "OBD_INTERFACE", "obdInterface", "VEHICLE_MIDDLEWARE", "vehicleMiddleware",
            "CUSTOM_DECODING_INTERFACE", "customDecodingInterface");
    private static final Map<String, String> SIGNAL_PAYLOAD = Map.of("CAN_SIGNAL", "canSignal", "OBD_SIGNAL",
            "obdSignal", "MESSAGE_SIGNAL", "messageSignal", "CUSTOM_DECODING_SIGNAL", "customDecodingSignal");
    private static final Map<String, String> SIGNAL_INTERFACE = Map.of("CAN_SIGNAL", "CAN_INTERFACE", "OBD_SIGNAL",
            "OBD_INTERFACE", "MESSAGE_SIGNAL", "VEHICLE_MIDDLEWARE", "CUSTOM_DECODING_SIGNAL",
            "CUSTOM_DECODING_INTERFACE");
    private static final String ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IotFleetWiseStore store;

    @Inject
    public IotFleetWiseModels(IotFleetWiseStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- signal catalog

    public ObjectNode createSignalCatalog(JsonNode in, String region) {
        return newCatalog(in.path("name").asText(), in.path("description"), nodesOf(in.path("nodes")),
                in.path("tags"), region);
    }

    public ObjectNode importSignalCatalog(JsonNode in, String region) {
        String vss = in.path("vss").path("vssJson").asText(null);
        List<ObjectNode> nodes = vss == null ? List.of() : IotFleetWiseVss.parse(store.mapper(), vss);
        return newCatalog(in.path("name").asText(), in.path("description"), nodes, in.path("tags"), region);
    }

    private ObjectNode newCatalog(String name, JsonNode description, List<ObjectNode> nodes, JsonNode tags,
                                  String region) {
        String account = store.account();
        if (store.get(account, key(region, CATALOG, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(CATALOG, name, "The signal catalog " + name + " already exists.");
        }
        if (!store.list(account, region, CATALOG).isEmpty()) {
            throw IotFleetWiseStore.limit(CATALOG, name, "IoT FleetWise supports one signal catalog in each "
                    + "account and Region.");
        }
        Map<String, ObjectNode> tree = new LinkedHashMap<>();
        addNodes(tree, nodes);
        ObjectNode record = store.record(CATALOG, name);
        record.put("name", name).put("arn", store.arn(account, region, CATALOG, name));
        if (!description.isMissingNode() && !description.isNull()) {
            record.set("description", description);
        }
        saveTree(record, tree);
        meta(record).set("tags", tagMap(tags));
        store.put(account, key(region, CATALOG, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode getSignalCatalog(JsonNode in, String region) {
        ObjectNode record = catalog(store.account(), region, in.path("name").asText());
        ObjectNode out = view(record);
        out.remove("nodes");
        return out;
    }

    public ObjectNode updateSignalCatalog(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = catalog(account, region, name);
        Map<String, ObjectNode> tree = tree(record);
        List<String> removed = new ArrayList<>();
        in.path("nodesToRemove").forEach(path -> removed.add(path.asText()));
        Set<String> used = usedSignals(account, region, record.path("arn").asText());
        List<Map<String, String>> invalid = new ArrayList<>();
        for (String path : removed) {
            if (!tree.containsKey(path)) {
                invalid.add(Map.of("name", path, "reason", "The node doesn't exist in the signal catalog."));
            } else if (used.contains(path)) {
                invalid.add(Map.of("name", path, "reason", "The node is used by a vehicle model, campaign or "
                        + "state template."));
            } else if (tree.keySet().stream().anyMatch(other -> other.startsWith(path + ".")
                    && !removed.contains(other))) {
                invalid.add(Map.of("name", path, "reason", "The branch has child nodes that aren't removed."));
            }
        }
        if (!invalid.isEmpty()) {
            throw IotFleetWiseStore.invalidSignals("The nodes can't be removed.", invalid);
        }
        removed.forEach(tree::remove);
        for (ObjectNode node : nodesOf(in.path("nodesToUpdate"))) {
            String path = fqn(node);
            ObjectNode current = tree.get(path);
            if (current == null || !type(current).equals(type(node))) {
                throw IotFleetWiseStore.invalidNodes("The node " + path + " to update doesn't exist with that "
                        + "type.", List.of(node));
            }
            node.with(type(node)).put("fullyQualifiedName", path);
            tree.put(path, node);
        }
        validateTree(tree);
        addNodes(tree, nodesOf(in.path("nodesToAdd")));
        if (in.hasNonNull("description")) {
            record.set("description", in.get("description"));
        }
        saveTree(record, tree);
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, CATALOG, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode deleteSignalCatalog(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = store.get(account, key(region, CATALOG, name)).orElse(null);
        if (record == null) {
            throw IotFleetWiseStore.notFound(CATALOG, name);
        }
        String arn = record.path("arn").asText();
        for (String kind : List.of(MODEL, IotFleetWiseStore.FLEET, IotFleetWiseStore.CAMPAIGN, STATE_TEMPLATE)) {
            for (ObjectNode other : store.list(account, region, kind)) {
                if (arn.equals(other.path("signalCatalogArn").asText())) {
                    throw IotFleetWiseStore.conflict(CATALOG, name, "The signal catalog " + name + " is used by "
                            + kind + " " + meta(other).path("name").asText() + ".");
                }
            }
        }
        store.delete(account, key(region, CATALOG, name));
        return store.mapper().createObjectNode().put("name", name).put("arn", arn);
    }

    public ObjectNode listSignalCatalogs(JsonNode in, String region) {
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, CATALOG)) {
            summaries.add(store.mapper().createObjectNode().put("name", record.path("name").asText())
                    .put("arn", record.path("arn").asText())
                    .put("creationTime", record.path("creationTime").asDouble())
                    .put("lastModificationTime", record.path("lastModificationTime").asDouble()));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "summaries");
        return out;
    }

    public ObjectNode listSignalCatalogNodes(JsonNode in, String region) {
        ObjectNode record = catalog(store.account(), region, in.path("name").asText());
        String nodeType = in.path("signalNodeType").asText(null);
        List<JsonNode> nodes = new ArrayList<>();
        for (ObjectNode node : tree(record).values()) {
            if (nodeType == null || nodeType.equals(SIGNAL_NODE_TYPES.get(type(node)))) {
                nodes.add(node);
            }
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(nodes, in, 100, out, "nodes");
        return out;
    }

    ObjectNode catalog(String account, String region, String name) {
        return store.get(account, key(region, CATALOG, name))
                .orElseThrow(() -> IotFleetWiseStore.notFound(CATALOG, name));
    }

    /** The catalog an ARN names, in the caller's account and Region. */
    ObjectNode catalogByArn(String account, String region, String arn) {
        String name = nameOf(arn, CATALOG);
        ObjectNode record = store.get(account, key(region, CATALOG, name)).orElse(null);
        if (record == null || !arn.equals(record.path("arn").asText())) {
            throw IotFleetWiseStore.notFound(CATALOG, arn);
        }
        return record;
    }

    static Map<String, ObjectNode> tree(ObjectNode catalog) {
        Map<String, ObjectNode> tree = new LinkedHashMap<>();
        for (JsonNode node : catalog.path("_").path("nodes")) {
            tree.put(fqn(node), (ObjectNode) node);
        }
        return tree;
    }

    /** The catalog's signal IDs: every node's, assigned once and never reused. */
    static Map<String, Integer> signalIds(ObjectNode catalog) {
        Map<String, Integer> ids = new HashMap<>();
        catalog.path("_").path("signalIds").fields()
                .forEachRemaining(entry -> ids.put(entry.getKey(), entry.getValue().asInt()));
        return ids;
    }

    private void saveTree(ObjectNode catalog, Map<String, ObjectNode> tree) {
        ArrayNode nodes = meta(catalog).putArray("nodes");
        tree.values().forEach(nodes::add);
        ObjectNode ids = meta(catalog).has("signalIds") ? (ObjectNode) meta(catalog).get("signalIds")
                : meta(catalog).putObject("signalIds");
        int next = meta(catalog).path("nextSignalId").asInt(1);
        for (String path : tree.keySet()) {
            if (!ids.has(path)) {
                ids.put(path, next++);
            }
        }
        meta(catalog).put("nextSignalId", next);
        ObjectNode counts = catalog.putObject("nodeCounts");
        counts.put("totalNodes", tree.size());
        for (String type : NODE_TYPES) {
            String field = switch (type) {
                case "branch" -> "totalBranches";
                case "sensor" -> "totalSensors";
                case "actuator" -> "totalActuators";
                case "attribute" -> "totalAttributes";
                case "struct" -> "totalStructs";
                default -> "totalProperties";
            };
            counts.put(field, tree.values().stream().filter(node -> type.equals(type(node))).count());
        }
    }

    private List<ObjectNode> nodesOf(JsonNode array) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (JsonNode node : array) {
            List<String> present = new ArrayList<>();
            node.fieldNames().forEachRemaining(present::add);
            present.retainAll(NODE_TYPES);
            if (present.size() != 1) {
                throw IotFleetWiseStore.invalidNodes("A node must be exactly one of " + String.join(", ", NODE_TYPES)
                        + ".", List.of(node));
            }
            ObjectNode copy = store.mapper().createObjectNode();
            copy.set(present.get(0), node.get(present.get(0)).deepCopy());
            nodes.add(copy);
        }
        return nodes;
    }

    private static void addNodes(Map<String, ObjectNode> tree, List<ObjectNode> nodes) {
        for (ObjectNode node : nodes) {
            String path = fqn(node);
            if (tree.containsKey(path)) {
                throw IotFleetWiseStore.invalidNodes("The node " + path + " already exists.", List.of(node));
            }
            tree.put(path, node);
        }
        validateTree(tree);
    }

    /** Checks each node's place and definition: a parent branch (or struct, for a property), types and bounds. */
    private static void validateTree(Map<String, ObjectNode> tree) {
        for (ObjectNode node : tree.values()) {
            String path = fqn(node);
            String type = type(node);
            JsonNode body = node.path(type);
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                if (!"branch".equals(type) && !"struct".equals(type)) {
                    throw IotFleetWiseStore.invalidNodes("The node " + path + " must be in a branch.", List.of(node));
                }
            } else {
                ObjectNode parent = tree.get(path.substring(0, dot));
                String expected = "property".equals(type) ? "struct" : "branch";
                if (parent == null || !(expected.equals(type(parent))
                        || ("struct".equals(type) && "branch".equals(type(parent))))) {
                    throw IotFleetWiseStore.invalidNodes("The parent " + expected + " of " + path
                            + " doesn't exist.", List.of(node));
                }
            }
            if (Set.of("sensor", "actuator", "attribute", "property").contains(type)) {
                if (!body.hasNonNull("dataType")) {
                    throw IotFleetWiseStore.invalidNodes("The node " + path + " needs a dataType.", List.of(node));
                }
                String dataType = body.path("dataType").asText();
                if (dataType.startsWith("STRUCT")) {
                    String struct = body.path("structFullyQualifiedName").asText(null);
                    if (struct == null || tree.get(struct) == null || !"struct".equals(type(tree.get(struct)))) {
                        throw IotFleetWiseStore.invalidNodes("The node " + path + " needs the struct it holds.",
                                List.of(node));
                    }
                }
            }
            if (body.hasNonNull("min") && body.hasNonNull("max")
                    && body.path("min").asDouble() > body.path("max").asDouble()) {
                throw IotFleetWiseStore.invalidNodes("The node " + path + " has a min above its max.",
                        List.of(node));
            }
        }
    }

    static String fqn(JsonNode node) {
        return node.path(type(node)).path("fullyQualifiedName").asText();
    }

    static String type(JsonNode node) {
        for (String type : NODE_TYPES) {
            if (node.has(type)) {
                return type;
            }
        }
        return "";
    }

    /** Signals that model manifests, campaigns and state templates of the catalog use, and their branches. */
    private Set<String> usedSignals(String account, String region, String catalogArn) {
        Set<String> used = new LinkedHashSet<>();
        for (ObjectNode model : store.list(account, region, MODEL)) {
            if (catalogArn.equals(model.path("signalCatalogArn").asText())) {
                model.path("_").path("nodes").forEach(path -> used.add(path.asText()));
            }
        }
        for (ObjectNode template : store.list(account, region, STATE_TEMPLATE)) {
            if (catalogArn.equals(template.path("signalCatalogArn").asText())) {
                template.path("stateTemplateProperties").forEach(path -> used.add(path.asText()));
            }
        }
        for (ObjectNode campaign : store.list(account, region, IotFleetWiseStore.CAMPAIGN)) {
            if (catalogArn.equals(campaign.path("signalCatalogArn").asText())) {
                campaign.path("signalsToCollect").forEach(signal -> used.add(signal.path("name").asText()));
            }
        }
        return used;
    }

    // ---------------------------------------------------------------- model manifests

    public ObjectNode createModelManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        if (store.get(account, key(region, MODEL, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(MODEL, name, "The vehicle model " + name + " already exists.");
        }
        if (store.list(account, region, MODEL).size() >= store.config().maxModelManifests()) {
            throw IotFleetWiseStore.limit(MODEL, name, "The account has the most vehicle models it can have.");
        }
        ObjectNode catalog = catalogByArn(account, region, in.path("signalCatalogArn").asText());
        List<String> nodes = new ArrayList<>();
        in.path("nodes").forEach(path -> nodes.add(path.asText()));
        checkSignals(catalog, nodes, Set.of("sensor", "actuator", "attribute", "branch", "struct", "property"));
        ObjectNode record = store.record(MODEL, name);
        record.put("name", name).put("arn", store.arn(account, region, MODEL, name))
                .put("signalCatalogArn", catalog.path("arn").asText()).put("status", "DRAFT");
        copy(in, record, "description");
        ArrayNode list = meta(record).putArray("nodes");
        nodes.forEach(list::add);
        meta(record).set("tags", tagMap(in.path("tags")));
        store.put(account, key(region, MODEL, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode getModelManifest(JsonNode in, String region) {
        return view(model(store.account(), region, in.path("name").asText()));
    }

    public ObjectNode updateModelManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = model(account, region, name);
        boolean edits = in.has("nodesToAdd") || in.has("nodesToRemove");
        if (edits && "ACTIVE".equals(record.path("status").asText())) {
            throw IotFleetWiseStore.conflict(MODEL, name, "The vehicle model " + name + " is ACTIVE; its signals "
                    + "can't change.");
        }
        Set<String> nodes = new LinkedHashSet<>();
        record.path("_").path("nodes").forEach(path -> nodes.add(path.asText()));
        List<String> added = new ArrayList<>();
        in.path("nodesToAdd").forEach(path -> added.add(path.asText()));
        ObjectNode catalog = catalogByArn(account, region, record.path("signalCatalogArn").asText());
        checkSignals(catalog, added, Set.of("sensor", "actuator", "attribute", "branch", "struct", "property"));
        List<Map<String, String>> invalid = new ArrayList<>();
        for (JsonNode path : in.path("nodesToRemove")) {
            if (!nodes.contains(path.asText())) {
                invalid.add(Map.of("name", path.asText(), "reason", "The signal isn't in the vehicle model."));
            }
        }
        if (!invalid.isEmpty()) {
            throw IotFleetWiseStore.invalidSignals("The signals aren't in the vehicle model.", invalid);
        }
        in.path("nodesToRemove").forEach(path -> nodes.remove(path.asText()));
        nodes.addAll(added);
        ArrayNode list = meta(record).putArray("nodes");
        nodes.forEach(list::add);
        copy(in, record, "description");
        if (in.hasNonNull("status")) {
            String status = in.path("status").asText();
            if (!Set.of("ACTIVE", "DRAFT").contains(status)) {
                throw IotFleetWiseStore.field("status", "A vehicle model's status can be set to ACTIVE or DRAFT.");
            }
            if ("DRAFT".equals(status) && "ACTIVE".equals(record.path("status").asText())) {
                throw IotFleetWiseStore.conflict(MODEL, name, "An ACTIVE vehicle model can't return to DRAFT.");
            }
            record.put("status", status);
        }
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, MODEL, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode deleteModelManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = store.get(account, key(region, MODEL, name)).orElse(null);
        if (record == null) {
            throw IotFleetWiseStore.notFound(MODEL, name);
        }
        String arn = record.path("arn").asText();
        for (String kind : List.of(DECODER, IotFleetWiseStore.VEHICLE)) {
            for (ObjectNode other : store.list(account, region, kind)) {
                if (arn.equals(other.path("modelManifestArn").asText())) {
                    throw IotFleetWiseStore.conflict(MODEL, name, "The vehicle model " + name + " is used by "
                            + kind + " " + meta(other).path("name").asText() + ".");
                }
            }
        }
        store.delete(account, key(region, MODEL, name));
        return store.mapper().createObjectNode().put("name", name).put("arn", arn);
    }

    public ObjectNode listModelManifests(JsonNode in, String region) {
        String catalogArn = in.path("signalCatalogArn").asText(null);
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, MODEL)) {
            if (catalogArn != null && !catalogArn.equals(record.path("signalCatalogArn").asText())) {
                continue;
            }
            summaries.add(summary(record, metadataOnly, "signalCatalogArn", "status"));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "summaries");
        return out;
    }

    public ObjectNode listModelManifestNodes(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = model(account, region, in.path("name").asText());
        Map<String, ObjectNode> tree = tree(catalogByArn(account, region, record.path("signalCatalogArn").asText()));
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode path : record.path("_").path("nodes")) {
            ObjectNode node = tree.get(path.asText());
            if (node != null) {
                nodes.add(node);
            }
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(nodes, in, 100, out, "nodes");
        return out;
    }

    ObjectNode model(String account, String region, String name) {
        return store.get(account, key(region, MODEL, name)).orElseThrow(() -> IotFleetWiseStore.notFound(MODEL,
                name));
    }

    ObjectNode modelByArn(String account, String region, String arn) {
        String name = nameOf(arn, MODEL);
        ObjectNode record = store.get(account, key(region, MODEL, name)).orElse(null);
        if (record == null || !arn.equals(record.path("arn").asText())) {
            throw IotFleetWiseStore.notFound(MODEL, arn);
        }
        return record;
    }

    /** Checks that each path is a node of one of {@code kinds} in the catalog. */
    void checkSignals(ObjectNode catalog, List<String> paths, Set<String> kinds) {
        Map<String, ObjectNode> tree = tree(catalog);
        List<Map<String, String>> invalid = new ArrayList<>();
        for (String path : paths) {
            ObjectNode node = tree.get(path);
            if (node == null) {
                invalid.add(Map.of("name", path, "reason", "The signal doesn't exist in the signal catalog."));
            } else if (!kinds.contains(type(node))) {
                invalid.add(Map.of("name", path, "reason", "The node is a " + type(node) + "."));
            }
        }
        if (!invalid.isEmpty()) {
            throw IotFleetWiseStore.invalidSignals("The signals aren't valid.", invalid);
        }
    }

    // ---------------------------------------------------------------- decoder manifests

    public ObjectNode createDecoderManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        if (store.get(account, key(region, DECODER, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(DECODER, name, "The decoder manifest " + name + " already exists.");
        }
        ObjectNode model = modelByArn(account, region, in.path("modelManifestArn").asText());
        long decoders = store.list(account, region, DECODER).stream()
                .filter(other -> model.path("arn").asText().equals(other.path("modelManifestArn").asText()))
                .count();
        if (decoders >= store.config().maxDecoderManifestsPerModel()) {
            throw IotFleetWiseStore.limit(DECODER, name, "The vehicle model has the most decoder manifests it can "
                    + "have.");
        }
        Map<String, JsonNode> interfaces = new LinkedHashMap<>();
        Map<String, JsonNode> signals = new LinkedHashMap<>();
        apply(model, interfaces, signals, in.path("networkInterfaces"), List.of(), List.of(), in.path("signalDecoders"),
                List.of(), List.of());
        ObjectNode record = store.record(DECODER, name);
        record.put("name", name).put("arn", store.arn(account, region, DECODER, name))
                .put("modelManifestArn", model.path("arn").asText()).put("status", "DRAFT");
        copy(in, record, "description", "defaultForUnmappedSignals");
        saveDecoders(record, interfaces, signals);
        meta(record).set("tags", tagMap(in.path("tags")));
        store.put(account, key(region, DECODER, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode importDecoderManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = decoder(account, region, name);
        if ("ACTIVE".equals(record.path("status").asText())) {
            throw IotFleetWiseStore.conflict(DECODER, name, "The decoder manifest " + name + " is ACTIVE.");
        }
        ObjectNode model = modelByArn(account, region, record.path("modelManifestArn").asText());
        Map<String, JsonNode> interfaces = interfaces(record);
        Map<String, JsonNode> signals = signals(record);
        List<JsonNode> added = new ArrayList<>();
        for (JsonNode definition : in.path("networkFileDefinitions")) {
            JsonNode dbc = definition.path("canDbc");
            String interfaceId = dbc.path("networkInterface").asText();
            JsonNode network = interfaces.get(interfaceId);
            if (network == null || !"CAN_INTERFACE".equals(network.path("type").asText())) {
                throw IotFleetWiseStore.validation("The network interface " + interfaceId + " isn't a CAN interface "
                        + "of the decoder manifest.");
            }
            Map<String, String> map = new HashMap<>();
            dbc.path("signalsMap").fields().forEachRemaining(entry -> map.put(entry.getKey(),
                    entry.getValue().asText()));
            for (JsonNode file : dbc.path("canDbcFiles")) {
                added.addAll(IotFleetWiseDbc.decoders(store.mapper(), IotFleetWiseDbc.text(file), interfaceId, map));
            }
        }
        apply(model, interfaces, signals, store.mapper().createArrayNode(), List.of(), List.of(),
                store.mapper().createArrayNode().addAll(added), List.of(), List.of());
        saveDecoders(record, interfaces, signals);
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, DECODER, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    public ObjectNode getDecoderManifest(JsonNode in, String region) {
        ObjectNode out = view(decoder(store.account(), region, in.path("name").asText()));
        out.remove("networkInterfaces");
        out.remove("signalDecoders");
        return out;
    }

    public ObjectNode updateDecoderManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = decoder(account, region, name);
        boolean edits = in.has("signalDecodersToAdd") || in.has("signalDecodersToUpdate")
                || in.has("signalDecodersToRemove") || in.has("networkInterfacesToAdd")
                || in.has("networkInterfacesToUpdate") || in.has("networkInterfacesToRemove");
        if (edits && "ACTIVE".equals(record.path("status").asText())) {
            throw IotFleetWiseStore.conflict(DECODER, name, "The decoder manifest " + name + " is ACTIVE; its "
                    + "decoders can't change.");
        }
        ObjectNode model = modelByArn(account, region, record.path("modelManifestArn").asText());
        Map<String, JsonNode> interfaces = interfaces(record);
        Map<String, JsonNode> signals = signals(record);
        apply(model, interfaces, signals, in.path("networkInterfacesToAdd"), list(in.path("networkInterfacesToUpdate")),
                strings(in.path("networkInterfacesToRemove")), in.path("signalDecodersToAdd"),
                list(in.path("signalDecodersToUpdate")), strings(in.path("signalDecodersToRemove")));
        saveDecoders(record, interfaces, signals);
        copy(in, record, "description", "defaultForUnmappedSignals");
        if (in.hasNonNull("status")) {
            String status = in.path("status").asText();
            if (!Set.of("ACTIVE", "DRAFT").contains(status)) {
                throw IotFleetWiseStore.field("status", "A decoder manifest's status can be set to ACTIVE or "
                        + "DRAFT.");
            }
            if ("ACTIVE".equals(status)) {
                activate(record, model, signals);
            } else if ("ACTIVE".equals(record.path("status").asText())) {
                throw IotFleetWiseStore.conflict(DECODER, name, "An ACTIVE decoder manifest can't return to DRAFT.");
            } else {
                record.put("status", "DRAFT");
                record.remove("message");
            }
        }
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, DECODER, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText());
    }

    /** Validates the manifest against its model: ACTIVE when every model sensor and actuator has a decoder. */
    private void activate(ObjectNode record, ObjectNode model, Map<String, JsonNode> signals) {
        if (!"ACTIVE".equals(model.path("status").asText())) {
            record.put("status", "INVALID").put("message", "The vehicle model isn't ACTIVE.");
            return;
        }
        if ("CUSTOM_DECODING".equals(record.path("defaultForUnmappedSignals").asText())) {
            record.put("status", "ACTIVE");
            record.remove("message");
            return;
        }
        Map<String, ObjectNode> tree = tree(catalogByArn(store.account(), regionOf(model.path("arn").asText()),
                model.path("signalCatalogArn").asText()));
        List<String> missing = new ArrayList<>();
        for (JsonNode path : model.path("_").path("nodes")) {
            ObjectNode node = tree.get(path.asText());
            if (node != null && Set.of("sensor", "actuator").contains(type(node))
                    && !signals.containsKey(path.asText())) {
                missing.add(path.asText());
            }
        }
        if (missing.isEmpty()) {
            record.put("status", "ACTIVE");
            record.remove("message");
        } else {
            record.put("status", "INVALID").put("message", "These signals of the vehicle model have no decoder: "
                    + String.join(", ", missing) + ".");
        }
    }

    public ObjectNode deleteDecoderManifest(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        ObjectNode record = store.get(account, key(region, DECODER, name)).orElse(null);
        if (record == null) {
            throw IotFleetWiseStore.notFound(DECODER, name);
        }
        String arn = record.path("arn").asText();
        for (ObjectNode vehicle : store.list(account, region, IotFleetWiseStore.VEHICLE)) {
            if (arn.equals(vehicle.path("decoderManifestArn").asText())) {
                throw IotFleetWiseStore.conflict(DECODER, name, "The decoder manifest " + name + " is used by "
                        + "vehicle " + vehicle.path("vehicleName").asText() + ".");
            }
        }
        store.delete(account, key(region, DECODER, name));
        return store.mapper().createObjectNode().put("name", name).put("arn", arn);
    }

    public ObjectNode listDecoderManifests(JsonNode in, String region) {
        String modelArn = in.path("modelManifestArn").asText(null);
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, DECODER)) {
            if (modelArn != null && !modelArn.equals(record.path("modelManifestArn").asText())) {
                continue;
            }
            summaries.add(summary(record, metadataOnly, "modelManifestArn", "status", "message"));
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "summaries");
        return out;
    }

    public ObjectNode listDecoderManifestNetworkInterfaces(JsonNode in, String region) {
        ObjectNode record = decoder(store.account(), region, in.path("name").asText());
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(new ArrayList<>(interfaces(record).values()), in, 100, out, "networkInterfaces");
        return out;
    }

    public ObjectNode listDecoderManifestSignals(JsonNode in, String region) {
        ObjectNode record = decoder(store.account(), region, in.path("name").asText());
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(new ArrayList<>(signals(record).values()), in, 100, out, "signalDecoders");
        return out;
    }

    ObjectNode decoder(String account, String region, String name) {
        return store.get(account, key(region, DECODER, name))
                .orElseThrow(() -> IotFleetWiseStore.notFound(DECODER, name));
    }

    ObjectNode decoderByArn(String account, String region, String arn) {
        String name = nameOf(arn, DECODER);
        ObjectNode record = store.get(account, key(region, DECODER, name)).orElse(null);
        if (record == null || !arn.equals(record.path("arn").asText())) {
            throw IotFleetWiseStore.notFound(DECODER, arn);
        }
        return record;
    }

    static Map<String, JsonNode> interfaces(ObjectNode decoder) {
        Map<String, JsonNode> interfaces = new LinkedHashMap<>();
        decoder.path("_").path("networkInterfaces").forEach(network -> interfaces.put(
                network.path("interfaceId").asText(), network));
        return interfaces;
    }

    static Map<String, JsonNode> signals(ObjectNode decoder) {
        Map<String, JsonNode> signals = new LinkedHashMap<>();
        decoder.path("_").path("signalDecoders").forEach(signal -> signals.put(
                signal.path("fullyQualifiedName").asText(), signal));
        return signals;
    }

    private static void saveDecoders(ObjectNode record, Map<String, JsonNode> interfaces,
                                     Map<String, JsonNode> signals) {
        ArrayNode networks = meta(record).putArray("networkInterfaces");
        interfaces.values().forEach(networks::add);
        ArrayNode decoders = meta(record).putArray("signalDecoders");
        signals.values().forEach(decoders::add);
    }

    /**
     * Applies interface and decoder changes, collecting every problem IoT FleetWise reports in a
     * DecoderManifestValidationException rather than stopping at the first.
     */
    private void apply(ObjectNode model, Map<String, JsonNode> interfaces, Map<String, JsonNode> signals,
                       JsonNode interfacesToAdd, List<JsonNode> interfacesToUpdate, List<String> interfacesToRemove,
                       JsonNode signalsToAdd, List<JsonNode> signalsToUpdate, List<String> signalsToRemove) {
        List<Map<String, String>> invalidInterfaces = new ArrayList<>();
        List<Map<String, String>> invalidSignals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode network : interfacesToAdd) {
            String id = network.path("interfaceId").asText();
            if (!seen.add(id)) {
                invalidInterfaces.add(Map.of("interfaceId", id, "reason", "DUPLICATE_NETWORK_INTERFACE"));
            } else if (interfaces.containsKey(id)) {
                invalidInterfaces.add(Map.of("interfaceId", id, "reason", "NETWORK_INTERFACE_TO_ADD_ALREADY_EXISTS"));
            } else {
                checkInterface(network, invalidInterfaces);
                interfaces.put(id, network);
            }
        }
        for (JsonNode network : interfacesToUpdate) {
            String id = network.path("interfaceId").asText();
            if (!interfaces.containsKey(id)) {
                invalidInterfaces.add(Map.of("interfaceId", id, "reason", "CONFLICTING_NETWORK_INTERFACE"));
            } else {
                checkInterface(network, invalidInterfaces);
                interfaces.put(id, network);
            }
        }
        for (String id : interfacesToRemove) {
            if (signals.values().stream().anyMatch(signal -> id.equals(signal.path("interfaceId").asText()))
                    && signalsToRemove.stream().noneMatch(name -> id.equals(signals.get(name) == null ? null
                    : signals.get(name).path("interfaceId").asText()))) {
                invalidInterfaces.add(Map.of("interfaceId", id, "reason",
                        "NETWORK_INTERFACE_TO_REMOVE_ASSOCIATED_WITH_SIGNALS"));
            } else {
                interfaces.remove(id);
            }
        }
        signalsToRemove.forEach(signals::remove);
        Set<String> modelNodes = new LinkedHashSet<>();
        model.path("_").path("nodes").forEach(path -> modelNodes.add(path.asText()));
        Set<String> added = new LinkedHashSet<>();
        List<JsonNode> all = new ArrayList<>();
        signalsToAdd.forEach(all::add);
        for (JsonNode signal : all) {
            String name = signal.path("fullyQualifiedName").asText();
            if (!added.add(name)) {
                invalidSignals.add(Map.of("name", name, "reason", "DUPLICATE_SIGNAL"));
            } else if (signals.containsKey(name)) {
                invalidSignals.add(Map.of("name", name, "reason", "SIGNAL_TO_ADD_ALREADY_EXISTS"));
            } else if (checkSignal(signal, interfaces, modelNodes, invalidSignals)) {
                signals.put(name, signal);
            }
        }
        for (JsonNode signal : signalsToUpdate) {
            String name = signal.path("fullyQualifiedName").asText();
            if (!signals.containsKey(name)) {
                invalidSignals.add(Map.of("name", name, "reason", "CONFLICTING_SIGNAL"));
            } else if (checkSignal(signal, interfaces, modelNodes, invalidSignals)) {
                signals.put(name, signal);
            }
        }
        if (!invalidInterfaces.isEmpty() || !invalidSignals.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("invalidSignals", invalidSignals);
            data.put("invalidNetworkInterfaces", invalidInterfaces);
            throw new AwsException("DecoderManifestValidationException", "The decoder manifest isn't valid.", 400,
                    data);
        }
    }

    private static void checkInterface(JsonNode network, List<Map<String, String>> invalid) {
        String type = network.path("type").asText();
        String payload = INTERFACE_PAYLOAD.get(type);
        if (payload != null && !network.hasNonNull(payload)) {
            String reason = switch (type) {
                case "CAN_INTERFACE" -> "CAN_NETWORK_INTERFACE_INFO_IS_NULL";
                case "OBD_INTERFACE" -> "OBD_NETWORK_INTERFACE_INFO_IS_NULL";
                case "VEHICLE_MIDDLEWARE" -> "VEHICLE_MIDDLEWARE_NETWORK_INTERFACE_INFO_IS_NULL";
                default -> "CUSTOM_DECODING_SIGNAL_NETWORK_INTERFACE_INFO_IS_NULL";
            };
            invalid.add(Map.of("interfaceId", network.path("interfaceId").asText(), "reason", reason));
        }
    }

    private static boolean checkSignal(JsonNode signal, Map<String, JsonNode> interfaces, Set<String> modelNodes,
                                       List<Map<String, String>> invalid) {
        String name = signal.path("fullyQualifiedName").asText();
        String type = signal.path("type").asText();
        JsonNode network = interfaces.get(signal.path("interfaceId").asText());
        String reason = null;
        if (!modelNodes.contains(name)) {
            reason = "SIGNAL_NOT_IN_MODEL";
        } else if (network == null) {
            reason = "SIGNAL_NOT_ASSOCIATED_WITH_NETWORK_INTERFACE";
        } else if (!network.path("type").asText().equals(SIGNAL_INTERFACE.get(type))) {
            reason = "NETWORK_INTERFACE_TYPE_INCOMPATIBLE_WITH_SIGNAL_DECODER_TYPE";
        } else if (!signal.hasNonNull(SIGNAL_PAYLOAD.getOrDefault(type, "-"))) {
            reason = switch (type) {
                case "CAN_SIGNAL" -> "CAN_SIGNAL_INFO_IS_NULL";
                case "OBD_SIGNAL" -> "OBD_SIGNAL_INFO_IS_NULL";
                case "MESSAGE_SIGNAL" -> "MESSAGE_SIGNAL_INFO_IS_NULL";
                default -> "CUSTOM_DECODING_SIGNAL_INFO_IS_NULL";
            };
        }
        if (reason != null) {
            invalid.add(Map.of("name", name, "reason", reason));
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- state templates

    public ObjectNode createStateTemplate(JsonNode in, String region) {
        String account = store.account();
        String name = in.path("name").asText();
        if (store.get(account, key(region, STATE_TEMPLATE, name)).isPresent()) {
            throw IotFleetWiseStore.conflict(STATE_TEMPLATE, name, "The state template " + name
                    + " already exists.");
        }
        ObjectNode catalog = catalogByArn(account, region, in.path("signalCatalogArn").asText());
        List<String> properties = strings(in.path("stateTemplateProperties"));
        checkSignals(catalog, properties, Set.of("sensor", "actuator", "attribute"));
        checkSignals(catalog, strings(in.path("dataExtraDimensions")), Set.of("attribute"));
        checkSignals(catalog, strings(in.path("metadataExtraDimensions")), Set.of("attribute"));
        String id = ulid();
        ObjectNode record = store.record(STATE_TEMPLATE, name);
        record.put("name", name).put("arn", store.arn(account, region, STATE_TEMPLATE, name)).put("id", id)
                .put("signalCatalogArn", catalog.path("arn").asText());
        copy(in, record, "description", "stateTemplateProperties", "dataExtraDimensions", "metadataExtraDimensions");
        meta(record).set("tags", tagMap(in.path("tags")));
        store.put(account, key(region, STATE_TEMPLATE, name), record);
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText())
                .put("id", id);
    }

    public ObjectNode getStateTemplate(JsonNode in, String region) {
        return view(stateTemplate(store.account(), region, in.path("identifier").asText()));
    }

    public ObjectNode updateStateTemplate(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = stateTemplate(account, region, in.path("identifier").asText());
        ObjectNode catalog = catalogByArn(account, region, record.path("signalCatalogArn").asText());
        Set<String> properties = new LinkedHashSet<>(strings(record.path("stateTemplateProperties")));
        List<String> added = strings(in.path("stateTemplatePropertiesToAdd"));
        checkSignals(catalog, added, Set.of("sensor", "actuator", "attribute"));
        strings(in.path("stateTemplatePropertiesToRemove")).forEach(properties::remove);
        properties.addAll(added);
        if (properties.isEmpty()) {
            throw IotFleetWiseStore.field("stateTemplateProperties", "A state template needs at least one signal.");
        }
        ArrayNode list = record.putArray("stateTemplateProperties");
        properties.forEach(list::add);
        for (String dimension : List.of("dataExtraDimensions", "metadataExtraDimensions")) {
            if (in.has(dimension)) {
                checkSignals(catalog, strings(in.path(dimension)), Set.of("attribute"));
                record.set(dimension, in.get(dimension));
            }
        }
        copy(in, record, "description");
        IotFleetWiseStore.touch(record);
        store.put(account, key(region, STATE_TEMPLATE, record.path("name").asText()), record);
        return store.mapper().createObjectNode().put("name", record.path("name").asText())
                .put("arn", record.path("arn").asText()).put("id", record.path("id").asText());
    }

    public ObjectNode deleteStateTemplate(JsonNode in, String region) {
        String account = store.account();
        ObjectNode record = stateTemplate(account, region, in.path("identifier").asText());
        String name = record.path("name").asText();
        for (ObjectNode vehicle : store.list(account, region, IotFleetWiseStore.VEHICLE)) {
            for (JsonNode association : vehicle.path("stateTemplates")) {
                String identifier = association.path("identifier").asText();
                if (identifier.equals(name) || identifier.equals(record.path("id").asText())
                        || identifier.equals(record.path("arn").asText())) {
                    throw IotFleetWiseStore.validation("The state template " + name + " is associated with vehicle "
                            + vehicle.path("vehicleName").asText() + ".");
                }
            }
        }
        store.delete(account, key(region, STATE_TEMPLATE, name));
        return store.mapper().createObjectNode().put("name", name).put("arn", record.path("arn").asText())
                .put("id", record.path("id").asText());
    }

    public ObjectNode listStateTemplates(JsonNode in, String region) {
        boolean metadataOnly = "METADATA_ONLY".equals(in.path("listResponseScope").asText());
        List<JsonNode> summaries = new ArrayList<>();
        for (ObjectNode record : store.list(store.account(), region, STATE_TEMPLATE)) {
            ObjectNode summary = summary(record, metadataOnly, "signalCatalogArn");
            summary.put("id", record.path("id").asText());
            summaries.add(summary);
        }
        ObjectNode out = store.mapper().createObjectNode();
        IotFleetWiseStore.page(summaries, in, 100, out, "summaries");
        return out;
    }

    /** A state template by name, ID or ARN. */
    ObjectNode stateTemplate(String account, String region, String identifier) {
        for (ObjectNode record : store.list(account, region, STATE_TEMPLATE)) {
            if (identifier.equals(record.path("name").asText()) || identifier.equals(record.path("id").asText())
                    || identifier.equals(record.path("arn").asText())) {
                return record;
            }
        }
        throw IotFleetWiseStore.notFound(STATE_TEMPLATE, identifier);
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode summary(ObjectNode record, boolean metadataOnly, String... fields) {
        ObjectNode summary = store.mapper().createObjectNode();
        summary.put("name", record.path("name").asText()).put("arn", record.path("arn").asText());
        if (!metadataOnly) {
            for (String field : fields) {
                if (record.has(field)) {
                    summary.set(field, record.get(field));
                }
            }
            copy(record, summary, "description");
            summary.put("creationTime", record.path("creationTime").asDouble())
                    .put("lastModificationTime", record.path("lastModificationTime").asDouble());
        }
        return summary;
    }

    ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = store.mapper().createObjectNode();
        IotFleetWiseStore.tags(tags).forEach(map::put);
        return map;
    }

    static void copy(JsonNode from, ObjectNode to, String... names) {
        for (String name : names) {
            if (from.hasNonNull(name)) {
                to.set(name, from.get(name).deepCopy());
            }
        }
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    /** The resource name an ARN of {@code kind} carries, or a ValidationException. */
    static String nameOf(String arn, String kind) {
        String marker = ":" + kind + "/";
        int at = arn == null ? -1 : arn.indexOf(marker);
        if (at < 0 || !arn.contains(":" + IotFleetWiseStore.SERVICE + ":")) {
            throw IotFleetWiseStore.validation("The ARN " + arn + " isn't a " + kind + " ARN.");
        }
        return arn.substring(at + marker.length());
    }

    static String regionOf(String arn) {
        return AwsArnUtils.parse(arn).region();
    }

    private static String ulid() {
        StringBuilder id = new StringBuilder();
        long time = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            id.append(ULID_ALPHABET.charAt((int) ((time >> (i * 5)) & 31)));
        }
        for (int i = 0; i < 16; i++) {
            id.append(ULID_ALPHABET.charAt(RANDOM.nextInt(32)));
        }
        return id.toString();
    }
}
