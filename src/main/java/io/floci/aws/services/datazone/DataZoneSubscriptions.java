package io.floci.aws.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.lakeformation.LakeFormationService;
import io.floci.aws.services.lakeformation.model.GrantPermissionsRequest;
import io.floci.aws.services.lakeformation.model.RevokePermissionsRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * DataZone subscriptions: a project requests a published listing, a member of the listing's owning project accepts
 * or rejects the request, and an accepted request becomes an approved subscription. Approval grants the asset to
 * every environment of the subscribing project whose subscription targets apply to its type: for a Glue table,
 * Lake Formation {@code SELECT} and {@code DESCRIBE} on the table to the target's authorized principals. Cancelling
 * (by the subscriber) or revoking (by the owner) removes those grants unless the owner asks to retain them.
 * Each step tells the other project through a notification and publishes a {@code aws.datazone} event to the
 * default event bus.
 */
@ApplicationScoped
public class DataZoneSubscriptions {

    private static final Logger LOG = Logger.getLogger(DataZoneSubscriptions.class);
    static final String REQUESTS = "subscription-requests";
    static final String SUBSCRIPTIONS = "subscriptions";
    static final String GRANTS = "subscription-grants";
    private static final List<String> TABLE_PERMISSIONS = List.of("SELECT", "DESCRIBE");

    private final DataZoneStore store;
    private final DataZoneService core;
    private final DataZoneCatalog catalog;
    private final DataZoneEnvironments environments;
    private final DataZoneRules rules;
    private final Instance<LakeFormationService> lakeFormation;
    private final Instance<EventBridgeService> events;

    @Inject
    public DataZoneSubscriptions(DataZoneStore store, DataZoneService core, DataZoneCatalog catalog,
                                 DataZoneEnvironments environments, DataZoneRules rules,
                                 Instance<LakeFormationService> lakeFormation, Instance<EventBridgeService> events) {
        this.store = store;
        this.core = core;
        this.catalog = catalog;
        this.environments = environments;
        this.rules = rules;
        this.lakeFormation = lakeFormation;
        this.events = events;
    }

    // ---------------------------------------------------------------- requests

    public synchronized ObjectNode createSubscriptionRequest(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateSubscriptionRequest")
                .path("id").asText();
        String reason = text(input, "requestReason", 4096);
        JsonNode principals = input.path("subscribedPrincipals");
        JsonNode listings = input.path("subscribedListings");
        if (!principals.isArray() || principals.size() != 1) {
            throw invalid("subscribedPrincipals must name exactly one principal.");
        }
        if (!listings.isArray() || listings.size() != 1) {
            throw invalid("subscribedListings must name exactly one listing.");
        }
        String projectId = text(principals.get(0).path("project"), "identifier", 64);
        String requester = core.requireMember(region, domainId, projectId, "CreateSubscriptionRequest");
        ObjectNode project = core.project(region, domainId, projectId);
        ObjectNode listing = catalog.listing(region, domainId, text(listings.get(0), "identifier", 64));
        if (!"ACTIVE".equals(listing.path("status").asText())) {
            throw invalid("Listing " + listing.path("id").asText() + " is not published.");
        }
        String ownerProject = DataZoneCatalog.ownerOf(listing);
        if (ownerProject.equals(projectId)) {
            throw invalid("A project cannot subscribe to its own listing.");
        }
        for (ObjectNode existing : store.list(region, domainId, REQUESTS)) {
            if ("PENDING".equals(existing.path("status").asText())
                    && projectId.equals(existing.path("_").path("projectId").asText())
                    && listing.path("id").asText().equals(existing.path("_").path("listingId").asText())) {
                throw conflict("Project " + projectId + " already has pending subscription request "
                        + existing.path("id").asText() + " for listing " + listing.path("id").asText() + ".");
            }
        }
        ArrayNode metadataForms = store.array();
        if (input.path("metadataForms").isArray()) {
            for (JsonNode form : input.get("metadataForms")) {
                String typeName = text(form, "typeIdentifier", 385);
                ObjectNode formType = catalog.formType(region, domainId, typeName, optional(form, "typeRevision"));
                metadataForms.addObject().put("formName", text(form, "formName", 128)).put("typeName", typeName)
                        .put("typeRevision", formType.path("revision").asText())
                        .put("content", form.path("content").asText("{}"));
            }
        }
        boolean product = listing.path("item").has("dataProductListing");
        rules.enforce(region, domainId, "CREATE_SUBSCRIPTION_REQUEST", ownerProject, product ? null
                : listing.path("item").path("assetListing").path("assetType").asText(), product, metadataForms,
                store.array());
        double now = now();
        String id = store.id();
        ObjectNode request = store.object();
        request.put("id", id);
        request.put("createdBy", requester);
        request.put("domainId", domainId);
        request.put("status", "PENDING");
        request.put("createdAt", now);
        request.put("updatedAt", now);
        request.put("requestReason", reason);
        request.putArray("subscribedPrincipals").add(principal(project));
        request.putArray("subscribedListings").add(subscribedListing(region, domainId, listing));
        ObjectNode existing = subscriptionFor(region, domainId, projectId, listing.path("id").asText());
        if (existing != null) {
            request.put("existingSubscriptionId", existing.path("id").asText());
        }
        if (input.path("metadataForms").isArray()) {
            request.set("metadataForms", metadataForms);
        }
        request.putObject("_").put("projectId", projectId).put("listingId", listing.path("id").asText())
                .put("ownerProjectId", ownerProject);
        store.put(region, domainId, REQUESTS, id, request);
        core.notify(region, domainId, "TASK", ownerProject, "PROJECT_OWNER", "SUBSCRIPTION_REQUEST_CREATED",
                "Project " + project.path("name").asText() + " requested a subscription to "
                        + listing.path("name").asText() + ".", Map.of("subscriptionRequestId", id));
        publish(region, domainId, "Subscription Request Created", id, Map.of("subscriptionRequestId", id,
                "listingId", listing.path("id").asText(), "projectId", projectId));
        return requestView(request);
    }

    private ObjectNode principal(ObjectNode project) {
        ObjectNode principal = store.object();
        principal.putObject("project").put("id", project.path("id").asText())
                .put("name", project.path("name").asText());
        return principal;
    }

    private ObjectNode subscribedListing(String region, String domainId, ObjectNode listing) {
        ObjectNode result = store.object();
        result.put("id", listing.path("id").asText());
        result.put("revision", listing.path("listingRevision").asText());
        result.put("name", listing.path("name").asText());
        result.put("description", listing.path("description").asText(""));
        String ownerProject = DataZoneCatalog.ownerOf(listing);
        result.put("ownerProjectId", ownerProject);
        store.get(region, domainId, DataZoneService.PROJECTS, ownerProject)
                .ifPresent(project -> result.put("ownerProjectName", project.path("name").asText()));
        if (listing.path("item").has("dataProductListing")) {
            JsonNode product = listing.path("item").path("dataProductListing");
            ObjectNode item = result.putObject("item").putObject("productListing");
            item.put("entityId", product.path("dataProductId").asText());
            item.put("entityRevision", product.path("dataProductRevision").asText());
            item.set("glossaryTerms", product.path("glossaryTerms").deepCopy());
            item.put("name", listing.path("name").asText());
            item.put("description", listing.path("description").asText(""));
            ArrayNode assets = item.putArray("assetListings");
            for (JsonNode summary : product.path("items")) {
                store.get(region, domainId, DataZoneCatalog.LISTINGS, summary.path("listingId").asText())
                        .ifPresent(assetListing -> {
                            JsonNode asset = assetListing.path("item").path("assetListing");
                            assets.addObject().put("entityId", asset.path("assetId").asText())
                                    .put("entityRevision", asset.path("assetRevision").asText())
                                    .put("entityType", asset.path("assetType").asText());
                        });
            }
            return result;
        }
        JsonNode asset = listing.path("item").path("assetListing");
        ObjectNode item = result.putObject("item").putObject("assetListing");
        item.put("entityId", asset.path("assetId").asText());
        item.put("entityRevision", asset.path("assetRevision").asText());
        item.put("entityType", asset.path("assetType").asText());
        item.put("forms", asset.path("forms").asText());
        item.set("glossaryTerms", asset.path("glossaryTerms").deepCopy());
        return result;
    }

    private ObjectNode request(String region, String domainId, String id) {
        return store.require(region, domainId, REQUESTS, id, "Subscription request");
    }

    private ObjectNode requestView(ObjectNode request) {
        return store.view(request);
    }

    public synchronized ObjectNode getSubscriptionRequestDetails(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetSubscriptionRequestDetails")
                .path("id").asText();
        return requestView(request(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode updateSubscriptionRequest(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateSubscriptionRequest")
                .path("id").asText();
        ObjectNode request = request(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, request.path("_").path("projectId").asText(),
                "UpdateSubscriptionRequest");
        if (!"PENDING".equals(request.path("status").asText())) {
            throw conflict("Subscription request " + request.path("id").asText() + " is "
                    + request.path("status").asText() + ".");
        }
        request.put("requestReason", text(input, "requestReason", 4096));
        request.put("updatedAt", now());
        store.put(region, domainId, REQUESTS, request.path("id").asText(), request);
        return requestView(request);
    }

    public synchronized ObjectNode deleteSubscriptionRequest(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteSubscriptionRequest")
                .path("id").asText();
        ObjectNode request = request(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, request.path("_").path("projectId").asText(),
                "DeleteSubscriptionRequest");
        if ("ACCEPTED".equals(request.path("status").asText())) {
            throw conflict("Subscription request " + request.path("id").asText()
                    + " was accepted; cancel its subscription instead.");
        }
        store.delete(region, domainId, REQUESTS, request.path("id").asText());
        return store.object();
    }

    /** Only a member of the listing's owning project may decide a request. */
    private String reviewer(String region, String domainId, ObjectNode request, String operation) {
        String reviewer = core.requireMember(region, domainId, request.path("_").path("ownerProjectId").asText(),
                operation);
        if (!"PENDING".equals(request.path("status").asText())) {
            throw conflict("Subscription request " + request.path("id").asText() + " is already "
                    + request.path("status").asText() + ".");
        }
        return reviewer;
    }

    public synchronized ObjectNode acceptSubscriptionRequest(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "AcceptSubscriptionRequest")
                .path("id").asText();
        ObjectNode request = request(region, domainId, text(input, "identifier", 64));
        String reviewer = reviewer(region, domainId, request, "AcceptSubscriptionRequest");
        double now = now();
        request.put("status", "ACCEPTED");
        request.put("reviewerId", reviewer);
        request.put("updatedAt", now);
        request.put("updatedBy", reviewer);
        if (present(input, "decisionComment")) {
            request.put("decisionComment", input.get("decisionComment").asText());
        }
        String projectId = request.path("_").path("projectId").asText();
        String listingId = request.path("_").path("listingId").asText();
        ObjectNode subscription = subscriptionFor(region, domainId, projectId, listingId);
        if (subscription == null) {
            subscription = store.object();
            subscription.put("id", store.id());
            subscription.put("createdBy", request.path("createdBy").asText());
            subscription.put("domainId", domainId);
            subscription.put("createdAt", now);
            subscription.set("subscribedPrincipal", request.path("subscribedPrincipals").get(0).deepCopy());
            subscription.putObject("_").put("projectId", projectId).put("ownerProjectId",
                    request.path("_").path("ownerProjectId").asText());
        }
        subscription.put("status", "APPROVED");
        subscription.put("updatedAt", now);
        subscription.put("updatedBy", reviewer);
        subscription.set("subscribedListing", request.path("subscribedListings").get(0).deepCopy());
        if (input.path("assetScopes").isArray()) {
            ObjectNode asset = (ObjectNode) subscription.path("subscribedListing").path("item")
                    .path("assetListing");
            for (JsonNode scope : input.get("assetScopes")) {
                asset.putObject("assetScope").put("assetId", scope.path("assetId").asText())
                        .put("status", "ACCEPTED").set("filterIds", scope.path("filterIds").deepCopy());
            }
        }
        subscription.put("subscriptionRequestId", request.path("id").asText());
        store.put(region, domainId, SUBSCRIPTIONS, subscription.path("id").asText(), subscription);
        request.put("existingSubscriptionId", subscription.path("id").asText());
        store.put(region, domainId, REQUESTS, request.path("id").asText(), request);
        autoGrant(region, domainId, subscription, reviewer);
        core.notify(region, domainId, "EVENT", projectId, "PROJECT_CONTRIBUTOR", "SUBSCRIPTION_REQUEST_ACCEPTED",
                "Your subscription request to " + subscription.path("subscribedListing").path("name").asText()
                        + " was accepted.", Map.of("subscriptionRequestId", request.path("id").asText(),
                        "subscriptionId", subscription.path("id").asText()));
        publish(region, domainId, "Subscription Request Accepted", request.path("id").asText(), Map.of(
                "subscriptionRequestId", request.path("id").asText(), "subscriptionId",
                subscription.path("id").asText()));
        return requestView(request);
    }

    public synchronized ObjectNode rejectSubscriptionRequest(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "RejectSubscriptionRequest")
                .path("id").asText();
        ObjectNode request = request(region, domainId, text(input, "identifier", 64));
        String reviewer = reviewer(region, domainId, request, "RejectSubscriptionRequest");
        request.put("status", "REJECTED");
        request.put("reviewerId", reviewer);
        request.put("updatedAt", now());
        request.put("updatedBy", reviewer);
        if (present(input, "decisionComment")) {
            request.put("decisionComment", input.get("decisionComment").asText());
        }
        store.put(region, domainId, REQUESTS, request.path("id").asText(), request);
        core.notify(region, domainId, "EVENT", request.path("_").path("projectId").asText(), "PROJECT_CONTRIBUTOR",
                "SUBSCRIPTION_REQUEST_REJECTED", "Your subscription request to "
                        + request.path("subscribedListings").get(0).path("name").asText() + " was rejected.",
                Map.of("subscriptionRequestId", request.path("id").asText()));
        publish(region, domainId, "Subscription Request Rejected", request.path("id").asText(), Map.of(
                "subscriptionRequestId", request.path("id").asText()));
        return requestView(request);
    }

    public synchronized ObjectNode listSubscriptionRequests(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListSubscriptionRequests")
                .path("id").asText();
        requireScope(input, "owningProjectId", "approverProjectId", "subscribedListingId", "owningUserId",
                "owningGroupId", "owningIamPrincipalArn");
        String status = oneOf(input, "status", Set.of("PENDING", "ACCEPTED", "REJECTED"), "PENDING");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode request : store.list(region, domainId, REQUESTS)) {
            JsonNode meta = request.path("_");
            if (status.equals(request.path("status").asText())
                    && matches(input, "owningProjectId", meta.path("projectId").asText())
                    && matches(input, "approverProjectId", meta.path("ownerProjectId").asText())
                    && matches(input, "subscribedListingId", meta.path("listingId").asText())
                    && matches(input, "owningUserId", request.path("createdBy").asText())) {
                ObjectNode summary = store.view(request);
                JsonNode forms = summary.remove("metadataForms");
                if (forms != null) {
                    ArrayNode summaries = summary.putArray("metadataFormsSummary");
                    forms.forEach(form -> summaries.addObject().put("formName", form.path("formName").asText())
                            .put("typeName", form.path("typeName").asText())
                            .put("typeRevision", form.path("typeRevision").asText()));
                }
                items.add(summary);
            }
        }
        sort(items, input);
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- subscriptions

    private ObjectNode subscriptionFor(String region, String domainId, String projectId, String listingId) {
        for (ObjectNode subscription : store.list(region, domainId, SUBSCRIPTIONS)) {
            if (projectId.equals(subscription.path("_").path("projectId").asText())
                    && listingId.equals(subscription.path("subscribedListing").path("id").asText())) {
                return subscription;
            }
        }
        return null;
    }

    private ObjectNode subscription(String region, String domainId, String id) {
        return store.require(region, domainId, SUBSCRIPTIONS, id, "Subscription");
    }

    public synchronized ObjectNode getSubscription(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetSubscription").path("id")
                .asText();
        return store.view(subscription(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode listSubscriptions(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListSubscriptions").path("id")
                .asText();
        requireScope(input, "owningProjectId", "approverProjectId", "subscribedListingId", "owningUserId",
                "owningGroupId", "owningIamPrincipalArn", "subscriptionRequestIdentifier");
        String status = oneOf(input, "status", Set.of("APPROVED", "REVOKED", "CANCELLED"), "APPROVED");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode subscription : store.list(region, domainId, SUBSCRIPTIONS)) {
            JsonNode meta = subscription.path("_");
            if (status.equals(subscription.path("status").asText())
                    && matches(input, "owningProjectId", meta.path("projectId").asText())
                    && matches(input, "approverProjectId", meta.path("ownerProjectId").asText())
                    && matches(input, "subscribedListingId", subscription.path("subscribedListing").path("id")
                            .asText())
                    && matches(input, "subscriptionRequestIdentifier", subscription.path("subscriptionRequestId")
                            .asText())
                    && matches(input, "owningUserId", subscription.path("createdBy").asText())) {
                items.add(store.view(subscription));
            }
        }
        sort(items, input);
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode cancelSubscription(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CancelSubscription")
                .path("id").asText();
        ObjectNode subscription = subscription(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, subscription.path("_").path("projectId").asText(),
                "CancelSubscription");
        end(region, domainId, subscription, "CANCELLED", editor, false);
        return store.view(subscription);
    }

    public synchronized ObjectNode revokeSubscription(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "RevokeSubscription")
                .path("id").asText();
        ObjectNode subscription = subscription(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, subscription.path("_").path("ownerProjectId").asText(),
                "RevokeSubscription");
        boolean retain = input.path("retainPermissions").asBoolean(false);
        subscription.put("retainPermissions", retain);
        end(region, domainId, subscription, "REVOKED", editor, retain);
        return store.view(subscription);
    }

    private void end(String region, String domainId, ObjectNode subscription, String status, String editor,
                     boolean retain) {
        if (!"APPROVED".equals(subscription.path("status").asText())) {
            throw conflict("Subscription " + subscription.path("id").asText() + " is "
                    + subscription.path("status").asText() + ".");
        }
        for (ObjectNode grant : store.list(region, domainId, GRANTS)) {
            if (subscription.path("id").asText().equals(grant.path("subscriptionId").asText())) {
                if (!retain) {
                    revoke(region, grant);
                }
                store.delete(region, domainId, GRANTS, grant.path("id").asText());
            }
        }
        subscription.put("status", status);
        subscription.put("updatedAt", now());
        subscription.put("updatedBy", editor);
        store.put(region, domainId, SUBSCRIPTIONS, subscription.path("id").asText(), subscription);
        String detail = "CANCELLED".equals(status) ? "Subscription Cancelled" : "Subscription Revoked";
        String notified = "CANCELLED".equals(status) ? subscription.path("_").path("ownerProjectId").asText()
                : subscription.path("_").path("projectId").asText();
        core.notify(region, domainId, "EVENT", notified, "PROJECT_OWNER", "SUBSCRIPTION_" + status,
                "The subscription to " + subscription.path("subscribedListing").path("name").asText() + " was "
                        + status.toLowerCase() + ".", Map.of("subscriptionId", subscription.path("id").asText()));
        publish(region, domainId, detail, subscription.path("id").asText(), Map.of("subscriptionId",
                subscription.path("id").asText()));
    }

    // ---------------------------------------------------------------- grants

    /** Grants an approved subscription to every subscribing environment with an applicable automatic target. */
    private void autoGrant(String region, String domainId, ObjectNode subscription, String editor) {
        String projectId = subscription.path("_").path("projectId").asText();
        List<ObjectNode> assets = grantedAssets(region, domainId, subscription);
        for (ObjectNode environment : store.list(region, domainId, DataZoneEnvironments.ENVIRONMENTS)) {
            if (!projectId.equals(environment.path("projectId").asText())
                    || !"ACTIVE".equals(environment.path("status").asText())) {
                continue;
            }
            for (ObjectNode target : environments.targetsOf(region, domainId, environment.path("id").asText())) {
                if (!"AUTOMATIC".equals(target.path("subscriptionGrantCreationMode").asText("AUTOMATIC"))
                        || assets.stream().noneMatch(asset -> applies(target, asset.path("entityType").asText()))) {
                    continue;
                }
                grant(region, domainId, environment, target, subscription, editor, null);
            }
        }
    }

    private static boolean applies(ObjectNode target, String assetType) {
        JsonNode types = target.path("applicableAssetTypes");
        if (!types.isArray() || types.isEmpty()) {
            return true;
        }
        for (JsonNode type : types) {
            if (assetType.equals(type.asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode grant(String region, String domainId, ObjectNode environment, ObjectNode target,
                             ObjectNode subscription, String editor, String targetName) {
        double now = now();
        JsonNode listing = subscription.path("subscribedListing");
        ObjectNode grant = store.object();
        grant.put("id", store.id());
        grant.put("createdBy", editor);
        grant.put("domainId", domainId);
        grant.put("createdAt", now);
        grant.put("updatedAt", now);
        grant.put("environmentId", environment.path("id").asText());
        grant.put("subscriptionTargetId", target.path("id").asText());
        grant.putObject("grantedEntity").putObject("listing").put("id", listing.path("id").asText())
                .put("revision", listing.path("revision").asText());
        grant.put("subscriptionId", subscription.path("id").asText());
        ArrayNode grantedAssets = grant.putArray("assets");
        boolean failed = false;
        for (ObjectNode asset : grantedAssets(region, domainId, subscription)) {
            if (!applies(target, asset.path("entityType").asText())) {
                continue;
            }
            ObjectNode granted = grantedAssets.addObject();
            granted.put("assetId", asset.path("entityId").asText());
            granted.put("assetRevision", asset.path("entityRevision").asText());
            String failure = lakeFormationGrant(region, target, asset, true);
            String name = targetName != null ? targetName : table(asset)[1];
            if (name != null) {
                granted.put("targetName", name);
            }
            if (failure == null) {
                granted.put("status", "GRANTED");
                granted.put("grantedTimestamp", now);
            } else {
                failed = true;
                granted.put("status", "GRANT_FAILED");
                granted.putObject("failureCause").put("message", failure);
                granted.put("failureTimestamp", now);
            }
        }
        grant.put("status", failed ? "GRANT_FAILED" : "COMPLETED");
        grant.putObject("_").put("projectId", subscription.path("_").path("projectId").asText());
        store.put(region, domainId, GRANTS, grant.path("id").asText(), grant);
        return grant;
    }

    /**
     * The assets a subscription grants, each with its type and forms: the listed asset, or each asset of a listed
     * data product.
     */
    private List<ObjectNode> grantedAssets(String region, String domainId, JsonNode subscription) {
        JsonNode item = subscription.path("subscribedListing").path("item");
        List<ObjectNode> result = new ArrayList<>();
        if (item.has("productListing")) {
            for (JsonNode asset : item.path("productListing").path("assetListings")) {
                ObjectNode listing = catalog.listingOf(region, domainId, asset.path("entityId").asText());
                ObjectNode entry = store.object().put("entityId", asset.path("entityId").asText())
                        .put("entityRevision", asset.path("entityRevision").asText())
                        .put("entityType", asset.path("entityType").asText());
                entry.put("forms", listing == null ? "{}" : listing.path("item").path("assetListing").path("forms")
                        .asText("{}"));
                result.add(entry);
            }
        } else {
            result.add(((ObjectNode) item.path("assetListing")).deepCopy());
        }
        return result;
    }

    /** The Glue database and table a listed asset names, from its Glue table form. */
    private String[] table(JsonNode asset) {
        try {
            JsonNode forms = store.mapper().readTree(asset.path("forms").asText("{}"));
            JsonNode form = forms.path("GlueTableForm");
            if (form.isObject()) {
                return new String[] {form.path("databaseName").asText(null), form.path("tableName").asText(null)};
            }
        } catch (JsonProcessingException e) {
            return new String[] {null, null};
        }
        return new String[] {null, null};
    }

    /** Grants or revokes Lake Formation access to a Glue table asset for the target's principals. */
    private String lakeFormationGrant(String region, JsonNode target, JsonNode asset, boolean grant) {
        String[] table = table(asset);
        if (table[0] == null || table[1] == null || !lakeFormation.isResolvable()) {
            return null;
        }
        for (JsonNode principal : target.path("authorizedPrincipals")) {
            ObjectNode request = store.object();
            request.putObject("Principal").put("DataLakePrincipalIdentifier", principal.asText());
            request.putObject("Resource").putObject("Table").put("DatabaseName", table[0]).put("Name", table[1]);
            ArrayNode permissions = request.putArray("Permissions");
            TABLE_PERMISSIONS.forEach(permissions::add);
            try {
                if (grant) {
                    lakeFormation.get().grantPermissions(region, store.mapper().convertValue(request,
                            GrantPermissionsRequest.class));
                } else {
                    lakeFormation.get().revokePermissions(region, store.mapper().convertValue(request,
                            RevokePermissionsRequest.class));
                }
            } catch (AwsException e) {
                if (grant) {
                    return e.getMessage();
                }
                LOG.debugv("Could not revoke Lake Formation access for {0}: {1}", principal.asText(), e.getMessage());
            }
        }
        return null;
    }

    private void revoke(String region, ObjectNode grant) {
        store.get(region, grant.path("domainId").asText(), DataZoneEnvironments.TARGETS,
                grant.path("subscriptionTargetId").asText()).ifPresent(target -> {
                    ObjectNode subscription = store.get(region, grant.path("domainId").asText(), SUBSCRIPTIONS,
                            grant.path("subscriptionId").asText()).orElse(null);
                    if (subscription != null) {
                        for (ObjectNode asset : grantedAssets(region, grant.path("domainId").asText(),
                                subscription)) {
                            lakeFormationGrant(region, target, asset, false);
                        }
                    }
                });
    }

    public synchronized ObjectNode createSubscriptionGrant(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateSubscriptionGrant")
                .path("id").asText();
        ObjectNode environment = environments.environment(region, domainId, text(input, "environmentIdentifier",
                64));
        String editor = core.requireMember(region, domainId, environment.path("projectId").asText(),
                "CreateSubscriptionGrant");
        JsonNode listingInput = input.path("grantedEntity").path("listing");
        String listingId = text(listingInput, "identifier", 64);
        ObjectNode subscription = subscriptionFor(region, domainId, environment.path("projectId").asText(),
                listingId);
        if (subscription == null || !"APPROVED".equals(subscription.path("status").asText())) {
            throw invalid("Project " + environment.path("projectId").asText()
                    + " has no approved subscription to listing " + listingId + ".");
        }
        List<ObjectNode> targets = environments.targetsOf(region, domainId, environment.path("id").asText());
        ObjectNode target;
        if (present(input, "subscriptionTargetIdentifier")) {
            String targetId = input.get("subscriptionTargetIdentifier").asText();
            target = targets.stream().filter(value -> targetId.equals(value.path("id").asText())).findFirst()
                    .orElseThrow(() -> notFound("Subscription target " + targetId + " was not found."));
        } else {
            List<ObjectNode> assets = grantedAssets(region, domainId, subscription);
            target = targets.stream().filter(value -> assets.stream().anyMatch(asset -> applies(value,
                    asset.path("entityType").asText()))).findFirst()
                    .orElseThrow(() -> invalid("Environment " + environment.path("id").asText()
                            + " has no subscription target for listing " + listingId + "."));
        }
        for (ObjectNode existing : store.list(region, domainId, GRANTS)) {
            if (target.path("id").asText().equals(existing.path("subscriptionTargetId").asText())
                    && subscription.path("id").asText().equals(existing.path("subscriptionId").asText())) {
                throw conflict("Listing " + listingId + " is already granted to subscription target "
                        + target.path("id").asText() + ".");
            }
        }
        String targetName = null;
        for (JsonNode name : input.path("assetTargetNames")) {
            targetName = name.path("targetName").asText(null);
        }
        return store.view(grant(region, domainId, environment, target, subscription, editor, targetName));
    }

    public synchronized ObjectNode getSubscriptionGrant(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetSubscriptionGrant")
                .path("id").asText();
        return store.view(store.require(region, domainId, GRANTS, text(input, "identifier", 64),
                "Subscription grant"));
    }

    public synchronized ObjectNode deleteSubscriptionGrant(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteSubscriptionGrant")
                .path("id").asText();
        ObjectNode grant = store.require(region, domainId, GRANTS, text(input, "identifier", 64),
                "Subscription grant");
        core.requireMember(region, domainId, grant.path("_").path("projectId").asText(), "DeleteSubscriptionGrant");
        revoke(region, grant);
        store.delete(region, domainId, GRANTS, grant.path("id").asText());
        for (JsonNode asset : grant.path("assets")) {
            ((ObjectNode) asset).put("status", "REVOKED");
        }
        grant.put("updatedAt", now());
        return store.view(grant);
    }

    public synchronized ObjectNode listSubscriptionGrants(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListSubscriptionGrants")
                .path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode grant : store.list(region, domainId, GRANTS)) {
            if (matches(input, "environmentId", grant.path("environmentId").asText())
                    && matches(input, "subscriptionTargetId", grant.path("subscriptionTargetId").asText())
                    && matches(input, "subscribedListingId", grant.path("grantedEntity").path("listing").path("id")
                            .asText())
                    && matches(input, "subscriptionId", grant.path("subscriptionId").asText())
                    && matches(input, "owningProjectId", grant.path("_").path("projectId").asText())) {
                items.add(store.view(grant));
            }
        }
        sort(items, input);
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- helpers

    private static void requireScope(JsonNode input, String... fields) {
        for (String field : fields) {
            if (present(input, field)) {
                return;
            }
        }
        throw invalid("Specify one of " + String.join(", ", fields) + ".");
    }

    private static boolean matches(JsonNode input, String field, String value) {
        return !present(input, field) || input.get(field).asText().equals(value);
    }

    private static void sort(List<ObjectNode> items, JsonNode input) {
        String key = "UPDATED_AT".equals(input.path("sortBy").asText()) ? "updatedAt" : "createdAt";
        items.sort((left, right) -> Double.compare(left.path(key).asDouble(), right.path(key).asDouble()));
        if ("DESCENDING".equals(input.path("sortOrder").asText())) {
            Collections.reverse(items);
        }
    }

    /** Publishes a DataZone event to the default event bus, as DataZone does for subscription workflows. */
    private void publish(String region, String domainId, String detailType, String id, Map<String, String> data) {
        if (!events.isResolvable()) {
            return;
        }
        ObjectNode detail = store.object();
        detail.putObject("metadata").put("id", id).put("domain", domainId).put("version", "1")
                .put("typeName", detailType.replace(" ", "")).put("owningProjectId", data.getOrDefault("projectId",
                        ""));
        ObjectNode payload = detail.putObject("data");
        data.forEach(payload::put);
        events.get().publishServiceEvent("aws.datazone", detailType, detail.toString(),
                store.arn(region, "domain/" + domainId), region);
    }
}
