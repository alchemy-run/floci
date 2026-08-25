package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local Amazon Kendra stub. Indexes and data sources are in-memory and become
 * {@code ACTIVE} immediately so Alchemy's bounded wait-for-status loops converge.
 *
 * @see <a href="https://docs.aws.amazon.com/kendra/latest/APIReference/API_Operations.html">Kendra API</a>
 */
@ApplicationScoped
public class KendraService implements Resettable {

    static final class Index {
        String id;
        String arn;
        String name;
        String edition;
        String roleArn;
        String description;
        String status;
        String userContextPolicy;
        JsonNode serverSideEncryption;
        JsonNode userTokenConfigurations;
        JsonNode userGroupResolution;
        JsonNode capacityUnits;
        String suggestionsMode = "ENABLED";
        int queryLogLookBackWindowInDays = 7;
        Long lastClearTime;
        long createdAt;
        long updatedAt;
        final Map<String, String> tags = new LinkedHashMap<>();
        final ConcurrentHashMap<String, Document> documents = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AccessControl> accessControls = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, PrincipalMapping> principalMappings = new ConcurrentHashMap<>();
    }

    static final class Document {
        String id;
        String title;
        String body;
        String status;
    }

    static final class AccessControl {
        String id;
        String name;
        String description;
        JsonNode accessControlList;
        JsonNode hierarchicalAccessControlList;
    }

    static final class PrincipalMapping {
        String groupId;
        JsonNode groupMembers;
        long orderingId;
        long receivedAt;
        long lastUpdatedAt;
        String status;
    }

    static final class SyncJob {
        String executionId;
        long startTime;
        Long endTime;
        String status;
    }

    static final class DataSource {
        String id;
        String arn;
        String indexId;
        String name;
        String type;
        String status;
        String description;
        String schedule;
        String roleArn;
        String languageCode;
        JsonNode configuration;
        JsonNode vpcConfiguration;
        JsonNode customDocumentEnrichment;
        long createdAt;
        long updatedAt;
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<SyncJob> syncJobs = new ArrayList<>();
    }

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ConcurrentHashMap<String, Index> indexes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DataSource> dataSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createIndexTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> createDataSourceTokens = new ConcurrentHashMap<>();
    private final AtomicLong orderingIds = new AtomicLong(1);

    @Inject
    public KendraService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        indexes.clear();
        dataSources.clear();
        createIndexTokens.clear();
        createDataSourceTokens.clear();
        orderingIds.set(1);
    }

    public ObjectNode createIndex(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = createIndexTokens.get(token);
            if (existingId != null) {
                Index existing = indexes.get(existingId);
                if (existing != null) {
                    return idResponse(existing.id);
                }
            }
        }
        String name = requireText(request, "Name");
        String roleArn = requireText(request, "RoleArn");
        if (findIndexByName(name) != null) {
            throw alreadyExists("An index with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        Index index = new Index();
        index.id = newId();
        index.arn = regionResolver.buildArn("kendra", region, "index/" + index.id);
        index.name = name;
        index.edition = textOrDefault(request, "Edition", "DEVELOPER_EDITION");
        index.roleArn = roleArn;
        index.description = textOrNull(request, "Description");
        index.status = "ACTIVE";
        index.userContextPolicy = textOrDefault(request, "UserContextPolicy", "ATTRIBUTE_FILTER");
        index.serverSideEncryption = copy(request.get("ServerSideEncryptionConfiguration"));
        index.userTokenConfigurations = copy(request.get("UserTokenConfigurations"));
        index.userGroupResolution = copy(request.get("UserGroupResolutionConfiguration"));
        index.capacityUnits = copyOrDefaultCapacity(request.get("CapacityUnits"));
        index.createdAt = now;
        index.updatedAt = now;
        index.tags.putAll(readTags(request));
        indexes.put(index.id, index);
        if (token != null) {
            createIndexTokens.put(token, index.id);
        }
        return idResponse(index.id);
    }

    public ObjectNode describeIndex(JsonNode request) {
        return indexNode(requireIndex(requireText(request, "Id")));
    }

    public ObjectNode updateIndex(JsonNode request) {
        Index index = requireIndex(requireText(request, "Id"));
        if (request.hasNonNull("Name")) {
            String name = request.get("Name").asText();
            Index clash = findIndexByName(name);
            if (clash != null && !clash.id.equals(index.id)) {
                throw alreadyExists("An index with the name " + name + " already exists.");
            }
            index.name = name;
        }
        if (request.hasNonNull("RoleArn")) {
            index.roleArn = request.get("RoleArn").asText();
        }
        if (request.has("Description")) {
            index.description = textOrNull(request, "Description");
        }
        if (request.hasNonNull("UserContextPolicy")) {
            index.userContextPolicy = request.get("UserContextPolicy").asText();
        }
        if (request.has("UserTokenConfigurations")) {
            index.userTokenConfigurations = copy(request.get("UserTokenConfigurations"));
        }
        if (request.has("UserGroupResolutionConfiguration")) {
            index.userGroupResolution = copy(request.get("UserGroupResolutionConfiguration"));
        }
        if (request.has("CapacityUnits")) {
            index.capacityUnits = copyOrDefaultCapacity(request.get("CapacityUnits"));
        }
        index.updatedAt = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteIndex(JsonNode request) {
        Index index = requireIndex(requireText(request, "Id"));
        indexes.remove(index.id);
        dataSources.values().removeIf(source -> index.id.equals(source.indexId));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listIndices() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("IndexConfigurationSummaryItems");
        indexes.values().stream()
                .sorted(Comparator.comparingLong((Index index) -> index.createdAt).reversed())
                .forEach(index -> list.add(indexSummary(index)));
        return response;
    }

    public ObjectNode createDataSource(JsonNode request, String region) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            String existingId = createDataSourceTokens.get(token);
            if (existingId != null) {
                DataSource existing = dataSources.get(dataSourceKey(index.id, existingId));
                if (existing != null) {
                    return requiredIdResponse(existing.id);
                }
            }
        }
        String name = requireText(request, "Name");
        String type = requireText(request, "Type");
        if (findDataSourceByName(index.id, name) != null) {
            throw alreadyExists("A data source with the name " + name + " already exists.");
        }
        long now = nowSeconds();
        DataSource source = new DataSource();
        source.id = newId();
        source.indexId = index.id;
        source.arn = regionResolver.buildArn("kendra", region,
                "index/" + index.id + "/data-source/" + source.id);
        source.name = name;
        source.type = type;
        source.status = "ACTIVE";
        source.description = textOrNull(request, "Description");
        source.schedule = textOrNull(request, "Schedule");
        source.roleArn = textOrNull(request, "RoleArn");
        source.languageCode = textOrDefault(request, "LanguageCode", "en");
        source.configuration = copy(request.get("Configuration"));
        source.vpcConfiguration = copy(request.get("VpcConfiguration"));
        source.customDocumentEnrichment = copy(request.get("CustomDocumentEnrichmentConfiguration"));
        source.createdAt = now;
        source.updatedAt = now;
        source.tags.putAll(readTags(request));
        dataSources.put(dataSourceKey(index.id, source.id), source);
        if (token != null) {
            createDataSourceTokens.put(token, source.id);
        }
        return requiredIdResponse(source.id);
    }

    public ObjectNode describeDataSource(JsonNode request) {
        return dataSourceNode(requireDataSource(request));
    }

    public ObjectNode updateDataSource(JsonNode request) {
        DataSource source = requireDataSource(request);
        if (request.hasNonNull("Name")) {
            String name = request.get("Name").asText();
            DataSource clash = findDataSourceByName(source.indexId, name);
            if (clash != null && !clash.id.equals(source.id)) {
                throw alreadyExists("A data source with the name " + name + " already exists.");
            }
            source.name = name;
        }
        if (request.has("Description")) {
            source.description = textOrNull(request, "Description");
        }
        if (request.has("Schedule")) {
            source.schedule = textOrNull(request, "Schedule");
        }
        if (request.hasNonNull("RoleArn")) {
            source.roleArn = request.get("RoleArn").asText();
        }
        if (request.hasNonNull("LanguageCode")) {
            source.languageCode = request.get("LanguageCode").asText();
        }
        if (request.has("Configuration")) {
            source.configuration = copy(request.get("Configuration"));
        }
        if (request.has("VpcConfiguration")) {
            source.vpcConfiguration = copy(request.get("VpcConfiguration"));
        }
        if (request.has("CustomDocumentEnrichmentConfiguration")) {
            source.customDocumentEnrichment = copy(request.get("CustomDocumentEnrichmentConfiguration"));
        }
        source.updatedAt = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteDataSource(JsonNode request) {
        DataSource source = requireDataSource(request);
        dataSources.remove(dataSourceKey(source.indexId, source.id));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataSources(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("SummaryItems");
        dataSources.values().stream()
                .filter(source -> index.id.equals(source.indexId))
                .sorted(Comparator.comparingLong((DataSource source) -> source.createdAt).reversed())
                .forEach(source -> list.add(dataSourceSummary(source)));
        return response;
    }

    public ObjectNode tagResource(JsonNode request) {
        tagged(requireText(request, "ResourceARN")).tags.putAll(readTags(request));
        return objectMapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode request) {
        Map<String, String> tags = tagged(requireText(request, "ResourceARN")).tags;
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                tags.remove(key.asText());
            }
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        writeTags(tags, tagged(requireText(request, "ResourceARN")).tags);
        return response;
    }

    public ObjectNode query(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String queryText = textOrDefault(request, "QueryText", "");
        String queryId = newId();
        ArrayNode items = objectMapper.createArrayNode();
        for (Document document : matchingDocuments(index, queryText)) {
            ObjectNode item = items.addObject();
            item.put("Id", queryId + ":" + document.id);
            item.put("Type", "DOCUMENT");
            item.put("Format", "TEXT");
            item.put("DocumentId", document.id);
            ObjectNode title = item.putObject("DocumentTitle");
            title.put("Text", document.title == null ? document.id : document.title);
            title.set("Highlights", objectMapper.createArrayNode());
            ObjectNode excerpt = item.putObject("DocumentExcerpt");
            excerpt.put("Text", excerpt(document.body, queryText));
            excerpt.set("Highlights", objectMapper.createArrayNode());
            item.put("FeedbackToken", document.id);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueryId", queryId);
        response.set("ResultItems", items);
        response.put("TotalNumberOfResults", items.size());
        return response;
    }

    public ObjectNode retrieve(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String queryText = textOrDefault(request, "QueryText", "");
        String queryId = newId();
        ArrayNode items = objectMapper.createArrayNode();
        for (Document document : matchingDocuments(index, queryText)) {
            ObjectNode item = items.addObject();
            item.put("Id", queryId + ":" + document.id);
            item.put("DocumentId", document.id);
            item.put("DocumentTitle", document.title == null ? document.id : document.title);
            item.put("Content", excerpt(document.body, queryText));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueryId", queryId);
        response.set("ResultItems", items);
        return response;
    }

    public ObjectNode getQuerySuggestions(JsonNode request) {
        requireIndex(requireText(request, "IndexId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QuerySuggestionsId", newId());
        response.set("Suggestions", objectMapper.createArrayNode());
        return response;
    }

    public ObjectNode submitFeedback(JsonNode request) {
        requireIndex(requireText(request, "IndexId"));
        requireText(request, "QueryId");
        return objectMapper.createObjectNode();
    }

    public ObjectNode batchPutDocument(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        JsonNode documents = request.get("Documents");
        if (documents == null || !documents.isArray()) {
            throw invalid("Documents is required.");
        }
        ArrayNode failed = objectMapper.createArrayNode();
        for (JsonNode node : documents) {
            String id = textOrNull(node, "Id");
            if (id == null) {
                ObjectNode failure = failed.addObject();
                failure.put("ErrorCode", "InvalidRequest");
                failure.put("ErrorMessage", "Document Id is required.");
                continue;
            }
            Document document = new Document();
            document.id = id;
            document.title = textOrNull(node, "Title");
            document.body = documentBody(node);
            document.status = "INDEXED";
            index.documents.put(id, document);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("FailedDocuments", failed);
        return response;
    }

    public ObjectNode batchDeleteDocument(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        JsonNode ids = request.get("DocumentIdList");
        if (ids == null || !ids.isArray()) {
            throw invalid("DocumentIdList is required.");
        }
        for (JsonNode idNode : ids) {
            if (!idNode.isNull()) {
                index.documents.remove(idNode.asText());
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("FailedDocuments", objectMapper.createArrayNode());
        return response;
    }

    public ObjectNode batchGetDocumentStatus(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        JsonNode infos = request.get("DocumentInfoList");
        if (infos == null || !infos.isArray()) {
            throw invalid("DocumentInfoList is required.");
        }
        ArrayNode statuses = objectMapper.createArrayNode();
        for (JsonNode info : infos) {
            String id = textOrNull(info, "DocumentId");
            ObjectNode status = statuses.addObject();
            status.put("DocumentId", id == null ? "" : id);
            Document document = id == null ? null : index.documents.get(id);
            status.put("DocumentStatus", document == null ? "NOT_FOUND" : document.status);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("DocumentStatusList", statuses);
        return response;
    }

    public ObjectNode getSnapshots(JsonNode request) {
        requireIndex(requireText(request, "IndexId"));
        requireText(request, "Interval");
        requireText(request, "MetricType");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode header = response.putArray("SnapshotsDataHeader");
        header.add("Date");
        header.add("Count");
        response.set("SnapshotsData", objectMapper.createArrayNode());
        return response;
    }

    public ObjectNode putPrincipalMapping(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String groupId = requireText(request, "GroupId");
        JsonNode members = request.get("GroupMembers");
        if (members == null || members.isNull()) {
            throw invalid("GroupMembers is required.");
        }
        PrincipalMapping mapping = index.principalMappings.computeIfAbsent(groupId, ignored -> new PrincipalMapping());
        mapping.groupId = groupId;
        mapping.groupMembers = copy(members);
        mapping.orderingId = orderingIds.getAndIncrement();
        long now = nowSeconds();
        mapping.receivedAt = now;
        mapping.lastUpdatedAt = now;
        mapping.status = "SUCCEEDED";
        return objectMapper.createObjectNode();
    }

    public ObjectNode deletePrincipalMapping(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String groupId = requireText(request, "GroupId");
        if (index.principalMappings.remove(groupId) == null) {
            throw notFound("Principal mapping " + groupId + " was not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode describePrincipalMapping(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String groupId = requireText(request, "GroupId");
        PrincipalMapping mapping = index.principalMappings.get(groupId);
        if (mapping == null) {
            throw notFound("Principal mapping " + groupId + " was not found.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IndexId", index.id);
        response.put("GroupId", mapping.groupId);
        ArrayNode summaries = response.putArray("GroupOrderingIdSummaries");
        ObjectNode summary = summaries.addObject();
        summary.put("Status", mapping.status);
        summary.put("LastUpdatedAt", mapping.lastUpdatedAt);
        summary.put("ReceivedAt", mapping.receivedAt);
        summary.put("OrderingId", mapping.orderingId);
        return response;
    }

    public ObjectNode listGroupsOlderThanOrderingId(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        if (!request.hasNonNull("OrderingId")) {
            throw invalid("OrderingId is required.");
        }
        long orderingId = request.get("OrderingId").asLong();
        ArrayNode summaries = objectMapper.createArrayNode();
        index.principalMappings.values().stream()
                .filter(mapping -> mapping.orderingId < orderingId)
                .forEach(mapping -> {
                    ObjectNode summary = summaries.addObject();
                    summary.put("GroupId", mapping.groupId);
                    summary.put("OrderingId", mapping.orderingId);
                });
        ObjectNode response = objectMapper.createObjectNode();
        response.set("GroupsSummaries", summaries);
        return response;
    }

    public ObjectNode clearQuerySuggestions(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        index.lastClearTime = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode describeQuerySuggestionsConfig(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Mode", index.suggestionsMode);
        response.put("Status", "ACTIVE");
        response.put("QueryLogLookBackWindowInDays", index.queryLogLookBackWindowInDays);
        response.put("IncludeQueriesWithoutUserInformation", true);
        response.put("MinimumNumberOfQueryingUsers", 3);
        response.put("MinimumQueryCount", 1);
        response.put("TotalSuggestionsCount", 0);
        if (index.lastClearTime != null) {
            response.put("LastClearTime", index.lastClearTime);
        }
        return response;
    }

    public ObjectNode updateQuerySuggestionsConfig(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        if (request.hasNonNull("Mode")) {
            index.suggestionsMode = request.get("Mode").asText();
        }
        if (request.hasNonNull("QueryLogLookBackWindowInDays")) {
            index.queryLogLookBackWindowInDays = request.get("QueryLogLookBackWindowInDays").asInt();
        }
        index.updatedAt = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode createAccessControlConfiguration(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String name = requireText(request, "Name");
        String id = newId();
        AccessControl acl = new AccessControl();
        acl.id = id;
        acl.name = name;
        acl.description = textOrNull(request, "Description");
        acl.accessControlList = copy(request.get("AccessControlList"));
        acl.hierarchicalAccessControlList = copy(request.get("HierarchicalAccessControlList"));
        index.accessControls.put(id, acl);
        return requiredIdResponse(id);
    }

    public ObjectNode describeAccessControlConfiguration(JsonNode request) {
        AccessControl acl = requireAcl(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", acl.name);
        if (acl.description != null) {
            response.put("Description", acl.description);
        }
        setIfPresent(response, "AccessControlList", acl.accessControlList);
        setIfPresent(response, "HierarchicalAccessControlList", acl.hierarchicalAccessControlList);
        return response;
    }

    public ObjectNode updateAccessControlConfiguration(JsonNode request) {
        AccessControl acl = requireAcl(request);
        if (request.hasNonNull("Name")) {
            acl.name = request.get("Name").asText();
        }
        if (request.has("Description")) {
            acl.description = textOrNull(request, "Description");
        }
        if (request.has("AccessControlList")) {
            acl.accessControlList = copy(request.get("AccessControlList"));
        }
        if (request.has("HierarchicalAccessControlList")) {
            acl.hierarchicalAccessControlList = copy(request.get("HierarchicalAccessControlList"));
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode deleteAccessControlConfiguration(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String id = requireText(request, "Id");
        if (index.accessControls.remove(id) == null) {
            throw notFound("Access control configuration " + id + " was not found.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode listAccessControlConfigurations(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        ArrayNode items = objectMapper.createArrayNode();
        index.accessControls.values().forEach(acl -> items.addObject().put("Id", acl.id));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AccessControlConfigurations", items);
        return response;
    }

    public ObjectNode startDataSourceSyncJob(JsonNode request) {
        DataSource source = requireDataSource(request);
        for (SyncJob job : source.syncJobs) {
            if ("SYNCING".equals(job.status) || "SYNCING_INDEXING".equals(job.status)) {
                throw new AwsException("ResourceInUseException",
                        "A sync job is already in progress for data source " + source.id, 400);
            }
        }
        long now = nowSeconds();
        SyncJob job = new SyncJob();
        job.executionId = newId();
        job.startTime = now;
        job.endTime = now;
        job.status = "SUCCEEDED";
        source.syncJobs.add(job);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ExecutionId", job.executionId);
        return response;
    }

    public ObjectNode stopDataSourceSyncJob(JsonNode request) {
        DataSource source = requireDataSource(request);
        SyncJob running = null;
        for (int i = source.syncJobs.size() - 1; i >= 0; i--) {
            SyncJob job = source.syncJobs.get(i);
            if ("SYNCING".equals(job.status) || "SYNCING_INDEXING".equals(job.status)) {
                running = job;
                break;
            }
        }
        if (running == null) {
            throw new AwsException("ConflictException",
                    "No sync job is currently in progress for data source " + source.id, 409);
        }
        running.status = "SUCCEEDED";
        running.endTime = nowSeconds();
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataSourceSyncJobs(JsonNode request) {
        DataSource source = requireDataSource(request);
        ArrayNode history = objectMapper.createArrayNode();
        for (int i = source.syncJobs.size() - 1; i >= 0; i--) {
            SyncJob job = source.syncJobs.get(i);
            ObjectNode node = history.addObject();
            node.put("ExecutionId", job.executionId);
            node.put("StartTime", job.startTime);
            if (job.endTime != null) {
                node.put("EndTime", job.endTime);
            }
            node.put("Status", job.status);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("History", history);
        return response;
    }

    private AccessControl requireAcl(JsonNode request) {
        Index index = requireIndex(requireText(request, "IndexId"));
        String id = requireText(request, "Id");
        AccessControl acl = index.accessControls.get(id);
        if (acl == null) {
            throw notFound("Access control configuration " + id + " was not found.");
        }
        return acl;
    }

    private List<Document> matchingDocuments(Index index, String queryText) {
        String needle = queryText.toLowerCase(Locale.ROOT).trim();
        List<Document> matches = new ArrayList<>();
        if (needle.isEmpty()) {
            return matches;
        }
        for (Document document : index.documents.values()) {
            String haystack = ((document.title == null ? "" : document.title) + " " + document.body)
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                matches.add(document);
            }
        }
        return matches;
    }

    private static String excerpt(String body, String queryText) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (queryText == null || queryText.isBlank()) {
            return body.length() > 240 ? body.substring(0, 240) : body;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        String needle = queryText.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(needle);
        if (at < 0) {
            return body.length() > 240 ? body.substring(0, 240) : body;
        }
        int start = Math.max(0, at - 40);
        int end = Math.min(body.length(), at + needle.length() + 80);
        return body.substring(start, end);
    }

    private static String documentBody(JsonNode document) {
        JsonNode blob = document.get("Blob");
        if (blob == null || blob.isNull() || blob.isMissingNode()) {
            return "";
        }
        if (blob.isBinary()) {
            try {
                return new String(blob.binaryValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return blob.asText("");
            }
        }
        String raw = blob.asText("");
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private Tagged tagged(String arn) {
        for (Index index : indexes.values()) {
            if (arn.equals(index.arn)) {
                return new Tagged(index.tags);
            }
        }
        for (DataSource source : dataSources.values()) {
            if (arn.equals(source.arn)) {
                return new Tagged(source.tags);
            }
        }
        throw notFound("Resource " + arn + " was not found.");
    }

    private record Tagged(Map<String, String> tags) {}

    private Index requireIndex(String id) {
        Index index = indexes.get(id);
        if (index == null) {
            throw notFound("Index " + id + " was not found.");
        }
        return index;
    }

    private DataSource requireDataSource(JsonNode request) {
        requireIndex(requireText(request, "IndexId"));
        String indexId = requireText(request, "IndexId");
        String id = requireText(request, "Id");
        DataSource source = dataSources.get(dataSourceKey(indexId, id));
        if (source == null) {
            throw notFound("Data source " + id + " was not found.");
        }
        return source;
    }

    private Index findIndexByName(String name) {
        return indexes.values().stream()
                .filter(index -> name.equals(index.name))
                .findFirst()
                .orElse(null);
    }

    private DataSource findDataSourceByName(String indexId, String name) {
        return dataSources.values().stream()
                .filter(source -> indexId.equals(source.indexId) && name.equals(source.name))
                .findFirst()
                .orElse(null);
    }

    private ObjectNode idResponse(String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", id);
        return response;
    }

    private ObjectNode requiredIdResponse(String id) {
        return idResponse(id);
    }

    private ObjectNode indexNode(Index index) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", index.name);
        node.put("Id", index.id);
        node.put("Edition", index.edition);
        node.put("RoleArn", index.roleArn);
        setIfPresent(node, "ServerSideEncryptionConfiguration", index.serverSideEncryption);
        node.put("Status", index.status);
        if (index.description != null) {
            node.put("Description", index.description);
        }
        node.put("CreatedAt", index.createdAt);
        node.put("UpdatedAt", index.updatedAt);
        ObjectNode stats = node.putObject("IndexStatistics");
        stats.putObject("FaqStatistics").put("IndexedQuestionAnswersCount", 0);
        ObjectNode text = stats.putObject("TextDocumentStatistics");
        text.put("IndexedTextDocumentsCount", 0);
        text.put("IndexedTextBytes", 0);
        setIfPresent(node, "CapacityUnits", index.capacityUnits);
        setIfPresent(node, "UserTokenConfigurations", index.userTokenConfigurations);
        if (index.userContextPolicy != null) {
            node.put("UserContextPolicy", index.userContextPolicy);
        }
        setIfPresent(node, "UserGroupResolutionConfiguration", index.userGroupResolution);
        return node;
    }

    private ObjectNode indexSummary(Index index) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", index.name);
        node.put("Id", index.id);
        node.put("Edition", index.edition);
        node.put("CreatedAt", index.createdAt);
        node.put("UpdatedAt", index.updatedAt);
        node.put("Status", index.status);
        return node;
    }

    private ObjectNode dataSourceNode(DataSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", source.id);
        node.put("IndexId", source.indexId);
        node.put("Name", source.name);
        node.put("Type", source.type);
        setIfPresent(node, "Configuration", source.configuration);
        setIfPresent(node, "VpcConfiguration", source.vpcConfiguration);
        node.put("CreatedAt", source.createdAt);
        node.put("UpdatedAt", source.updatedAt);
        if (source.description != null) {
            node.put("Description", source.description);
        }
        node.put("Status", source.status);
        if (source.schedule != null) {
            node.put("Schedule", source.schedule);
        }
        if (source.roleArn != null) {
            node.put("RoleArn", source.roleArn);
        }
        if (source.languageCode != null) {
            node.put("LanguageCode", source.languageCode);
        }
        setIfPresent(node, "CustomDocumentEnrichmentConfiguration", source.customDocumentEnrichment);
        return node;
    }

    private ObjectNode dataSourceSummary(DataSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", source.name);
        node.put("Id", source.id);
        node.put("Type", source.type);
        node.put("CreatedAt", source.createdAt);
        node.put("UpdatedAt", source.updatedAt);
        node.put("Status", source.status);
        if (source.languageCode != null) {
            node.put("LanguageCode", source.languageCode);
        }
        return node;
    }

    private JsonNode copyOrDefaultCapacity(JsonNode capacity) {
        JsonNode copied = copy(capacity);
        if (copied != null) {
            return copied;
        }
        ObjectNode defaults = objectMapper.createObjectNode();
        defaults.put("StorageCapacityUnits", 0);
        defaults.put("QueryCapacityUnits", 0);
        return defaults;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            node.set(field, value);
        }
    }

    private JsonNode copy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.deepCopy();
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request == null ? null : request.get("Tags");
        if (node != null && node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "Key");
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static void writeTags(ArrayNode list, Map<String, String> tags) {
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
    }

    private static String dataSourceKey(String indexId, String dataSourceId) {
        return indexId + ":" + dataSourceId;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value != null ? value : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistException", message, 400);
    }
}
