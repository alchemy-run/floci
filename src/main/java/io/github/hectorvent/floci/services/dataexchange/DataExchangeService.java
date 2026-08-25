package io.github.hectorvent.floci.services.dataexchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dataexchange.model.Asset;
import io.github.hectorvent.floci.services.dataexchange.model.DataSet;
import io.github.hectorvent.floci.services.dataexchange.model.EventAction;
import io.github.hectorvent.floci.services.dataexchange.model.Job;
import io.github.hectorvent.floci.services.dataexchange.model.Revision;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Data Exchange restJson1 — data sets, revisions, assets, jobs, event actions,
 * and empty grant enumerations. Import jobs copy S3 objects into a revision.
 *
 * <p>Provider-generated notifications require a Marketplace product, which floci
 * does not provision; {@code SendDataSetNotification} therefore raises the same
 * {@code ValidationException} AWS returns for an owned data set.
 */
@ApplicationScoped
public class DataExchangeService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(DataExchangeService.class);

    static final String SERVICE = "dataexchange";
    private static final Set<String> ASSET_TYPES = Set.of(
            "S3_SNAPSHOT",
            "REDSHIFT_DATA_SHARE",
            "API_GATEWAY_API",
            "S3_DATA_ACCESS",
            "LAKE_FORMATION_DATA_PERMISSION");
    private static final String MARKETPLACE_MESSAGE =
            "The data set is not configured for AWS Marketplace.";

    private final StorageBackend<String, DataSet> dataSets;
    private final StorageBackend<String, Revision> revisions;
    private final StorageBackend<String, Asset> assets;
    private final StorageBackend<String, Job> jobs;
    private final StorageBackend<String, EventAction> eventActions;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public DataExchangeService(
            StorageFactory storageFactory,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this(storageFactory.create("dataexchange", "dataexchange-data-sets.json",
                        new TypeReference<Map<String, DataSet>>() {
                        }),
                storageFactory.create("dataexchange", "dataexchange-revisions.json",
                        new TypeReference<Map<String, Revision>>() {
                        }),
                storageFactory.create("dataexchange", "dataexchange-assets.json",
                        new TypeReference<Map<String, Asset>>() {
                        }),
                storageFactory.create("dataexchange", "dataexchange-jobs.json",
                        new TypeReference<Map<String, Job>>() {
                        }),
                storageFactory.create("dataexchange", "dataexchange-event-actions.json",
                        new TypeReference<Map<String, EventAction>>() {
                        }),
                regionResolver, s3Service, objectMapper);
    }

    DataExchangeService(
            StorageBackend<String, DataSet> dataSets,
            StorageBackend<String, Revision> revisions,
            StorageBackend<String, Asset> assets,
            StorageBackend<String, Job> jobs,
            StorageBackend<String, EventAction> eventActions,
            RegionResolver regionResolver,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this.dataSets = dataSets;
        this.revisions = revisions;
        this.assets = assets;
        this.jobs = jobs;
        this.eventActions = eventActions;
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    public synchronized DataSet createDataSet(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "Name");
        String description = requireText(request, "Description");
        String assetType = requireText(request, "AssetType");
        if (!ASSET_TYPES.contains(assetType)) {
            throw new AwsException("ValidationException", "Invalid AssetType: " + assetType, 400);
        }
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        DataSet dataSet = new DataSet();
        dataSet.setId(id);
        dataSet.setArn(arn(region, account, "data-sets/" + id));
        dataSet.setName(name);
        dataSet.setDescription(description);
        dataSet.setAssetType(assetType);
        dataSet.setOrigin("OWNED");
        dataSet.setCreatedAt(now);
        dataSet.setUpdatedAt(now);
        dataSet.setRegion(region);
        dataSet.setTags(readTags(request.get("Tags")));
        dataSets.put(dataSetKey(region, id), dataSet);
        LOG.infov("Created Data Exchange data set {0}", dataSet.getArn());
        return dataSet;
    }

    public DataSet getDataSet(String region, String dataSetId) {
        return requireDataSet(region, dataSetId);
    }

    public List<DataSet> listDataSets(String region, String origin) {
        List<DataSet> result = new ArrayList<>();
        for (DataSet dataSet : dataSets.values()) {
            if (!region.equals(dataSet.getRegion())) {
                continue;
            }
            if (origin != null && !origin.isBlank() && !origin.equals(dataSet.getOrigin())) {
                continue;
            }
            result.add(dataSet);
        }
        result.sort(Comparator.comparing(DataSet::getCreatedAt));
        return result;
    }

    public synchronized DataSet updateDataSet(String region, String dataSetId, JsonNode request) {
        requireObject(request, "Request body");
        DataSet dataSet = requireDataSet(region, dataSetId);
        if (request.hasNonNull("Name")) {
            dataSet.setName(requireText(request, "Name"));
        }
        if (request.hasNonNull("Description")) {
            dataSet.setDescription(requireText(request, "Description"));
        }
        dataSet.setUpdatedAt(now());
        dataSets.put(dataSetKey(region, dataSetId), dataSet);
        return dataSet;
    }

    public synchronized void deleteDataSet(String region, String dataSetId) {
        requireDataSet(region, dataSetId);
        if (!listRevisions(region, dataSetId).isEmpty()) {
            throw new AwsException(
                    "ConflictException",
                    "The data set " + dataSetId + " still contains revisions.",
                    409);
        }
        dataSets.delete(dataSetKey(region, dataSetId));
    }

    public synchronized Revision createRevision(String region, String dataSetId, JsonNode request) {
        requireObject(request, "Request body");
        DataSet dataSet = requireDataSet(region, dataSetId);
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        Revision revision = new Revision();
        revision.setId(id);
        revision.setArn(arn(region, account, "data-sets/" + dataSet.getId() + "/revisions/" + id));
        revision.setDataSetId(dataSet.getId());
        revision.setComment(optionalText(request, "Comment"));
        revision.setFinalized(false);
        revision.setCreatedAt(now);
        revision.setUpdatedAt(now);
        revision.setRegion(region);
        revision.setTags(readTags(request.get("Tags")));
        revisions.put(revisionKey(region, dataSetId, id), revision);
        return revision;
    }

    public Revision getRevision(String region, String dataSetId, String revisionId) {
        requireDataSet(region, dataSetId);
        return requireRevision(region, dataSetId, revisionId);
    }

    public List<Revision> listRevisions(String region, String dataSetId) {
        requireDataSet(region, dataSetId);
        List<Revision> result = new ArrayList<>();
        for (Revision revision : revisions.values()) {
            if (region.equals(revision.getRegion()) && dataSetId.equals(revision.getDataSetId())) {
                result.add(revision);
            }
        }
        result.sort(Comparator.comparing(Revision::getCreatedAt));
        return result;
    }

    public synchronized Revision updateRevision(
            String region, String dataSetId, String revisionId, JsonNode request) {
        requireObject(request, "Request body");
        requireDataSet(region, dataSetId);
        Revision revision = requireRevision(region, dataSetId, revisionId);
        if (request.has("Comment") && !request.get("Comment").isNull()) {
            revision.setComment(optionalText(request, "Comment"));
        }
        if (request.has("Finalized") && request.get("Finalized").isBoolean()) {
            boolean finalized = request.get("Finalized").booleanValue();
            if (finalized && listAssets(region, dataSetId, revisionId).isEmpty()) {
                throw new AwsException(
                        "ValidationException",
                        "A revision must contain at least one asset before it can be finalized.",
                        400);
            }
            revision.setFinalized(finalized);
        }
        revision.setUpdatedAt(now());
        revisions.put(revisionKey(region, dataSetId, revisionId), revision);
        return revision;
    }

    public synchronized void deleteRevision(String region, String dataSetId, String revisionId) {
        requireDataSet(region, dataSetId);
        requireRevision(region, dataSetId, revisionId);
        for (Asset asset : listAssets(region, dataSetId, revisionId)) {
            assets.delete(assetKey(region, dataSetId, revisionId, asset.getId()));
        }
        revisions.delete(revisionKey(region, dataSetId, revisionId));
    }

    public Asset getAsset(String region, String dataSetId, String revisionId, String assetId) {
        requireDataSet(region, dataSetId);
        requireRevision(region, dataSetId, revisionId);
        return requireAsset(region, dataSetId, revisionId, assetId);
    }

    public List<Asset> listAssets(String region, String dataSetId, String revisionId) {
        requireDataSet(region, dataSetId);
        requireRevision(region, dataSetId, revisionId);
        List<Asset> result = new ArrayList<>();
        for (Asset asset : assets.values()) {
            if (region.equals(asset.getRegion())
                    && dataSetId.equals(asset.getDataSetId())
                    && revisionId.equals(asset.getRevisionId())) {
                result.add(asset);
            }
        }
        result.sort(Comparator.comparing(Asset::getCreatedAt));
        return result;
    }

    public synchronized Asset updateAsset(
            String region, String dataSetId, String revisionId, String assetId, JsonNode request) {
        requireObject(request, "Request body");
        requireDataSet(region, dataSetId);
        requireRevision(region, dataSetId, revisionId);
        Asset asset = requireAsset(region, dataSetId, revisionId, assetId);
        if (request.hasNonNull("Name")) {
            asset.setName(requireText(request, "Name"));
        }
        asset.setUpdatedAt(now());
        assets.put(assetKey(region, dataSetId, revisionId, assetId), asset);
        return asset;
    }

    public synchronized void deleteAsset(String region, String dataSetId, String revisionId, String assetId) {
        requireDataSet(region, dataSetId);
        requireRevision(region, dataSetId, revisionId);
        requireAsset(region, dataSetId, revisionId, assetId);
        assets.delete(assetKey(region, dataSetId, revisionId, assetId));
    }

    public synchronized Job createJob(String region, JsonNode request) {
        requireObject(request, "Request body");
        String type = requireText(request, "Type");
        JsonNode details = requireObjectField(request, "Details");
        String dataSetId = jobDataSetId(type, details);
        String revisionId = jobRevisionId(type, details);
        if (dataSetId != null) {
            requireDataSet(region, dataSetId);
        }
        if (dataSetId != null && revisionId != null) {
            requireRevision(region, dataSetId, revisionId);
        }
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        Job job = new Job();
        job.setId(id);
        job.setArn(arn(region, account, "jobs/" + id));
        job.setType(type);
        job.setState("WAITING");
        job.setDataSetId(dataSetId);
        job.setRevisionId(revisionId);
        job.setDetails(details.deepCopy());
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setRegion(region);
        jobs.put(jobKey(region, id), job);
        return job;
    }

    public Job getJob(String region, String jobId) {
        return requireJob(region, jobId);
    }

    public List<Job> listJobs(String region, String dataSetId, String revisionId) {
        List<Job> result = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (!region.equals(job.getRegion())) {
                continue;
            }
            if (dataSetId != null && !dataSetId.isBlank() && !dataSetId.equals(job.getDataSetId())) {
                continue;
            }
            if (revisionId != null && !revisionId.isBlank() && !revisionId.equals(job.getRevisionId())) {
                continue;
            }
            result.add(job);
        }
        result.sort(Comparator.comparing(Job::getCreatedAt));
        return result;
    }

    public synchronized Job startJob(String region, String jobId) {
        Job job = requireJob(region, jobId);
        if ("COMPLETED".equals(job.getState()) || "ERROR".equals(job.getState())
                || "CANCELLED".equals(job.getState())) {
            return job;
        }
        job.setState("IN_PROGRESS");
        job.setUpdatedAt(now());
        if ("IMPORT_ASSETS_FROM_S3".equals(job.getType())) {
            runImportFromS3(region, job);
        } else {
            job.setState("COMPLETED");
        }
        job.setUpdatedAt(now());
        jobs.put(jobKey(region, jobId), job);
        return job;
    }

    public synchronized void cancelJob(String region, String jobId) {
        Job job = requireJob(region, jobId);
        if ("WAITING".equals(job.getState()) || "IN_PROGRESS".equals(job.getState())) {
            job.setState("CANCELLED");
            job.setUpdatedAt(now());
            jobs.put(jobKey(region, jobId), job);
        }
    }

    public void sendDataSetNotification(String region, String dataSetId, JsonNode request) {
        requireObject(request, "Request body");
        requireDataSet(region, dataSetId);
        throw new AwsException("ValidationException", MARKETPLACE_MESSAGE, 400);
    }

    public synchronized EventAction createEventAction(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode event = requireObjectField(request, "Event");
        JsonNode action = requireObjectField(request, "Action");
        String dataSetId = event.path("RevisionPublished").path("DataSetId").asText(null);
        if (dataSetId == null || dataSetId.isBlank()) {
            throw new AwsException("ValidationException", "Event.RevisionPublished.DataSetId is required.", 400);
        }
        DataSet dataSet = requireDataSet(region, dataSetId);
        if (!"ENTITLED".equals(dataSet.getOrigin())) {
            throw new AwsException(
                    "ValidationException",
                    "Event actions can only be created for entitled data sets.",
                    400);
        }
        String now = now();
        String id = newId();
        String account = regionResolver.getAccountId();
        EventAction eventAction = new EventAction();
        eventAction.setId(id);
        eventAction.setArn(arn(region, account, "event-actions/" + id));
        eventAction.setDataSetId(dataSetId);
        eventAction.setEvent(event.deepCopy());
        eventAction.setAction(action.deepCopy());
        eventAction.setCreatedAt(now);
        eventAction.setUpdatedAt(now);
        eventAction.setRegion(region);
        eventAction.setTags(readTags(request.get("Tags")));
        eventActions.put(eventActionKey(region, id), eventAction);
        return eventAction;
    }

    public EventAction getEventAction(String region, String eventActionId) {
        return requireEventAction(region, eventActionId);
    }

    public List<EventAction> listEventActions(String region, String eventSourceId) {
        List<EventAction> result = new ArrayList<>();
        for (EventAction eventAction : eventActions.values()) {
            if (!region.equals(eventAction.getRegion())) {
                continue;
            }
            if (eventSourceId != null && !eventSourceId.isBlank()
                    && !eventSourceId.equals(eventAction.getDataSetId())) {
                continue;
            }
            result.add(eventAction);
        }
        result.sort(Comparator.comparing(EventAction::getCreatedAt));
        return result;
    }

    public synchronized EventAction updateEventAction(String region, String eventActionId, JsonNode request) {
        requireObject(request, "Request body");
        EventAction eventAction = requireEventAction(region, eventActionId);
        if (request.has("Action") && request.get("Action").isObject()) {
            eventAction.setAction(request.get("Action").deepCopy());
        }
        eventAction.setUpdatedAt(now());
        eventActions.put(eventActionKey(region, eventActionId), eventAction);
        return eventAction;
    }

    public synchronized void deleteEventAction(String region, String eventActionId) {
        requireEventAction(region, eventActionId);
        eventActions.delete(eventActionKey(region, eventActionId));
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tags != null) {
            current.putAll(tags);
        }
        persistTags(tagged, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        persistTags(tagged, current);
    }

    private void runImportFromS3(String region, Job job) {
        JsonNode importDetails = job.getDetails() == null ? null : job.getDetails().get("ImportAssetsFromS3");
        if (importDetails == null || !importDetails.isObject()) {
            failJob(job, "VALIDATION_ERROR", "ImportAssetsFromS3 details are required.");
            return;
        }
        String dataSetId = textOrNull(importDetails, "DataSetId");
        String revisionId = textOrNull(importDetails, "RevisionId");
        JsonNode sources = importDetails.get("AssetSources");
        if (dataSetId == null || revisionId == null || sources == null || !sources.isArray()) {
            failJob(job, "VALIDATION_ERROR", "ImportAssetsFromS3.AssetSources is required.");
            return;
        }
        DataSet dataSet;
        Revision revision;
        try {
            dataSet = requireDataSet(region, dataSetId);
            revision = requireRevision(region, dataSetId, revisionId);
        } catch (AwsException e) {
            failJob(job, "RESOURCE_NOT_FOUND", e.getMessage());
            return;
        }
        if (s3Service == null) {
            failJob(job, "MALFORMED_INFO", "S3 is not available.");
            return;
        }
        for (JsonNode source : sources) {
            String bucket = textOrNull(source, "Bucket");
            String key = textOrNull(source, "Key");
            if (bucket == null || key == null) {
                failJob(job, "VALIDATION_ERROR", "AssetSources entries require Bucket and Key.");
                return;
            }
            S3Object object;
            try {
                object = s3Service.getObject(bucket, key);
            } catch (AwsException e) {
                failJob(job, "MALFORMED_INFO", "Unable to read s3://" + bucket + "/" + key + ": " + e.getMessage());
                return;
            }
            String now = now();
            String assetId = newId();
            String account = regionResolver.getAccountId();
            Asset asset = new Asset();
            asset.setId(assetId);
            asset.setArn(arn(region, account,
                    "data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets/" + assetId));
            asset.setDataSetId(dataSetId);
            asset.setRevisionId(revisionId);
            asset.setName(key);
            asset.setAssetType(dataSet.getAssetType());
            asset.setSize(object.getSize());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            asset.setRegion(region);
            assets.put(assetKey(region, dataSetId, revisionId, assetId), asset);
        }
        revision.setUpdatedAt(now());
        revisions.put(revisionKey(region, dataSetId, revisionId), revision);
        job.setState("COMPLETED");
    }

    private void failJob(Job job, String code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("Code", code);
        error.put("Message", message);
        List<JsonNode> errors = new ArrayList<>(job.getErrors());
        errors.add(error);
        job.setErrors(errors);
        job.setState("ERROR");
    }

    private DataSet requireDataSet(String region, String dataSetId) {
        if (dataSetId == null || dataSetId.isBlank()) {
            throw new AwsException("ValidationException", "DataSetId is required.", 400);
        }
        return dataSets.get(dataSetKey(region, dataSetId)).orElseThrow(
                () -> notFound(dataSetId, "DATA_SET"));
    }

    private Revision requireRevision(String region, String dataSetId, String revisionId) {
        if (revisionId == null || revisionId.isBlank()) {
            throw new AwsException("ValidationException", "RevisionId is required.", 400);
        }
        return revisions.get(revisionKey(region, dataSetId, revisionId)).orElseThrow(
                () -> notFound(revisionId, "REVISION"));
    }

    private Asset requireAsset(String region, String dataSetId, String revisionId, String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new AwsException("ValidationException", "AssetId is required.", 400);
        }
        return assets.get(assetKey(region, dataSetId, revisionId, assetId)).orElseThrow(
                () -> notFound(assetId, "ASSET"));
    }

    private Job requireJob(String region, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("ValidationException", "JobId is required.", 400);
        }
        return jobs.get(jobKey(region, jobId)).orElseThrow(() -> notFound(jobId, "JOB"));
    }

    private EventAction requireEventAction(String region, String eventActionId) {
        if (eventActionId == null || eventActionId.isBlank()) {
            throw new AwsException("ValidationException", "EventActionId is required.", 400);
        }
        return eventActions.get(eventActionKey(region, eventActionId)).orElseThrow(
                () -> notFound(eventActionId, "EVENT_ACTION"));
    }

    private Tagged requireTagged(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN.", 400);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw new AwsException("ValidationException", "Invalid resource ARN.", 400);
        }
        String resource = parsed.resource();
        String lookupRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        String[] parts = resource.split("/");
        if (parts.length >= 2 && "data-sets".equals(parts[0]) && parts.length == 2) {
            DataSet dataSet = requireDataSet(lookupRegion, parts[1]);
            return new Tagged(Kind.DATA_SET, dataSet, null, null);
        }
        if (parts.length >= 4 && "data-sets".equals(parts[0]) && "revisions".equals(parts[2]) && parts.length == 4) {
            Revision revision = requireRevision(lookupRegion, parts[1], parts[3]);
            return new Tagged(Kind.REVISION, null, revision, null);
        }
        if (parts.length >= 6 && "data-sets".equals(parts[0]) && "revisions".equals(parts[2])
                && "assets".equals(parts[4])) {
            Asset asset = requireAsset(lookupRegion, parts[1], parts[3], parts[5]);
            return new Tagged(Kind.ASSET, null, null, asset);
        }
        if (parts.length >= 2 && "event-actions".equals(parts[0])) {
            EventAction eventAction = requireEventAction(lookupRegion, parts[1]);
            return new Tagged(Kind.EVENT_ACTION, eventAction);
        }
        throw notFound(resource, "RESOURCE");
    }

    private void persistTags(Tagged tagged, Map<String, String> tags) {
        String stamp = now();
        switch (tagged.kind) {
            case DATA_SET -> {
                DataSet dataSet = tagged.dataSet;
                dataSet.setTags(tags);
                dataSet.setUpdatedAt(stamp);
                dataSets.put(dataSetKey(dataSet.getRegion(), dataSet.getId()), dataSet);
            }
            case REVISION -> {
                Revision revision = tagged.revision;
                revision.setTags(tags);
                revision.setUpdatedAt(stamp);
                revisions.put(revisionKey(revision.getRegion(), revision.getDataSetId(), revision.getId()),
                        revision);
            }
            case ASSET -> {
                Asset asset = tagged.asset;
                asset.setTags(tags);
                asset.setUpdatedAt(stamp);
                assets.put(assetKey(asset.getRegion(), asset.getDataSetId(), asset.getRevisionId(), asset.getId()),
                        asset);
            }
            case EVENT_ACTION -> {
                EventAction eventAction = tagged.eventAction;
                eventAction.setTags(tags);
                eventAction.setUpdatedAt(stamp);
                eventActions.put(eventActionKey(eventAction.getRegion(), eventAction.getId()), eventAction);
            }
        }
    }

    private static String jobDataSetId(String type, JsonNode details) {
        JsonNode nested = jobDetailsNode(type, details);
        return nested == null ? null : textOrNull(nested, "DataSetId");
    }

    private static String jobRevisionId(String type, JsonNode details) {
        JsonNode nested = jobDetailsNode(type, details);
        return nested == null ? null : textOrNull(nested, "RevisionId");
    }

    private static JsonNode jobDetailsNode(String type, JsonNode details) {
        if (details == null) {
            return null;
        }
        return switch (type) {
            case "IMPORT_ASSETS_FROM_S3" -> details.get("ImportAssetsFromS3");
            case "EXPORT_ASSETS_TO_S3" -> details.get("ExportAssetsToS3");
            case "EXPORT_REVISIONS_TO_S3" -> details.get("ExportRevisionsToS3");
            default -> details.elements().hasNext() ? details.elements().next() : details;
        };
    }

    ObjectNode toDataSet(DataSet dataSet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", dataSet.getArn());
        node.put("AssetType", dataSet.getAssetType());
        node.put("CreatedAt", dataSet.getCreatedAt());
        node.put("Description", dataSet.getDescription());
        node.put("Id", dataSet.getId());
        node.put("Name", dataSet.getName());
        node.put("Origin", dataSet.getOrigin());
        node.put("UpdatedAt", dataSet.getUpdatedAt());
        putTags(node, dataSet.getTags());
        return node;
    }

    ObjectNode toRevision(Revision revision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", revision.getArn());
        if (revision.getComment() != null) {
            node.put("Comment", revision.getComment());
        }
        node.put("CreatedAt", revision.getCreatedAt());
        node.put("DataSetId", revision.getDataSetId());
        node.put("Finalized", revision.isFinalized());
        node.put("Id", revision.getId());
        node.put("Revoked", revision.isRevoked());
        node.put("UpdatedAt", revision.getUpdatedAt());
        putTags(node, revision.getTags());
        return node;
    }

    ObjectNode toAsset(Asset asset) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", asset.getArn());
        ObjectNode details = node.putObject("AssetDetails");
        details.putObject("S3SnapshotAsset").put("Size", asset.getSize());
        node.put("AssetType", asset.getAssetType());
        node.put("CreatedAt", asset.getCreatedAt());
        node.put("DataSetId", asset.getDataSetId());
        node.put("Id", asset.getId());
        node.put("Name", asset.getName());
        node.put("RevisionId", asset.getRevisionId());
        node.put("UpdatedAt", asset.getUpdatedAt());
        putTags(node, asset.getTags());
        return node;
    }

    ObjectNode toJob(Job job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", job.getArn());
        node.put("CreatedAt", job.getCreatedAt());
        if (job.getDetails() != null) {
            node.set("Details", job.getDetails());
        }
        ArrayNode errors = node.putArray("Errors");
        for (JsonNode error : job.getErrors()) {
            errors.add(error);
        }
        node.put("Id", job.getId());
        node.put("State", job.getState());
        node.put("Type", job.getType());
        node.put("UpdatedAt", job.getUpdatedAt());
        return node;
    }

    ObjectNode toEventAction(EventAction eventAction) {
        ObjectNode node = objectMapper.createObjectNode();
        if (eventAction.getAction() != null) {
            node.set("Action", eventAction.getAction());
        }
        node.put("Arn", eventAction.getArn());
        node.put("CreatedAt", eventAction.getCreatedAt());
        if (eventAction.getEvent() != null) {
            node.set("Event", eventAction.getEvent());
        }
        node.put("Id", eventAction.getId());
        node.put("UpdatedAt", eventAction.getUpdatedAt());
        putTags(node, eventAction.getTags());
        return node;
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode node = parent.putObject("Tags");
        tags.forEach(node::put);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + resourceId + " of type " + resourceType + " was not found.",
                404,
                Map.of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    private static void requireObject(JsonNode request, String name) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", name + " must be a JSON object.", 400);
        }
    }

    private static JsonNode requireObjectField(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || !node.isObject()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return node;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        return textOrNull(request, field);
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        JsonNode node = request.get(field);
        if (!node.isTextual()) {
            throw new AwsException("ValidationException", field + " must be a string.", 400);
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String arn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    private static String dataSetKey(String region, String id) {
        return region + ":ds:" + id;
    }

    private static String revisionKey(String region, String dataSetId, String revisionId) {
        return region + ":rev:" + dataSetId + ":" + revisionId;
    }

    private static String assetKey(String region, String dataSetId, String revisionId, String assetId) {
        return region + ":asset:" + dataSetId + ":" + revisionId + ":" + assetId;
    }

    private static String jobKey(String region, String jobId) {
        return region + ":job:" + jobId;
    }

    private static String eventActionKey(String region, String eventActionId) {
        return region + ":ea:" + eventActionId;
    }

    private enum Kind {
        DATA_SET, REVISION, ASSET, EVENT_ACTION
    }

    private record Tagged(Kind kind, DataSet dataSet, Revision revision, Asset asset, EventAction eventAction) {
        Tagged(Kind kind, DataSet dataSet, Revision revision, Asset asset) {
            this(kind, dataSet, revision, asset, null);
        }

        Tagged(Kind kind, EventAction eventAction) {
            this(kind, null, null, null, eventAction);
        }

        Map<String, String> tags() {
            return switch (kind) {
                case DATA_SET -> dataSet.getTags();
                case REVISION -> revision.getTags();
                case ASSET -> asset.getTags();
                case EVENT_ACTION -> eventAction.getTags();
            };
        }
    }
}
