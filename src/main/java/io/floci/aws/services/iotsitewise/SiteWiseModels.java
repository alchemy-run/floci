package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.ASSET;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.ASSET_MODEL;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.conflicting;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.meta;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * Asset models: their properties (attributes, measurements, transforms and metrics), hierarchies, composite models
 * (inline, created on their own, and composed from component models), interface relationships, versions and ETags.
 * A model is CREATING, then ACTIVE; an update makes it UPDATING and, once its assets have its new definition, ACTIVE
 * again with the new version active; a delete makes it DELETING until it's gone. Transforms and metrics are checked
 * as SiteWise checks them: their expressions parse, their variables name properties of the model (or, for metrics, of
 * a hierarchy's child model), and no property depends on itself.
 */
@ApplicationScoped
public class SiteWiseModels {

    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> COMPUTED_TYPES = Set.of("DOUBLE", "STRING");

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final SiteWiseAssets assets;
    private final Instance<SiteWiseComputations> computations;

    @Inject
    public SiteWiseModels(SiteWiseStore store, SiteWiseAssets assets,
                          Instance<SiteWiseComputations> computations) {
        this.store = store;
        this.mapper = store.mapper();
        this.assets = assets;
        this.computations = computations;
    }

    // ---------------------------------------------------------------- lookups

    /** A model by ID or {@code externalId:<id>}, settled; ResourceNotFoundException when absent or gone. */
    ObjectNode require(String account, String region, String reference) {
        ObjectNode model = find(account, region, reference);
        if (model == null) {
            throw notFound("Asset model " + reference + " not found.");
        }
        return model;
    }

    ObjectNode find(String account, String region, String reference) {
        if (reference == null) {
            return null;
        }
        ObjectNode model;
        if (reference.startsWith("externalId:")) {
            String external = reference.substring("externalId:".length());
            model = store.list(account, region, ASSET_MODEL).stream()
                    .filter(m -> external.equals(m.path("assetModelExternalId").asText(null)))
                    .findFirst().orElse(null);
        } else {
            model = store.get(account, region, ASSET_MODEL, reference).orElse(null);
        }
        return model == null ? null : settle(account, region, model);
    }

    /**
     * Moves a model through its timed states: CREATING, UPDATING and PROPAGATING become ACTIVE, DELETING disappears.
     * Returns the model, or null once deleted.
     */
    ObjectNode settle(String account, String region, ObjectNode model) {
        String state = model.path("assetModelStatus").path("state").asText();
        long readyAt = meta(model).path("readyAt").asLong(0);
        if (readyAt == 0 || System.currentTimeMillis() < readyAt) {
            return model;
        }
        String id = model.get("assetModelId").asText();
        if ("DELETING".equals(state)) {
            store.delete(account, region, ASSET_MODEL, id);
            return null;
        }
        ObjectNode status = mapper.createObjectNode().put("state", "ACTIVE");
        model.set("assetModelStatus", status);
        meta(model).remove("readyAt");
        String latest = model.path("assetModelVersion").asText("1");
        meta(model).put("active", latest);
        ObjectNode snapshot = view(model);
        ((ObjectNode) meta(model).with("versions")).set(latest, snapshot);
        store.put(account, region, ASSET_MODEL, id, model);
        return model;
    }

    private void transition(ObjectNode model, String state) {
        model.set("assetModelStatus", mapper.createObjectNode().put("state", state));
        meta(model).put("readyAt", System.currentTimeMillis() + store.config().transitionMillis());
    }

    private static void requireActive(ObjectNode model) {
        String state = model.path("assetModelStatus").path("state").asText();
        if (!"ACTIVE".equals(state) && !"FAILED".equals(state)) {
            throw conflicting("Asset model " + model.get("assetModelId").asText() + " is " + state
                    + "; wait until it's ACTIVE.", model.get("assetModelId").asText(),
                    model.get("assetModelArn").asText());
        }
    }

    /** The model's composite models, including those composed and created on their own. */
    static List<ObjectNode> composites(ObjectNode model) {
        List<ObjectNode> list = new ArrayList<>();
        meta(model).path("composites").forEach(c -> list.add((ObjectNode) c));
        return list;
    }

    // ---------------------------------------------------------------- asset models

    ObjectNode createAssetModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateAssetModel", in);
        if (repeated != null) {
            return repeated;
        }
        String name = in.get("assetModelName").asText();
        List<ObjectNode> existing = store.list(account, region, ASSET_MODEL);
        if (existing.size() >= store.config().maxAssetModels()) {
            throw SiteWiseStore.limit("You can have at most " + store.config().maxAssetModels()
                    + " asset models in this Region.");
        }
        for (ObjectNode other : existing) {
            if (name.equals(other.path("assetModelName").asText())
                    && !"DELETING".equals(other.path("assetModelStatus").path("state").asText())) {
                throw SiteWiseStore.exists("An asset model named " + name + " already exists.",
                        other.get("assetModelId").asText(), other.get("assetModelArn").asText());
            }
        }
        String id = in.hasNonNull("assetModelId") ? in.get("assetModelId").asText() : SiteWiseStore.uuid();
        if (store.get(account, region, ASSET_MODEL, id).isPresent()) {
            throw SiteWiseStore.exists("An asset model with ID " + id + " already exists.", id,
                    store.arn(account, region, ASSET_MODEL, id));
        }
        String external = in.path("assetModelExternalId").asText(null);
        if (external != null && existing.stream()
                .anyMatch(m -> external.equals(m.path("assetModelExternalId").asText(null)))) {
            throw invalid("The external ID " + external + " is already in use.");
        }
        String type = in.path("assetModelType").asText("ASSET_MODEL");
        ObjectNode model = store.record();
        double now = SiteWiseStore.now();
        model.put("assetModelId", id);
        if (external != null) {
            model.put("assetModelExternalId", external);
        }
        model.put("assetModelArn", store.arn(account, region, ASSET_MODEL, id));
        model.put("assetModelName", name);
        model.put("assetModelType", type);
        model.put("assetModelDescription", in.path("assetModelDescription").asText(""));
        model.put("assetModelCreationDate", now);
        model.put("assetModelLastUpdateDate", now);
        model.put("assetModelVersion", "1");
        model.put("eTag", eTag());
        meta(model).set("tags", SiteWiseStore.tagsOf(mapper, in.get("tags")));
        define(account, region, model, in.path("assetModelProperties"), in.path("assetModelHierarchies"),
                in.path("assetModelCompositeModels"), false);
        transition(model, "CREATING");
        store.put(account, region, ASSET_MODEL, id, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", id);
        out.put("assetModelArn", model.get("assetModelArn").asText());
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        store.remember(account, region, "CreateAssetModel", in, out);
        return out;
    }

    ObjectNode describeAssetModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode view = version(model, in.path("assetModelVersion").asText(null));
        if (in.path("excludeProperties").asBoolean(false)) {
            view.putArray("assetModelProperties");
            view.withArray("assetModelCompositeModels").forEach(c -> ((ObjectNode) c).remove("properties"));
        }
        return view;
    }

    /** The model as of a version: LATEST (the default), ACTIVE or a version number. */
    ObjectNode version(ObjectNode model, String version) {
        if (version == null || "LATEST".equals(version) || version.equals(model.path("assetModelVersion").asText())) {
            return view(model);
        }
        String wanted = "ACTIVE".equals(version) ? meta(model).path("active").asText(null) : version;
        JsonNode snapshot = wanted == null ? null : meta(model).path("versions").get(wanted);
        if (snapshot == null) {
            throw notFound("Version " + version + " of asset model " + model.get("assetModelId").asText()
                    + " not found.");
        }
        ObjectNode copy = snapshot.deepCopy();
        copy.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return copy;
    }

    /** DescribeAssetModel's response for the model's latest definition. */
    ObjectNode view(ObjectNode model) {
        ObjectNode view = SiteWiseStore.view(model);
        ArrayNode inline = view.putArray("assetModelCompositeModels");
        ArrayNode summaries = view.putArray("assetModelCompositeModelSummaries");
        for (ObjectNode composite : composites(model)) {
            if (composite.path("_inline").asBoolean(false)) {
                ObjectNode copy = mapper.createObjectNode();
                copy.put("name", composite.get("name").asText());
                if (composite.hasNonNull("description")) {
                    copy.put("description", composite.get("description").asText());
                }
                copy.put("type", composite.get("type").asText());
                copy.set("properties", composite.path("properties").deepCopy());
                copy.put("id", composite.get("id").asText());
                if (composite.hasNonNull("externalId")) {
                    copy.put("externalId", composite.get("externalId").asText());
                }
                inline.add(copy);
            }
            summaries.add(summary(model, composite));
        }
        ArrayNode interfaces = view.putArray("interfaceDetails");
        meta(model).path("interfaces").fieldNames().forEachRemaining(i -> interfaces.addObject().put("id", i));
        return view;
    }

    private ObjectNode summary(ObjectNode model, ObjectNode composite) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("id", composite.get("id").asText());
        if (composite.hasNonNull("externalId")) {
            summary.put("externalId", composite.get("externalId").asText());
        }
        summary.put("name", composite.get("name").asText());
        summary.put("type", composite.get("type").asText());
        if (composite.hasNonNull("description")) {
            summary.put("description", composite.get("description").asText());
        }
        summary.set("path", compositePath(model, composite));
        return summary;
    }

    ArrayNode compositePath(ObjectNode model, ObjectNode composite) {
        List<ObjectNode> chain = new ArrayList<>();
        ObjectNode current = composite;
        while (current != null) {
            chain.add(0, current);
            String parent = current.path("_parent").asText(null);
            current = parent == null ? null : composites(model).stream()
                    .filter(c -> parent.equals(c.get("id").asText())).findFirst().orElse(null);
        }
        ArrayNode path = mapper.createArrayNode();
        path.addObject().put("id", model.get("assetModelId").asText()).put("name", model.get("assetModelName").asText());
        chain.forEach(c -> path.addObject().put("id", c.get("id").asText()).put("name", c.get("name").asText()));
        return path;
    }

    ObjectNode listAssetModels(ObjectNode in, String region) {
        String account = store.account();
        Set<String> types = new HashSet<>();
        in.path("assetModelTypes").forEach(t -> types.add(t.asText()));
        String version = in.path("assetModelVersion").asText("LATEST");
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode stored : store.list(account, region, ASSET_MODEL)) {
            ObjectNode model = settle(account, region, stored);
            if (model == null) {
                continue;
            }
            if (!types.isEmpty() && !types.contains(model.path("assetModelType").asText("ASSET_MODEL"))) {
                continue;
            }
            ObjectNode source = model;
            if ("ACTIVE".equals(version)) {
                String active = meta(model).path("active").asText(null);
                if (active == null) {
                    continue;
                }
                source = (ObjectNode) meta(model).path("versions").path(active);
                source = source.deepCopy();
                source.set("assetModelStatus", model.get("assetModelStatus"));
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("id", source.get("assetModelId").asText());
            if (source.hasNonNull("assetModelExternalId")) {
                summary.put("externalId", source.get("assetModelExternalId").asText());
            }
            summary.put("arn", source.get("assetModelArn").asText());
            summary.put("name", source.get("assetModelName").asText());
            summary.put("assetModelType", source.path("assetModelType").asText("ASSET_MODEL"));
            summary.put("description", source.path("assetModelDescription").asText(""));
            summary.put("creationDate", source.get("assetModelCreationDate").asDouble());
            summary.put("lastUpdateDate", source.get("assetModelLastUpdateDate").asDouble());
            summary.set("status", model.get("assetModelStatus").deepCopy());
            summary.put("version", source.path("assetModelVersion").asText("1"));
            summaries.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetModelSummaries");
        return out;
    }

    ObjectNode updateAssetModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        requireActive(model);
        checkPreconditions(model, in);
        String id = model.get("assetModelId").asText();
        String name = in.get("assetModelName").asText();
        for (ObjectNode other : store.list(account, region, ASSET_MODEL)) {
            if (!id.equals(other.get("assetModelId").asText()) && name.equals(other.path("assetModelName").asText())) {
                throw SiteWiseStore.exists("An asset model named " + name + " already exists.",
                        other.get("assetModelId").asText(), other.get("assetModelArn").asText());
            }
        }
        if (in.hasNonNull("assetModelExternalId")) {
            String external = in.get("assetModelExternalId").asText();
            if (model.hasNonNull("assetModelExternalId") && !external.equals(model.get("assetModelExternalId").asText())) {
                throw invalid("An asset model's external ID can't be changed once set.");
            }
            model.put("assetModelExternalId", external);
        }
        model.put("assetModelName", name);
        model.put("assetModelDescription", in.path("assetModelDescription").asText(""));
        define(account, region, model, in.path("assetModelProperties"), in.path("assetModelHierarchies"),
                in.path("assetModelCompositeModels"), true);
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, id, model);
        assets.propagate(account, region, model);
        ObjectNode out = mapper.createObjectNode();
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    /** An ETag as SiteWise writes them: 43 base64url characters. */
    static String eTag() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void bump(ObjectNode model) {
        model.put("assetModelVersion", String.valueOf(model.path("assetModelVersion").asInt(1) + 1));
        model.put("eTag", eTag());
        model.put("assetModelLastUpdateDate", SiteWiseStore.now());
    }

    /** If-Match and If-None-Match against the latest or active version's ETag. */
    private void checkPreconditions(ObjectNode model, JsonNode in) {
        String ifMatch = in.path("ifMatch").asText(null);
        String ifNoneMatch = in.path("ifNoneMatch").asText(null);
        if (ifMatch == null && ifNoneMatch == null) {
            return;
        }
        String eTag;
        if ("ACTIVE".equals(in.path("matchForVersionType").asText("LATEST"))) {
            String active = meta(model).path("active").asText(null);
            eTag = active == null ? null : meta(model).path("versions").path(active).path("eTag").asText(null);
        } else {
            eTag = model.path("eTag").asText(null);
        }
        String id = model.get("assetModelId").asText();
        String arn = model.get("assetModelArn").asText();
        if (ifMatch != null && !ifMatch.equals(eTag)) {
            throw SiteWiseStore.precondition("The ETag " + ifMatch + " doesn't match the asset model's.", id, arn);
        }
        if ("*".equals(ifNoneMatch) && eTag != null) {
            throw SiteWiseStore.precondition("The asset model already has a version of that type.", id, arn);
        }
    }

    ObjectNode deleteAssetModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        String id = model.get("assetModelId").asText();
        String arn = model.get("assetModelArn").asText();
        if ("DELETING".equals(model.path("assetModelStatus").path("state").asText())) {
            ObjectNode out = mapper.createObjectNode();
            out.put("assetModelId", id);
            out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
            return out;
        }
        requireActive(model);
        checkPreconditions(model, in);
        if (store.list(account, region, ASSET).stream()
                .anyMatch(a -> id.equals(a.path("assetModelId").asText()))) {
            throw conflicting("Asset model " + id + " still has assets; delete them first.", id, arn);
        }
        for (ObjectNode other : store.list(account, region, ASSET_MODEL)) {
            if (id.equals(other.get("assetModelId").asText())) {
                continue;
            }
            boolean parent = false;
            for (JsonNode hierarchy : other.path("assetModelHierarchies")) {
                parent |= id.equals(hierarchy.path("childAssetModelId").asText());
            }
            for (ObjectNode composite : composites(other)) {
                parent |= id.equals(composite.path("_composed").asText());
            }
            parent |= meta(other).path("interfaces").has(id);
            if (parent) {
                throw conflicting("Asset model " + id + " is used by asset model "
                        + other.get("assetModelId").asText() + ".", id, arn);
            }
        }
        transition(model, "DELETING");
        store.put(account, region, ASSET_MODEL, id, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", id);
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    // ---------------------------------------------------------------- composite models

    ObjectNode createAssetModelCompositeModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode repeated = store.repeated(account, region, "CreateAssetModelCompositeModel", in);
        if (repeated != null) {
            return repeated;
        }
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        requireActive(model);
        checkPreconditions(model, in);
        String parent = null;
        if (in.hasNonNull("parentAssetModelCompositeModelId")) {
            parent = composite(model, in.get("parentAssetModelCompositeModelId").asText()).get("id").asText();
        }
        String name = in.get("assetModelCompositeModelName").asText();
        String parentId = parent;
        if (composites(model).stream().anyMatch(c -> name.equals(c.path("name").asText())
                && Objects.equals(parentId, c.path("_parent").asText(null)))) {
            throw invalid("A composite model named " + name + " already exists at that level.");
        }
        ObjectNode composite = mapper.createObjectNode();
        String id = in.hasNonNull("assetModelCompositeModelId") ? in.get("assetModelCompositeModelId").asText()
                : SiteWiseStore.uuid();
        composite.put("id", id);
        if (in.hasNonNull("assetModelCompositeModelExternalId")) {
            composite.put("externalId", in.get("assetModelCompositeModelExternalId").asText());
        }
        composite.put("name", name);
        if (in.hasNonNull("assetModelCompositeModelDescription")) {
            composite.put("description", in.get("assetModelCompositeModelDescription").asText());
        }
        composite.put("type", in.get("assetModelCompositeModelType").asText());
        if ("AWS/L4E_ANOMALY".equals(composite.get("type").asText())) {
            composite.set("_actions", computations.get().l4eActions());
        }
        if (parent != null) {
            composite.put("_parent", parent);
        }
        Map<String, ObjectNode> owned = propertiesByName(model);
        if (in.hasNonNull("composedAssetModelId")) {
            ObjectNode component = require(account, region, in.get("composedAssetModelId").asText());
            if (!"COMPONENT_MODEL".equals(component.path("assetModelType").asText())) {
                throw invalid("Only a component model can be composed into an asset model.");
            }
            if (!"CUSTOM".equals(composite.get("type").asText())) {
                throw invalid("A composite model that composes a component model has the type CUSTOM.");
            }
            if (in.path("assetModelCompositeModelProperties").size() > 0) {
                throw invalid("A composed composite model takes its properties from the component model.");
            }
            composite.put("_composed", component.get("assetModelId").asText());
            ArrayNode properties = composite.putArray("properties");
            component.path("assetModelProperties").forEach(p -> properties.add(p.deepCopy()));
        } else {
            composite.set("properties", properties(account, region, model, in.path("assetModelCompositeModelProperties"),
                    owned, mapper.createArrayNode(), false));
        }
        ((ArrayNode) meta(model).withArray("composites")).add(composite);
        retarget(model);
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, model.get("assetModelId").asText(), model);
        assets.propagate(account, region, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.put("assetModelCompositeModelId", id);
        out.set("assetModelCompositeModelPath", compositePath(model, composite));
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        store.remember(account, region, "CreateAssetModelCompositeModel", in, out);
        return out;
    }

    /** Sets each composite's path segments on its properties. */
    private void retarget(ObjectNode model) {
        for (ObjectNode composite : composites(model)) {
            ArrayNode path = compositePath(model, composite);
            for (JsonNode property : composite.path("properties")) {
                ArrayNode propertyPath = path.deepCopy();
                propertyPath.addObject().put("id", property.get("id").asText()).put("name", property.get("name").asText());
                ((ObjectNode) property).set("path", propertyPath);
            }
        }
    }

    ObjectNode composite(ObjectNode model, String reference) {
        for (ObjectNode composite : composites(model)) {
            if (reference.equals(composite.get("id").asText()) || reference.startsWith("externalId:")
                    && reference.substring(11).equals(composite.path("externalId").asText(null))) {
                return composite;
            }
        }
        throw notFound("Composite model " + reference + " not found in asset model "
                + model.get("assetModelId").asText() + ".");
    }

    ObjectNode describeAssetModelCompositeModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode composite = composite(model, in.get("assetModelCompositeModelId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.put("assetModelCompositeModelId", composite.get("id").asText());
        if (composite.hasNonNull("externalId")) {
            out.put("assetModelCompositeModelExternalId", composite.get("externalId").asText());
        }
        out.set("assetModelCompositeModelPath", compositePath(model, composite));
        out.put("assetModelCompositeModelName", composite.get("name").asText());
        out.put("assetModelCompositeModelDescription", composite.path("description").asText(""));
        out.put("assetModelCompositeModelType", composite.get("type").asText());
        out.set("assetModelCompositeModelProperties", composite.path("properties").deepCopy());
        if (composite.hasNonNull("_composed")) {
            out.putObject("compositionDetails").putArray("compositionRelationship").addObject()
                    .put("id", composite.get("_composed").asText());
        }
        ArrayNode children = out.putArray("assetModelCompositeModelSummaries");
        for (ObjectNode child : composites(model)) {
            if (composite.get("id").asText().equals(child.path("_parent").asText(null))) {
                children.add(summary(model, child));
            }
        }
        out.set("actionDefinitions", composite.has("_actions") ? composite.get("_actions").deepCopy()
                : mapper.createArrayNode());
        return out;
    }

    ObjectNode updateAssetModelCompositeModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        requireActive(model);
        checkPreconditions(model, in);
        ObjectNode composite = composite(model, in.get("assetModelCompositeModelId").asText());
        composite.put("name", in.get("assetModelCompositeModelName").asText());
        if (in.hasNonNull("assetModelCompositeModelDescription")) {
            composite.put("description", in.get("assetModelCompositeModelDescription").asText());
        } else {
            composite.remove("description");
        }
        if (in.hasNonNull("assetModelCompositeModelExternalId")) {
            composite.put("externalId", in.get("assetModelCompositeModelExternalId").asText());
        }
        if (!composite.hasNonNull("_composed")) {
            ArrayNode existing = (ArrayNode) composite.path("properties");
            composite.set("properties", properties(account, region, model,
                    in.path("assetModelCompositeModelProperties"), propertiesByName(model), existing, true));
        } else if (in.path("assetModelCompositeModelProperties").size() > 0) {
            throw invalid("A composed composite model takes its properties from the component model.");
        }
        retarget(model);
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, model.get("assetModelId").asText(), model);
        assets.propagate(account, region, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.set("assetModelCompositeModelPath", compositePath(model, composite));
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    ObjectNode deleteAssetModelCompositeModel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        requireActive(model);
        checkPreconditions(model, in);
        ObjectNode composite = composite(model, in.get("assetModelCompositeModelId").asText());
        Set<String> doomed = new HashSet<>();
        doomed.add(composite.get("id").asText());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ObjectNode c : composites(model)) {
                if (doomed.contains(c.path("_parent").asText("")) && doomed.add(c.get("id").asText())) {
                    grew = true;
                }
            }
        }
        ArrayNode kept = mapper.createArrayNode();
        composites(model).stream().filter(c -> !doomed.contains(c.get("id").asText())).forEach(kept::add);
        meta(model).set("composites", kept);
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, model.get("assetModelId").asText(), model);
        assets.propagate(account, region, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    ObjectNode listAssetModelCompositeModels(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode view = version(model, in.path("assetModelVersion").asText(null));
        List<JsonNode> summaries = new ArrayList<>();
        view.path("assetModelCompositeModelSummaries").forEach(summaries::add);
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetModelCompositeModelSummaries");
        return out;
    }

    ObjectNode listCompositionRelationships(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode component = require(account, region, in.get("assetModelId").asText());
        String id = component.get("assetModelId").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode other : store.list(account, region, ASSET_MODEL)) {
            for (ObjectNode composite : composites(other)) {
                if (id.equals(composite.path("_composed").asText())) {
                    summaries.add(mapper.createObjectNode().put("assetModelId", other.get("assetModelId").asText())
                            .put("assetModelCompositeModelId", composite.get("id").asText())
                            .put("assetModelCompositeModelType", composite.get("type").asText()));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "compositionRelationshipSummaries");
        return out;
    }

    ObjectNode listAssetModelProperties(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode view = version(model, in.path("assetModelVersion").asText(null));
        boolean all = "ALL".equals(in.path("filter").asText("BASE"));
        Map<String, List<ObjectNode>> interfaceSummaries = new HashMap<>();
        meta(model).path("interfaces").fields().forEachRemaining(entry -> entry.getValue().path("propertyMappings")
                .forEach(mapping -> interfaceSummaries.computeIfAbsent(mapping.get("assetModelPropertyId").asText(),
                        k -> new ArrayList<>()).add(mapper.createObjectNode()
                        .put("interfaceAssetModelId", entry.getKey())
                        .put("interfaceAssetModelPropertyId", mapping.get("interfaceAssetModelPropertyId").asText()))));
        List<ObjectNode> summaries = new ArrayList<>();
        for (JsonNode property : view.path("assetModelProperties")) {
            summaries.add(propertySummary(property, null, interfaceSummaries));
        }
        if (all) {
            for (ObjectNode composite : composites(model)) {
                for (JsonNode property : composite.path("properties")) {
                    summaries.add(propertySummary(property, composite.get("id").asText(), interfaceSummaries));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "assetModelPropertySummaries");
        return out;
    }

    private ObjectNode propertySummary(JsonNode property, String composite, Map<String, List<ObjectNode>> interfaces) {
        ObjectNode summary = mapper.createObjectNode();
        for (String field : List.of("id", "externalId", "name", "dataType", "dataTypeSpec", "unit")) {
            if (property.hasNonNull(field)) {
                summary.set(field, property.get(field));
            }
        }
        summary.set("type", property.get("type"));
        if (composite != null) {
            summary.put("assetModelCompositeModelId", composite);
        }
        if (property.has("path")) {
            summary.set("path", property.get("path"));
        }
        List<ObjectNode> mapped = interfaces.get(property.get("id").asText());
        if (mapped != null) {
            ArrayNode array = summary.putArray("interfaceSummaries");
            mapped.forEach(array::add);
        }
        return summary;
    }

    // ---------------------------------------------------------------- interfaces

    ObjectNode putAssetModelInterfaceRelationship(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode iface = require(account, region, in.get("interfaceAssetModelId").asText());
        requireActive(model);
        if (!"INTERFACE".equals(iface.path("assetModelType").asText())) {
            throw invalid("Asset model " + iface.get("assetModelId").asText() + " isn't an interface.");
        }
        if ("INTERFACE".equals(model.path("assetModelType").asText())) {
            throw invalid("An interface can't implement another interface.");
        }
        JsonNode configuration = in.get("propertyMappingConfiguration");
        boolean byName = configuration.path("matchByPropertyName").asBoolean(false);
        boolean create = configuration.path("createMissingProperty").asBoolean(false);
        Map<String, String> overrides = new LinkedHashMap<>();
        configuration.path("overrides").forEach(o -> overrides.put(
                resolveProperty(iface, o.get("interfaceAssetModelPropertyId").asText()),
                resolveProperty(model, o.get("assetModelPropertyId").asText())));
        ArrayNode propertyMappings = mapper.createArrayNode();
        ArrayNode modelProperties = (ArrayNode) model.withArray("assetModelProperties");
        for (JsonNode property : iface.path("assetModelProperties")) {
            String interfaceProperty = property.get("id").asText();
            String target = overrides.get(interfaceProperty);
            if (target == null && byName) {
                for (JsonNode own : modelProperties) {
                    if (own.get("name").asText().equals(property.get("name").asText())) {
                        target = own.get("id").asText();
                    }
                }
            }
            if (target == null && create) {
                ObjectNode copy = property.deepCopy();
                copy.put("id", SiteWiseStore.uuid());
                copy.remove("externalId");
                copy.remove("path");
                modelProperties.add(copy);
                target = copy.get("id").asText();
            }
            if (target == null) {
                throw invalid("Interface property " + property.get("name").asText()
                        + " isn't mapped to a property of the asset model.");
            }
            ObjectNode targetProperty = null;
            for (JsonNode own : modelProperties) {
                if (own.get("id").asText().equals(target)) {
                    targetProperty = (ObjectNode) own;
                }
            }
            if (targetProperty == null || !targetProperty.get("dataType").equals(property.get("dataType"))) {
                throw invalid("Interface property " + property.get("name").asText()
                        + " maps to a property of a different data type.");
            }
            propertyMappings.addObject().put("assetModelPropertyId", target)
                    .put("interfaceAssetModelPropertyId", interfaceProperty);
        }
        ArrayNode hierarchyMappings = mapper.createArrayNode();
        for (JsonNode hierarchy : iface.path("assetModelHierarchies")) {
            for (JsonNode own : model.path("assetModelHierarchies")) {
                if (own.get("name").asText().equals(hierarchy.get("name").asText())) {
                    hierarchyMappings.addObject().put("assetModelHierarchyId", own.get("id").asText())
                            .put("interfaceAssetModelHierarchyId", hierarchy.get("id").asText());
                }
            }
        }
        ObjectNode relationship = mapper.createObjectNode();
        relationship.set("propertyMappings", propertyMappings);
        relationship.set("hierarchyMappings", hierarchyMappings);
        ((ObjectNode) meta(model).with("interfaces")).set(iface.get("assetModelId").asText(), relationship);
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, model.get("assetModelId").asText(), model);
        assets.propagate(account, region, model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.put("interfaceAssetModelId", iface.get("assetModelId").asText());
        out.put("assetModelArn", model.get("assetModelArn").asText());
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    private String resolveProperty(ObjectNode model, String reference) {
        for (JsonNode property : model.path("assetModelProperties")) {
            if (reference.equals(property.get("id").asText()) || reference.startsWith("externalId:")
                    && reference.substring(11).equals(property.path("externalId").asText(null))) {
                return property.get("id").asText();
            }
        }
        throw notFound("Property " + reference + " not found in asset model " + model.get("assetModelId").asText()
                + ".");
    }

    ObjectNode describeAssetModelInterfaceRelationship(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode iface = require(account, region, in.get("interfaceAssetModelId").asText());
        JsonNode relationship = meta(model).path("interfaces").get(iface.get("assetModelId").asText());
        if (relationship == null) {
            throw notFound("Asset model " + model.get("assetModelId").asText() + " doesn't implement interface "
                    + iface.get("assetModelId").asText() + ".");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.put("interfaceAssetModelId", iface.get("assetModelId").asText());
        out.set("propertyMappings", relationship.get("propertyMappings").deepCopy());
        out.set("hierarchyMappings", relationship.get("hierarchyMappings").deepCopy());
        return out;
    }

    ObjectNode deleteAssetModelInterfaceRelationship(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode model = require(account, region, in.get("assetModelId").asText());
        ObjectNode iface = require(account, region, in.get("interfaceAssetModelId").asText());
        requireActive(model);
        if (!meta(model).path("interfaces").has(iface.get("assetModelId").asText())) {
            throw notFound("Asset model " + model.get("assetModelId").asText() + " doesn't implement interface "
                    + iface.get("assetModelId").asText() + ".");
        }
        ((ObjectNode) meta(model).get("interfaces")).remove(iface.get("assetModelId").asText());
        bump(model);
        transition(model, "UPDATING");
        store.put(account, region, ASSET_MODEL, model.get("assetModelId").asText(), model);
        ObjectNode out = mapper.createObjectNode();
        out.put("assetModelId", model.get("assetModelId").asText());
        out.put("interfaceAssetModelId", iface.get("assetModelId").asText());
        out.put("assetModelArn", model.get("assetModelArn").asText());
        out.set("assetModelStatus", model.get("assetModelStatus").deepCopy());
        return out;
    }

    ObjectNode listInterfaceRelationships(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode iface = require(account, region, in.get("interfaceAssetModelId").asText());
        String id = iface.get("assetModelId").asText();
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode model : store.list(account, region, ASSET_MODEL)) {
            if (meta(model).path("interfaces").has(id)) {
                summaries.add(mapper.createObjectNode().put("id", model.get("assetModelId").asText()));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "interfaceRelationshipSummaries");
        return out;
    }

    // ---------------------------------------------------------------- definitions

    private static Map<String, ObjectNode> propertiesByName(ObjectNode model) {
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        model.path("assetModelProperties").forEach(p -> byName.put(p.get("name").asText(), (ObjectNode) p));
        return byName;
    }

    /**
     * Sets a model's properties, hierarchies and inline composite models from a create or update request. On an
     * update, entries with an ID keep it and must exist; entries without one get a new ID.
     */
    private void define(String account, String region, ObjectNode model, JsonNode properties, JsonNode hierarchies,
                        JsonNode compositeModels, boolean update) {
        String type = model.path("assetModelType").asText("ASSET_MODEL");
        ArrayNode existingHierarchies = update ? (ArrayNode) model.withArray("assetModelHierarchies")
                : mapper.createArrayNode();
        ArrayNode newHierarchies = mapper.createArrayNode();
        Set<String> hierarchyNames = new HashSet<>();
        for (JsonNode hierarchy : hierarchies) {
            if ("COMPONENT_MODEL".equals(type)) {
                throw invalid("A component model can't have hierarchies.");
            }
            String name = hierarchy.get("name").asText();
            if (!hierarchyNames.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("The hierarchy name " + name + " is used more than once.");
            }
            ObjectNode child = require(account, region, hierarchy.get("childAssetModelId").asText());
            if ("COMPONENT_MODEL".equals(child.path("assetModelType").asText())) {
                throw invalid("A hierarchy's child model can't be a component model.");
            }
            ObjectNode entry = mapper.createObjectNode();
            entry.put("id", existingId(existingHierarchies, hierarchy, update, "hierarchy"));
            if (hierarchy.hasNonNull("externalId")) {
                entry.put("externalId", hierarchy.get("externalId").asText());
            }
            entry.put("name", name);
            entry.put("childAssetModelId", child.get("assetModelId").asText());
            newHierarchies.add(entry);
        }
        if (newHierarchies.size() > 10) {
            throw SiteWiseStore.limit("An asset model can have at most 10 hierarchies.");
        }
        model.set("assetModelHierarchies", newHierarchies);
        ArrayNode existingProperties = update ? (ArrayNode) model.withArray("assetModelProperties")
                : mapper.createArrayNode();
        model.set("assetModelProperties", properties(account, region, model, properties, new LinkedHashMap<>(),
                existingProperties, update));

        ArrayNode composites = mapper.createArrayNode();
        Map<String, ObjectNode> previous = new HashMap<>();
        for (ObjectNode composite : composites(model)) {
            if (composite.path("_inline").asBoolean(false)) {
                previous.put(composite.get("id").asText(), composite);
            } else {
                composites.add(composite);
            }
        }
        for (JsonNode definition : compositeModels) {
            ObjectNode composite = mapper.createObjectNode();
            String id = definition.hasNonNull("id") ? definition.get("id").asText() : SiteWiseStore.uuid();
            ObjectNode before = previous.get(id);
            composite.put("id", id);
            if (definition.hasNonNull("externalId")) {
                composite.put("externalId", definition.get("externalId").asText());
            }
            composite.put("name", definition.get("name").asText());
            if (definition.hasNonNull("description")) {
                composite.put("description", definition.get("description").asText());
            }
            String compositeType = definition.get("type").asText();
            composite.put("type", compositeType);
            composite.put("_inline", true);
            if ("AWS/L4E_ANOMALY".equals(compositeType)) {
                composite.set("_actions", before != null && before.has("_actions") ? before.get("_actions")
                        : computations.get().l4eActions());
            }
            ArrayNode compositeProperties = properties(account, region, model, definition.path("properties"),
                    propertiesByName(model), before == null ? mapper.createArrayNode()
                            : (ArrayNode) before.withArray("properties"), update && before != null);
            if ("AWS/ALARM".equals(compositeType)) {
                checkAlarm(compositeProperties);
            }
            composite.set("properties", compositeProperties);
            composites.add(composite);
        }
        meta(model).set("composites", composites);
        retarget(model);
        checkCycles(model);
    }

    private String existingId(ArrayNode existing, JsonNode definition, boolean update, String what) {
        if (!definition.hasNonNull("id")) {
            return SiteWiseStore.uuid();
        }
        String id = definition.get("id").asText();
        if (update) {
            for (JsonNode entry : existing) {
                if (id.equals(entry.get("id").asText()) || id.startsWith("externalId:")
                        && id.substring(11).equals(entry.path("externalId").asText(null))) {
                    return entry.get("id").asText();
                }
            }
            if (!UUID.matcher(id).matches()) {
                throw invalid("The " + what + " ID " + id + " isn't a UUID.");
            }
            return id;
        }
        if (!UUID.matcher(id).matches()) {
            throw invalid("The " + what + " ID " + id + " isn't a UUID.");
        }
        return id;
    }

    private static void checkAlarm(ArrayNode properties) {
        Set<String> names = new HashSet<>();
        properties.forEach(p -> names.add(p.get("name").asText()));
        if (!names.contains("AWS/ALARM_TYPE") || !names.contains("AWS/ALARM_STATE")) {
            throw invalid("An AWS/ALARM composite model has the properties AWS/ALARM_TYPE and AWS/ALARM_STATE.");
        }
    }

    /**
     * Checks and normalizes property definitions: unique names, data types the property type allows, default values
     * of the property's type, and transforms and metrics whose expressions parse and whose variables resolve, with
     * names resolved to IDs as SiteWise stores them.
     */
    private ArrayNode properties(String account, String region, ObjectNode model, JsonNode definitions,
                                 Map<String, ObjectNode> outer, ArrayNode existing, boolean update) {
        ArrayNode result = mapper.createArrayNode();
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Map<String, ObjectNode> byName = new LinkedHashMap<>(outer);
        for (JsonNode definition : definitions) {
            String name = definition.get("name").asText();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("The property name " + name + " is used more than once.");
            }
            ObjectNode property = mapper.createObjectNode();
            String id = existingId(existing, definition, update, "property");
            if (!ids.add(id)) {
                throw invalid("The property ID " + id + " is used more than once.");
            }
            property.put("id", id);
            if (definition.hasNonNull("externalId")) {
                property.put("externalId", definition.get("externalId").asText());
            }
            property.put("name", name);
            String dataType = definition.get("dataType").asText();
            property.put("dataType", dataType);
            if (definition.hasNonNull("dataTypeSpec")) {
                property.put("dataTypeSpec", definition.get("dataTypeSpec").asText());
            } else if ("STRUCT".equals(dataType)) {
                throw invalid("Property " + name + " has the data type STRUCT, which needs a dataTypeSpec.");
            }
            if (definition.hasNonNull("unit")) {
                property.put("unit", definition.get("unit").asText());
            }
            JsonNode type = definition.get("type");
            int kinds = 0;
            for (String kind : List.of("attribute", "measurement", "transform", "metric")) {
                kinds += type.has(kind) ? 1 : 0;
            }
            if (kinds != 1) {
                throw invalid("Property " + name + " has exactly one of attribute, measurement, transform or metric.");
            }
            if (type.has("attribute") && type.get("attribute").hasNonNull("defaultValue")) {
                checkValue(name, dataType, type.get("attribute").get("defaultValue").asText());
            }
            if ((type.has("transform") || type.has("metric")) && !COMPUTED_TYPES.contains(dataType)) {
                throw invalid("Property " + name + " is computed, so its data type is DOUBLE or STRING.");
            }
            property.set("type", type.deepCopy());
            if (definition.has("path")) {
                property.set("path", definition.get("path").deepCopy());
            }
            byName.put(name, property);
            result.add(property);
        }
        if (result.size() > store.config().maxPropertiesPerModel()) {
            throw SiteWiseStore.limit("An asset model can have at most " + store.config().maxPropertiesPerModel()
                    + " properties.");
        }
        for (JsonNode node : result) {
            ObjectNode property = (ObjectNode) node;
            JsonNode type = property.get("type");
            if (type.has("transform")) {
                expression(account, region, model, property, (ObjectNode) type.get("transform"), byName, false);
            } else if (type.has("metric")) {
                ObjectNode metric = (ObjectNode) type.get("metric");
                SiteWiseWindows.parse(metric.path("window").path("tumbling"));
                if (metric.hasNonNull("expression")) {
                    expression(account, region, model, property, metric, byName, true);
                }
            }
        }
        return result;
    }

    private void expression(String account, String region, ObjectNode model, ObjectNode property, ObjectNode formula,
                            Map<String, ObjectNode> byName, boolean metric) {
        String name = property.get("name").asText();
        String expression = formula.path("expression").asText("");
        SiteWiseFormula.Node node;
        try {
            node = SiteWiseFormula.parse(expression);
        } catch (IllegalArgumentException e) {
            throw invalid("The expression of property " + name + " isn't valid: " + e.getMessage());
        }
        if (SiteWiseFormula.functions(expression).size() > 10) {
            throw invalid("The expression of property " + name + " calls more than 10 functions.");
        }
        if (!metric && SiteWiseFormula.usesAggregation(expression)) {
            throw invalid("The transform " + name + " can't use aggregation or temporal functions.");
        }
        if (metric && SiteWiseFormula.functions(expression).contains("pretrigger")) {
            throw invalid("The metric " + name + " can't use pretrigger.");
        }
        Set<String> declared = new HashSet<>();
        ArrayNode variables = (ArrayNode) formula.withArray("variables");
        for (JsonNode variable : variables) {
            String variableName = variable.get("name").asText();
            if (!variableName.matches("[a-z][a-z0-9_]*")) {
                throw invalid("The variable name " + variableName + " must start with a lowercase letter and use only"
                        + " lowercase letters, digits and underscores.");
            }
            declared.add(variableName);
            ObjectNode value = (ObjectNode) variable.get("value");
            if (value.hasNonNull("hierarchyId")) {
                if (!metric) {
                    throw invalid("The transform " + name + " can't read child assets' properties.");
                }
                JsonNode hierarchy = hierarchy(model, value.get("hierarchyId").asText());
                value.put("hierarchyId", hierarchy.get("id").asText());
                ObjectNode child = require(account, region, hierarchy.get("childAssetModelId").asText());
                JsonNode childProperty = null;
                for (JsonNode candidate : child.path("assetModelProperties")) {
                    if (candidate.get("id").asText().equals(value.path("propertyId").asText())) {
                        childProperty = candidate;
                    }
                }
                if (childProperty == null) {
                    throw invalid("Variable " + variableName + " names property " + value.path("propertyId").asText()
                            + ", which the child model " + child.get("assetModelId").asText() + " doesn't have.");
                }
                continue;
            }
            ObjectNode target = null;
            String reference = value.path("propertyId").asText(null);
            if (reference == null && value.has("propertyPath")) {
                JsonNode last = value.get("propertyPath").get(value.get("propertyPath").size() - 1);
                reference = last.hasNonNull("id") ? last.get("id").asText() : last.path("name").asText(null);
            }
            for (ObjectNode candidate : byName.values()) {
                if (candidate.get("id").asText().equals(reference) || candidate.get("name").asText().equals(reference)
                        || reference != null && reference.startsWith("externalId:")
                        && reference.substring(11).equals(candidate.path("externalId").asText(null))) {
                    target = candidate;
                }
            }
            if (target == null) {
                throw invalid("Variable " + variableName + " of property " + name + " names property " + reference
                        + ", which the asset model doesn't have.");
            }
            if (target == property) {
                throw invalid("Property " + name + " can't use itself as a variable.");
            }
            JsonNode targetType = target.get("type");
            if (!metric && targetType.has("metric")) {
                throw invalid("The transform " + name + " can't use the metric " + target.get("name").asText() + ".");
            }
            if (metric && targetType.has("metric")) {
                String window = formula.path("window").path("tumbling").path("interval").asText();
                String other = targetType.get("metric").path("window").path("tumbling").path("interval").asText();
                if (!SiteWiseWindows.parse(formula.path("window").path("tumbling")).equals(
                        SiteWiseWindows.parse(targetType.get("metric").path("window").path("tumbling")))) {
                    throw invalid("The metric " + name + " (" + window + ") can only use metrics with the same window, "
                            + "not " + target.get("name").asText() + " (" + other + ").");
                }
            }
            value.remove("propertyPath");
            value.put("propertyId", target.get("id").asText());
        }
        for (String used : SiteWiseFormula.variables(node)) {
            if (!declared.contains(used)) {
                throw invalid("The expression of property " + name + " uses the variable " + used
                        + ", which isn't defined.");
            }
        }
    }

    static JsonNode hierarchy(ObjectNode model, String reference) {
        for (JsonNode hierarchy : model.path("assetModelHierarchies")) {
            if (reference.equals(hierarchy.get("id").asText()) || reference.equals(hierarchy.get("name").asText())
                    || reference.startsWith("externalId:")
                    && reference.substring(11).equals(hierarchy.path("externalId").asText(null))) {
                return hierarchy;
            }
        }
        throw invalid("Hierarchy " + reference + " not found in asset model " + model.get("assetModelId").asText()
                + ".");
    }

    /** A transform or metric that depends, through other properties of the model, on itself. */
    private void checkCycles(ObjectNode model) {
        Map<String, List<String>> edges = new HashMap<>();
        List<JsonNode> all = new ArrayList<>();
        model.path("assetModelProperties").forEach(all::add);
        composites(model).forEach(c -> c.path("properties").forEach(all::add));
        for (JsonNode property : all) {
            JsonNode type = property.get("type");
            JsonNode formula = type.has("transform") ? type.get("transform") : type.get("metric");
            List<String> targets = new ArrayList<>();
            if (formula != null) {
                formula.path("variables").forEach(v -> {
                    if (!v.path("value").hasNonNull("hierarchyId")) {
                        targets.add(v.path("value").path("propertyId").asText());
                    }
                });
            }
            edges.put(property.get("id").asText(), targets);
        }
        Map<String, Integer> marks = new HashMap<>();
        for (String id : edges.keySet()) {
            visit(id, edges, marks);
        }
    }

    private static void visit(String id, Map<String, List<String>> edges, Map<String, Integer> marks) {
        int mark = marks.getOrDefault(id, 0);
        if (mark == 2) {
            return;
        }
        if (mark == 1) {
            throw invalid("The property " + id + " depends on itself.");
        }
        marks.put(id, 1);
        for (String next : edges.getOrDefault(id, List.of())) {
            visit(next, edges, marks);
        }
        marks.put(id, 2);
    }

    /** A value of the given data type, as an attribute's default or value is written. */
    static void checkValue(String name, String dataType, String value) {
        boolean ok = switch (dataType) {
            case "INTEGER" -> value.matches("-?\\d+") && fitsInt(value);
            case "DOUBLE" -> value.matches("-?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
            case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            default -> true;
        };
        if (!ok) {
            throw invalid("The value " + value + " of property " + name + " isn't a valid " + dataType + ".");
        }
    }

    private static boolean fitsInt(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Every property of the model, its own and its composite models'. */
    static List<ObjectNode> allProperties(ObjectNode model) {
        List<ObjectNode> all = new ArrayList<>();
        model.path("assetModelProperties").forEach(p -> all.add((ObjectNode) p));
        composites(model).forEach(c -> c.path("properties").forEach(p -> all.add((ObjectNode) p)));
        return all;
    }
}
