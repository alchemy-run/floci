package io.floci.aws.services.dataexchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * AWS Data Exchange: owned data sets with revisions and assets, data grants that share a data set with another
 * account, the entitled copies the receiver gets, event actions that auto-export new revisions, provider
 * notifications, and tags. Jobs move data in and out of revisions ({@link DataExchangeJobs}).
 * <p>
 * Accepting a data grant creates an {@code ENTITLED} data set in the receiver's account holding the source's
 * finalized revisions. Finalizing a revision on a shared data set publishes it to every receiver, revoking it revokes
 * it there, and each delivery reports {@code aws.dataexchange} events to the receiver's EventBridge and runs its
 * auto-export event actions. Every account also holds an entitled {@code AWS Data Exchange Heartbeat} data set, like
 * the free product of that name, which receives a revision with the current time every hour.
 */
@ApplicationScoped
public class DataExchangeService implements Resettable {

    private static final Logger LOG = Logger.getLogger(DataExchangeService.class);
    static final String HEARTBEAT_ID = "f10c1000000040008000000000000ea7";
    static final Set<String> ASSET_TYPES = Set.of("S3_SNAPSHOT", "REDSHIFT_DATA_SHARE", "API_GATEWAY_API",
            "S3_DATA_ACCESS", "LAKE_FORMATION_DATA_PERMISSION");
    private static final int MAX_DATA_SETS = 1000;
    private static final int MAX_REVISIONS = 300;
    private static final int MAX_EVENT_ACTIONS_PER_DATA_SET = 5;

    final AccountAwareStorageBackend<ObjectNode> dataSets;
    final AccountAwareStorageBackend<ObjectNode> revisions;
    final AccountAwareStorageBackend<ObjectNode> assets;
    final AccountAwareStorageBackend<String> assetData;
    final AccountAwareStorageBackend<ObjectNode> eventActions;
    final AccountAwareStorageBackend<ObjectNode> grants;
    final ObjectMapper mapper;
    final RegionResolver regionResolver;
    private final Instance<EventBridgeService> events;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dataexchange-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final Instance<DataExchangeJobs> jobs;

    @Inject
    public DataExchangeService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver,
                               Instance<EventBridgeService> events, Instance<DataExchangeJobs> jobs) {
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        this.dataSets = storageFactory.create("dataexchange", "data-sets.json", type);
        this.revisions = storageFactory.create("dataexchange", "revisions.json", type);
        this.assets = storageFactory.create("dataexchange", "assets.json", type);
        this.assetData = storageFactory.create("dataexchange", "asset-data.json",
                new TypeReference<Map<String, String>>() {});
        this.eventActions = storageFactory.create("dataexchange", "event-actions.json", type);
        this.grants = storageFactory.create("dataexchange", "data-grants.json", type);
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.events = events;
        this.jobs = jobs;
        ticker.scheduleAtFixedRate(this::heartbeats, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
    }

    // ---------------------------------------------------------------- data sets

    public synchronized ObjectNode createDataSet(JsonNode request, String region) {
        seed(region);
        String assetType = oneOf(request, "AssetType", ASSET_TYPES);
        String name = text(request, "Name", 256);
        String description = text(request, "Description", 16384);
        if (owned(region).size() >= MAX_DATA_SETS) {
            throw limit("Data sets per account", MAX_DATA_SETS);
        }
        ObjectNode dataSet = mapper.createObjectNode();
        String id = id();
        dataSet.put("Arn", arn(region, "data-sets/" + id));
        dataSet.put("AssetType", assetType);
        stamp(dataSet, true);
        dataSet.put("Description", description);
        dataSet.put("Id", id);
        dataSet.put("Name", name);
        dataSet.put("Origin", "OWNED");
        dataSet.set("Tags", tags(request.path("Tags")));
        dataSet.putObject("_").put("region", region);
        save(dataSets, region, id, dataSet);
        return view(dataSet);
    }

    public synchronized ObjectNode getDataSet(String id, String region) {
        seed(region);
        return view(dataSet(region, id));
    }

    public synchronized ObjectNode updateDataSet(String id, JsonNode request, String region) {
        ObjectNode dataSet = dataSet(region, id);
        requireOwned(dataSet);
        if (present(request, "Name")) {
            dataSet.put("Name", text(request, "Name", 256));
        }
        if (present(request, "Description")) {
            dataSet.put("Description", text(request, "Description", 16384));
        }
        stamp(dataSet, false);
        save(dataSets, region, id, dataSet);
        ObjectNode view = view(dataSet);
        view.remove("Tags");
        return view;
    }

    public synchronized void deleteDataSet(String id, String region) {
        ObjectNode dataSet = dataSet(region, id);
        if ("ENTITLED".equals(dataSet.path("Origin").asText())) {
            throw invalid("Entitled data sets cannot be deleted; they end with their grant or subscription.");
        }
        for (ObjectNode grant : regional(grants, region)) {
            if (id.equals(grant.path("SourceDataSetId").asText())) {
                throw conflict("Data set " + id + " is shared by data grant " + grant.path("Id").asText()
                        + "; delete the grant first.", id, "DATA_SET");
            }
        }
        if (jobs.get().hasJobInProgress(region, id)) {
            throw conflict("Data set " + id + " has a job in progress.", id, "DATA_SET");
        }
        for (ObjectNode revision : revisionsOf(region, id)) {
            dropRevision(region, id, revision.path("Id").asText());
        }
        dataSets.deleteForAccount(account(), key(region, id));
    }

    public synchronized ObjectNode listDataSets(JsonNode query, String region) {
        seed(region);
        String origin = query.path("origin").asText(null);
        if (origin != null && !Set.of("OWNED", "ENTITLED").contains(origin)) {
            throw invalid("Origin must be OWNED or ENTITLED.");
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode dataSet : regional(dataSets, region)) {
            if (origin == null || origin.equals(dataSet.path("Origin").asText())) {
                ObjectNode entry = view(dataSet);
                entry.remove("Tags");
                items.add(entry);
            }
        }
        return page(items, query, "DataSets", item -> item.path("Id").asText());
    }

    // ---------------------------------------------------------------- revisions

    public synchronized ObjectNode createRevision(String dataSetId, JsonNode request, String region) {
        ObjectNode dataSet = dataSet(region, dataSetId);
        requireOwned(dataSet);
        if (revisionsOf(region, dataSetId).size() >= MAX_REVISIONS) {
            throw limit("Revisions per data set", MAX_REVISIONS);
        }
        String id = id();
        ObjectNode revision = mapper.createObjectNode();
        revision.put("Arn", dataSet.path("Arn").asText() + "/revisions/" + id);
        if (present(request, "Comment")) {
            revision.put("Comment", text(request, "Comment", 16384));
        }
        stamp(revision, true);
        revision.put("DataSetId", dataSetId);
        revision.put("Finalized", false);
        revision.put("Id", id);
        revision.set("Tags", tags(request.path("Tags")));
        revision.putObject("_").put("region", region);
        save(revisions, region, dataSetId + "/" + id, revision);
        return view(revision);
    }

    public synchronized ObjectNode getRevision(String dataSetId, String revisionId, String region) {
        seed(region);
        return view(revision(region, dataSetId, revisionId));
    }

    public synchronized ObjectNode updateRevision(String dataSetId, String revisionId, JsonNode request,
                                                  String region) {
        requireOwned(dataSet(region, dataSetId));
        ObjectNode revision = revision(region, dataSetId, revisionId);
        if (revision.path("Revoked").asBoolean(false)) {
            throw conflict("Revision " + revisionId + " is revoked.", revisionId, "REVISION");
        }
        if (present(request, "Comment")) {
            revision.put("Comment", text(request, "Comment", 16384));
        }
        boolean publish = false;
        if (request.has("Finalized")) {
            boolean finalize = request.get("Finalized").asBoolean();
            boolean was = revision.path("Finalized").asBoolean(false);
            if (finalize && !was && assetsOf(region, dataSetId, revisionId).isEmpty()) {
                throw invalid("A revision must have at least one asset before it can be finalized.");
            }
            if (!finalize && was && !receivers(region, dataSetId).isEmpty()) {
                throw conflict("Revision " + revisionId + " is published to data grant receivers and cannot be "
                        + "unfinalized.", revisionId, "REVISION");
            }
            revision.put("Finalized", finalize);
            publish = finalize && !was;
        }
        stamp(revision, false);
        save(revisions, region, dataSetId + "/" + revisionId, revision);
        if (publish) {
            for (ObjectNode grant : receivers(region, dataSetId)) {
                deliver(grant, revision, region);
            }
        }
        ObjectNode view = view(revision);
        view.remove("Tags");
        return view;
    }

    public synchronized void deleteRevision(String dataSetId, String revisionId, String region) {
        requireOwned(dataSet(region, dataSetId));
        ObjectNode revision = revision(region, dataSetId, revisionId);
        if (revision.path("Finalized").asBoolean(false) && !receivers(region, dataSetId).isEmpty()) {
            throw conflict("Revision " + revisionId + " is published to data grant receivers; revoke it instead.",
                    revisionId, "REVISION");
        }
        dropRevision(region, dataSetId, revisionId);
    }

    public synchronized ObjectNode revokeRevision(String dataSetId, String revisionId, JsonNode request,
                                                  String region) {
        requireOwned(dataSet(region, dataSetId));
        ObjectNode revision = revision(region, dataSetId, revisionId);
        String comment = text(request, "RevocationComment", 512);
        if (comment.length() < 10) {
            throw invalid("RevocationComment must be between 10 and 512 characters.");
        }
        if (!revision.path("Finalized").asBoolean(false)) {
            throw conflict("Only finalized revisions can be revoked.", revisionId, "REVISION");
        }
        if (revision.path("Revoked").asBoolean(false)) {
            throw conflict("Revision " + revisionId + " is already revoked.", revisionId, "REVISION");
        }
        revoke(revision, comment);
        save(revisions, region, dataSetId + "/" + revisionId, revision);
        for (ObjectNode grant : receivers(region, dataSetId)) {
            revokeDelivered(grant, revisionId, comment, region);
        }
        ObjectNode view = view(revision);
        view.remove("Tags");
        return view;
    }

    public synchronized ObjectNode listDataSetRevisions(String dataSetId, JsonNode query, String region) {
        seed(region);
        dataSet(region, dataSetId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode revision : revisionsOf(region, dataSetId)) {
            ObjectNode entry = view(revision);
            entry.remove("Tags");
            items.add(entry);
        }
        items.sort(Comparator.comparing((ObjectNode item) -> item.path("CreatedAt").asText()).reversed());
        return page(items, query, "Revisions", item -> item.path("Id").asText());
    }

    // ---------------------------------------------------------------- assets

    public synchronized ObjectNode getAsset(String dataSetId, String revisionId, String assetId, String region) {
        seed(region);
        return view(asset(region, dataSetId, revisionId, assetId));
    }

    public synchronized ObjectNode updateAsset(String dataSetId, String revisionId, String assetId, JsonNode request,
                                               String region) {
        requireOwned(dataSet(region, dataSetId));
        if (revision(region, dataSetId, revisionId).path("Finalized").asBoolean(false)) {
            throw conflict("Assets of a finalized revision cannot be changed.", revisionId, "REVISION");
        }
        ObjectNode asset = asset(region, dataSetId, revisionId, assetId);
        asset.put("Name", text(request, "Name", 1024));
        stamp(asset, false);
        save(assets, region, dataSetId + "/" + revisionId + "/" + assetId, asset);
        return view(asset);
    }

    public synchronized void deleteAsset(String dataSetId, String revisionId, String assetId, String region) {
        requireOwned(dataSet(region, dataSetId));
        if (revision(region, dataSetId, revisionId).path("Finalized").asBoolean(false)) {
            throw conflict("Assets of a finalized revision cannot be deleted.", revisionId, "REVISION");
        }
        asset(region, dataSetId, revisionId, assetId);
        String assetKey = key(region, dataSetId + "/" + revisionId + "/" + assetId);
        assets.deleteForAccount(account(), assetKey);
        assetData.deleteForAccount(account(), assetKey);
    }

    public synchronized ObjectNode listRevisionAssets(String dataSetId, String revisionId, JsonNode query,
                                                      String region) {
        seed(region);
        revision(region, dataSetId, revisionId);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode asset : assetsOf(region, dataSetId, revisionId)) {
            items.add(view(asset));
        }
        return page(items, query, "Assets", item -> item.path("Id").asText());
    }

    /** Stores an asset in a revision, replacing one of the same name, with its bytes when it has any. */
    synchronized ObjectNode putAsset(String region, String dataSetId, String revisionId, String name,
                                     String assetType, ObjectNode details, byte[] data, String sourceId) {
        ObjectNode asset = null;
        for (ObjectNode existing : assetsOf(region, dataSetId, revisionId)) {
            if (name.equals(existing.path("Name").asText())) {
                asset = existing;
            }
        }
        if (asset == null) {
            String id = id();
            asset = mapper.createObjectNode();
            asset.put("Arn", arn(region, "data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets/" + id));
            stamp(asset, true);
            asset.put("DataSetId", dataSetId);
            asset.put("Id", id);
            asset.put("RevisionId", revisionId);
            asset.putObject("_").put("region", region);
        }
        asset.put("Name", name);
        asset.put("AssetType", assetType);
        asset.set("AssetDetails", details);
        if (sourceId != null) {
            asset.put("SourceId", sourceId);
        }
        stamp(asset, false);
        String assetKey = dataSetId + "/" + revisionId + "/" + asset.path("Id").asText();
        save(assets, region, assetKey, asset);
        if (data != null) {
            assetData.putForAccount(account(), key(region, assetKey), Base64.getEncoder().encodeToString(data));
        }
        return asset;
    }

    byte[] assetBytes(String region, String dataSetId, String revisionId, String assetId) {
        return assetData.getForAccount(account(), key(region, dataSetId + "/" + revisionId + "/" + assetId))
                .map(Base64.getDecoder()::decode).orElse(new byte[0]);
    }

    // ---------------------------------------------------------------- data grants

    public synchronized ObjectNode createDataGrant(JsonNode request, String region) {
        String sourceId = text(request, "SourceDataSetId", 40);
        ObjectNode source = dataSet(region, sourceId);
        requireOwned(source);
        String receiver = text(request, "ReceiverPrincipal", 12);
        if (!receiver.matches("\\d{12}")) {
            throw invalid("ReceiverPrincipal must be a 12-digit AWS account ID.");
        }
        if (receiver.equals(account())) {
            throw invalid("A data grant cannot be sent to the sending account.");
        }
        String scope = oneOf(request, "GrantDistributionScope", Set.of("AWS_ORGANIZATION", "NONE"));
        Instant endsAt = present(request, "EndsAt") ? instant(request.get("EndsAt")) : null;
        if (endsAt != null && endsAt.isBefore(Instant.now())) {
            throw invalid("EndsAt must be in the future.");
        }
        String id = id();
        ObjectNode grant = mapper.createObjectNode();
        grant.put("Name", text(request, "Name", 256));
        grant.put("SenderPrincipal", account());
        grant.put("ReceiverPrincipal", receiver);
        if (present(request, "Description")) {
            grant.put("Description", text(request, "Description", 16384));
        }
        grant.put("AcceptanceState", "PENDING_RECEIVER_ACCEPTANCE");
        if (endsAt != null) {
            grant.put("EndsAt", endsAt.toString());
        }
        grant.put("GrantDistributionScope", scope);
        grant.put("DataSetId", sourceId);
        grant.put("SourceDataSetId", sourceId);
        grant.put("Id", id);
        grant.put("Arn", arn(region, "data-grants/" + id));
        stamp(grant, true);
        grant.set("Tags", tags(request.path("Tags")));
        grant.putObject("_").put("region", region);
        save(grants, region, id, grant);
        ObjectNode received = grant.deepCopy();
        received.remove("SourceDataSetId");
        received.remove("Tags");
        grants.putForAccount(receiver, key(region, "received/" + id), received);
        return view(grant);
    }

    public synchronized ObjectNode getDataGrant(String grantId, String region) {
        return view(sentGrant(region, grantId));
    }

    public synchronized void deleteDataGrant(String grantId, String region) {
        ObjectNode grant = sentGrant(region, grantId);
        String id = grant.path("Id").asText();
        String receiver = grant.path("ReceiverPrincipal").asText();
        grants.deleteForAccount(account(), key(region, id));
        ObjectNode received = grants.getForAccount(receiver, key(region, "received/" + id)).orElse(null);
        grants.deleteForAccount(receiver, key(region, "received/" + id));
        if (received != null && "ACCEPTED".equals(received.path("AcceptanceState").asText())) {
            String entitled = received.path("DataSetId").asText();
            RequestScopes.runAs(receiver, region, () -> {
                for (ObjectNode revision : revisionsOf(region, entitled)) {
                    dropRevision(region, entitled, revision.path("Id").asText());
                }
                dataSets.deleteForAccount(receiver, key(region, entitled));
                publish(region, "Data Grant Revoked", grantDetail(received), received.path("Arn").asText());
            });
        }
    }

    public synchronized ObjectNode listDataGrants(JsonNode query, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode grant : regional(grants, region)) {
            ObjectNode entry = view(grant);
            entry.remove("Tags");
            entry.remove("Description");
            entry.remove("GrantDistributionScope");
            items.add(entry);
        }
        return page(items, query, "DataGrantSummaries", item -> item.path("Id").asText());
    }

    public synchronized ObjectNode getReceivedDataGrant(String grantArn, String region) {
        return view(receivedGrant(region, grantArn));
    }

    public synchronized ObjectNode listReceivedDataGrants(JsonNode query, List<String> states, String region) {
        for (String state : states) {
            if (!Set.of("PENDING_RECEIVER_ACCEPTANCE", "ACCEPTED").contains(state)) {
                throw invalid("AcceptanceState must be PENDING_RECEIVER_ACCEPTANCE or ACCEPTED.");
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode grant : grants.scanForAccount(account(), key -> key.startsWith(region + "/received/"))) {
            if (states.isEmpty() || states.contains(grant.path("AcceptanceState").asText())) {
                ObjectNode entry = view(grant);
                entry.remove("Description");
                entry.remove("GrantDistributionScope");
                items.add(entry);
            }
        }
        return page(items, query, "DataGrantSummaries", item -> item.path("Id").asText());
    }

    /** Accepting a grant gives the receiver an entitled copy of the source data set and its finalized revisions. */
    public synchronized ObjectNode acceptDataGrant(String grantArn, String region) {
        ObjectNode received = receivedGrant(region, grantArn);
        String grantId = received.path("Id").asText();
        if ("ACCEPTED".equals(received.path("AcceptanceState").asText())) {
            throw conflict("Data grant " + grantId + " is already accepted.", grantId, "DATA_GRANT");
        }
        if (received.hasNonNull("EndsAt") && Instant.parse(received.get("EndsAt").asText()).isBefore(Instant.now())) {
            throw invalid("Data grant " + grantId + " has expired.");
        }
        String sender = received.path("SenderPrincipal").asText();
        String receiver = account();
        ObjectNode sent = grants.getForAccount(sender, key(region, grantId))
                .orElseThrow(() -> notFound("Data grant " + grantId + " no longer exists.", grantId, "DATA_GRANT"));
        String sourceId = sent.path("SourceDataSetId").asText();
        ObjectNode source = dataSets.getForAccount(sender, key(region, sourceId))
                .orElseThrow(() -> notFound("The granted data set no longer exists.", grantId, "DATA_GRANT"));
        String entitledId = id();
        ObjectNode entitled = mapper.createObjectNode();
        entitled.put("Arn", arn(region, "data-sets/" + entitledId));
        entitled.put("AssetType", source.path("AssetType").asText());
        stamp(entitled, true);
        entitled.put("Description", source.path("Description").asText());
        entitled.put("Id", entitledId);
        entitled.put("Name", source.path("Name").asText());
        entitled.put("Origin", "ENTITLED");
        entitled.putObject("OriginDetails").put("DataGrantId", grantId);
        entitled.put("SourceId", sourceId);
        entitled.putObject("Tags");
        entitled.putObject("_").put("region", region).put("sender", sender);
        save(dataSets, region, entitledId, entitled);
        String now = now();
        received.put("AcceptanceState", "ACCEPTED");
        received.put("AcceptedAt", now);
        received.put("UpdatedAt", now);
        received.put("DataSetId", entitledId);
        grants.putForAccount(receiver, key(region, "received/" + grantId), received);
        sent.put("AcceptanceState", "ACCEPTED");
        sent.put("AcceptedAt", now);
        sent.put("UpdatedAt", now);
        grants.putForAccount(sender, key(region, grantId), sent);
        List<ObjectNode> sourceRevisions = RequestScopes.callAs(sender, region, () -> revisionsOf(region, sourceId));
        sourceRevisions.sort(Comparator.comparing(value -> value.path("CreatedAt").asText()));
        for (ObjectNode revision : sourceRevisions) {
            if (revision.path("Finalized").asBoolean(false)) {
                copyRevision(sender, region, revision, entitled, false);
            }
        }
        publish(region, "Data Grant Accepted", grantDetail(received), received.path("Arn").asText());
        RequestScopes.runAs(sender, region, () -> publish(region, "Data Grant Accepted", grantDetail(sent),
                sent.path("Arn").asText()));
        return view(received);
    }

    private ObjectNode grantDetail(JsonNode grant) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("DataGrantId", grant.path("Id").asText());
        detail.put("DataGrantArn", grant.path("Arn").asText());
        detail.put("DataSetId", grant.path("DataSetId").asText());
        return detail;
    }

    /** The accepted grants that share {@code dataSetId}, as the sender's records. */
    private List<ObjectNode> receivers(String region, String dataSetId) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode grant : regional(grants, region)) {
            if (dataSetId.equals(grant.path("SourceDataSetId").asText())
                    && "ACCEPTED".equals(grant.path("AcceptanceState").asText())) {
                result.add(grant);
            }
        }
        return result;
    }

    private void deliver(ObjectNode grant, ObjectNode revision, String region) {
        String receiver = grant.path("ReceiverPrincipal").asText();
        String sender = account();
        String grantId = grant.path("Id").asText();
        RequestScopes.runAs(receiver, region, () -> grants.getForAccount(receiver, key(region, "received/" + grantId))
                .flatMap(received -> dataSets.getForAccount(receiver, key(region, received.path("DataSetId").asText())))
                .ifPresent(entitled -> copyRevision(sender, region, revision, entitled, true)));
    }

    private void revokeDelivered(ObjectNode grant, String sourceRevisionId, String comment, String region) {
        String receiver = grant.path("ReceiverPrincipal").asText();
        String grantId = grant.path("Id").asText();
        RequestScopes.runAs(receiver, region, () -> {
            ObjectNode received = grants.getForAccount(receiver, key(region, "received/" + grantId)).orElse(null);
            if (received == null) {
                return;
            }
            String entitled = received.path("DataSetId").asText();
            String dataSetArn = dataSets.getForAccount(receiver, key(region, entitled))
                    .map(value -> value.path("Arn").asText()).orElse(entitled);
            for (ObjectNode copy : revisionsOf(region, entitled)) {
                if (sourceRevisionId.equals(copy.path("SourceId").asText())) {
                    revoke(copy, comment);
                    save(revisions, region, entitled + "/" + copy.path("Id").asText(), copy);
                    ObjectNode detail = mapper.createObjectNode();
                    detail.putArray("RevisionIds").add(copy.path("Id").asText());
                    detail.put("RevocationComment", comment);
                    publish(region, "Revision Revoked", detail, dataSetArn);
                }
            }
        });
    }

    /** Copies a finalized revision and its assets into an entitled data set of the current account. */
    private void copyRevision(String sourceAccount, String region, ObjectNode source, ObjectNode entitled,
                              boolean notify) {
        String entitledId = entitled.path("Id").asText();
        String revisionId = id();
        ObjectNode copy = mapper.createObjectNode();
        copy.put("Arn", entitled.path("Arn").asText() + "/revisions/" + revisionId);
        if (source.hasNonNull("Comment")) {
            copy.put("Comment", source.get("Comment").asText());
        }
        stamp(copy, true);
        copy.put("DataSetId", entitledId);
        copy.put("Finalized", true);
        copy.put("Id", revisionId);
        copy.put("SourceId", source.path("Id").asText());
        if (source.path("Revoked").asBoolean(false)) {
            revoke(copy, source.path("RevocationComment").asText());
        }
        copy.putObject("Tags");
        copy.putObject("_").put("region", region);
        save(revisions, region, entitledId + "/" + revisionId, copy);
        String sourceDataSet = source.path("DataSetId").asText();
        String sourceRevision = source.path("Id").asText();
        List<ObjectNode> sourceAssets = RequestScopes.callAs(sourceAccount, region,
                () -> assetsOf(region, sourceDataSet, sourceRevision));
        for (ObjectNode asset : sourceAssets) {
            String assetId = asset.path("Id").asText();
            byte[] data = RequestScopes.callAs(sourceAccount, region,
                    () -> assetBytes(region, sourceDataSet, sourceRevision, assetId));
            putAsset(region, entitledId, revisionId, asset.path("Name").asText(), asset.path("AssetType").asText(),
                    asset.path("AssetDetails").deepCopy(), data.length == 0 ? null : data, assetId);
        }
        if (notify) {
            ObjectNode detail = mapper.createObjectNode();
            detail.putArray("RevisionIds").add(revisionId);
            publish(region, "Revision Published To Data Set", detail, entitled.path("Arn").asText());
            jobs.get().autoExport(region, entitled, copy);
        }
    }

    // ---------------------------------------------------------------- event actions

    public synchronized ObjectNode createEventAction(JsonNode request, String region) {
        seed(region);
        String dataSetId = text(request.path("Event").path("RevisionPublished"), "DataSetId", 40);
        ObjectNode dataSet = dataSets.getForAccount(account(), key(region, dataSetId))
                .orElseThrow(() -> invalid("Data set " + dataSetId + " does not exist."));
        if (!"ENTITLED".equals(dataSet.path("Origin").asText())) {
            throw invalid("Event actions can only be created for entitled data sets; " + dataSetId + " is "
                    + dataSet.path("Origin").asText() + ".");
        }
        if (eventActionsFor(region, dataSetId).size() >= MAX_EVENT_ACTIONS_PER_DATA_SET) {
            throw limit("Auto export event actions per data set", MAX_EVENT_ACTIONS_PER_DATA_SET);
        }
        ObjectNode action = mapper.createObjectNode();
        String id = id();
        action.set("Action", exportAction(request.path("Action")));
        action.put("Arn", arn(region, "event-actions/" + id));
        stamp(action, true);
        action.set("Event", request.get("Event").deepCopy());
        action.put("Id", id);
        action.set("Tags", tags(request.path("Tags")));
        action.putObject("_").put("region", region);
        save(eventActions, region, id, action);
        return view(action);
    }

    public synchronized ObjectNode getEventAction(String id, String region) {
        return view(eventAction(region, id));
    }

    public synchronized ObjectNode updateEventAction(String id, JsonNode request, String region) {
        ObjectNode action = eventAction(region, id);
        if (present(request, "Action")) {
            action.set("Action", exportAction(request.get("Action")));
        }
        stamp(action, false);
        save(eventActions, region, id, action);
        ObjectNode view = view(action);
        view.remove("Tags");
        return view;
    }

    public synchronized void deleteEventAction(String id, String region) {
        eventAction(region, id);
        eventActions.deleteForAccount(account(), key(region, id));
    }

    public synchronized ObjectNode listEventActions(JsonNode query, String region) {
        String source = query.path("eventSourceId").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode action : regional(eventActions, region)) {
            if (source == null || source.equals(action.path("Event").path("RevisionPublished").path("DataSetId")
                    .asText())) {
                ObjectNode entry = view(action);
                entry.remove("Tags");
                items.add(entry);
            }
        }
        return page(items, query, "EventActions", item -> item.path("Id").asText());
    }

    List<ObjectNode> eventActionsFor(String region, String dataSetId) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode action : regional(eventActions, region)) {
            if (dataSetId.equals(action.path("Event").path("RevisionPublished").path("DataSetId").asText())) {
                result.add(action);
            }
        }
        return result;
    }

    private ObjectNode exportAction(JsonNode action) {
        JsonNode export = action.path("ExportRevisionToS3");
        JsonNode destination = export.path("RevisionDestination");
        text(destination, "Bucket", 63);
        if (destination.hasNonNull("KeyPattern")) {
            String pattern = destination.get("KeyPattern").asText();
            if (!pattern.contains("${Asset.Name}") && !pattern.contains("${Asset.Id}")) {
                throw invalid("KeyPattern must include ${Asset.Name} or ${Asset.Id}.");
            }
        }
        if (export.has("Encryption")) {
            String type = text(export.get("Encryption"), "Type", 16);
            if (!Set.of("aws:kms", "AES256").contains(type)) {
                throw invalid("Encryption.Type must be aws:kms or AES256.");
            }
            if ("aws:kms".equals(type) && !export.get("Encryption").hasNonNull("KmsKeyArn")) {
                throw invalid("aws:kms encryption needs a KmsKeyArn.");
            }
        }
        return (ObjectNode) action.deepCopy();
    }

    // ---------------------------------------------------------------- notifications

    public synchronized void sendDataSetNotification(String dataSetId, JsonNode request, String region) {
        ObjectNode dataSet = dataSet(region, dataSetId);
        String type = oneOf(request, "Type", Set.of("DATA_DELAY", "DATA_UPDATE", "DEPRECATION", "SCHEMA_CHANGE"));
        if (!"OWNED".equals(dataSet.path("Origin").asText())) {
            throw invalid("Only the data set's provider can send notifications about it.");
        }
        List<ObjectNode> receivers = receivers(region, dataSetId);
        if (receivers.isEmpty()) {
            throw invalid("Data set " + dataSetId + " is not configured for AWS Marketplace or shared through an "
                    + "accepted data grant, so it has no subscribers to notify.");
        }
        if ("DEPRECATION".equals(type) && !request.path("Details").path("Deprecation").has("DeprecationAt")) {
            throw invalid("DEPRECATION notifications need Details.Deprecation.DeprecationAt.");
        }
        if ("SCHEMA_CHANGE".equals(type) && !request.path("Details").path("SchemaChange").has("SchemaChangeAt")) {
            throw invalid("SCHEMA_CHANGE notifications need Details.SchemaChange.SchemaChangeAt.");
        }
        String detailType = switch (type) {
            case "DATA_DELAY" -> "Data Set Update Delayed";
            case "DATA_UPDATE" -> "Data Updated in Data Set";
            case "DEPRECATION" -> "Deprecation Planned for Data Set";
            default -> "Schema Change Planned for Data Set";
        };
        for (ObjectNode grant : receivers) {
            String receiver = grant.path("ReceiverPrincipal").asText();
            String grantId = grant.path("Id").asText();
            RequestScopes.runAs(receiver, region, () -> grants.getForAccount(receiver,
                    key(region, "received/" + grantId)).ifPresent(received -> {
                        String entitledId = received.path("DataSetId").asText();
                        ObjectNode detail = mapper.createObjectNode();
                        detail.put("DataSetId", entitledId);
                        detail.put("Type", type);
                        if (present(request, "Comment")) {
                            detail.put("Comment", request.get("Comment").asText());
                        }
                        for (String field : List.of("Details", "Scope")) {
                            if (request.has(field)) {
                                detail.set(field, request.get(field).deepCopy());
                            }
                        }
                        publish(region, detailType, detail, dataSets.getForAccount(receiver, key(region, entitledId))
                                .map(value -> value.path("Arn").asText()).orElse(entitledId));
                    }));
        }
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTags(String resourceArn) {
        // The tagging operations name the member "tags" on the wire, unlike every other operation's "Tags".
        ObjectNode response = mapper.createObjectNode();
        response.set("tags", locate(resourceArn).resource().path("Tags").deepCopy());
        return response;
    }

    public synchronized void tagResource(String resourceArn, JsonNode request) {
        Located located = locate(resourceArn);
        JsonNode input = request.has("tags") ? request.path("tags") : request.path("Tags");
        ((ObjectNode) located.resource().get("Tags")).setAll(tags(input));
        located.save().run();
    }

    public synchronized void untagResource(String resourceArn, List<String> keys) {
        Located located = locate(resourceArn);
        ObjectNode tags = (ObjectNode) located.resource().get("Tags");
        keys.forEach(tags::remove);
        located.save().run();
    }

    private record Located(ObjectNode resource, Runnable save) {}

    private Located locate(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid ResourceArn " + resourceArn + ".");
        }
        if (!"dataexchange".equals(arn.service()) || !arn.accountId().equals(account())) {
            throw notFound("Resource " + resourceArn + " was not found.", resourceArn, "DATA_SET");
        }
        String region = arn.region();
        String[] parts = arn.resource().split("/");
        AccountAwareStorageBackend<ObjectNode> storage;
        String storageKey;
        if (parts.length == 2 && "data-sets".equals(parts[0])) {
            storage = dataSets;
            storageKey = parts[1];
        } else if (parts.length == 4 && "revisions".equals(parts[2])) {
            storage = revisions;
            storageKey = parts[1] + "/" + parts[3];
        } else if (parts.length == 6 && "assets".equals(parts[4])) {
            storage = assets;
            storageKey = parts[1] + "/" + parts[3] + "/" + parts[5];
        } else if (parts.length == 2 && "event-actions".equals(parts[0])) {
            storage = eventActions;
            storageKey = parts[1];
        } else if (parts.length == 2 && "data-grants".equals(parts[0])) {
            storage = grants;
            storageKey = parts[1];
        } else {
            throw invalid("Resource " + resourceArn + " cannot be tagged.");
        }
        ObjectNode resource = storage.getForAccount(account(), key(region, storageKey))
                .orElseThrow(() -> notFound("Resource " + resourceArn + " was not found.", resourceArn, "DATA_SET"));
        if (!resource.has("Tags")) {
            resource.putObject("Tags");
        }
        return new Located(resource, () -> save(storage, region, storageKey, resource));
    }

    // ---------------------------------------------------------------- heartbeat

    /**
     * Every account sees the entitled {@code AWS Data Exchange Heartbeat} data set, with a revision holding the current
     * Unix time, created the first time the account calls Data Exchange in a Region.
     */
    void seed(String region) {
        if (dataSets.getForAccount(account(), key(region, HEARTBEAT_ID)).isPresent()) {
            return;
        }
        ObjectNode heartbeat = mapper.createObjectNode();
        heartbeat.put("Arn", arn(region, "data-sets/" + HEARTBEAT_ID));
        heartbeat.put("AssetType", "S3_SNAPSHOT");
        stamp(heartbeat, true);
        heartbeat.put("Description", "A test product from AWS Data Exchange that publishes a revision with the current"
                + " time every hour, for trying subscriptions, event actions and exports.");
        heartbeat.put("Id", HEARTBEAT_ID);
        heartbeat.put("Name", "AWS Data Exchange Heartbeat (Test product)");
        heartbeat.put("Origin", "ENTITLED");
        heartbeat.putObject("OriginDetails").put("ProductId", "prod-floci-heartbeat");
        heartbeat.put("SourceId", HEARTBEAT_ID);
        heartbeat.putObject("Tags");
        heartbeat.putObject("_").put("region", region).put("heartbeat", true);
        save(dataSets, region, HEARTBEAT_ID, heartbeat);
        beat(region, heartbeat, false);
    }

    private void beat(String region, ObjectNode heartbeat, boolean notify) {
        String revisionId = id();
        ObjectNode revision = mapper.createObjectNode();
        revision.put("Arn", heartbeat.path("Arn").asText() + "/revisions/" + revisionId);
        revision.put("Comment", "Heartbeat at " + now());
        stamp(revision, true);
        revision.put("DataSetId", HEARTBEAT_ID);
        revision.put("Finalized", true);
        revision.put("Id", revisionId);
        revision.put("SourceId", revisionId);
        revision.putObject("Tags");
        revision.putObject("_").put("region", region);
        save(revisions, region, HEARTBEAT_ID + "/" + revisionId, revision);
        byte[] body = mapper.createObjectNode().put("TimestampSinceEpoch", Instant.now().getEpochSecond()).toString()
                .getBytes(StandardCharsets.UTF_8);
        ObjectNode details = mapper.createObjectNode();
        details.putObject("S3SnapshotAsset").put("Size", body.length);
        putAsset(region, HEARTBEAT_ID, revisionId, "Epoch Time", "S3_SNAPSHOT", details, body, null);
        heartbeat.put("UpdatedAt", now());
        save(dataSets, region, HEARTBEAT_ID, heartbeat);
        if (notify) {
            ObjectNode detail = mapper.createObjectNode();
            detail.putArray("RevisionIds").add(revisionId);
            publish(region, "Revision Published To Data Set", detail, heartbeat.path("Arn").asText());
            jobs.get().autoExport(region, heartbeat, revision);
        }
    }

    /** Publishes the hourly Heartbeat revision in every account that has the data set. */
    void heartbeats() {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : dataSets.scanAllAccountEntries(key -> key.endsWith("/" + HEARTBEAT_ID))) {
            String region = entry.value().path("_").path("region").asText();
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    synchronized (this) {
                        dataSets.getForAccount(account(), key(region, HEARTBEAT_ID)).ifPresent(heartbeat -> {
                            Instant last = Instant.parse(heartbeat.path("UpdatedAt").asText(now()));
                            if (last.plus(1, ChronoUnit.HOURS).isBefore(Instant.now())) {
                                beat(region, heartbeat, true);
                            }
                        });
                    }
                });
            } catch (RuntimeException e) {
                LOG.debugv("Data Exchange heartbeat failed: {0}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void clear() {
        dataSets.clear();
        revisions.clear();
        assets.clear();
        assetData.clear();
        eventActions.clear();
        grants.clear();
    }

    // ---------------------------------------------------------------- helpers

    void publish(String region, String detailType, ObjectNode detail, String resource) {
        if (!events.isResolvable()) {
            return;
        }
        try {
            events.get().publishServiceEvent("aws.dataexchange", detailType, detail.toString(), resource, region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not publish a Data Exchange event: {0}", e.getMessage());
        }
    }

    private void dropRevision(String region, String dataSetId, String revisionId) {
        for (ObjectNode asset : assetsOf(region, dataSetId, revisionId)) {
            String assetKey = key(region, dataSetId + "/" + revisionId + "/" + asset.path("Id").asText());
            assets.deleteForAccount(account(), assetKey);
            assetData.deleteForAccount(account(), assetKey);
        }
        revisions.deleteForAccount(account(), key(region, dataSetId + "/" + revisionId));
    }

    private static void revoke(ObjectNode revision, String comment) {
        revision.put("Revoked", true);
        revision.put("RevokedAt", now());
        revision.put("RevocationComment", comment);
        revision.put("UpdatedAt", now());
    }

    ObjectNode dataSet(String region, String id) {
        return dataSets.getForAccount(account(), key(region, id))
                .orElseThrow(() -> notFound("Data set " + id + " was not found.", id, "DATA_SET"));
    }

    ObjectNode revision(String region, String dataSetId, String revisionId) {
        dataSet(region, dataSetId);
        return revisions.getForAccount(account(), key(region, dataSetId + "/" + revisionId))
                .orElseThrow(() -> notFound("Revision " + revisionId + " was not found.", revisionId, "REVISION"));
    }

    ObjectNode asset(String region, String dataSetId, String revisionId, String assetId) {
        revision(region, dataSetId, revisionId);
        return assets.getForAccount(account(), key(region, dataSetId + "/" + revisionId + "/" + assetId))
                .orElseThrow(() -> notFound("Asset " + assetId + " was not found.", assetId, "ASSET"));
    }

    private ObjectNode eventAction(String region, String id) {
        return eventActions.getForAccount(account(), key(region, id))
                .orElseThrow(() -> notFound("Event action " + id + " was not found.", id, "EVENT_ACTION"));
    }

    private ObjectNode sentGrant(String region, String grantId) {
        String id = grantId.substring(grantId.lastIndexOf('/') + 1);
        return grants.getForAccount(account(), key(region, id))
                .orElseThrow(() -> notFound("Data grant " + id + " was not found.", id, "DATA_GRANT"));
    }

    private ObjectNode receivedGrant(String region, String grantArn) {
        String id = grantArn.substring(grantArn.lastIndexOf('/') + 1);
        return grants.getForAccount(account(), key(region, "received/" + id))
                .orElseThrow(() -> notFound("Data grant " + id + " was not found.", id, "DATA_GRANT"));
    }

    List<ObjectNode> revisionsOf(String region, String dataSetId) {
        String prefix = key(region, dataSetId + "/");
        return new ArrayList<>(revisions.scanForAccount(account(), key -> key.startsWith(prefix)));
    }

    List<ObjectNode> assetsOf(String region, String dataSetId, String revisionId) {
        String prefix = key(region, dataSetId + "/" + revisionId + "/");
        List<ObjectNode> result = new ArrayList<>(assets.scanForAccount(account(), key -> key.startsWith(prefix)));
        result.sort(Comparator.comparing(asset -> asset.path("CreatedAt").asText()));
        return result;
    }

    private List<ObjectNode> owned(String region) {
        return regional(dataSets, region).stream().filter(value -> "OWNED".equals(value.path("Origin").asText()))
                .toList();
    }

    private static void requireOwned(ObjectNode dataSet) {
        if (!"OWNED".equals(dataSet.path("Origin").asText())) {
            throw invalid("Data set " + dataSet.path("Id").asText() + " is entitled and cannot be changed.");
        }
    }

    List<ObjectNode> regional(AccountAwareStorageBackend<ObjectNode> storage, String region) {
        String prefix = region + "/";
        List<ObjectNode> result = new ArrayList<>(storage.scanForAccount(account(),
                key -> key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0));
        result.sort(Comparator.comparing(value -> value.path("CreatedAt").asText()));
        return result;
    }

    void save(AccountAwareStorageBackend<ObjectNode> storage, String region, String name, ObjectNode value) {
        storage.putForAccount(account(), key(region, name), value);
    }

    String account() {
        return dataSets.accountId();
    }

    String arn(String region, String resource) {
        return regionResolver.buildArn("dataexchange", region, account(), resource);
    }

    String id() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    static void stamp(ObjectNode resource, boolean created) {
        String now = now();
        if (created) {
            resource.put("CreatedAt", now);
        }
        resource.put("UpdatedAt", now);
    }

    ObjectNode view(ObjectNode stored) {
        ObjectNode copy = stored.deepCopy();
        copy.remove("_");
        return copy;
    }

    ObjectNode tags(JsonNode input) {
        ObjectNode tags = mapper.createObjectNode();
        if (input != null && input.isObject()) {
            input.properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

    ObjectNode page(List<ObjectNode> items, JsonNode query, String field, Function<ObjectNode, String> cursor) {
        int max = 25;
        if (query.hasNonNull("maxResults")) {
            max = query.get("maxResults").asInt(-1);
            if (max < 1 || max > 200) {
                throw invalid("MaxResults must be between 1 and 200.");
            }
        }
        String token = query.path("nextToken").asText(null);
        PaginatedResult<ObjectNode> paged = Pagination.paginate(items, cursor, max, token, field,
                bad -> invalid("Invalid NextToken."));
        ObjectNode response = mapper.createObjectNode();
        response.putArray(field).addAll(paged.items());
        if (paged.nextToken() != null) {
            response.put("NextToken", paged.nextToken());
        }
        return response;
    }

    static Instant instant(JsonNode value) {
        try {
            return value.isNumber() ? Instant.ofEpochMilli((long) (value.asDouble() * 1000))
                    : Instant.parse(value.asText());
        } catch (RuntimeException e) {
            throw invalid("Invalid timestamp " + value.asText() + ".");
        }
    }

    static String key(String region, String name) {
        return region + "/" + name;
    }

    static String text(JsonNode parent, String field, int maximum) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(field + " is required.");
        }
        if (value.textValue().length() > maximum) {
            throw invalid(field + " must be at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    static String oneOf(JsonNode parent, String field, Set<String> allowed) {
        String value = text(parent, field, 1024);
        if (!allowed.contains(value)) {
            throw invalid(field + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return value;
    }

    static boolean present(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException conflict(String message, String resourceId, String resourceType) {
        return new AwsException("ConflictException", message, 409,
                Map.<String, Object>of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    static AwsException notFound(String message, String resourceId, String resourceType) {
        return new AwsException("ResourceNotFoundException", message, 404,
                Map.<String, Object>of("ResourceId", resourceId, "ResourceType", resourceType));
    }

    static AwsException limit(String name, double value) {
        return new AwsException("ServiceLimitExceededException", "You have reached the limit of " + (int) value
                + " for " + name + ".", 402, Map.<String, Object>of("LimitName", name, "LimitValue", value));
    }
}
