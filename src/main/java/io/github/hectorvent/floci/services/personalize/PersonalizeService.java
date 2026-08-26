package io.github.hectorvent.floci.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Local Amazon Personalize stub. Schemas, dataset groups, datasets, and
 * event trackers are in-memory; created resources are {@code ACTIVE}
 * immediately. Binding probes (import jobs, solutions, campaigns, events,
 * runtime) share the same store.
 *
 * @see <a href="https://docs.aws.amazon.com/personalize/latest/dg/API_Operations.html">Personalize API</a>
 */
@ApplicationScoped
public class PersonalizeService implements Resettable {

    static final String SERVICE = "personalize";

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9\\-_]{0,62}$");

    static final class Schema {
        String arn;
        String name;
        String schema;
        String domain;
        long creationDateTime;
        long lastUpdatedDateTime;
    }

    static final class DatasetGroup {
        String arn;
        String name;
        String domain;
        String roleArn;
        String kmsKeyArn;
        String status;
        long creationDateTime;
        long lastUpdatedDateTime;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class Dataset {
        String arn;
        String name;
        String datasetGroupArn;
        String schemaArn;
        String datasetType;
        String status;
        long creationDateTime;
        long lastUpdatedDateTime;
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, JsonNode> records = new ConcurrentHashMap<>();
    }

    static final class EventTracker {
        String arn;
        String name;
        String datasetGroupArn;
        String trackingId;
        String accountId;
        String status;
        long creationDateTime;
        long lastUpdatedDateTime;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    static final class NamedResource {
        String arn;
        String name;
        String parentArn;
        String status;
        long creationDateTime;
        long lastUpdatedDateTime;
        JsonNode request;
        final Map<String, String> tags = new LinkedHashMap<>();
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, Schema> schemas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DatasetGroup> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Dataset> datasets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EventTracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EventTracker> trackersByTrackingId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> importJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> solutions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> solutionVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> campaigns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> batchJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> filters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<JsonNode>> ingestedEvents = new ConcurrentHashMap<>();

    @Inject
    public PersonalizeService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        schemas.clear();
        groups.clear();
        datasets.clear();
        trackers.clear();
        trackersByTrackingId.clear();
        importJobs.clear();
        solutions.clear();
        solutionVersions.clear();
        campaigns.clear();
        batchJobs.clear();
        filters.clear();
        ingestedEvents.clear();
    }

    public ObjectNode createSchema(JsonNode request, String region) {
        String name = requireText(request, "name");
        validateName(name, "name");
        String schema = requireText(request, "schema");
        String arn = regionResolver.buildArn("personalize", region, "schema/" + name);
        if (schemas.containsKey(arn) || schemaByName(name) != null) {
            throw alreadyExists("A schema with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        Schema created = new Schema();
        created.arn = arn;
        created.name = name;
        created.schema = schema;
        created.domain = textOrNull(request, "domain");
        created.creationDateTime = now;
        created.lastUpdatedDateTime = now;
        schemas.put(arn, created);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("schemaArn", arn);
        return response;
    }

    public ObjectNode describeSchema(JsonNode request) {
        Schema schema = requireSchema(requireText(request, "schemaArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode body = response.putObject("schema");
        body.put("name", schema.name);
        body.put("schemaArn", schema.arn);
        body.put("schema", schema.schema);
        if (schema.domain != null) {
            body.put("domain", schema.domain);
        }
        body.put("creationDateTime", schema.creationDateTime);
        body.put("lastUpdatedDateTime", schema.lastUpdatedDateTime);
        return response;
    }

    public ObjectNode deleteSchema(JsonNode request) {
        String arn = requireText(request, "schemaArn");
        requireSchema(arn);
        for (Dataset dataset : datasets.values()) {
            if (arn.equals(dataset.schemaArn)) {
                throw inUse("Schema " + arn + " is associated with a dataset.");
            }
        }
        schemas.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listSchemas() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("schemas");
        for (Schema schema : schemas.values()) {
            ObjectNode summary = list.addObject();
            summary.put("name", schema.name);
            summary.put("schemaArn", schema.arn);
            summary.put("creationDateTime", schema.creationDateTime);
            summary.put("lastUpdatedDateTime", schema.lastUpdatedDateTime);
            if (schema.domain != null) {
                summary.put("domain", schema.domain);
            }
        }
        return response;
    }

    public ObjectNode createDatasetGroup(JsonNode request, String region) {
        String name = requireText(request, "name");
        validateName(name, "name");
        String arn = regionResolver.buildArn("personalize", region, "dataset-group/" + name);
        if (groups.containsKey(arn) || groupByName(name) != null) {
            throw alreadyExists("A dataset group with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        DatasetGroup group = new DatasetGroup();
        group.arn = arn;
        group.name = name;
        group.domain = textOrNull(request, "domain");
        group.roleArn = textOrNull(request, "roleArn");
        group.kmsKeyArn = textOrNull(request, "kmsKeyArn");
        group.status = "ACTIVE";
        group.creationDateTime = now;
        group.lastUpdatedDateTime = now;
        group.tags.putAll(readTags(request));
        groups.put(arn, group);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("datasetGroupArn", arn);
        if (group.domain != null) {
            response.put("domain", group.domain);
        }
        return response;
    }

    public ObjectNode describeDatasetGroup(JsonNode request) {
        DatasetGroup group = requireGroup(requireText(request, "datasetGroupArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode body = response.putObject("datasetGroup");
        body.put("name", group.name);
        body.put("datasetGroupArn", group.arn);
        body.put("status", group.status);
        if (group.roleArn != null) {
            body.put("roleArn", group.roleArn);
        }
        if (group.kmsKeyArn != null) {
            body.put("kmsKeyArn", group.kmsKeyArn);
        }
        if (group.domain != null) {
            body.put("domain", group.domain);
        }
        body.put("creationDateTime", group.creationDateTime);
        body.put("lastUpdatedDateTime", group.lastUpdatedDateTime);
        return response;
    }

    public ObjectNode deleteDatasetGroup(JsonNode request) {
        String arn = requireText(request, "datasetGroupArn");
        requireGroup(arn);
        if (hasChildren(arn)) {
            throw inUse("Dataset group " + arn + " has associated resources.");
        }
        groups.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDatasetGroups() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("datasetGroups");
        for (DatasetGroup group : groups.values()) {
            ObjectNode summary = list.addObject();
            summary.put("name", group.name);
            summary.put("datasetGroupArn", group.arn);
            summary.put("status", group.status);
            summary.put("creationDateTime", group.creationDateTime);
            summary.put("lastUpdatedDateTime", group.lastUpdatedDateTime);
            if (group.domain != null) {
                summary.put("domain", group.domain);
            }
        }
        return response;
    }

    public ObjectNode createDataset(JsonNode request, String region) {
        String name = requireText(request, "name");
        validateName(name, "name");
        String schemaArn = requireText(request, "schemaArn");
        requireSchema(schemaArn);
        String groupArn = requireText(request, "datasetGroupArn");
        DatasetGroup group = requireGroup(groupArn);
        String datasetType = canonicalizeDatasetType(requireText(request, "datasetType"));
        for (Dataset existing : datasets.values()) {
            if (groupArn.equals(existing.datasetGroupArn) && datasetType.equals(existing.datasetType)) {
                throw alreadyExists("A dataset of type " + datasetType + " already exists in the dataset group.");
            }
        }
        String arn = regionResolver.buildArn("personalize", region,
                "dataset/" + group.name + "/" + datasetType);
        if (datasets.containsKey(arn)) {
            throw alreadyExists("A dataset with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        Dataset dataset = new Dataset();
        dataset.arn = arn;
        dataset.name = name;
        dataset.datasetGroupArn = groupArn;
        dataset.schemaArn = schemaArn;
        dataset.datasetType = datasetType;
        dataset.status = "ACTIVE";
        dataset.creationDateTime = now;
        dataset.lastUpdatedDateTime = now;
        dataset.tags.putAll(readTags(request));
        datasets.put(arn, dataset);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("datasetArn", arn);
        return response;
    }

    public ObjectNode describeDataset(JsonNode request) {
        Dataset dataset = requireDataset(requireText(request, "datasetArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode body = response.putObject("dataset");
        putDataset(body, dataset);
        return response;
    }

    public ObjectNode deleteDataset(JsonNode request) {
        String arn = requireText(request, "datasetArn");
        Dataset dataset = requireDataset(arn);
        for (EventTracker tracker : trackers.values()) {
            if (dataset.datasetGroupArn.equals(tracker.datasetGroupArn)
                    && "INTERACTIONS".equals(dataset.datasetType)) {
                throw inUse("Dataset " + arn + " is associated with an event tracker.");
            }
        }
        datasets.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDatasets(JsonNode request) {
        String groupArn = textOrNull(request, "datasetGroupArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("datasets");
        for (Dataset dataset : datasets.values()) {
            if (groupArn != null && !groupArn.equals(dataset.datasetGroupArn)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("name", dataset.name);
            summary.put("datasetArn", dataset.arn);
            summary.put("datasetType", dataset.datasetType);
            summary.put("status", dataset.status);
            summary.put("creationDateTime", dataset.creationDateTime);
            summary.put("lastUpdatedDateTime", dataset.lastUpdatedDateTime);
        }
        return response;
    }

    public ObjectNode createEventTracker(JsonNode request, String region) {
        String name = requireText(request, "name");
        validateName(name, "name");
        String groupArn = requireText(request, "datasetGroupArn");
        requireGroup(groupArn);
        if (!hasInteractionsDataset(groupArn)) {
            throw invalid("Dataset group " + groupArn + " does not contain an Interactions dataset.");
        }
        for (EventTracker existing : trackers.values()) {
            if (groupArn.equals(existing.datasetGroupArn)) {
                throw alreadyExists("An event tracker already exists for dataset group " + groupArn + ".");
            }
        }
        String arn = regionResolver.buildArn("personalize", region, "event-tracker/" + name);
        if (trackers.containsKey(arn) || trackerByName(name) != null) {
            throw alreadyExists("An event tracker with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        EventTracker tracker = new EventTracker();
        tracker.arn = arn;
        tracker.name = name;
        tracker.datasetGroupArn = groupArn;
        tracker.trackingId = UUID.randomUUID().toString().replace("-", "");
        tracker.accountId = regionResolver.getAccountId();
        tracker.status = "ACTIVE";
        tracker.creationDateTime = now;
        tracker.lastUpdatedDateTime = now;
        tracker.tags.putAll(readTags(request));
        trackers.put(arn, tracker);
        trackersByTrackingId.put(tracker.trackingId, tracker);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("eventTrackerArn", arn);
        response.put("trackingId", tracker.trackingId);
        return response;
    }

    public ObjectNode describeEventTracker(JsonNode request) {
        EventTracker tracker = requireTracker(requireText(request, "eventTrackerArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode body = response.putObject("eventTracker");
        body.put("name", tracker.name);
        body.put("eventTrackerArn", tracker.arn);
        body.put("accountId", tracker.accountId);
        body.put("trackingId", tracker.trackingId);
        body.put("datasetGroupArn", tracker.datasetGroupArn);
        body.put("status", tracker.status);
        body.put("creationDateTime", tracker.creationDateTime);
        body.put("lastUpdatedDateTime", tracker.lastUpdatedDateTime);
        return response;
    }

    public ObjectNode deleteEventTracker(JsonNode request) {
        String arn = requireText(request, "eventTrackerArn");
        EventTracker tracker = requireTracker(arn);
        trackers.remove(arn);
        trackersByTrackingId.remove(tracker.trackingId);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listEventTrackers(JsonNode request) {
        String groupArn = textOrNull(request, "datasetGroupArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("eventTrackers");
        for (EventTracker tracker : trackers.values()) {
            if (groupArn != null && !groupArn.equals(tracker.datasetGroupArn)) {
                continue;
            }
            ObjectNode summary = list.addObject();
            summary.put("name", tracker.name);
            summary.put("eventTrackerArn", tracker.arn);
            summary.put("status", tracker.status);
            summary.put("creationDateTime", tracker.creationDateTime);
            summary.put("lastUpdatedDateTime", tracker.lastUpdatedDateTime);
        }
        return response;
    }

    public ObjectNode listFilters(JsonNode request) {
        String groupArn = textOrNull(request, "datasetGroupArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Filters");
        for (NamedResource filter : filters.values()) {
            if (groupArn != null && !groupArn.equals(filter.parentArn)) {
                continue;
            }
            list.add(namedSummary(filter, "filterArn"));
        }
        return response;
    }

    public ObjectNode listSolutions(JsonNode request) {
        String groupArn = textOrNull(request, "datasetGroupArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("solutions");
        for (NamedResource solution : solutions.values()) {
            if (groupArn != null && !groupArn.equals(solution.parentArn)) {
                continue;
            }
            list.add(namedSummary(solution, "solutionArn"));
        }
        return response;
    }

    public ObjectNode listCampaigns(JsonNode request) {
        String solutionArn = textOrNull(request, "solutionArn");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("campaigns");
        for (NamedResource campaign : campaigns.values()) {
            if (solutionArn != null) {
                NamedResource version = solutionVersions.get(campaign.parentArn);
                if (version == null || !solutionArn.equals(version.parentArn)) {
                    continue;
                }
            }
            list.add(namedSummary(campaign, "campaignArn"));
        }
        return response;
    }

    public ObjectNode deleteFilter(JsonNode request) {
        String arn = requireText(request, "filterArn");
        if (filters.remove(arn) == null) {
            throw notFound(arn);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteSolution(JsonNode request) {
        String arn = requireText(request, "solutionArn");
        requireNamed(solutions, arn);
        for (NamedResource campaign : campaigns.values()) {
            NamedResource version = solutionVersions.get(campaign.parentArn);
            if (version != null && arn.equals(version.parentArn)) {
                throw inUse("Solution " + arn + " has dependent campaigns.");
            }
        }
        solutions.remove(arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteCampaign(JsonNode request) {
        String arn = requireText(request, "campaignArn");
        if (campaigns.remove(arn) == null) {
            throw notFound(arn);
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode createDatasetImportJob(JsonNode request, String region) {
        String name = requireText(request, "jobName");
        Dataset dataset = requireDataset(requireText(request, "datasetArn"));
        requireObject(request, "dataSource");
        String arn = regionResolver.buildArn("personalize", region, "dataset-import-job/" + name);
        putNamed(importJobs, arn, name, dataset.arn, request);
        return arnResponse("datasetImportJobArn", arn);
    }

    public ObjectNode describeDatasetImportJob(JsonNode request) {
        NamedResource job = requireNamed(importJobs, requireText(request, "datasetImportJobArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("datasetImportJob", describeNamed(job, "datasetImportJobArn", "jobName"));
        return response;
    }

    public ObjectNode createSolution(JsonNode request, String region) {
        String name = requireText(request, "name");
        DatasetGroup group = requireGroup(requireText(request, "datasetGroupArn"));
        String arn = regionResolver.buildArn("personalize", region, "solution/" + name);
        putNamed(solutions, arn, name, group.arn, request);
        return arnResponse("solutionArn", arn);
    }

    public ObjectNode createSolutionVersion(JsonNode request, String region) {
        NamedResource solution = requireNamed(solutions, requireText(request, "solutionArn"));
        String versionId = Long.toHexString(nowSeconds())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name = textOrNull(request, "name");
        if (name == null) {
            name = versionId;
        }
        String arn = regionResolver.buildArn("personalize", region,
                "solution/" + solution.name + "/" + versionId);
        putNamed(solutionVersions, arn, name, solution.arn, request);
        return arnResponse("solutionVersionArn", arn);
    }

    public ObjectNode describeSolutionVersion(JsonNode request) {
        NamedResource version = requireNamed(solutionVersions, requireText(request, "solutionVersionArn"));
        ObjectNode body = describeNamed(version, "solutionVersionArn", "name");
        body.put("solutionArn", version.parentArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("solutionVersion", body);
        return response;
    }

    public ObjectNode getSolutionMetrics(JsonNode request) {
        NamedResource version = requireNamed(solutionVersions, requireText(request, "solutionVersionArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("solutionVersionArn", version.arn);
        ObjectNode metrics = response.putObject("metrics");
        metrics.put("coverage", 0.1);
        metrics.put("mean_reciprocal_rank_at_25", 0.2);
        metrics.put("normalized_discounted_cumulative_gain_at_10", 0.15);
        metrics.put("normalized_discounted_cumulative_gain_at_25", 0.18);
        metrics.put("precision_at_10", 0.05);
        metrics.put("precision_at_25", 0.04);
        return response;
    }

    public ObjectNode createCampaign(JsonNode request, String region) {
        String name = requireText(request, "name");
        NamedResource version = requireNamed(solutionVersions, requireText(request, "solutionVersionArn"));
        String arn = regionResolver.buildArn("personalize", region, "campaign/" + name);
        putNamed(campaigns, arn, name, version.arn, request);
        return arnResponse("campaignArn", arn);
    }

    public ObjectNode updateCampaign(JsonNode request) {
        NamedResource campaign = requireNamed(campaigns, requireText(request, "campaignArn"));
        String versionArn = textOrNull(request, "solutionVersionArn");
        if (versionArn != null) {
            requireNamed(solutionVersions, versionArn);
            campaign.parentArn = versionArn;
        }
        if (campaign.request != null && campaign.request.isObject()) {
            ObjectNode updated = campaign.request.deepCopy();
            request.fields().forEachRemaining(field -> {
                if (!"tags".equalsIgnoreCase(field.getKey())) {
                    updated.set(field.getKey(), field.getValue().deepCopy());
                }
            });
            campaign.request = updated;
        }
        campaign.lastUpdatedDateTime = nowSeconds();
        return arnResponse("campaignArn", campaign.arn);
    }

    public ObjectNode describeCampaign(JsonNode request) {
        NamedResource campaign = requireNamed(campaigns, requireText(request, "campaignArn"));
        ObjectNode body = describeNamed(campaign, "campaignArn", "name");
        body.put("solutionVersionArn", campaign.parentArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("campaign", body);
        return response;
    }

    public ObjectNode createBatchInferenceJob(JsonNode request, String region) {
        String name = requireText(request, "jobName");
        NamedResource version = requireNamed(solutionVersions, requireText(request, "solutionVersionArn"));
        requireObject(request, "jobInput");
        requireObject(request, "jobOutput");
        String arn = regionResolver.buildArn("personalize", region, "batch-inference-job/" + name);
        putNamed(batchJobs, arn, name, version.arn, request);
        return arnResponse("batchInferenceJobArn", arn);
    }

    public ObjectNode describeBatchInferenceJob(JsonNode request) {
        NamedResource job = requireNamed(batchJobs, requireText(request, "batchInferenceJobArn"));
        ObjectNode body = describeNamed(job, "batchInferenceJobArn", "jobName");
        body.put("solutionVersionArn", job.parentArn);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("batchInferenceJob", body);
        return response;
    }

    public ObjectNode putEvents(JsonNode request) {
        String trackingId = requireText(request, "trackingId");
        requireText(request, "sessionId");
        JsonNode eventList = first(request, "eventList", "EventList");
        if (eventList == null || !eventList.isArray() || eventList.isEmpty()) {
            throw invalid("eventList is required.");
        }
        EventTracker tracker = trackersByTrackingId.get(trackingId);
        if (tracker == null) {
            throw invalid("trackingId " + trackingId + " is not valid.");
        }
        ingestedEvents.computeIfAbsent(tracker.arn, key -> new ArrayList<>()).add(request.deepCopy());
        return objectMapper.createObjectNode();
    }

    public ObjectNode putItems(JsonNode request) {
        Dataset dataset = requireDataset(requireText(request, "datasetArn"));
        requireDatasetType(dataset, "ITEMS");
        putRecords(dataset, first(request, "items", "Items"), "itemId");
        return objectMapper.createObjectNode();
    }

    public ObjectNode putUsers(JsonNode request) {
        Dataset dataset = requireDataset(requireText(request, "datasetArn"));
        requireDatasetType(dataset, "USERS");
        putRecords(dataset, first(request, "users", "Users"), "userId");
        return objectMapper.createObjectNode();
    }

    public ObjectNode putActions(JsonNode request) {
        Dataset dataset = requireDataset(requireText(request, "datasetArn"));
        requireDatasetType(dataset, "ACTIONS");
        putRecords(dataset, first(request, "actions", "Actions"), "actionId");
        return objectMapper.createObjectNode();
    }

    public ObjectNode putActionInteractions(JsonNode request) {
        String trackingId = requireText(request, "trackingId");
        JsonNode interactions = first(request, "actionInteractions", "ActionInteractions");
        if (interactions == null || !interactions.isArray() || interactions.isEmpty()) {
            throw invalid("actionInteractions is required.");
        }
        EventTracker tracker = trackersByTrackingId.get(trackingId);
        if (tracker == null) {
            throw notFound("trackingId " + trackingId);
        }
        if (findDataset(tracker.datasetGroupArn, "ACTION_INTERACTIONS") == null) {
            throw notFound("No Action_Interactions dataset in dataset group " + tracker.datasetGroupArn);
        }
        ingestedEvents.computeIfAbsent(tracker.arn + "#actions", key -> new ArrayList<>()).add(request.deepCopy());
        return objectMapper.createObjectNode();
    }

    public ObjectNode getRecommendations(JsonNode request) {
        String campaignArn = textOrNull(request, "campaignArn");
        String recommenderArn = textOrNull(request, "recommenderArn");
        if (campaignArn == null && recommenderArn == null) {
            throw invalid("campaignArn or recommenderArn is required.");
        }
        if (campaignArn != null) {
            requireNamed(campaigns, campaignArn);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("itemList");
        ObjectNode item = items.addObject();
        item.put("itemId", "alchemy-item-1");
        item.put("score", 0.9);
        response.put("recommendationId", UUID.randomUUID().toString());
        return response;
    }

    public ObjectNode getPersonalizedRanking(JsonNode request) {
        requireNamed(campaigns, requireText(request, "campaignArn"));
        requireText(request, "userId");
        JsonNode inputList = first(request, "inputList", "InputList");
        if (inputList == null || !inputList.isArray() || inputList.isEmpty()) {
            throw invalid("inputList is required.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ranking = response.putArray("personalizedRanking");
        double score = 1.0;
        for (JsonNode rankedItem : inputList) {
            ObjectNode ranked = ranking.addObject();
            ranked.put("itemId", rankedItem.asText());
            ranked.put("score", score);
            score -= 0.1;
        }
        response.put("recommendationId", UUID.randomUUID().toString());
        return response;
    }

    public ObjectNode getActionRecommendations(JsonNode request) {
        String campaignArn = textOrNull(request, "campaignArn");
        if (campaignArn == null) {
            throw invalid("campaignArn is required.");
        }
        requireNamed(campaigns, campaignArn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode actions = response.putArray("actionList");
        ObjectNode action = actions.addObject();
        action.put("actionId", "alchemy-action-1");
        action.put("score", 0.8);
        response.put("recommendationId", UUID.randomUUID().toString());
        return response;
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("tags"), tags);
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "resourceArn"));
        tags.putAll(readTags(request));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Map<String, String> tags = tagsFor(requireText(request, "resourceArn"));
        JsonNode keys = first(request, "tagKeys", "TagKeys");
        for (String key : stringList(keys)) {
            tags.remove(key);
        }
        return objectMapper.createObjectNode();
    }

    private Map<String, String> tagsFor(String arn) {
        DatasetGroup group = groups.get(arn);
        if (group != null) {
            return group.tags;
        }
        Dataset dataset = datasets.get(arn);
        if (dataset != null) {
            return dataset.tags;
        }
        EventTracker tracker = trackers.get(arn);
        if (tracker != null) {
            return tracker.tags;
        }
        NamedResource named = findNamed(arn);
        if (named != null) {
            return named.tags;
        }
        throw notFound(arn);
    }

    private Schema requireSchema(String arn) {
        Schema schema = schemas.get(arn);
        if (schema == null) {
            throw notFound(arn);
        }
        return schema;
    }

    private DatasetGroup requireGroup(String arn) {
        DatasetGroup group = groups.get(arn);
        if (group == null) {
            throw notFound(arn);
        }
        return group;
    }

    private Dataset requireDataset(String arn) {
        Dataset dataset = datasets.get(arn);
        if (dataset == null) {
            throw notFound(arn);
        }
        return dataset;
    }

    private EventTracker requireTracker(String arn) {
        EventTracker tracker = trackers.get(arn);
        if (tracker == null) {
            throw notFound(arn);
        }
        return tracker;
    }

    private Schema schemaByName(String name) {
        for (Schema schema : schemas.values()) {
            if (name.equals(schema.name)) {
                return schema;
            }
        }
        return null;
    }

    private DatasetGroup groupByName(String name) {
        for (DatasetGroup group : groups.values()) {
            if (name.equals(group.name)) {
                return group;
            }
        }
        return null;
    }

    private EventTracker trackerByName(String name) {
        for (EventTracker tracker : trackers.values()) {
            if (name.equals(tracker.name)) {
                return tracker;
            }
        }
        return null;
    }

    private boolean hasChildren(String groupArn) {
        for (Dataset dataset : datasets.values()) {
            if (groupArn.equals(dataset.datasetGroupArn)) {
                return true;
            }
        }
        for (EventTracker tracker : trackers.values()) {
            if (groupArn.equals(tracker.datasetGroupArn)) {
                return true;
            }
        }
        for (NamedResource solution : solutions.values()) {
            if (groupArn.equals(solution.parentArn)) {
                return true;
            }
        }
        for (NamedResource filter : filters.values()) {
            if (groupArn.equals(filter.parentArn)) {
                return true;
            }
        }
        return false;
    }

    private Dataset findDataset(String groupArn, String datasetType) {
        for (Dataset dataset : datasets.values()) {
            if (groupArn.equals(dataset.datasetGroupArn) && datasetType.equals(dataset.datasetType)) {
                return dataset;
            }
        }
        return null;
    }

    private boolean hasInteractionsDataset(String groupArn) {
        for (Dataset dataset : datasets.values()) {
            if (groupArn.equals(dataset.datasetGroupArn) && "INTERACTIONS".equals(dataset.datasetType)) {
                return true;
            }
        }
        return false;
    }

    private static void putDataset(ObjectNode body, Dataset dataset) {
        body.put("name", dataset.name);
        body.put("datasetArn", dataset.arn);
        body.put("datasetGroupArn", dataset.datasetGroupArn);
        body.put("schemaArn", dataset.schemaArn);
        body.put("datasetType", dataset.datasetType);
        body.put("status", dataset.status);
        body.put("creationDateTime", dataset.creationDateTime);
        body.put("lastUpdatedDateTime", dataset.lastUpdatedDateTime);
    }

    private static String canonicalizeDatasetType(String type) {
        return type.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static void validateName(String name, String field) {
        if (!NAME.matcher(name).matches()) {
            throw invalid(field + " must start with a letter or number and contain only letters, numbers, hyphens, and underscores.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = first(request, "tags", "Tags");
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "tagKey");
                if (key != null) {
                    String value = textOrNull(tag, "tagValue");
                    tags.put(key, value == null ? "" : value);
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("tagKey", key);
            tag.put("tagValue", value);
        });
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isNull()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = rawText(node, field);
        if (value != null) {
            return value;
        }
        if (field == null || field.isEmpty()) {
            return null;
        }
        if (Character.isLowerCase(field.charAt(0))) {
            return rawText(node, Character.toUpperCase(field.charAt(0)) + field.substring(1));
        }
        return rawText(node, Character.toLowerCase(field.charAt(0)) + field.substring(1));
    }

    private static String rawText(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static JsonNode first(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field);
            }
        }
        return null;
    }

    private static void requireObject(JsonNode request, String field) {
        JsonNode value = first(request, field, Character.toUpperCase(field.charAt(0)) + field.substring(1));
        if (value == null || !value.isObject()) {
            throw invalid(field + " is required.");
        }
    }

    private static void requireDatasetType(Dataset dataset, String expected) {
        if (!expected.equalsIgnoreCase(dataset.datasetType)) {
            throw invalid("Dataset " + dataset.arn + " is type " + dataset.datasetType
                    + ", expected " + expected + ".");
        }
    }

    private void putRecords(Dataset dataset, JsonNode records, String idField) {
        if (records == null || !records.isArray() || records.isEmpty()) {
            throw invalid(idField + " list is required.");
        }
        for (JsonNode record : records) {
            String id = textOrNull(record, idField);
            if (id == null) {
                throw invalid(idField + " is required.");
            }
            dataset.records.put(id, record.deepCopy());
        }
        dataset.lastUpdatedDateTime = nowSeconds();
    }

    private void putNamed(ConcurrentHashMap<String, NamedResource> store, String arn, String name,
                          String parentArn, JsonNode request) {
        if (store.containsKey(arn)) {
            throw alreadyExists("A resource with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        NamedResource resource = new NamedResource();
        resource.arn = arn;
        resource.name = name;
        resource.parentArn = parentArn;
        resource.status = "ACTIVE";
        resource.creationDateTime = now;
        resource.lastUpdatedDateTime = now;
        resource.request = request == null ? objectMapper.createObjectNode() : request.deepCopy();
        resource.tags.putAll(readTags(request));
        store.put(arn, resource);
    }

    private NamedResource requireNamed(ConcurrentHashMap<String, NamedResource> store, String arn) {
        NamedResource resource = store.get(arn);
        if (resource == null) {
            throw notFound(arn);
        }
        return resource;
    }

    private NamedResource findNamed(String arn) {
        for (ConcurrentHashMap<String, NamedResource> store : List.of(
                importJobs, solutions, solutionVersions, campaigns, batchJobs, filters)) {
            NamedResource resource = store.get(arn);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    private ObjectNode namedSummary(NamedResource resource, String arnField) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("name", resource.name);
        summary.put(arnField, resource.arn);
        summary.put("status", resource.status);
        summary.put("creationDateTime", resource.creationDateTime);
        summary.put("lastUpdatedDateTime", resource.lastUpdatedDateTime);
        return summary;
    }

    private ObjectNode describeNamed(NamedResource resource, String arnField, String nameField) {
        ObjectNode response = objectMapper.createObjectNode();
        if (resource.request != null && resource.request.isObject()) {
            resource.request.fields().forEachRemaining(field -> {
                if (!"tags".equalsIgnoreCase(field.getKey())) {
                    response.set(field.getKey(), field.getValue().deepCopy());
                }
            });
        }
        response.put(arnField, resource.arn);
        response.put(nameField, resource.name);
        response.put("status", resource.status);
        response.put("creationDateTime", resource.creationDateTime);
        response.put("lastUpdatedDateTime", resource.lastUpdatedDateTime);
        return response;
    }

    private ObjectNode arnResponse(String field, String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put(field, arn);
        return response;
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsException", message, 400);
    }

    private static AwsException inUse(String message) {
        return new AwsException("ResourceInUseException", message, 409);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "No resource found with the arn " + arn, 404);
    }
}
