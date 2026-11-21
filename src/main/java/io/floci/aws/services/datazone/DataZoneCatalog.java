package io.floci.aws.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.conflict;
import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.notFound;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.oneOf;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * The DataZone catalog: form types and asset types (DataZone's managed {@code amazon.datazone.*} types and the
 * custom ones projects create), assets and their revisions, listings published from them, inventory, listing and
 * type search, glossaries and their terms, metadata predictions, and time-series data points.
 * <p>
 * An asset belongs to its owning project, and only that project's members may create, change or search it.
 * Publishing an asset snapshots its current revision into a listing every user of the domain can search and
 * subscribe to; a new asset revision does not change the listing until it is published again.
 */
@ApplicationScoped
public class DataZoneCatalog {

    static final String FORM_TYPES = "form-types";
    static final String ASSET_TYPES = "asset-types";
    static final String ASSETS = "assets";
    static final String LISTINGS = "listings";
    static final String GLOSSARIES = "glossaries";
    static final String TERMS = "glossary-terms";
    static final String DATA_POINTS = "time-series-data-points";
    static final double MANAGED_AT = 1700000000.0;

    /** DataZone's managed form types and the fields their Smithy models declare. */
    private static final Map<String, String> MANAGED_FORMS = new LinkedHashMap<>();
    /** DataZone's managed asset types and the managed forms each carries. */
    private static final Map<String, List<String>> MANAGED_ASSETS = new LinkedHashMap<>();
    /** DataZone's managed lineage node types. */
    static final List<String> LINEAGE_TYPES = List.of("amazon.datazone.JobType", "amazon.datazone.JobRunType",
            "amazon.datazone.DatasetType");

    static {
        MANAGED_FORMS.put("amazon.datazone.GlueTableFormType", "tableName: String, tableArn: String, "
                + "catalogId: String, region: String, sourceClientType: String, columns: List, "
                + "partitionKeys: List, tableDescription: String");
        MANAGED_FORMS.put("amazon.datazone.GlueViewFormType", "viewName: String, viewArn: String, "
                + "catalogId: String, region: String, columns: List");
        MANAGED_FORMS.put("amazon.datazone.RedshiftTableFormType", "tableName: String, schemaName: String, "
                + "redshiftStorage: Structure, columns: List");
        MANAGED_FORMS.put("amazon.datazone.RedshiftViewFormType", "viewName: String, schemaName: String, "
                + "redshiftStorage: Structure, columns: List");
        MANAGED_FORMS.put("amazon.datazone.S3ObjectCollectionFormType", "arn: String, bucketArn: String, "
                + "prefix: String");
        MANAGED_FORMS.put("amazon.datazone.DataSourceReferenceFormType", "dataSourceIdentifier: String, "
                + "filterableDataSourceId: String, dataSourceRunId: String");
        MANAGED_FORMS.put("amazon.datazone.AssetCommonDetailsFormType", "customProperties: Map");
        MANAGED_FORMS.put("amazon.datazone.SubscriptionTermsFormType", "terms: List");
        MANAGED_FORMS.put("amazon.datazone.DataQualityResultFormType", "passingPercentage: Double, "
                + "evaluationsCount: Integer, evaluations: List");
        MANAGED_FORMS.put("amazon.datazone.ColumnBusinessMetadataFormType", "columnsBusinessMetadata: List");
        MANAGED_ASSETS.put("amazon.datazone.GlueTableAssetType", List.of("GlueTableForm:amazon.datazone"
                + ".GlueTableFormType", "ColumnBusinessMetadataForm:amazon.datazone.ColumnBusinessMetadataFormType",
                "DataSourceReferenceForm:amazon.datazone.DataSourceReferenceFormType",
                "AssetCommonDetailsForm:amazon.datazone.AssetCommonDetailsFormType",
                "SubscriptionTermsForm:amazon.datazone.SubscriptionTermsFormType"));
        MANAGED_ASSETS.put("amazon.datazone.GlueViewAssetType", List.of("GlueViewForm:amazon.datazone"
                + ".GlueViewFormType", "DataSourceReferenceForm:amazon.datazone.DataSourceReferenceFormType",
                "AssetCommonDetailsForm:amazon.datazone.AssetCommonDetailsFormType"));
        MANAGED_ASSETS.put("amazon.datazone.RedshiftTableAssetType", List.of("RedshiftTableForm:amazon.datazone"
                + ".RedshiftTableFormType", "DataSourceReferenceForm:amazon.datazone.DataSourceReferenceFormType",
                "AssetCommonDetailsForm:amazon.datazone.AssetCommonDetailsFormType"));
        MANAGED_ASSETS.put("amazon.datazone.RedshiftViewAssetType", List.of("RedshiftViewForm:amazon.datazone"
                + ".RedshiftViewFormType", "DataSourceReferenceForm:amazon.datazone.DataSourceReferenceFormType",
                "AssetCommonDetailsForm:amazon.datazone.AssetCommonDetailsFormType"));
        MANAGED_ASSETS.put("amazon.datazone.S3ObjectCollectionAssetType", List.of("S3ObjectCollectionForm:amazon"
                + ".datazone.S3ObjectCollectionFormType",
                "AssetCommonDetailsForm:amazon.datazone.AssetCommonDetailsFormType"));
    }

    private final DataZoneStore store;
    private final DataZoneService core;
    private final DataZoneRules rules;

    @Inject
    public DataZoneCatalog(DataZoneStore store, DataZoneService core, DataZoneRules rules) {
        this.store = store;
        this.core = core;
        this.rules = rules;
    }

    // ---------------------------------------------------------------- form types

    private ObjectNode managedForm(String domainId, String name) {
        ObjectNode form = store.object();
        form.put("domainId", domainId);
        form.put("name", name);
        form.put("revision", "1");
        String shape = name.substring(name.lastIndexOf('.') + 1);
        form.putObject("model").put("smithy", "structure " + shape + " { " + MANAGED_FORMS.get(name) + " }");
        form.put("status", "ENABLED");
        form.put("createdAt", MANAGED_AT);
        form.put("createdBy", "SYSTEM");
        form.put("description", "DataZone managed form type " + shape);
        form.putArray("imports");
        form.putObject("_").put("managed", true);
        return form;
    }

    /** A form type by name, at a revision or its latest. */
    ObjectNode formType(String region, String domainId, String name, String revision) {
        if (MANAGED_FORMS.containsKey(name)) {
            return managedForm(domainId, name);
        }
        ObjectNode form = store.get(region, domainId, FORM_TYPES, name)
                .orElseThrow(() -> notFound("Form type " + name + " was not found."));
        return revision(form, revision, "Form type " + name);
    }

    public synchronized ObjectNode createFormType(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateFormType").path("id")
                .asText();
        String name = text(input, "name", 128);
        if (name.startsWith("amazon.datazone.")) {
            throw invalid("Form type names starting with amazon.datazone. are reserved.");
        }
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateFormType");
        String smithy = text(input.path("model"), "smithy", 100000);
        if (!smithy.matches("(?s).*\\bstructure\\s+\\w+\\s*\\{.*}.*")) {
            throw invalid("The form type model must declare a Smithy structure.");
        }
        ObjectNode existing = store.get(region, domainId, FORM_TYPES, name).orElse(null);
        if (existing != null && !projectId.equals(existing.path("owningProjectId").asText())) {
            throw conflict("Form type " + name + " is owned by another project.");
        }
        ObjectNode revision = store.object();
        revision.put("domainId", domainId);
        revision.put("name", name);
        revision.put("revision", String.valueOf(existing == null ? 1 : existing.path("revision").asInt() + 1));
        revision.putObject("model").put("smithy", smithy);
        revision.put("owningProjectId", projectId);
        revision.put("originDomainId", domainId);
        revision.put("originProjectId", projectId);
        revision.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), "ENABLED"));
        revision.put("createdAt", existing == null ? now() : existing.path("createdAt").asDouble());
        revision.put("createdBy", creator);
        if (present(input, "description")) {
            revision.put("description", input.get("description").asText());
        }
        revision.putArray("imports");
        store.put(region, domainId, FORM_TYPES, name, withHistory(existing, revision));
        return store.view(revision, "domainId", "name", "revision", "description", "owningProjectId",
                "originDomainId", "originProjectId");
    }

    public synchronized ObjectNode getFormType(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetFormType").path("id")
                .asText();
        ObjectNode form = formType(region, domainId, text(input, "formTypeIdentifier", 385), optional(input,
                "revision"));
        ObjectNode view = store.view(form);
        view.remove("_");
        return view;
    }

    public synchronized ObjectNode deleteFormType(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteFormType").path("id")
                .asText();
        String name = text(input, "formTypeIdentifier", 385);
        if (MANAGED_FORMS.containsKey(name)) {
            throw invalid("Managed form types cannot be deleted.");
        }
        ObjectNode form = store.require(region, domainId, FORM_TYPES, name, "Form type");
        core.requireMember(region, domainId, form.path("owningProjectId").asText(), "DeleteFormType");
        for (ObjectNode type : store.list(region, domainId, ASSET_TYPES)) {
            for (JsonNode entry : type.path("formsOutput")) {
                if (name.equals(entry.path("typeName").asText())) {
                    throw conflict("Form type " + name + " is used by asset type " + type.path("name").asText()
                            + ".");
                }
            }
        }
        for (ObjectNode asset : store.list(region, domainId, ASSETS)) {
            for (JsonNode form2 : asset.path("formsOutput")) {
                if (name.equals(form2.path("typeName").asText())) {
                    throw conflict("Form type " + name + " is used by asset " + asset.path("id").asText() + ".");
                }
            }
        }
        store.delete(region, domainId, FORM_TYPES, name);
        return store.object();
    }

    // ---------------------------------------------------------------- asset types

    private ObjectNode managedAssetType(String domainId, String name) {
        ObjectNode type = store.object();
        type.put("domainId", domainId);
        type.put("name", name);
        type.put("revision", "1");
        type.put("description", "DataZone managed asset type " + name.substring(name.lastIndexOf('.') + 1));
        ObjectNode forms = type.putObject("formsOutput");
        for (String entry : MANAGED_ASSETS.get(name)) {
            String[] parts = entry.split(":", 2);
            forms.putObject(parts[0]).put("typeName", parts[1]).put("typeRevision", "1").put("required", false);
        }
        type.put("owningProjectId", "SYSTEM");
        type.put("createdAt", MANAGED_AT);
        type.put("createdBy", "SYSTEM");
        type.put("updatedAt", MANAGED_AT);
        type.put("updatedBy", "SYSTEM");
        type.putObject("_").put("managed", true);
        return type;
    }

    ObjectNode assetType(String region, String domainId, String name, String revision) {
        if (MANAGED_ASSETS.containsKey(name)) {
            return managedAssetType(domainId, name);
        }
        ObjectNode type = store.get(region, domainId, ASSET_TYPES, name)
                .orElseThrow(() -> notFound("Asset type " + name + " was not found."));
        return revision(type, revision, "Asset type " + name);
    }

    public synchronized ObjectNode createAssetType(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateAssetType").path("id")
                .asText();
        String name = text(input, "name", 256);
        if (name.startsWith("amazon.datazone.")) {
            throw invalid("Asset type names starting with amazon.datazone. are reserved.");
        }
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateAssetType");
        if (!input.path("formsInput").isObject()) {
            throw invalid("formsInput is required.");
        }
        ObjectNode forms = store.object();
        input.get("formsInput").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            String typeName = text(value, "typeIdentifier", 385);
            ObjectNode form = formType(region, domainId, typeName, optional(value, "typeRevision"));
            if ("DISABLED".equals(form.path("status").asText())) {
                throw invalid("Form type " + typeName + " is disabled.");
            }
            forms.putObject(entry.getKey()).put("typeName", typeName)
                    .put("typeRevision", form.path("revision").asText())
                    .put("required", value.path("required").asBoolean(false));
        });
        ObjectNode existing = store.get(region, domainId, ASSET_TYPES, name).orElse(null);
        if (existing != null && !projectId.equals(existing.path("owningProjectId").asText())) {
            throw conflict("Asset type " + name + " is owned by another project.");
        }
        double now = now();
        ObjectNode type = store.object();
        type.put("domainId", domainId);
        type.put("name", name);
        type.put("revision", String.valueOf(existing == null ? 1 : existing.path("revision").asInt() + 1));
        if (present(input, "description")) {
            type.put("description", input.get("description").asText());
        }
        type.set("formsOutput", forms);
        type.put("owningProjectId", projectId);
        type.put("originDomainId", domainId);
        type.put("originProjectId", projectId);
        type.put("createdAt", existing == null ? now : existing.path("createdAt").asDouble());
        type.put("createdBy", existing == null ? creator : existing.path("createdBy").asText());
        type.put("updatedAt", now);
        type.put("updatedBy", creator);
        store.put(region, domainId, ASSET_TYPES, name, withHistory(existing, type));
        return store.view(type);
    }

    public synchronized ObjectNode getAssetType(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetAssetType").path("id")
                .asText();
        return store.view(assetType(region, domainId, text(input, "identifier", 513), optional(input, "revision")));
    }

    public synchronized ObjectNode deleteAssetType(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteAssetType").path("id")
                .asText();
        String name = text(input, "identifier", 513);
        if (MANAGED_ASSETS.containsKey(name)) {
            throw invalid("Managed asset types cannot be deleted.");
        }
        ObjectNode type = store.require(region, domainId, ASSET_TYPES, name, "Asset type");
        core.requireMember(region, domainId, type.path("owningProjectId").asText(), "DeleteAssetType");
        for (ObjectNode asset : store.list(region, domainId, ASSETS)) {
            if (name.equals(asset.path("typeIdentifier").asText())) {
                throw conflict("Asset type " + name + " is used by asset " + asset.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, ASSET_TYPES, name);
        return store.object();
    }

    // ---------------------------------------------------------------- assets

    ObjectNode asset(String region, String domainId, String id) {
        return store.require(region, domainId, ASSETS, id, "Asset");
    }

    /** Resolves the asset's forms against its type: each form names a known form type and carries a JSON object. */
    private ArrayNode forms(String region, String domainId, ObjectNode type, JsonNode formsInput, JsonNode previous) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        if (previous != null) {
            for (JsonNode form : previous) {
                result.put(form.path("formName").asText(), ((ObjectNode) form).deepCopy());
            }
        }
        if (formsInput.isArray()) {
            for (JsonNode form : formsInput) {
                String formName = text(form, "formName", 128);
                JsonNode declared = type.path("formsOutput").path(formName);
                String typeName = present(form, "typeIdentifier") ? form.get("typeIdentifier").asText()
                        : declared.path("typeName").asText(null);
                if (typeName == null) {
                    throw invalid("Form " + formName + " is not declared by asset type " + type.path("name").asText()
                            + "; give its typeIdentifier.");
                }
                ObjectNode formType = formType(region, domainId, typeName, optional(form, "typeRevision"));
                String content = optional(form, "content");
                if (content != null) {
                    try {
                        if (!store.mapper().readTree(content).isObject()) {
                            throw invalid("The content of form " + formName + " must be a JSON object.");
                        }
                    } catch (JsonProcessingException e) {
                        throw invalid("The content of form " + formName + " is not valid JSON.");
                    }
                }
                ObjectNode output = store.object();
                output.put("formName", formName);
                output.put("typeName", typeName);
                output.put("typeRevision", formType.path("revision").asText());
                if (content != null) {
                    output.put("content", content);
                }
                result.put(formName, output);
            }
        }
        type.path("formsOutput").properties().forEach(entry -> {
            if (entry.getValue().path("required").asBoolean(false) && !result.containsKey(entry.getKey())) {
                throw invalid("Asset type " + type.path("name").asText() + " requires form " + entry.getKey() + ".");
            }
        });
        ArrayNode forms = store.array();
        result.values().forEach(forms::add);
        return forms;
    }

    public synchronized ObjectNode createAsset(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateAsset").path("id")
                .asText();
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateAsset");
        String name = text(input, "name", 256);
        ObjectNode type = assetType(region, domainId, text(input, "typeIdentifier", 513), optional(input,
                "typeRevision"));
        String external = optional(input, "externalIdentifier");
        if (external != null) {
            for (ObjectNode existing : store.list(region, domainId, ASSETS)) {
                if (external.equals(existing.path("externalIdentifier").asText())) {
                    throw conflict("An asset with external identifier " + external + " already exists.");
                }
            }
        }
        double now = now();
        String id = store.id();
        ObjectNode asset = store.object();
        asset.put("id", id);
        asset.put("name", name);
        asset.put("typeIdentifier", type.path("name").asText());
        asset.put("typeRevision", type.path("revision").asText());
        if (external != null) {
            asset.put("externalIdentifier", external);
        }
        asset.put("revision", "1");
        if (present(input, "description")) {
            asset.put("description", input.get("description").asText());
        }
        asset.put("createdAt", now);
        asset.put("createdBy", creator);
        asset.put("firstRevisionCreatedAt", now);
        asset.put("firstRevisionCreatedBy", creator);
        asset.set("glossaryTerms", glossaryTerms(region, domainId, input.path("glossaryTerms")));
        asset.put("owningProjectId", projectId);
        asset.put("domainId", domainId);
        asset.set("formsOutput", forms(region, domainId, type, input.path("formsInput"), null));
        asset.set("readOnlyFormsOutput", store.array());
        asset.set("latestTimeSeriesDataPointFormsOutput", store.array());
        asset.set("predictionConfiguration", predictionConfiguration(input));
        asset.putObject("_").putArray("revisions").add(snapshot(asset));
        store.put(region, domainId, ASSETS, id, asset);
        return assetView(region, domainId, asset);
    }

    private ObjectNode predictionConfiguration(JsonNode input) {
        ObjectNode configuration = store.object();
        configuration.putObject("businessNameGeneration").put("enabled", input.path("predictionConfiguration")
                .path("businessNameGeneration").path("enabled").asBoolean(true));
        return configuration;
    }

    private ArrayNode glossaryTerms(String region, String domainId, JsonNode terms) {
        ArrayNode result = store.array();
        if (terms.isArray()) {
            for (JsonNode term : terms) {
                ObjectNode stored = store.require(region, domainId, TERMS, term.asText(), "Glossary term");
                if (!"ENABLED".equals(stored.path("status").asText())) {
                    throw invalid("Glossary term " + term.asText() + " is disabled.");
                }
                result.add(term.asText());
            }
        }
        return result;
    }

    private ObjectNode snapshot(ObjectNode asset) {
        ObjectNode snapshot = asset.deepCopy();
        snapshot.remove("_");
        return snapshot;
    }

    /** The asset as of a revision, with its current listing and latest data points. */
    private ObjectNode assetView(String region, String domainId, ObjectNode asset) {
        ObjectNode view = store.view(asset);
        view.set("latestTimeSeriesDataPointFormsOutput", latestPoints(region, domainId, "ASSET",
                asset.path("id").asText()));
        ObjectNode listing = listingOf(region, domainId, asset.path("id").asText());
        if (listing != null) {
            view.putObject("listing").put("listingId", listing.path("id").asText())
                    .put("listingStatus", listing.path("status").asText());
        }
        return view;
    }

    public synchronized ObjectNode getAsset(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetAsset").path("id").asText();
        ObjectNode asset = asset(region, domainId, text(input, "identifier", 64));
        String revision = optional(input, "revision");
        if (revision != null && !revision.equals(asset.path("revision").asText())) {
            for (JsonNode snapshot : asset.path("_").path("revisions")) {
                if (revision.equals(snapshot.path("revision").asText())) {
                    ObjectNode view = ((ObjectNode) snapshot).deepCopy();
                    view.set("latestTimeSeriesDataPointFormsOutput", latestPoints(region, domainId, "ASSET",
                            asset.path("id").asText()));
                    return view;
                }
            }
            throw notFound("Revision " + revision + " of asset " + asset.path("id").asText() + " was not found.");
        }
        return assetView(region, domainId, asset);
    }

    public synchronized ObjectNode createAssetRevision(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateAssetRevision")
                .path("id").asText();
        ObjectNode asset = asset(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, asset.path("owningProjectId").asText(),
                "CreateAssetRevision");
        ObjectNode type = assetType(region, domainId, asset.path("typeIdentifier").asText(),
                optional(input, "typeRevision"));
        asset.put("name", text(input, "name", 256));
        if (input.has("description")) {
            asset.put("description", input.path("description").asText());
        }
        if (input.has("glossaryTerms")) {
            asset.set("glossaryTerms", glossaryTerms(region, domainId, input.get("glossaryTerms")));
        }
        asset.put("typeRevision", type.path("revision").asText());
        asset.set("formsOutput", forms(region, domainId, type, input.path("formsInput"), asset.path("formsOutput")));
        if (input.has("predictionConfiguration")) {
            asset.set("predictionConfiguration", predictionConfiguration(input));
        }
        bumpRevision(asset, editor);
        store.put(region, domainId, ASSETS, asset.path("id").asText(), asset);
        return assetView(region, domainId, asset);
    }

    void bumpRevision(ObjectNode asset, String editor) {
        asset.put("revision", String.valueOf(asset.path("revision").asInt() + 1));
        asset.put("createdAt", now());
        asset.put("createdBy", editor);
        ((ArrayNode) asset.path("_").path("revisions")).add(snapshot(asset));
    }

    public synchronized ObjectNode listAssetRevisions(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListAssetRevisions").path("id")
                .asText();
        ObjectNode asset = asset(region, domainId, text(input, "identifier", 64));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode snapshot : asset.path("_").path("revisions")) {
            items.add(store.object().put("domainId", domainId).put("id", asset.path("id").asText())
                    .put("revision", snapshot.path("revision").asText())
                    .put("createdBy", snapshot.path("createdBy").asText())
                    .put("createdAt", snapshot.path("createdAt").asDouble()));
        }
        items.sort(Comparator.comparing((ObjectNode item) -> item.path("revision").asInt()).reversed());
        return store.page(items, input, "items", item -> item.path("revision").asText());
    }

    public synchronized ObjectNode deleteAsset(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteAsset").path("id")
                .asText();
        ObjectNode asset = asset(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, asset.path("owningProjectId").asText(), "DeleteAsset");
        ObjectNode listing = listingOf(region, domainId, asset.path("id").asText());
        if (listing != null) {
            for (ObjectNode subscription : store.list(region, domainId, DataZoneSubscriptions.SUBSCRIPTIONS)) {
                if (listing.path("id").asText().equals(subscription.path("subscribedListing").path("id").asText())
                        && "APPROVED".equals(subscription.path("status").asText())) {
                    throw conflict("Asset " + asset.path("id").asText() + " has approved subscription "
                            + subscription.path("id").asText() + "; revoke it first.");
                }
            }
            store.delete(region, domainId, LISTINGS, listing.path("id").asText());
        }
        for (String kind : List.of(DATA_POINTS)) {
            for (ObjectNode point : store.list(region, domainId, kind)) {
                if (asset.path("id").asText().equals(point.path("entityId").asText())) {
                    store.delete(region, domainId, kind, point.path("id").asText());
                }
            }
        }
        store.delete(region, domainId, ASSETS, asset.path("id").asText());
        return store.object();
    }

    // ---------------------------------------------------------------- predictions

    /** Records the predictions a metadata generation run made for an asset. */
    void predict(String region, String domainId, ObjectNode asset, Set<String> types) {
        ObjectNode predictions = ((ObjectNode) asset.get("_")).putObject("predictions");
        if (types.contains("BUSINESS_NAMES")) {
            predictions.put("name", humanize(asset.path("name").asText()));
            ArrayNode columns = predictions.putArray("columns");
            for (String column : columnNames(asset)) {
                columns.addObject().put("columnIdentifier", column).put("name", humanize(column));
            }
        }
        if (types.contains("BUSINESS_DESCRIPTIONS")) {
            predictions.put("description", "Records of " + humanize(asset.path("name").asText()).toLowerCase(
                    Locale.ROOT) + ".");
        }
        if (types.contains("BUSINESS_GLOSSARY_ASSOCIATIONS")) {
            ArrayNode terms = predictions.putArray("glossaryTerms");
            String name = asset.path("name").asText().toLowerCase(Locale.ROOT);
            for (ObjectNode term : store.list(region, domainId, TERMS)) {
                if ("ENABLED".equals(term.path("status").asText())
                        && name.contains(term.path("name").asText().toLowerCase(Locale.ROOT))) {
                    terms.add(term.path("id").asText());
                }
            }
        }
        store.put(region, domainId, ASSETS, asset.path("id").asText(), asset);
    }

    private List<String> columnNames(ObjectNode asset) {
        List<String> names = new ArrayList<>();
        for (JsonNode form : asset.path("formsOutput")) {
            try {
                JsonNode content = store.mapper().readTree(form.path("content").asText("{}"));
                for (JsonNode column : content.path("columns")) {
                    names.add(column.path("columnName").asText(column.path("name").asText()));
                }
            } catch (JsonProcessingException e) {
                continue;
            }
        }
        return names;
    }

    private static String humanize(String technical) {
        String spaced = technical.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[_\\-.]+", " ").trim();
        StringBuilder result = new StringBuilder();
        for (String word : spaced.split("\\s+")) {
            if (!word.isEmpty()) {
                result.append(result.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    public synchronized ObjectNode acceptPredictions(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "AcceptPredictions")
                .path("id").asText();
        ObjectNode asset = predictionTarget(region, domainId, input, "AcceptPredictions");
        String editor = core.callerProfileId(region, domainId);
        JsonNode predictions = asset.path("_").path("predictions");
        String rule = input.path("acceptRule").path("rule").asText("ALL");
        Map<String, String> edits = new LinkedHashMap<>();
        for (JsonNode choice : input.path("acceptChoices")) {
            String target = text(choice, "predictionTarget", 256);
            edits.put(target, choice.path("editedValue").asText(null));
        }
        boolean changed = false;
        for (String target : List.of("name", "description")) {
            if (!predictions.hasNonNull(target)) {
                continue;
            }
            if (edits.containsKey(target) || "ALL".equals(rule)) {
                String value = edits.get(target) != null ? edits.get(target) : predictions.get(target).asText();
                asset.put(target, value);
                changed = true;
            }
        }
        if (predictions.path("glossaryTerms").isArray() && ("ALL".equals(rule) || edits.containsKey("glossaryTerms"))) {
            ArrayNode terms = (ArrayNode) asset.path("glossaryTerms");
            for (JsonNode term : predictions.get("glossaryTerms")) {
                boolean present = false;
                for (JsonNode existing : terms) {
                    present |= existing.asText().equals(term.asText());
                }
                if (!present) {
                    terms.add(term.asText());
                }
            }
            changed = true;
        }
        if (predictions.path("columns").isArray() && ("ALL".equals(rule) || edits.containsKey("columns"))) {
            ArrayNode columns = store.array();
            for (JsonNode column : predictions.get("columns")) {
                String edited = edits.get(column.path("columnIdentifier").asText());
                columns.addObject().put("columnIdentifier", column.path("columnIdentifier").asText())
                        .put("name", edited != null ? edited : column.path("name").asText());
            }
            ObjectNode content = store.object();
            content.set("columnsBusinessMetadata", columns);
            upsertForm(asset, "ColumnBusinessMetadataForm", "amazon.datazone.ColumnBusinessMetadataFormType",
                    content.toString());
            changed = true;
        }
        ((ObjectNode) asset.get("_")).remove("predictions");
        if (changed) {
            bumpRevision(asset, editor);
        }
        store.put(region, domainId, ASSETS, asset.path("id").asText(), asset);
        return store.object().put("domainId", domainId).put("assetId", asset.path("id").asText())
                .put("revision", asset.path("revision").asText());
    }

    public synchronized ObjectNode rejectPredictions(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "RejectPredictions")
                .path("id").asText();
        ObjectNode asset = predictionTarget(region, domainId, input, "RejectPredictions");
        String rule = input.path("rejectRule").path("rule").asText("ALL");
        ObjectNode predictions = asset.path("_").path("predictions") instanceof ObjectNode object ? object : null;
        if (predictions != null) {
            if ("ALL".equals(rule)) {
                ((ObjectNode) asset.get("_")).remove("predictions");
            } else {
                for (JsonNode choice : input.path("rejectChoices")) {
                    predictions.remove(text(choice, "predictionTarget", 256));
                }
            }
        }
        store.put(region, domainId, ASSETS, asset.path("id").asText(), asset);
        return store.object().put("domainId", domainId).put("assetId", asset.path("id").asText())
                .put("assetRevision", asset.path("revision").asText());
    }

    private ObjectNode predictionTarget(String region, String domainId, ObjectNode input, String operation) {
        ObjectNode asset = asset(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, asset.path("owningProjectId").asText(), operation);
        String revision = optional(input, "revision");
        if (revision != null && !revision.equals(asset.path("revision").asText())) {
            throw conflict("Predictions apply to the latest revision of asset " + asset.path("id").asText()
                    + ", which is " + asset.path("revision").asText() + ".");
        }
        return asset;
    }

    void upsertForm(ObjectNode asset, String formName, String typeName, String content) {
        ArrayNode forms = (ArrayNode) asset.path("formsOutput");
        for (JsonNode form : forms) {
            if (formName.equals(form.path("formName").asText())) {
                ((ObjectNode) form).put("content", content);
                return;
            }
        }
        forms.addObject().put("formName", formName).put("typeName", typeName).put("typeRevision", "1")
                .put("content", content);
    }

    // ---------------------------------------------------------------- listings

    ObjectNode listingOf(String region, String domainId, String assetId) {
        for (ObjectNode listing : store.list(region, domainId, LISTINGS)) {
            if (assetId.equals(listing.path("_").path("entityId").asText())) {
                return listing;
            }
        }
        return null;
    }

    ObjectNode listing(String region, String domainId, String id) {
        return store.require(region, domainId, LISTINGS, id, "Listing");
    }

    public synchronized ObjectNode createListingChangeSet(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateListingChangeSet")
                .path("id").asText();
        String entityType = oneOf(input, "entityType", Set.of("ASSET", "DATA_PRODUCT"), null);
        String action = oneOf(input, "action", Set.of("PUBLISH", "UNPUBLISH"), null);
        if ("DATA_PRODUCT".equals(entityType)) {
            ObjectNode product = store.require(region, domainId, DataZoneGovernance.DATA_PRODUCTS,
                    text(input, "entityIdentifier", 64), "Data product");
            String editor = core.requireMember(region, domainId, product.path("owningProjectId").asText(),
                    "CreateListingChangeSet");
            ObjectNode result = publishDataProduct(region, domainId, product, action, editor);
            return store.object().put("listingId", result.path("id").asText())
                    .put("listingRevision", result.path("listingRevision").asText())
                    .put("status", result.path("status").asText());
        }
        ObjectNode asset = asset(region, domainId, text(input, "entityIdentifier", 64));
        String editor = core.requireMember(region, domainId, asset.path("owningProjectId").asText(),
                "CreateListingChangeSet");
        ObjectNode result = publish(region, domainId, asset, optional(input, "entityRevision"), action, editor);
        return store.object().put("listingId", result.path("id").asText())
                .put("listingRevision", result.path("listingRevision").asText())
                .put("status", result.path("status").asText());
    }

    /** Publishes (or unpublishes) an asset revision as its listing. */
    ObjectNode publish(String region, String domainId, ObjectNode asset, String revision, String action,
                       String editor) {
        ObjectNode listing = listingOf(region, domainId, asset.path("id").asText());
        if ("UNPUBLISH".equals(action)) {
            if (listing == null) {
                throw notFound("Asset " + asset.path("id").asText() + " is not published.");
            }
            listing.put("status", "INACTIVE");
            listing.put("updatedAt", now());
            listing.put("updatedBy", editor);
            store.put(region, domainId, LISTINGS, listing.path("id").asText(), listing);
            return listing;
        }
        ObjectNode snapshot = asset;
        if (revision != null && !revision.equals(asset.path("revision").asText())) {
            snapshot = null;
            for (JsonNode candidate : asset.path("_").path("revisions")) {
                if (revision.equals(candidate.path("revision").asText())) {
                    snapshot = (ObjectNode) candidate;
                }
            }
            if (snapshot == null) {
                throw notFound("Revision " + revision + " of asset " + asset.path("id").asText() + " was not found.");
            }
        }
        rules.enforce(region, domainId, "CREATE_LISTING_CHANGE_SET", asset.path("owningProjectId").asText(),
                snapshot.path("typeIdentifier").asText(), false, snapshot.path("formsOutput"),
                snapshot.path("glossaryTerms"));
        double now = now();
        if (listing == null) {
            listing = store.object();
            listing.put("domainId", domainId);
            listing.put("id", store.id());
            listing.put("listingRevision", "1");
            listing.put("createdAt", now);
            listing.put("createdBy", editor);
            listing.putObject("_").put("entityId", asset.path("id").asText());
        } else {
            listing.put("listingRevision", String.valueOf(listing.path("listingRevision").asInt() + 1));
        }
        listing.put("updatedAt", now);
        listing.put("updatedBy", editor);
        listing.put("name", snapshot.path("name").asText());
        if (snapshot.hasNonNull("description")) {
            listing.put("description", snapshot.get("description").asText());
        }
        listing.put("status", "ACTIVE");
        ObjectNode item = listing.putObject("item").putObject("assetListing");
        item.put("assetId", asset.path("id").asText());
        item.put("assetRevision", snapshot.path("revision").asText());
        item.put("assetType", snapshot.path("typeIdentifier").asText());
        item.put("createdAt", now);
        ObjectNode forms = store.object();
        for (JsonNode form : snapshot.path("formsOutput")) {
            try {
                forms.set(form.path("formName").asText(), store.mapper().readTree(form.path("content").asText("{}")));
            } catch (JsonProcessingException e) {
                forms.putObject(form.path("formName").asText());
            }
        }
        item.put("forms", forms.toString());
        ArrayNode terms = item.putArray("glossaryTerms");
        for (JsonNode term : snapshot.path("glossaryTerms")) {
            store.get(region, domainId, TERMS, term.asText()).ifPresent(stored -> terms.addObject()
                    .put("name", stored.path("name").asText())
                    .put("shortDescription", stored.path("shortDescription").asText("")));
        }
        item.put("owningProjectId", asset.path("owningProjectId").asText());
        store.put(region, domainId, LISTINGS, listing.path("id").asText(), listing);
        return listing;
    }

    /** Publishes (or unpublishes) a data product's current revision, its asset items by their own listings. */
    private ObjectNode publishDataProduct(String region, String domainId, ObjectNode product, String action,
                                          String editor) {
        ObjectNode listing = listingOf(region, domainId, product.path("id").asText());
        if ("UNPUBLISH".equals(action)) {
            if (listing == null) {
                throw notFound("Data product " + product.path("id").asText() + " is not published.");
            }
            listing.put("status", "INACTIVE");
            listing.put("updatedAt", now());
            listing.put("updatedBy", editor);
            store.put(region, domainId, LISTINGS, listing.path("id").asText(), listing);
            return listing;
        }
        rules.enforce(region, domainId, "CREATE_LISTING_CHANGE_SET", product.path("owningProjectId").asText(),
                null, true, product.path("formsOutput"), product.path("glossaryTerms"));
        ArrayNode items = store.array();
        for (JsonNode item : product.path("items")) {
            ObjectNode assetListing = listingOf(region, domainId, item.path("identifier").asText());
            if (assetListing == null || !"ACTIVE".equals(assetListing.path("status").asText())) {
                assetListing = publish(region, domainId, asset(region, domainId, item.path("identifier").asText()),
                        null, "PUBLISH", editor);
            }
            ObjectNode summary = items.addObject();
            summary.put("listingId", assetListing.path("id").asText());
            summary.put("listingRevision", assetListing.path("listingRevision").asText());
            summary.set("glossaryTerms", assetListing.path("item").path("assetListing").path("glossaryTerms")
                    .deepCopy());
        }
        double now = now();
        if (listing == null) {
            listing = store.object();
            listing.put("domainId", domainId);
            listing.put("id", store.id());
            listing.put("listingRevision", "1");
            listing.put("createdAt", now);
            listing.put("createdBy", editor);
            listing.putObject("_").put("entityId", product.path("id").asText());
        } else {
            listing.put("listingRevision", String.valueOf(listing.path("listingRevision").asInt() + 1));
        }
        listing.put("updatedAt", now);
        listing.put("updatedBy", editor);
        listing.put("name", product.path("name").asText());
        if (product.hasNonNull("description")) {
            listing.put("description", product.get("description").asText());
        }
        listing.put("status", "ACTIVE");
        ObjectNode item = listing.putObject("item").putObject("dataProductListing");
        item.put("dataProductId", product.path("id").asText());
        item.put("dataProductRevision", product.path("revision").asText());
        item.put("createdAt", now);
        ObjectNode forms = store.object();
        for (JsonNode form : product.path("formsOutput")) {
            try {
                forms.set(form.path("formName").asText(), store.mapper().readTree(form.path("content").asText("{}")));
            } catch (JsonProcessingException e) {
                forms.putObject(form.path("formName").asText());
            }
        }
        item.put("forms", forms.toString());
        item.set("glossaryTerms", detailedTerms(region, domainId, product.path("glossaryTerms")));
        item.put("owningProjectId", product.path("owningProjectId").asText());
        item.set("items", items);
        store.put(region, domainId, LISTINGS, listing.path("id").asText(), listing);
        return listing;
    }

    private ArrayNode detailedTerms(String region, String domainId, JsonNode termIds) {
        ArrayNode terms = store.array();
        for (JsonNode term : termIds) {
            store.get(region, domainId, TERMS, term.asText()).ifPresent(stored -> terms.addObject()
                    .put("name", stored.path("name").asText())
                    .put("shortDescription", stored.path("shortDescription").asText("")));
        }
        return terms;
    }

    public synchronized ObjectNode getListing(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetListing").path("id")
                .asText();
        ObjectNode listing = listing(region, domainId, text(input, "identifier", 64));
        String revision = optional(input, "listingRevision");
        if (revision != null && !revision.equals(listing.path("listingRevision").asText())) {
            throw notFound("Revision " + revision + " of listing " + listing.path("id").asText() + " was not found.");
        }
        ObjectNode view = store.view(listing);
        if (view.path("item").path("assetListing") instanceof ObjectNode item) {
            item.set("latestTimeSeriesDataPointForms", latestPoints(region, domainId, "LISTING",
                    listing.path("id").asText()));
        }
        return view;
    }

    public synchronized ObjectNode deleteListing(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteListing").path("id")
                .asText();
        ObjectNode listing = listing(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, ownerOf(listing), "DeleteListing");
        for (ObjectNode subscription : store.list(region, domainId, DataZoneSubscriptions.SUBSCRIPTIONS)) {
            if (listing.path("id").asText().equals(subscription.path("subscribedListing").path("id").asText())
                    && "APPROVED".equals(subscription.path("status").asText())) {
                throw conflict("Listing " + listing.path("id").asText() + " has approved subscriptions.");
            }
        }
        store.delete(region, domainId, LISTINGS, listing.path("id").asText());
        return store.object();
    }

    /** The project that owns a listing's asset or data product. */
    static String ownerOf(JsonNode listing) {
        JsonNode item = listing.path("item");
        return item.has("dataProductListing") ? item.path("dataProductListing").path("owningProjectId").asText()
                : item.path("assetListing").path("owningProjectId").asText();
    }

    // ---------------------------------------------------------------- search

    public synchronized ObjectNode search(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "Search").path("id").asText();
        String scope = oneOf(input, "searchScope", Set.of("ASSET", "GLOSSARY", "GLOSSARY_TERM", "DATA_PRODUCT"),
                null);
        String project = optional(input, "owningProjectIdentifier");
        String profileId = core.callerProfileId(region, domainId);
        if (project != null) {
            core.requireMember(region, domainId, project, "Search");
        }
        boolean forms = additional(input, "FORMS");
        List<ObjectNode> candidates = new ArrayList<>();
        switch (scope) {
            case "ASSET" -> {
                for (ObjectNode asset : store.list(region, domainId, ASSETS)) {
                    String owner = asset.path("owningProjectId").asText();
                    if ((project != null && !project.equals(owner))
                            || (project == null && !core.isMember(region, domainId, owner, profileId))) {
                        continue;
                    }
                    ObjectNode item = store.view(asset, "domainId", "name", "typeIdentifier", "typeRevision",
                            "externalIdentifier", "description", "createdAt", "createdBy", "firstRevisionCreatedAt",
                            "firstRevisionCreatedBy", "glossaryTerms", "owningProjectId");
                    item.put("identifier", asset.path("id").asText());
                    if (forms) {
                        item.putObject("additionalAttributes").set("formsOutput",
                                asset.path("formsOutput").deepCopy());
                    }
                    candidates.add(store.object().set("assetItem", item));
                }
            }
            case "GLOSSARY" -> {
                for (ObjectNode glossary : store.list(region, domainId, GLOSSARIES)) {
                    if (project == null || project.equals(glossary.path("owningProjectId").asText())) {
                        candidates.add(store.object().set("glossaryItem", store.view(glossary)));
                    }
                }
            }
            case "GLOSSARY_TERM" -> {
                for (ObjectNode term : store.list(region, domainId, TERMS)) {
                    candidates.add(store.object().set("glossaryTermItem", store.view(term)));
                }
            }
            default -> {
                for (ObjectNode product : store.list(region, domainId, DataZoneGovernance.DATA_PRODUCTS)) {
                    String owner = product.path("owningProjectId").asText();
                    if ((project != null && !project.equals(owner))
                            || (project == null && !core.isMember(region, domainId, owner, profileId))) {
                        continue;
                    }
                    ObjectNode item = store.view(product, "domainId", "id", "name", "owningProjectId", "description",
                            "glossaryTerms", "createdAt", "createdBy", "firstRevisionCreatedAt",
                            "firstRevisionCreatedBy");
                    if (forms) {
                        item.putObject("additionalAttributes").set("formsOutput",
                                product.path("formsOutput").deepCopy());
                    }
                    candidates.add(store.object().set("dataProductItem", item));
                }
            }
        }
        List<ObjectNode> items = filterSortPage(candidates, input);
        return result(items, candidates, input);
    }

    public synchronized ObjectNode searchListings(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "SearchListings").path("id")
                .asText();
        boolean forms = additional(input, "FORMS");
        List<ObjectNode> candidates = new ArrayList<>();
        for (ObjectNode listing : store.list(region, domainId, LISTINGS)) {
            if (!"ACTIVE".equals(listing.path("status").asText())) {
                continue;
            }
            if (listing.path("item").has("dataProductListing")) {
                JsonNode product = listing.path("item").path("dataProductListing");
                ObjectNode item = store.object();
                item.put("listingId", listing.path("id").asText());
                item.put("listingRevision", listing.path("listingRevision").asText());
                item.put("name", listing.path("name").asText());
                item.put("entityId", product.path("dataProductId").asText());
                item.put("entityRevision", product.path("dataProductRevision").asText());
                if (listing.hasNonNull("description")) {
                    item.put("description", listing.get("description").asText());
                }
                item.put("createdAt", listing.path("createdAt").asDouble());
                item.put("listingCreatedBy", listing.path("createdBy").asText());
                item.put("listingUpdatedBy", listing.path("updatedBy").asText());
                item.set("glossaryTerms", product.path("glossaryTerms").deepCopy());
                item.put("owningProjectId", product.path("owningProjectId").asText());
                item.set("items", product.path("items").deepCopy());
                if (forms) {
                    item.putObject("additionalAttributes").put("forms", product.path("forms").asText());
                }
                candidates.add(store.object().set("dataProductListing", item));
                continue;
            }
            JsonNode asset = listing.path("item").path("assetListing");
            ObjectNode item = store.object();
            item.put("listingId", listing.path("id").asText());
            item.put("listingRevision", listing.path("listingRevision").asText());
            item.put("name", listing.path("name").asText());
            item.put("entityId", asset.path("assetId").asText());
            item.put("entityRevision", asset.path("assetRevision").asText());
            item.put("entityType", asset.path("assetType").asText());
            if (listing.hasNonNull("description")) {
                item.put("description", listing.get("description").asText());
            }
            item.put("createdAt", listing.path("createdAt").asDouble());
            item.put("listingCreatedBy", listing.path("createdBy").asText());
            item.put("listingUpdatedBy", listing.path("updatedBy").asText());
            item.set("glossaryTerms", asset.path("glossaryTerms").deepCopy());
            item.put("owningProjectId", asset.path("owningProjectId").asText());
            if (forms) {
                item.putObject("additionalAttributes").put("forms", asset.path("forms").asText());
            }
            candidates.add(store.object().set("assetListing", item));
        }
        List<ObjectNode> filtered = filterSortPage(candidates, input);
        ObjectNode response = result(filtered, candidates, input);
        if (input.path("aggregations").isArray() && !input.get("aggregations").isEmpty()) {
            ArrayNode aggregates = response.putArray("aggregates");
            List<ObjectNode> matching = matching(candidates, input);
            for (JsonNode aggregation : input.get("aggregations")) {
                String attribute = text(aggregation, "attribute", 128);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (ObjectNode candidate : matching) {
                    String value = attribute(candidate, attribute);
                    if (value != null) {
                        counts.merge(value, 1, Integer::sum);
                    }
                }
                ObjectNode aggregate = aggregates.addObject().put("attribute", attribute)
                        .put("displayValue", aggregation.path("displayValue").asText(attribute));
                ArrayNode values = aggregate.putArray("items");
                counts.forEach((value, count) -> values.addObject().put("value", value).put("count", count)
                        .put("displayValue", value));
            }
        }
        return response;
    }

    public synchronized ObjectNode searchTypes(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "SearchTypes").path("id")
                .asText();
        String scope = oneOf(input, "searchScope", Set.of("ASSET_TYPE", "FORM_TYPE", "LINEAGE_NODE_TYPE"), null);
        if (!input.has("managed")) {
            throw invalid("managed is required.");
        }
        boolean managed = input.get("managed").asBoolean();
        List<ObjectNode> candidates = new ArrayList<>();
        switch (scope) {
            case "ASSET_TYPE" -> {
                if (managed) {
                    MANAGED_ASSETS.keySet().forEach(name -> candidates.add(store.object().set("assetTypeItem",
                            store.view(managedAssetType(domainId, name)))));
                } else {
                    store.list(region, domainId, ASSET_TYPES).forEach(type -> candidates.add(store.object()
                            .set("assetTypeItem", store.view(type))));
                }
            }
            case "FORM_TYPE" -> {
                if (managed) {
                    MANAGED_FORMS.keySet().forEach(name -> candidates.add(store.object().set("formTypeItem",
                            store.view(managedForm(domainId, name)))));
                } else {
                    store.list(region, domainId, FORM_TYPES).forEach(type -> candidates.add(store.object()
                            .set("formTypeItem", store.view(type))));
                }
            }
            default -> {
                if (managed) {
                    for (String name : LINEAGE_TYPES) {
                        ObjectNode type = store.object().put("domainId", domainId).put("name", name)
                                .put("description", "DataZone managed lineage node type")
                                .put("createdAt", MANAGED_AT).put("createdBy", "SYSTEM").put("revision", "1");
                        type.putObject("formsOutput");
                        candidates.add(store.object().set("lineageNodeTypeItem", type));
                    }
                }
            }
        }
        return result(filterSortPage(candidates, input), candidates, input);
    }

    private static boolean additional(JsonNode input, String attribute) {
        for (JsonNode value : input.path("additionalAttributes")) {
            if (attribute.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode result(List<ObjectNode> page, List<ObjectNode> candidates, ObjectNode input) {
        ObjectNode response = store.page(page, input, "items", DataZoneCatalog::cursor);
        response.put("totalMatchCount", matching(candidates, input).size());
        return response;
    }

    private static String cursor(ObjectNode item) {
        JsonNode inner = item.elements().next();
        for (String field : List.of("identifier", "listingId", "id", "name")) {
            if (inner.hasNonNull(field)) {
                return inner.get(field).asText();
            }
        }
        return inner.toString();
    }

    private List<ObjectNode> filterSortPage(List<ObjectNode> candidates, ObjectNode input) {
        List<ObjectNode> matching = matching(candidates, input);
        JsonNode sort = input.path("sort");
        if (sort.hasNonNull("attribute")) {
            String attribute = sort.get("attribute").asText();
            Comparator<ObjectNode> comparator = Comparator.comparing(item -> {
                String value = attribute(item, attribute);
                return value == null ? "" : value;
            });
            if ("DESCENDING".equals(sort.path("order").asText())) {
                comparator = comparator.reversed();
            }
            matching.sort(comparator);
        }
        return matching;
    }

    private List<ObjectNode> matching(List<ObjectNode> candidates, ObjectNode input) {
        String text = optional(input, "searchText");
        List<String> fields = new ArrayList<>();
        input.path("searchIn").forEach(searchIn -> fields.add(searchIn.path("attribute").asText()));
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode candidate : candidates) {
            if ((text == null || text.isBlank() || textMatches(candidate, text, fields))
                    && clause(candidate, input.path("filters"))) {
                result.add(candidate);
            }
        }
        return result;
    }

    private boolean textMatches(ObjectNode candidate, String text, List<String> fields) {
        JsonNode item = candidate.elements().next();
        List<String> haystack = new ArrayList<>();
        if (fields.isEmpty()) {
            for (String field : List.of("name", "description", "externalIdentifier", "shortDescription",
                    "longDescription", "identifier", "entityId")) {
                haystack.add(item.path(field).asText(""));
            }
            haystack.add(item.path("additionalAttributes").toString());
        } else {
            for (String field : fields) {
                String value = attribute(candidate, field);
                haystack.add(value == null ? "" : value);
            }
        }
        String joined = String.join(" ", haystack).toLowerCase(Locale.ROOT);
        for (String token : text.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (joined.contains(token.replace("*", ""))) {
                return true;
            }
        }
        return false;
    }

    private boolean clause(ObjectNode candidate, JsonNode clause) {
        if (clause.isMissingNode() || clause.isNull() || clause.isEmpty()) {
            return true;
        }
        if (clause.has("and")) {
            for (JsonNode child : clause.get("and")) {
                if (!clause(candidate, child)) {
                    return false;
                }
            }
            return true;
        }
        if (clause.has("or")) {
            for (JsonNode child : clause.get("or")) {
                if (clause(candidate, child)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode filter = clause.path("filter");
        String value = attribute(candidate, text(filter, "attribute", 128));
        String operator = filter.path("operator").asText("EQ");
        if (filter.hasNonNull("intValue")) {
            if (value == null) {
                return false;
            }
            double actual;
            try {
                actual = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return false;
            }
            double expected = filter.get("intValue").asDouble();
            return switch (operator) {
                case "LE" -> actual <= expected;
                case "LT" -> actual < expected;
                case "GE" -> actual >= expected;
                case "GT" -> actual > expected;
                default -> actual == expected;
            };
        }
        String expected = filter.path("value").asText("");
        if (value == null) {
            return false;
        }
        if ("TEXT_SEARCH".equals(operator)) {
            return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        }
        return value.equals(expected) || value.contains("\"" + expected + "\"");
    }

    /** A filter or sort attribute: a field of the item, or {@code <FormName>.<field>} of one of its forms. */
    private String attribute(ObjectNode candidate, String attribute) {
        JsonNode item = candidate.elements().next();
        String field = switch (attribute) {
            case "typeName", "assetType" -> item.has("typeIdentifier") ? "typeIdentifier" : "entityType";
            case "owningProjectId", "owningProjectIdentifier" -> "owningProjectId";
            case "updatedAt" -> item.has("updatedAt") ? "updatedAt" : "createdAt";
            default -> attribute;
        };
        JsonNode value = item.get(field);
        if (value != null && !value.isNull()) {
            return value.isValueNode() ? value.asText() : value.toString();
        }
        int dot = attribute.lastIndexOf('.');
        if (dot > 0) {
            String formName = attribute.substring(0, dot);
            String member = attribute.substring(dot + 1);
            JsonNode forms = item.path("additionalAttributes").path("formsOutput");
            for (JsonNode form : forms) {
                if (formName.equals(form.path("formName").asText())) {
                    try {
                        JsonNode content = store.mapper().readTree(form.path("content").asText("{}"));
                        JsonNode found = content.get(member);
                        return found == null ? null : found.isValueNode() ? found.asText() : found.toString();
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                }
            }
            String listingForms = item.path("additionalAttributes").path("forms").asText(null);
            if (listingForms != null) {
                try {
                    JsonNode found = store.mapper().readTree(listingForms).path(formName).get(member);
                    return found == null ? null : found.isValueNode() ? found.asText() : found.toString();
                } catch (JsonProcessingException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- glossaries

    public synchronized ObjectNode createGlossary(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateGlossary").path("id")
                .asText();
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateGlossary");
        String name = text(input, "name", 256);
        for (ObjectNode existing : store.list(region, domainId, GLOSSARIES)) {
            if (name.equals(existing.path("name").asText())) {
                throw conflict("A glossary named " + name + " already exists.");
            }
        }
        double now = now();
        ObjectNode glossary = store.object();
        glossary.put("domainId", domainId);
        glossary.put("id", store.id());
        glossary.put("name", name);
        glossary.put("owningProjectId", projectId);
        if (present(input, "description")) {
            glossary.put("description", input.get("description").asText());
        }
        glossary.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), "ENABLED"));
        glossary.set("usageRestrictions", input.path("usageRestrictions").isArray()
                ? input.get("usageRestrictions").deepCopy() : store.array());
        glossary.put("createdAt", now);
        glossary.put("createdBy", creator);
        glossary.put("updatedAt", now);
        glossary.put("updatedBy", creator);
        store.put(region, domainId, GLOSSARIES, glossary.path("id").asText(), glossary);
        return store.view(glossary, "domainId", "id", "name", "owningProjectId", "description", "status",
                "usageRestrictions");
    }

    public synchronized ObjectNode getGlossary(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetGlossary").path("id")
                .asText();
        return store.view(store.require(region, domainId, GLOSSARIES, text(input, "identifier", 64), "Glossary"));
    }

    public synchronized ObjectNode updateGlossary(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateGlossary").path("id")
                .asText();
        ObjectNode glossary = store.require(region, domainId, GLOSSARIES, text(input, "identifier", 64),
                "Glossary");
        String editor = core.requireMember(region, domainId, glossary.path("owningProjectId").asText(),
                "UpdateGlossary");
        if (present(input, "name")) {
            String name = input.get("name").asText();
            for (ObjectNode existing : store.list(region, domainId, GLOSSARIES)) {
                if (name.equals(existing.path("name").asText())
                        && !existing.path("id").asText().equals(glossary.path("id").asText())) {
                    throw conflict("A glossary named " + name + " already exists.");
                }
            }
            glossary.put("name", name);
        }
        if (present(input, "description")) {
            glossary.put("description", input.get("description").asText());
        }
        if (present(input, "status")) {
            glossary.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), null));
        }
        glossary.put("updatedAt", now());
        glossary.put("updatedBy", editor);
        store.put(region, domainId, GLOSSARIES, glossary.path("id").asText(), glossary);
        return store.view(glossary, "domainId", "id", "name", "owningProjectId", "description", "status",
                "usageRestrictions");
    }

    public synchronized ObjectNode deleteGlossary(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteGlossary").path("id")
                .asText();
        ObjectNode glossary = store.require(region, domainId, GLOSSARIES, text(input, "identifier", 64),
                "Glossary");
        core.requireMember(region, domainId, glossary.path("owningProjectId").asText(), "DeleteGlossary");
        for (ObjectNode term : store.list(region, domainId, TERMS)) {
            if (glossary.path("id").asText().equals(term.path("glossaryId").asText())) {
                throw conflict("Glossary " + glossary.path("id").asText() + " still has term "
                        + term.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, GLOSSARIES, glossary.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode createGlossaryTerm(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateGlossaryTerm")
                .path("id").asText();
        ObjectNode glossary = store.require(region, domainId, GLOSSARIES, text(input, "glossaryIdentifier", 64),
                "Glossary");
        if (!"ENABLED".equals(glossary.path("status").asText())) {
            throw invalid("Glossary " + glossary.path("id").asText() + " is disabled.");
        }
        String creator = core.requireMember(region, domainId, glossary.path("owningProjectId").asText(),
                "CreateGlossaryTerm");
        String name = text(input, "name", 256);
        for (ObjectNode existing : store.list(region, domainId, TERMS)) {
            if (name.equals(existing.path("name").asText())
                    && glossary.path("id").asText().equals(existing.path("glossaryId").asText())) {
                throw conflict("Glossary " + glossary.path("id").asText() + " already has a term named " + name
                        + ".");
            }
        }
        double now = now();
        ObjectNode term = store.object();
        term.put("id", store.id());
        term.put("domainId", domainId);
        term.put("glossaryId", glossary.path("id").asText());
        term.put("name", name);
        term.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), "ENABLED"));
        for (String field : List.of("shortDescription", "longDescription")) {
            if (present(input, field)) {
                term.put(field, input.get(field).asText());
            }
        }
        if (input.has("termRelations")) {
            term.set("termRelations", relations(region, domainId, input.get("termRelations")));
        }
        term.set("usageRestrictions", glossary.path("usageRestrictions").deepCopy());
        term.put("createdAt", now);
        term.put("createdBy", creator);
        term.put("updatedAt", now);
        term.put("updatedBy", creator);
        store.put(region, domainId, TERMS, term.path("id").asText(), term);
        return termView(term);
    }

    private JsonNode relations(String region, String domainId, JsonNode relations) {
        for (String kind : List.of("isA", "classifies")) {
            for (JsonNode related : relations.path(kind)) {
                store.require(region, domainId, TERMS, related.asText(), "Glossary term");
            }
        }
        return relations.deepCopy();
    }

    private ObjectNode termView(ObjectNode term) {
        return store.view(term, "id", "domainId", "glossaryId", "name", "status", "shortDescription",
                "longDescription", "termRelations", "usageRestrictions");
    }

    public synchronized ObjectNode getGlossaryTerm(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetGlossaryTerm").path("id")
                .asText();
        return store.view(store.require(region, domainId, TERMS, text(input, "identifier", 64), "Glossary term"));
    }

    public synchronized ObjectNode updateGlossaryTerm(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateGlossaryTerm")
                .path("id").asText();
        ObjectNode term = store.require(region, domainId, TERMS, text(input, "identifier", 64), "Glossary term");
        ObjectNode glossary = store.require(region, domainId, GLOSSARIES, term.path("glossaryId").asText(),
                "Glossary");
        String editor = core.requireMember(region, domainId, glossary.path("owningProjectId").asText(),
                "UpdateGlossaryTerm");
        if (present(input, "glossaryIdentifier")) {
            String target = input.get("glossaryIdentifier").asText();
            store.require(region, domainId, GLOSSARIES, target, "Glossary");
            term.put("glossaryId", target);
        }
        if (present(input, "name")) {
            term.put("name", input.get("name").asText());
        }
        for (String field : List.of("shortDescription", "longDescription")) {
            if (present(input, field)) {
                term.put(field, input.get(field).asText());
            }
        }
        if (present(input, "status")) {
            term.put("status", oneOf(input, "status", Set.of("ENABLED", "DISABLED"), null));
        }
        if (input.has("termRelations")) {
            term.set("termRelations", relations(region, domainId, input.get("termRelations")));
        }
        term.put("updatedAt", now());
        term.put("updatedBy", editor);
        store.put(region, domainId, TERMS, term.path("id").asText(), term);
        return termView(term);
    }

    public synchronized ObjectNode deleteGlossaryTerm(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteGlossaryTerm")
                .path("id").asText();
        ObjectNode term = store.require(region, domainId, TERMS, text(input, "identifier", 64), "Glossary term");
        ObjectNode glossary = store.require(region, domainId, GLOSSARIES, term.path("glossaryId").asText(),
                "Glossary");
        core.requireMember(region, domainId, glossary.path("owningProjectId").asText(), "DeleteGlossaryTerm");
        for (ObjectNode asset : store.list(region, domainId, ASSETS)) {
            for (JsonNode used : asset.path("glossaryTerms")) {
                if (term.path("id").asText().equals(used.asText())) {
                    throw conflict("Glossary term " + term.path("id").asText() + " is used by asset "
                            + asset.path("id").asText() + ".");
                }
            }
        }
        store.delete(region, domainId, TERMS, term.path("id").asText());
        return store.object();
    }

    // ---------------------------------------------------------------- time series

    private String entity(String region, String domainId, ObjectNode input) {
        String type = oneOf(input, "entityType", Set.of("ASSET", "LISTING"), null);
        String id = text(input, "entityIdentifier", 64);
        if ("ASSET".equals(type)) {
            asset(region, domainId, id);
        } else {
            listing(region, domainId, id);
        }
        return id;
    }

    public synchronized ObjectNode postTimeSeriesDataPoints(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "PostTimeSeriesDataPoints")
                .path("id").asText();
        String entityId = entity(region, domainId, input);
        String entityType = input.get("entityType").asText();
        if (!input.path("forms").isArray() || input.get("forms").isEmpty()) {
            throw invalid("forms is required.");
        }
        ArrayNode outputs = store.array();
        for (JsonNode form : input.get("forms")) {
            String formName = text(form, "formName", 128);
            String typeName = text(form, "typeIdentifier", 385);
            ObjectNode formType = formType(region, domainId, typeName, optional(form, "typeRevision"));
            if (!form.has("timestamp")) {
                throw invalid("timestamp is required.");
            }
            String content = optional(form, "content");
            if (content != null) {
                try {
                    store.mapper().readTree(content);
                } catch (JsonProcessingException e) {
                    throw invalid("The content of form " + formName + " is not valid JSON.");
                }
            }
            ObjectNode point = store.object();
            point.put("id", store.id());
            point.put("formName", formName);
            point.put("typeIdentifier", typeName);
            point.put("typeRevision", formType.path("revision").asText());
            point.put("timestamp", timestamp(form.get("timestamp")));
            if (content != null) {
                point.put("content", content);
            }
            point.put("entityId", entityId);
            point.put("entityType", entityType);
            point.put("createdAt", now());
            store.put(region, domainId, DATA_POINTS, point.path("id").asText(), point);
            outputs.add(store.view(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "content", "id"));
        }
        ObjectNode response = store.object().put("domainId", domainId).put("entityId", entityId)
                .put("entityType", entityType);
        response.set("forms", outputs);
        return response;
    }

    private static double timestamp(JsonNode value) {
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return OffsetDateTime.parse(value.asText()).toInstant().toEpochMilli() / 1000.0;
        } catch (DateTimeParseException e) {
            throw invalid("Invalid timestamp " + value.asText() + ".");
        }
    }

    private List<ObjectNode> points(String region, String domainId, String entityType, String entityId,
                                    String formName) {
        List<ObjectNode> points = new ArrayList<>();
        for (ObjectNode point : store.list(region, domainId, DATA_POINTS)) {
            if (entityId.equals(point.path("entityId").asText()) && entityType.equals(point.path("entityType")
                    .asText()) && (formName == null || formName.equals(point.path("formName").asText()))) {
                points.add(point);
            }
        }
        points.sort(Comparator.comparing(point -> point.path("timestamp").asDouble()));
        return points;
    }

    private ObjectNode summary(ObjectNode point) {
        ObjectNode summary = store.view(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "id");
        String content = point.path("content").asText(null);
        if (content != null) {
            summary.put("contentSummary", content.length() > 20000 ? content.substring(0, 20000) : content);
        }
        return summary;
    }

    ArrayNode latestPoints(String region, String domainId, String entityType, String entityId) {
        Map<String, ObjectNode> latest = new LinkedHashMap<>();
        for (ObjectNode point : points(region, domainId, entityType, entityId, null)) {
            latest.put(point.path("formName").asText(), point);
        }
        ArrayNode result = store.array();
        latest.values().forEach(point -> result.add(summary(point)));
        return result;
    }

    public synchronized ObjectNode listTimeSeriesDataPoints(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListTimeSeriesDataPoints")
                .path("id").asText();
        String entityId = entity(region, domainId, input);
        String formName = text(input, "formName", 128);
        double started = input.path("startedAt").asDouble(Double.NEGATIVE_INFINITY);
        double ended = input.path("endedAt").asDouble(Double.POSITIVE_INFINITY);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode point : points(region, domainId, input.get("entityType").asText(), entityId, formName)) {
            double at = point.path("timestamp").asDouble();
            if (at >= started && at <= ended) {
                items.add(summary(point));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode getTimeSeriesDataPoint(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetTimeSeriesDataPoint")
                .path("id").asText();
        String entityId = entity(region, domainId, input);
        String formName = text(input, "formName", 128);
        ObjectNode point = store.require(region, domainId, DATA_POINTS, text(input, "identifier", 64),
                "Time series data point");
        if (!entityId.equals(point.path("entityId").asText()) || !formName.equals(point.path("formName").asText())) {
            throw notFound("Time series data point " + point.path("id").asText() + " was not found.");
        }
        ObjectNode response = store.object().put("domainId", domainId).put("entityId", entityId)
                .put("entityType", input.get("entityType").asText()).put("formName", formName);
        response.set("form", store.view(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "content",
                "id"));
        return response;
    }

    public synchronized ObjectNode deleteTimeSeriesDataPoints(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteTimeSeriesDataPoints")
                .path("id").asText();
        String entityId = entity(region, domainId, input);
        for (ObjectNode point : points(region, domainId, input.get("entityType").asText(), entityId,
                text(input, "formName", 128))) {
            store.delete(region, domainId, DATA_POINTS, point.path("id").asText());
        }
        return store.object();
    }

    // ---------------------------------------------------------------- revisions

    /** Stores a new revision of a type, keeping earlier ones readable. */
    private ObjectNode withHistory(ObjectNode existing, ObjectNode revision) {
        ObjectNode stored = revision.deepCopy();
        ArrayNode history = stored.putObject("_").putArray("revisions");
        if (existing != null) {
            existing.path("_").path("revisions").forEach(history::add);
        }
        history.add(revision.deepCopy());
        return stored;
    }

    private ObjectNode revision(ObjectNode stored, String revision, String label) {
        if (revision == null || revision.equals(stored.path("revision").asText())) {
            return stored;
        }
        for (JsonNode candidate : stored.path("_").path("revisions")) {
            if (revision.equals(candidate.path("revision").asText())) {
                return (ObjectNode) candidate.deepCopy();
            }
        }
        throw notFound(label + " has no revision " + revision + ".");
    }
}
