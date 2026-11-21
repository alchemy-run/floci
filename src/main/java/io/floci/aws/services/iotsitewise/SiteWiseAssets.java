package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.ASSET;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.conflicting;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * Assets: instances of asset models whose properties, hierarchies and composite models carry the model's IDs, with
 * each property's alias, notification state and unit set on the asset. Assets are associated to a parent through a
 * hierarchy of the parent's model; an asset that has a parent or children can't be deleted. A model update
 * propagates to its assets, keeping each surviving property's alias and notification. Assets move from CREATING or
 * UPDATING to ACTIVE, and from DELETING to gone, as models do.
 */
@ApplicationScoped
public class SiteWiseAssets {

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<SiteWiseModels> models;
    private final Instance<SiteWiseData> data;

    @Inject
    public SiteWiseAssets(SiteWiseStore store, Instance<SiteWiseModels> models, Instance<SiteWiseData> data) {
        this.store = store;
        this.mapper = store.mapper();
        this.models = models;
        this.data = data;
    }

    // ---------------------------------------------------------------- lookups

    ObjectNode require(String account, String region, String reference) {
        ObjectNode asset = find(account, region, reference);
        if (asset == null) {
            throw notFound("Asset " + reference + " not found.");
        }
        return asset;
    }

    ObjectNode find(String account, String region, String reference) {
        if (reference == null) {
            return null;
        }
        ObjectNode asset;
        if (reference.startsWith("externalId:")) {
            String external = reference.substring(11);
            asset = store.list(account, region, ASSET).stream()
                    .filter(a -> external.equals(a.path("assetExternalId").asText(null))).findFirst().orElse(null);
        } else {
            asset = store.get(account, region, ASSET, reference).orElse(null);
        }
        return asset == null ? null : settle(account, region, asset);
    }

    ObjectNode settle(String account, String region, ObjectNode asset) {
        long readyAt = meta(asset).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return asset;
        }
        String id = asset.get("assetId").asText();
        if ("DELETING".equals(asset.path("assetStatus").path("state").asText())) {
            store.delete(account, region, ASSET, id);
            data.get().forgetAsset(account, region, asset);
            return null;
        }
        asset.set("assetStatus", mapper.createObjectNode().put("state", "ACTIVE"));
        meta(asset).remove("readyAt");
        store.put(account, region, ASSET, id, asset);
        return asset;
    }

    List<ObjectNode> all(String account, String region) {
        List<ObjectNode> assets = new ArrayList<>();
        for (ObjectNode asset : store.list(account, region, ASSET)) {
            ObjectNode settled = settle(account, region, asset);
            if (settled != null) {
                assets.add(settled);
            }
        }
        return assets;
    }

    private void transition(ObjectNode asset, String state) {
        asset.set("assetStatus", mapper.createObjectNode().put("state", state));
        meta(asset).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    private static void requireActive(ObjectNode asset) {
        String state = asset.path("assetStatus").path("state").asText();
        if (!"ACTIVE".equals(state) && !"FAILED".equals(state)) {
            throw conflicting("Asset " + asset.get("assetId").asText() + " is " + state + "; wait until it's ACTIVE.",
                    asset.get("assetId").asText(), asset.get("assetArn").asText());
        }
    }

    /** The asset property (its own or a composite model's) with an ID or external ID. */
    ObjectNode property(ObjectNode asset, String reference) {
        for (ObjectNode property : properties(asset)) {
            if (reference.equals(property.get("id").asText()) || reference.startsWith("externalId:")
                    && reference.substring(11).equals(property.path("externalId").asText(null))) {
                return property;
            }
        }
        throw notFound("Property " + reference + " not found in asset " + asset.get("assetId").asText() + ".");
    }

    static List<ObjectNode> properties(ObjectNode asset) {
        List<ObjectNode> all = new ArrayList<>();
        asset.path("assetProperties").forEach(p -> all.add((ObjectNode) p));
        asset.path("assetCompositeModels").forEach(c -> c.path("properties").forEach(p -> all.add((ObjectNode) p)));
        return all;
    }

    /** The model property an asset property instantiates. */
    ObjectNode modelProperty(String account, String region, ObjectNode asset, String propertyId) {
        ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
        if (model == null) {
            return null;
        }
        for (ObjectNode property : SiteWiseModels.allProperties(model)) {
            if (propertyId.equals(property.get("id").asText())) {
                return property;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- assets

    ObjectNode createAsset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateAsset", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode model = models.get().require(account, region, in.get("assetModelId").asText());
        String modelState = model.path("assetModelStatus").path("state").asText();
        if (!"ACTIVE".equals(modelState)) {
            throw conflicting("Asset model " + model.get("assetModelId").asText() + " is " + modelState
                    + "; wait until it's ACTIVE.", model.get("assetModelId").asText(),
                    model.get("assetModelArn").asText());
        }
        if (!"ASSET_MODEL".equals(model.path("assetModelType").asText("ASSET_MODEL"))) {
            throw invalid("Assets can only be created from asset models, not "
                    + model.path("assetModelType").asText() + "s.");
        }
        List<ObjectNode> existing = store.list(account, region, ASSET);
        if (existing.size() >= store.config().maxAssets()) {
            throw SiteWiseStore.limit("You can have at most " + store.config().maxAssets() + " assets in this Region.");
        }
        String id = in.hasNonNull("assetId") ? in.get("assetId").asText() : SiteWiseStore.uuid();
        if (store.get(account, region, ASSET, id).isPresent()) {
            throw SiteWiseStore.exists("An asset with ID " + id + " already exists.", id,
                    store.arn(account, region, ASSET, id));
        }
        String external = in.path("assetExternalId").asText(null);
        if (external != null && existing.stream().anyMatch(a -> external.equals(a.path("assetExternalId").asText(null)))) {
            throw invalid("The external ID " + external + " is already in use.");
        }
        String name = in.get("assetName").asText();
        ObjectNode asset = store.record();
        double now = SiteWiseStore.now();
        asset.put("assetId", id);
        if (external != null) {
            asset.put("assetExternalId", external);
        }
        asset.put("assetArn", store.arn(account, region, ASSET, id));
        asset.put("assetName", name);
        asset.put("assetModelId", model.get("assetModelId").asText());
        if (in.hasNonNull("assetDescription")) {
            asset.put("assetDescription", in.get("assetDescription").asText());
        }
        asset.put("assetCreationDate", now);
        asset.put("assetLastUpdateDate", now);
        meta(asset).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        materialize(model, asset);
        transition(asset, "CREATING");
        store.put(account, region, ASSET, id, asset);
        data.get().attributeDefaults(account, region, asset, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetId", id);
        out.put("assetArn", asset.get("assetArn").asText());
        out.set("assetStatus", asset.get("assetStatus").deepCopy());
        store.remember(account, region, "CreateAsset", in, out);
        return out;
    }

    /** Sets the asset's properties, hierarchies and composite models from its model, keeping what the asset set. */
    private void materialize(ObjectNode model, ObjectNode asset) {
        Map<String, ObjectNode> previous = new HashMap<>();
        properties(asset).forEach(p -> previous.put(p.get("id").asText(), p));
        String modelId = model.get("assetModelId").asText();
        String assetId = asset.get("assetId").asText();
        ArrayNode properties = asset.putArray("assetProperties");
        for (JsonNode definition : model.path("assetModelProperties")) {
            properties.add(property(modelId, assetId, asset, definition, previous, null));
        }
        ArrayNode hierarchies = asset.putArray("assetHierarchies");
        for (JsonNode hierarchy : model.path("assetModelHierarchies")) {
            ObjectNode entry = hierarchies.addObject().put("id", hierarchy.get("id").asText());
            if (hierarchy.hasNonNull("externalId")) {
                entry.put("externalId", hierarchy.get("externalId").asText());
            }
            entry.put("name", hierarchy.get("name").asText());
        }
        ArrayNode composites = asset.putArray("assetCompositeModels");
        ArrayNode summaries = asset.putArray("assetCompositeModelSummaries");
        for (ObjectNode composite : SiteWiseModels.composites(model)) {
            ArrayNode path = models.get().compositePath(model, composite);
            ArrayNode assetPath = mapper.createArrayNode();
            path.forEach(segment -> assetPath.addObject().put("id", segment.get("id").asText())
                    .put("name", segment.get("name").asText()));
            ((ObjectNode) assetPath.get(0)).put("id", assetId).put("name", asset.get("assetName").asText());
            ObjectNode entry = composites.addObject();
            entry.put("name", composite.get("name").asText());
            if (composite.hasNonNull("description")) {
                entry.put("description", composite.get("description").asText());
            }
            entry.put("type", composite.get("type").asText());
            ArrayNode compositeProperties = entry.putArray("properties");
            for (JsonNode definition : composite.path("properties")) {
                compositeProperties.add(property(modelId, assetId, asset, definition, previous, assetPath));
            }
            entry.put("id", composite.get("id").asText());
            if (composite.hasNonNull("externalId")) {
                entry.put("externalId", composite.get("externalId").asText());
            }
            ObjectNode summary = summaries.addObject();
            summary.put("id", composite.get("id").asText());
            if (composite.hasNonNull("externalId")) {
                summary.put("externalId", composite.get("externalId").asText());
            }
            summary.put("name", composite.get("name").asText());
            summary.put("type", composite.get("type").asText());
            summary.put("description", composite.path("description").asText(""));
            summary.set("path", assetPath);
        }
    }

    private ObjectNode property(String modelId, String assetId, ObjectNode asset, JsonNode definition,
                                Map<String, ObjectNode> previous, ArrayNode compositePath) {
        String id = definition.get("id").asText();
        ObjectNode before = previous.get(id);
        ObjectNode property = mapper.createObjectNode();
        property.put("id", id);
        if (definition.hasNonNull("externalId")) {
            property.put("externalId", definition.get("externalId").asText());
        }
        property.put("name", definition.get("name").asText());
        if (before != null && before.hasNonNull("alias")) {
            property.put("alias", before.get("alias").asText());
        }
        ObjectNode notification = property.putObject("notification");
        notification.put("topic", "$aws/sitewise/asset-models/" + modelId + "/assets/" + assetId + "/properties/" + id);
        notification.put("state", before == null ? "DISABLED"
                : before.path("notification").path("state").asText("DISABLED"));
        property.put("dataType", definition.get("dataType").asText());
        if (definition.hasNonNull("dataTypeSpec")) {
            property.put("dataTypeSpec", definition.get("dataTypeSpec").asText());
        }
        String unit = before != null && before.path("_unit").asBoolean(false) ? before.path("unit").asText(null)
                : definition.path("unit").asText(null);
        if (unit != null) {
            property.put("unit", unit);
        }
        if (before != null && before.path("_unit").asBoolean(false)) {
            property.put("_unit", true);
        }
        ArrayNode path = compositePath == null ? mapper.createArrayNode() : compositePath.deepCopy();
        if (compositePath == null) {
            path.addObject().put("id", assetId).put("name", asset.get("assetName").asText());
        }
        path.addObject().put("id", id).put("name", definition.get("name").asText());
        property.set("path", path);
        return property;
    }

    /** Applies a model's new definition to each of its assets, and drops data of properties it removed. */
    void propagate(String account, String region, ObjectNode model) {
        String modelId = model.get("assetModelId").asText();
        for (ObjectNode asset : store.list(account, region, ASSET)) {
            if (!modelId.equals(asset.path("assetModelId").asText())) {
                continue;
            }
            Set<String> before = new HashSet<>();
            properties(asset).forEach(p -> before.add(p.get("id").asText()));
            materialize(model, asset);
            properties(asset).forEach(p -> before.remove(p.get("id").asText()));
            asset.put("assetLastUpdateDate", SiteWiseStore.now());
            store.put(account, region, ASSET, asset.get("assetId").asText(), asset);
            for (String removed : before) {
                data.get().forgetProperty(account, region, asset.get("assetId").asText(), removed);
            }
            data.get().attributeDefaults(account, region, asset, model);
            // A child no longer reachable through a hierarchy the model dropped is disassociated.
            Set<String> hierarchies = new HashSet<>();
            asset.path("assetHierarchies").forEach(h -> hierarchies.add(h.get("id").asText()));
            for (ObjectNode child : store.list(account, region, ASSET)) {
                JsonNode parent = meta(child).path("parent");
                if (asset.get("assetId").asText().equals(parent.path("assetId").asText())
                        && !hierarchies.contains(parent.path("hierarchyId").asText())) {
                    meta(child).remove("parent");
                    store.put(account, region, ASSET, child.get("assetId").asText(), child);
                }
            }
        }
    }

    ObjectNode describeAsset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        ObjectNode view = view(asset);
        if (in.path("excludeProperties").asBoolean(false)) {
            view.putArray("assetProperties");
            view.path("assetCompositeModels").forEach(c -> ((ObjectNode) c).putArray("properties"));
        }
        return view;
    }

    static ObjectNode view(ObjectNode asset) {
        ObjectNode view = SiteWiseStore.view(asset);
        properties(view).forEach(p -> p.remove("_unit"));
        return view;
    }

    ObjectNode updateAsset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        requireActive(asset);
        asset.put("assetName", in.get("assetName").asText());
        if (in.hasNonNull("assetDescription")) {
            asset.put("assetDescription", in.get("assetDescription").asText());
        } else {
            asset.remove("assetDescription");
        }
        if (in.hasNonNull("assetExternalId")) {
            String external = in.get("assetExternalId").asText();
            if (asset.hasNonNull("assetExternalId") && !external.equals(asset.get("assetExternalId").asText())) {
                throw invalid("An asset's external ID can't be changed once set.");
            }
            asset.put("assetExternalId", external);
        }
        asset.put("assetLastUpdateDate", SiteWiseStore.now());
        ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
        if (model != null) {
            materialize(model, asset);
        }
        transition(asset, "UPDATING");
        store.put(account, region, ASSET, asset.get("assetId").asText(), asset);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetId", asset.get("assetId").asText());
        out.set("assetStatus", asset.get("assetStatus").deepCopy());
        return out;
    }

    ObjectNode deleteAsset(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        String id = asset.get("assetId").asText();
        if (!"DELETING".equals(asset.path("assetStatus").path("state").asText())) {
            requireActive(asset);
            if (meta(asset).has("parent")) {
                throw conflicting("Asset " + id + " is associated to the parent asset "
                        + meta(asset).path("parent").path("assetId").asText() + "; disassociate it first.", id,
                        asset.get("assetArn").asText());
            }
            for (ObjectNode other : store.list(account, region, ASSET)) {
                if (id.equals(meta(other).path("parent").path("assetId").asText())) {
                    throw conflicting("Asset " + id + " has the child asset " + other.get("assetId").asText()
                            + "; disassociate it first.", id, asset.get("assetArn").asText());
                }
            }
            transition(asset, "DELETING");
            store.put(account, region, ASSET, id, asset);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("assetId", id);
        out.set("assetStatus", asset.get("assetStatus").deepCopy());
        return out;
    }

    ObjectNode listAssets(ObjectNode in, String region) {
        String account = store.account();
        String filter = in.path("filter").asText("ALL");
        String modelReference = in.path("assetModelId").asText(null);
        if ("ALL".equals(filter) && modelReference == null) {
            throw invalid("Listing ALL assets needs an assetModelId.");
        }
        String modelId = modelReference == null ? null
                : models.get().require(account, region, modelReference).get("assetModelId").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode asset : all(account, region)) {
            if (modelId != null && !modelId.equals(asset.get("assetModelId").asText())) {
                continue;
            }
            if ("TOP_LEVEL".equals(filter) && meta(asset).has("parent")) {
                continue;
            }
            summaries.add(summary(asset));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetSummaries");
        return out;
    }

    ObjectNode summary(ObjectNode asset) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("id", asset.get("assetId").asText());
        if (asset.hasNonNull("assetExternalId")) {
            summary.put("externalId", asset.get("assetExternalId").asText());
        }
        summary.put("arn", asset.get("assetArn").asText());
        summary.put("name", asset.get("assetName").asText());
        summary.put("assetModelId", asset.get("assetModelId").asText());
        summary.put("creationDate", asset.get("assetCreationDate").asDouble());
        summary.put("lastUpdateDate", asset.get("assetLastUpdateDate").asDouble());
        summary.set("status", asset.get("assetStatus").deepCopy());
        summary.set("hierarchies", asset.path("assetHierarchies").deepCopy());
        if (asset.hasNonNull("assetDescription")) {
            summary.put("description", asset.get("assetDescription").asText());
        }
        return summary;
    }

    // ---------------------------------------------------------------- hierarchies

    ObjectNode associateAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode parent = require(account, region, in.get("assetId").asText());
        ObjectNode child = require(account, region, in.get("childAssetId").asText());
        requireActive(parent);
        requireActive(child);
        ObjectNode model = models.get().require(account, region, parent.get("assetModelId").asText());
        JsonNode hierarchy = SiteWiseModels.hierarchy(model, in.get("hierarchyId").asText());
        String parentId = parent.get("assetId").asText();
        String childId = child.get("assetId").asText();
        if (!hierarchy.get("childAssetModelId").asText().equals(child.get("assetModelId").asText())) {
            throw invalid("Hierarchy " + hierarchy.get("name").asText() + " holds assets of model "
                    + hierarchy.get("childAssetModelId").asText() + ", not " + child.get("assetModelId").asText() + ".");
        }
        if (meta(child).has("parent")) {
            if (parentId.equals(meta(child).path("parent").path("assetId").asText())) {
                throw SiteWiseStore.exists("Asset " + childId + " is already associated to " + parentId + ".", childId,
                        child.get("assetArn").asText());
            }
            throw conflicting("Asset " + childId + " already has a parent.", childId, child.get("assetArn").asText());
        }
        for (String ancestor = parentId; ancestor != null; ) {
            if (ancestor.equals(childId)) {
                throw invalid("Associating " + childId + " under " + parentId + " would make a cycle.");
            }
            ObjectNode node = store.get(account, region, ASSET, ancestor).orElse(null);
            ancestor = node == null ? null : meta(node).path("parent").path("assetId").asText(null);
        }
        meta(child).putObject("parent").put("assetId", parentId).put("hierarchyId", hierarchy.get("id").asText());
        store.put(account, region, ASSET, childId, child);
        return mapper.createObjectNode();
    }

    ObjectNode disassociateAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode parent = require(account, region, in.get("assetId").asText());
        ObjectNode child = require(account, region, in.get("childAssetId").asText());
        ObjectNode model = models.get().require(account, region, parent.get("assetModelId").asText());
        JsonNode hierarchy = SiteWiseModels.hierarchy(model, in.get("hierarchyId").asText());
        JsonNode link = meta(child).path("parent");
        if (!parent.get("assetId").asText().equals(link.path("assetId").asText())
                || !hierarchy.get("id").asText().equals(link.path("hierarchyId").asText())) {
            throw notFound("Asset " + child.get("assetId").asText() + " isn't associated to "
                    + parent.get("assetId").asText() + " through " + hierarchy.get("name").asText() + ".");
        }
        meta(child).remove("parent");
        store.put(account, region, ASSET, child.get("assetId").asText(), child);
        return mapper.createObjectNode();
    }

    /** The asset's children through a hierarchy, all hierarchies when null. */
    List<ObjectNode> children(String account, String region, String assetId, String hierarchyId) {
        List<ObjectNode> children = new ArrayList<>();
        for (ObjectNode other : all(account, region)) {
            JsonNode link = meta(other).path("parent");
            if (assetId.equals(link.path("assetId").asText())
                    && (hierarchyId == null || hierarchyId.equals(link.path("hierarchyId").asText()))) {
                children.add(other);
            }
        }
        return children;
    }

    ObjectNode listAssociatedAssets(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        String direction = in.path("traversalDirection").asText("CHILD");
        List<ObjectNode> summaries = new ArrayList<>();
        if ("PARENT".equals(direction)) {
            String parent = meta(asset).path("parent").path("assetId").asText(null);
            ObjectNode node = parent == null ? null : find(account, region, parent);
            if (node != null) {
                summaries.add(summary(node));
            }
        } else {
            if (!in.hasNonNull("hierarchyId")) {
                throw invalid("Listing an asset's children needs a hierarchyId.");
            }
            ObjectNode model = models.get().require(account, region, asset.get("assetModelId").asText());
            JsonNode hierarchy = SiteWiseModels.hierarchy(model, in.get("hierarchyId").asText());
            for (ObjectNode child : children(account, region, asset.get("assetId").asText(),
                    hierarchy.get("id").asText())) {
                summaries.add(summary(child));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetSummaries");
        return out;
    }

    ObjectNode listAssetRelationships(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        List<ObjectNode> summaries = new ArrayList<>();
        ObjectNode current = asset;
        while (current != null) {
            String parent = meta(current).path("parent").path("assetId").asText(null);
            ObjectNode summary = mapper.createObjectNode();
            ObjectNode info = summary.putObject("hierarchyInfo");
            if (parent != null) {
                info.put("parentAssetId", parent);
            }
            info.put("childAssetId", current.get("assetId").asText());
            summary.put("relationshipType", "HIERARCHY");
            summaries.add(summary);
            current = parent == null ? null : find(account, region, parent);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetRelationshipSummaries");
        return out;
    }

    // ---------------------------------------------------------------- properties

    ObjectNode listAssetProperties(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        boolean all = "ALL".equals(in.path("filter").asText("BASE"));
        List<ObjectNode> summaries = new ArrayList<>();
        for (JsonNode property : asset.path("assetProperties")) {
            summaries.add(propertySummary(property, null));
        }
        if (all) {
            for (JsonNode composite : asset.path("assetCompositeModels")) {
                for (JsonNode property : composite.path("properties")) {
                    summaries.add(propertySummary(property, composite.get("id").asText()));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetPropertySummaries");
        return out;
    }

    private ObjectNode propertySummary(JsonNode property, String composite) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("id", property.get("id").asText());
        if (property.hasNonNull("externalId")) {
            summary.put("externalId", property.get("externalId").asText());
        }
        if (property.hasNonNull("alias")) {
            summary.put("alias", property.get("alias").asText());
        }
        if (property.hasNonNull("unit")) {
            summary.put("unit", property.get("unit").asText());
        }
        summary.set("notification", property.get("notification").deepCopy());
        if (composite != null) {
            summary.put("assetCompositeModelId", composite);
        }
        summary.set("path", property.get("path").deepCopy());
        return summary;
    }

    ObjectNode describeAssetProperty(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        ObjectNode property = property(asset, in.get("propertyId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("assetId", asset.get("assetId").asText());
        if (asset.hasNonNull("assetExternalId")) {
            out.put("assetExternalId", asset.get("assetExternalId").asText());
        }
        out.put("assetName", asset.get("assetName").asText());
        out.put("assetModelId", asset.get("assetModelId").asText());
        ObjectNode described = property.deepCopy();
        described.remove("dataTypeSpec");
        described.remove("_unit");
        ObjectNode definition = modelProperty(account, region, asset, property.get("id").asText());
        if (definition != null) {
            described.set("type", definition.get("type").deepCopy());
        }
        for (JsonNode composite : asset.path("assetCompositeModels")) {
            for (JsonNode candidate : composite.path("properties")) {
                if (candidate.get("id").asText().equals(property.get("id").asText())) {
                    ObjectNode compositeProperty = out.putObject("compositeModel");
                    compositeProperty.put("name", composite.get("name").asText());
                    compositeProperty.put("type", composite.get("type").asText());
                    compositeProperty.set("assetProperty", described);
                    compositeProperty.put("id", composite.get("id").asText());
                    if (composite.hasNonNull("externalId")) {
                        compositeProperty.put("externalId", composite.get("externalId").asText());
                    }
                    return out;
                }
            }
        }
        out.set("assetProperty", described);
        return out;
    }

    ObjectNode updateAssetProperty(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        requireActive(asset);
        ObjectNode property = property(asset, in.get("propertyId").asText());
        String alias = in.path("propertyAlias").asText(null);
        String previous = property.path("alias").asText(null);
        if (alias != null && !alias.isEmpty()) {
            if (!alias.equals(previous)) {
                for (ObjectNode other : store.list(account, region, ASSET)) {
                    for (ObjectNode candidate : properties(other)) {
                        if (alias.equals(candidate.path("alias").asText(null))) {
                            throw SiteWiseStore.exists("The alias " + alias + " is already used by property "
                                    + candidate.get("id").asText() + " of asset " + other.get("assetId").asText() + ".",
                                    other.get("assetId").asText(), other.get("assetArn").asText());
                        }
                    }
                }
                data.get().claimAlias(account, region, alias, asset.get("assetId").asText(),
                        property.get("id").asText());
            }
            property.put("alias", alias);
        } else {
            property.remove("alias");
        }
        if (previous != null && !previous.equals(alias)) {
            data.get().releaseAlias(account, region, asset.get("assetId").asText(), property.get("id").asText());
        }
        property.with("notification").put("state", in.path("propertyNotificationState").asText("DISABLED"));
        if (in.has("propertyUnit")) {
            if (in.hasNonNull("propertyUnit")) {
                property.put("unit", in.get("propertyUnit").asText());
            } else {
                property.remove("unit");
            }
            property.put("_unit", true);
        }
        asset.put("assetLastUpdateDate", SiteWiseStore.now());
        transition(asset, "UPDATING");
        store.put(account, region, ASSET, asset.get("assetId").asText(), asset);
        return mapper.createObjectNode();
    }

    ObjectNode describeAssetCompositeModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode asset = require(account, region, in.get("assetId").asText());
        String reference = in.get("assetCompositeModelId").asText();
        for (JsonNode composite : asset.path("assetCompositeModels")) {
            if (!reference.equals(composite.get("id").asText()) && !(reference.startsWith("externalId:")
                    && reference.substring(11).equals(composite.path("externalId").asText(null)))) {
                continue;
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("assetId", asset.get("assetId").asText());
            out.put("assetCompositeModelId", composite.get("id").asText());
            if (composite.hasNonNull("externalId")) {
                out.put("assetCompositeModelExternalId", composite.get("externalId").asText());
            }
            ArrayNode path = mapper.createArrayNode();
            for (JsonNode summary : asset.path("assetCompositeModelSummaries")) {
                if (summary.get("id").asText().equals(composite.get("id").asText())) {
                    path = (ArrayNode) summary.get("path").deepCopy();
                }
            }
            out.set("assetCompositeModelPath", path);
            out.put("assetCompositeModelName", composite.get("name").asText());
            out.put("assetCompositeModelDescription", composite.path("description").asText(""));
            out.put("assetCompositeModelType", composite.get("type").asText());
            ArrayNode properties = out.putArray("assetCompositeModelProperties");
            composite.path("properties").forEach(p -> {
                ObjectNode copy = p.deepCopy();
                copy.remove("_unit");
                properties.add(copy);
            });
            ArrayNode children = out.putArray("assetCompositeModelSummaries");
            ObjectNode model = models.get().find(account, region, asset.get("assetModelId").asText());
            if (model != null) {
                for (ObjectNode child : SiteWiseModels.composites(model)) {
                    if (composite.get("id").asText().equals(child.path("_parent").asText(null))) {
                        for (JsonNode summary : asset.path("assetCompositeModelSummaries")) {
                            if (summary.get("id").asText().equals(child.get("id").asText())) {
                                children.add(summary.deepCopy());
                            }
                        }
                    }
                }
            }
            ArrayNode actions = out.putArray("actionDefinitions");
            if (model != null) {
                for (ObjectNode definition : SiteWiseModels.composites(model)) {
                    if (definition.get("id").asText().equals(composite.get("id").asText())) {
                        definition.path("_actions").forEach(a -> actions.add(a.deepCopy()));
                    }
                }
            }
            return out;
        }
        throw notFound("Composite model " + reference + " not found in asset " + asset.get("assetId").asText() + ".");
    }
}
