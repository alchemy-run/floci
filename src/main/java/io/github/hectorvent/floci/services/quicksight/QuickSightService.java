package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightDashboard;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightDataSet;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightDataSource;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightIngestion;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightSnapshotJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon QuickSight restJson1 — data sources, datasets, dashboards, SPICE
 * ingestions, dashboard snapshot jobs, embed URLs, and resource tags.
 */
@ApplicationScoped
public class QuickSightService {

    static final String SERVICE = "quicksight";

    private static final Set<String> IMPORT_MODES = Set.of("SPICE", "DIRECT_QUERY");
    private static final Set<String> INGESTION_TYPES = Set.of("FULL_REFRESH", "INCREMENTAL_REFRESH");
    private static final Set<String> CANCELABLE =
            Set.of("INITIALIZED", "QUEUED", "RUNNING");
    private static final Pattern USER_ARN = Pattern.compile(
            "^arn:aws:quicksight:[^:]+:[0-9]{12}:user/.+");
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;

    private final StorageBackend<String, QuickSightDataSource> dataSources;
    private final StorageBackend<String, QuickSightDataSet> dataSets;
    private final StorageBackend<String, QuickSightDashboard> dashboards;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public QuickSightService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(
                        SERVICE,
                        "quicksight-data-sources.json",
                        new TypeReference<Map<String, QuickSightDataSource>>() {
                        }),
                storageFactory.create(
                        SERVICE,
                        "quicksight-data-sets.json",
                        new TypeReference<Map<String, QuickSightDataSet>>() {
                        }),
                storageFactory.create(
                        SERVICE,
                        "quicksight-dashboards.json",
                        new TypeReference<Map<String, QuickSightDashboard>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    QuickSightService(
            StorageBackend<String, QuickSightDataSource> dataSources,
            StorageBackend<String, QuickSightDataSet> dataSets,
            StorageBackend<String, QuickSightDashboard> dashboards,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.dataSources = dataSources;
        this.dataSets = dataSets;
        this.dashboards = dashboards;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createDataSource(String region, String awsAccountId, JsonNode request) {
        requireObject(request, "Request body");
        String dataSourceId = requireText(request, "DataSourceId");
        String key = storageKey(region, dataSourceId);
        if (dataSources.get(key).isPresent()) {
            throw exists(dataSourceId, "DATA_SOURCE");
        }
        long now = now();
        QuickSightDataSource source = new QuickSightDataSource();
        source.setDataSourceId(dataSourceId);
        source.setName(requireText(request, "Name"));
        source.setType(requireText(request, "Type"));
        source.setRegion(region);
        source.setAccountId(accountId(awsAccountId));
        source.setArn(arn(region, accountId(awsAccountId), "datasource/" + dataSourceId));
        source.setStatus("CREATION_SUCCESSFUL");
        source.setCreatedTime(now);
        source.setLastUpdatedTime(now);
        source.setDataSourceParameters(optionalNode(request, "DataSourceParameters"));
        source.setCredentials(optionalNode(request, "Credentials"));
        source.setVpcConnectionProperties(optionalNode(request, "VpcConnectionProperties"));
        source.setSslProperties(optionalNode(request, "SslProperties"));
        source.setPermissions(optionalNode(request, "Permissions"));
        source.setTags(readTags(request));
        dataSources.put(key, source);
        return createdResource(source.getArn(), source.getDataSourceId(), "DataSourceId", source.getStatus());
    }

    public ObjectNode describeDataSource(String region, String dataSourceId) {
        QuickSightDataSource source = requireDataSource(region, dataSourceId);
        ObjectNode response = envelope();
        ObjectNode node = response.putObject("DataSource");
        node.put("Arn", source.getArn());
        node.put("DataSourceId", source.getDataSourceId());
        node.put("Name", source.getName());
        node.put("Type", source.getType());
        node.put("Status", source.getStatus());
        node.put("CreatedTime", source.getCreatedTime());
        node.put("LastUpdatedTime", source.getLastUpdatedTime());
        copyIfPresent(node, "DataSourceParameters", source.getDataSourceParameters());
        copyIfPresent(node, "VpcConnectionProperties", source.getVpcConnectionProperties());
        copyIfPresent(node, "SslProperties", source.getSslProperties());
        return response;
    }

    public synchronized ObjectNode updateDataSource(String region, String dataSourceId, JsonNode request) {
        QuickSightDataSource source = requireDataSource(region, dataSourceId);
        requireObject(request, "Request body");
        source.setName(requireText(request, "Name"));
        if (request.has("DataSourceParameters")) {
            source.setDataSourceParameters(optionalNode(request, "DataSourceParameters"));
        }
        if (request.has("Credentials")) {
            source.setCredentials(optionalNode(request, "Credentials"));
        }
        if (request.has("VpcConnectionProperties")) {
            source.setVpcConnectionProperties(optionalNode(request, "VpcConnectionProperties"));
        }
        if (request.has("SslProperties")) {
            source.setSslProperties(optionalNode(request, "SslProperties"));
        }
        source.setStatus("UPDATE_SUCCESSFUL");
        source.setLastUpdatedTime(now());
        dataSources.put(storageKey(region, dataSourceId), source);
        ObjectNode response = envelope();
        response.put("Arn", source.getArn());
        response.put("DataSourceId", source.getDataSourceId());
        response.put("UpdateStatus", source.getStatus());
        return response;
    }

    public synchronized ObjectNode deleteDataSource(String region, String dataSourceId) {
        QuickSightDataSource source = requireDataSource(region, dataSourceId);
        dataSources.delete(storageKey(region, dataSourceId));
        ObjectNode response = envelope();
        response.put("Arn", source.getArn());
        response.put("DataSourceId", source.getDataSourceId());
        return response;
    }

    public ObjectNode listDataSources(String region, String awsAccountId, String maxResults, String nextToken) {
        List<QuickSightDataSource> sources = new ArrayList<>(dataSources.scan(key -> key.startsWith(region + "::")));
        if (awsAccountId != null && !awsAccountId.isBlank()) {
            sources.removeIf(source -> !awsAccountId.equals(source.getAccountId()));
        }
        sources.sort(Comparator.comparing(QuickSightDataSource::getDataSourceId));
        Page<QuickSightDataSource> page = paginate(sources, maxResults, nextToken);
        ObjectNode response = envelope();
        ArrayNode list = response.putArray("DataSources");
        for (QuickSightDataSource source : page.items()) {
            ObjectNode node = list.addObject();
            node.put("Arn", source.getArn());
            node.put("DataSourceId", source.getDataSourceId());
            node.put("Name", source.getName());
            node.put("Type", source.getType());
            node.put("Status", source.getStatus());
            node.put("CreatedTime", source.getCreatedTime());
            node.put("LastUpdatedTime", source.getLastUpdatedTime());
            copyIfPresent(node, "DataSourceParameters", source.getDataSourceParameters());
            copyIfPresent(node, "VpcConnectionProperties", source.getVpcConnectionProperties());
            copyIfPresent(node, "SslProperties", source.getSslProperties());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode createDataSet(String region, String awsAccountId, JsonNode request) {
        requireObject(request, "Request body");
        String dataSetId = requireText(request, "DataSetId");
        String key = storageKey(region, dataSetId);
        if (dataSets.get(key).isPresent()) {
            throw exists(dataSetId, "DATA_SET");
        }
        String importMode = requireEnum(request, "ImportMode", IMPORT_MODES);
        JsonNode physicalTableMap = requireObjectField(request, "PhysicalTableMap");
        long now = now();
        QuickSightDataSet dataSet = new QuickSightDataSet();
        dataSet.setDataSetId(dataSetId);
        dataSet.setName(requireText(request, "Name"));
        dataSet.setImportMode(importMode);
        dataSet.setRegion(region);
        dataSet.setAccountId(accountId(awsAccountId));
        dataSet.setArn(arn(region, accountId(awsAccountId), "dataset/" + dataSetId));
        dataSet.setCreatedTime(now);
        dataSet.setLastUpdatedTime(now);
        dataSet.setPhysicalTableMap(physicalTableMap);
        dataSet.setLogicalTableMap(optionalNode(request, "LogicalTableMap"));
        dataSet.setColumnGroups(optionalNode(request, "ColumnGroups"));
        dataSet.setFieldFolders(optionalNode(request, "FieldFolders"));
        dataSet.setPermissions(optionalNode(request, "Permissions"));
        dataSet.setRowLevelPermissionDataSet(optionalNode(request, "RowLevelPermissionDataSet"));
        dataSet.setDataSetUsageConfiguration(optionalNode(request, "DataSetUsageConfiguration"));
        dataSet.setDatasetParameters(optionalNode(request, "DatasetParameters"));
        dataSet.setTags(readTags(request));
        dataSets.put(key, dataSet);
        ObjectNode response = envelope();
        response.put("Arn", dataSet.getArn());
        response.put("DataSetId", dataSet.getDataSetId());
        return response;
    }

    public ObjectNode describeDataSet(String region, String dataSetId) {
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        ObjectNode response = envelope();
        ObjectNode node = response.putObject("DataSet");
        node.put("Arn", dataSet.getArn());
        node.put("DataSetId", dataSet.getDataSetId());
        node.put("Name", dataSet.getName());
        node.put("ImportMode", dataSet.getImportMode());
        node.put("CreatedTime", dataSet.getCreatedTime());
        node.put("LastUpdatedTime", dataSet.getLastUpdatedTime());
        copyIfPresent(node, "PhysicalTableMap", dataSet.getPhysicalTableMap());
        copyIfPresent(node, "LogicalTableMap", dataSet.getLogicalTableMap());
        copyIfPresent(node, "ColumnGroups", dataSet.getColumnGroups());
        copyIfPresent(node, "FieldFolders", dataSet.getFieldFolders());
        copyIfPresent(node, "RowLevelPermissionDataSet", dataSet.getRowLevelPermissionDataSet());
        copyIfPresent(node, "DataSetUsageConfiguration", dataSet.getDataSetUsageConfiguration());
        copyIfPresent(node, "DatasetParameters", dataSet.getDatasetParameters());
        ArrayNode outputColumns = node.putArray("OutputColumns");
        addOutputColumns(outputColumns, dataSet.getPhysicalTableMap());
        return response;
    }

    public synchronized ObjectNode updateDataSet(String region, String dataSetId, JsonNode request) {
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        requireObject(request, "Request body");
        dataSet.setName(requireText(request, "Name"));
        dataSet.setImportMode(requireEnum(request, "ImportMode", IMPORT_MODES));
        dataSet.setPhysicalTableMap(requireObjectField(request, "PhysicalTableMap"));
        if (request.has("LogicalTableMap")) {
            dataSet.setLogicalTableMap(optionalNode(request, "LogicalTableMap"));
        }
        if (request.has("ColumnGroups")) {
            dataSet.setColumnGroups(optionalNode(request, "ColumnGroups"));
        }
        if (request.has("FieldFolders")) {
            dataSet.setFieldFolders(optionalNode(request, "FieldFolders"));
        }
        if (request.has("RowLevelPermissionDataSet")) {
            dataSet.setRowLevelPermissionDataSet(optionalNode(request, "RowLevelPermissionDataSet"));
        }
        if (request.has("DataSetUsageConfiguration")) {
            dataSet.setDataSetUsageConfiguration(optionalNode(request, "DataSetUsageConfiguration"));
        }
        if (request.has("DatasetParameters")) {
            dataSet.setDatasetParameters(optionalNode(request, "DatasetParameters"));
        }
        dataSet.setLastUpdatedTime(now());
        dataSets.put(storageKey(region, dataSetId), dataSet);
        ObjectNode response = envelope();
        response.put("Arn", dataSet.getArn());
        response.put("DataSetId", dataSet.getDataSetId());
        return response;
    }

    public synchronized ObjectNode deleteDataSet(String region, String dataSetId) {
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        dataSets.delete(storageKey(region, dataSetId));
        ObjectNode response = envelope();
        response.put("Arn", dataSet.getArn());
        response.put("DataSetId", dataSet.getDataSetId());
        return response;
    }

    public synchronized ObjectNode createDashboard(
            String region, String awsAccountId, String dashboardId, JsonNode request) {
        requireId(dashboardId, "DashboardId");
        requireObject(request, "Request body");
        String key = storageKey(region, dashboardId);
        if (dashboards.get(key).isPresent()) {
            throw exists(dashboardId, "DASHBOARD");
        }
        long now = now();
        QuickSightDashboard dashboard = new QuickSightDashboard();
        dashboard.setDashboardId(dashboardId);
        dashboard.setName(requireText(request, "Name"));
        dashboard.setRegion(region);
        dashboard.setAccountId(accountId(awsAccountId));
        dashboard.setArn(arn(region, accountId(awsAccountId), "dashboard/" + dashboardId));
        dashboard.setCreatedTime(now);
        dashboard.setLastUpdatedTime(now);
        dashboard.setLastPublishedTime(now);
        dashboard.setVersionNumber(1);
        dashboard.setVersionStatus("CREATION_SUCCESSFUL");
        dashboard.setDefinition(optionalNode(request, "Definition"));
        dashboard.setSourceEntity(optionalNode(request, "SourceEntity"));
        dashboard.setParameters(optionalNode(request, "Parameters"));
        dashboard.setPermissions(optionalNode(request, "Permissions"));
        dashboard.setDashboardPublishOptions(optionalNode(request, "DashboardPublishOptions"));
        dashboard.setThemeArn(textOrNull(request, "ThemeArn"));
        dashboard.setVersionDescription(textOrNull(request, "VersionDescription"));
        dashboard.setTags(readTags(request));
        dashboards.put(key, dashboard);
        ObjectNode response = envelope();
        response.put("Arn", dashboard.getArn());
        response.put("VersionArn", dashboard.getArn() + "/version/1");
        response.put("DashboardId", dashboard.getDashboardId());
        response.put("CreationStatus", dashboard.getVersionStatus());
        return response;
    }

    public ObjectNode describeDashboard(String region, String dashboardId) {
        QuickSightDashboard dashboard = requireDashboard(region, dashboardId);
        ObjectNode response = envelope();
        ObjectNode node = response.putObject("Dashboard");
        node.put("DashboardId", dashboard.getDashboardId());
        node.put("Arn", dashboard.getArn());
        node.put("Name", dashboard.getName());
        node.put("CreatedTime", dashboard.getCreatedTime());
        node.put("LastPublishedTime", dashboard.getLastPublishedTime());
        node.put("LastUpdatedTime", dashboard.getLastUpdatedTime());
        ObjectNode version = node.putObject("Version");
        version.put("CreatedTime", dashboard.getCreatedTime());
        version.put("VersionNumber", dashboard.getVersionNumber());
        version.put("Status", dashboard.getVersionStatus());
        version.put("Arn", dashboard.getArn() + "/version/" + dashboard.getVersionNumber());
        if (dashboard.getVersionDescription() != null) {
            version.put("Description", dashboard.getVersionDescription());
        }
        if (dashboard.getThemeArn() != null) {
            version.put("ThemeArn", dashboard.getThemeArn());
        }
        return response;
    }

    public synchronized ObjectNode updateDashboard(String region, String dashboardId, JsonNode request) {
        QuickSightDashboard dashboard = requireDashboard(region, dashboardId);
        requireObject(request, "Request body");
        dashboard.setName(requireText(request, "Name"));
        if (request.has("Definition")) {
            dashboard.setDefinition(optionalNode(request, "Definition"));
        }
        if (request.has("SourceEntity")) {
            dashboard.setSourceEntity(optionalNode(request, "SourceEntity"));
        }
        if (request.has("Parameters")) {
            dashboard.setParameters(optionalNode(request, "Parameters"));
        }
        if (request.has("DashboardPublishOptions")) {
            dashboard.setDashboardPublishOptions(optionalNode(request, "DashboardPublishOptions"));
        }
        if (request.has("ThemeArn")) {
            dashboard.setThemeArn(textOrNull(request, "ThemeArn"));
        }
        if (request.has("VersionDescription")) {
            dashboard.setVersionDescription(textOrNull(request, "VersionDescription"));
        }
        long now = now();
        dashboard.setVersionNumber(dashboard.getVersionNumber() + 1);
        dashboard.setVersionStatus("UPDATE_SUCCESSFUL");
        dashboard.setLastUpdatedTime(now);
        dashboard.setLastPublishedTime(now);
        dashboards.put(storageKey(region, dashboardId), dashboard);
        ObjectNode response = envelope();
        response.put("Arn", dashboard.getArn());
        response.put("VersionArn", dashboard.getArn() + "/version/" + dashboard.getVersionNumber());
        response.put("DashboardId", dashboard.getDashboardId());
        response.put("CreationStatus", dashboard.getVersionStatus());
        return response;
    }

    public synchronized ObjectNode deleteDashboard(String region, String dashboardId) {
        QuickSightDashboard dashboard = requireDashboard(region, dashboardId);
        dashboards.delete(storageKey(region, dashboardId));
        ObjectNode response = envelope();
        response.put("Arn", dashboard.getArn());
        response.put("DashboardId", dashboard.getDashboardId());
        return response;
    }

    public synchronized ObjectNode createIngestion(
            String region, String dataSetId, String ingestionId, JsonNode request) {
        requireId(ingestionId, "IngestionId");
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        if ("DIRECT_QUERY".equals(dataSet.getImportMode())) {
            throw invalid("SPICE ingestion is not supported for datasets with DIRECT_QUERY import mode.");
        }
        if (dataSet.getIngestions().containsKey(ingestionId)) {
            throw exists(ingestionId, "INGESTION");
        }
        String ingestionType = "FULL_REFRESH";
        if (request != null && request.has("IngestionType") && !request.get("IngestionType").isNull()) {
            ingestionType = requireEnum(request, "IngestionType", INGESTION_TYPES);
        }
        QuickSightIngestion ingestion = new QuickSightIngestion();
        ingestion.setIngestionId(ingestionId);
        ingestion.setDataSetId(dataSetId);
        ingestion.setArn(dataSet.getArn() + "/ingestion/" + ingestionId);
        ingestion.setIngestionStatus("INITIALIZED");
        ingestion.setRequestType(ingestionType);
        ingestion.setRequestSource("MANUAL");
        ingestion.setCreatedTime(now());
        dataSet.getIngestions().put(ingestionId, ingestion);
        dataSets.put(storageKey(region, dataSetId), dataSet);
        ObjectNode response = envelope();
        response.put("Arn", ingestion.getArn());
        response.put("IngestionId", ingestion.getIngestionId());
        response.put("IngestionStatus", ingestion.getIngestionStatus());
        return response;
    }

    public ObjectNode describeIngestion(String region, String dataSetId, String ingestionId) {
        QuickSightIngestion ingestion = requireIngestion(region, dataSetId, ingestionId);
        ObjectNode response = envelope();
        response.set("Ingestion", toIngestionNode(ingestion));
        return response;
    }

    public synchronized ObjectNode cancelIngestion(String region, String dataSetId, String ingestionId) {
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        QuickSightIngestion ingestion = dataSet.getIngestions().get(ingestionId);
        if (ingestion == null) {
            throw notFound(ingestionId, "INGESTION");
        }
        if (!CANCELABLE.contains(ingestion.getIngestionStatus())) {
            throw invalid("Ingestion " + ingestionId + " cannot be cancelled in status "
                    + ingestion.getIngestionStatus() + ".");
        }
        ingestion.setIngestionStatus("CANCELLED");
        dataSets.put(storageKey(region, dataSetId), dataSet);
        ObjectNode response = envelope();
        response.put("Arn", ingestion.getArn());
        response.put("IngestionId", ingestion.getIngestionId());
        return response;
    }

    public ObjectNode listIngestions(String region, String dataSetId, String maxResults, String nextToken) {
        QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
        List<QuickSightIngestion> items = new ArrayList<>(dataSet.getIngestions().values());
        items.sort(Comparator.comparing(QuickSightIngestion::getCreatedTime).reversed());
        Page<QuickSightIngestion> page = paginate(items, maxResults, nextToken);
        ObjectNode response = envelope();
        ArrayNode list = response.putArray("Ingestions");
        for (QuickSightIngestion ingestion : page.items()) {
            list.add(toIngestionNode(ingestion));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode startDashboardSnapshotJob(
            String region, String awsAccountId, String dashboardId, JsonNode request) {
        QuickSightDashboard dashboard = requireDashboard(region, dashboardId);
        requireObject(request, "Request body");
        String snapshotJobId = requireText(request, "SnapshotJobId");
        if (dashboard.getSnapshotJobs().containsKey(snapshotJobId)) {
            throw exists(snapshotJobId, "SNAPSHOT_JOB");
        }
        JsonNode configuration = requireObjectField(request, "SnapshotConfiguration");
        long now = now();
        QuickSightSnapshotJob job = new QuickSightSnapshotJob();
        job.setSnapshotJobId(snapshotJobId);
        job.setDashboardId(dashboardId);
        job.setAwsAccountId(accountId(awsAccountId));
        job.setArn(dashboard.getArn() + "/snapshot-job/" + snapshotJobId);
        job.setJobStatus("COMPLETED");
        job.setCreatedTime(now);
        job.setLastUpdatedTime(now);
        job.setUserConfiguration(optionalNode(request, "UserConfiguration"));
        job.setSnapshotConfiguration(configuration);
        dashboard.getSnapshotJobs().put(snapshotJobId, job);
        dashboards.put(storageKey(region, dashboardId), dashboard);
        ObjectNode response = envelope();
        response.put("Arn", job.getArn());
        response.put("SnapshotJobId", job.getSnapshotJobId());
        return response;
    }

    public ObjectNode describeDashboardSnapshotJob(String region, String dashboardId, String snapshotJobId) {
        QuickSightSnapshotJob job = requireSnapshotJob(region, dashboardId, snapshotJobId);
        ObjectNode response = envelope();
        response.put("AwsAccountId", job.getAwsAccountId());
        response.put("DashboardId", job.getDashboardId());
        response.put("SnapshotJobId", job.getSnapshotJobId());
        response.put("Arn", job.getArn());
        response.put("JobStatus", job.getJobStatus());
        response.put("CreatedTime", job.getCreatedTime());
        response.put("LastUpdatedTime", job.getLastUpdatedTime());
        copyIfPresent(response, "UserConfiguration", job.getUserConfiguration());
        copyIfPresent(response, "SnapshotConfiguration", job.getSnapshotConfiguration());
        return response;
    }

    public ObjectNode describeDashboardSnapshotJobResult(String region, String dashboardId, String snapshotJobId) {
        QuickSightSnapshotJob job = requireSnapshotJob(region, dashboardId, snapshotJobId);
        ObjectNode response = envelope();
        response.put("Arn", job.getArn());
        response.put("JobStatus", job.getJobStatus());
        response.put("CreatedTime", job.getCreatedTime());
        response.put("LastUpdatedTime", job.getLastUpdatedTime());
        return response;
    }

    public ObjectNode generateEmbedUrlForRegisteredUser(String region, String awsAccountId, JsonNode request) {
        requireObject(request, "Request body");
        String userArn = requireText(request, "UserArn");
        requireObjectField(request, "ExperienceConfiguration");
        if (!USER_ARN.matcher(userArn).matches()) {
            throw invalid("UserArn is not a valid QuickSight user ARN: " + userArn);
        }
        throw new AwsException(
                "QuickSightUserNotFoundException",
                "The user specified in UserArn is not registered in this account.",
                404);
    }

    public ObjectNode generateEmbedUrlForAnonymousUser(String region, String awsAccountId, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "Namespace");
        JsonNode arns = request.get("AuthorizedResourceArns");
        if (arns == null || !arns.isArray() || arns.isEmpty()) {
            throw invalid("AuthorizedResourceArns is required.");
        }
        requireObjectField(request, "ExperienceConfiguration");
        ObjectNode response = envelope();
        response.put(
                "EmbedUrl",
                "https://quicksight." + region + ".amazonaws.com/embed/" + UUID.randomUUID()
                        + "?account=" + accountId(awsAccountId));
        response.put("AnonymousUserArn",
                arn(region, accountId(awsAccountId), "user/anonymous/" + UUID.randomUUID()));
        return response;
    }

    public ObjectNode listTagsForResource(String region, String resourceArn) {
        Map<String, String> tags = tagsFor(region, resourceArn);
        ObjectNode response = envelope();
        ArrayNode list = response.putArray("Tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return response;
    }

    public synchronized ObjectNode tagResource(String region, String resourceArn, JsonNode request) {
        requireObject(request, "Request body");
        Map<String, String> added = readTags(request);
        TaggedResource tagged = requireTagged(region, resourceArn);
        tagged.tags().putAll(added);
        persistTagged(tagged);
        return envelope();
    }

    public synchronized ObjectNode untagResource(String region, String resourceArn, List<String> keys) {
        TaggedResource tagged = requireTagged(region, resourceArn);
        if (keys != null) {
            for (String key : keys) {
                tagged.tags().remove(key);
            }
            persistTagged(tagged);
        }
        return envelope();
    }

    private ObjectNode toIngestionNode(QuickSightIngestion ingestion) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", ingestion.getArn());
        node.put("IngestionId", ingestion.getIngestionId());
        node.put("IngestionStatus", ingestion.getIngestionStatus());
        node.put("CreatedTime", ingestion.getCreatedTime());
        if (ingestion.getRequestType() != null) {
            node.put("RequestType", ingestion.getRequestType());
        }
        if (ingestion.getRequestSource() != null) {
            node.put("RequestSource", ingestion.getRequestSource());
        }
        return node;
    }

    private QuickSightDataSource requireDataSource(String region, String dataSourceId) {
        requireId(dataSourceId, "DataSourceId");
        return dataSources.get(storageKey(region, dataSourceId))
                .orElseThrow(() -> notFound(dataSourceId, "DATA_SOURCE"));
    }

    private QuickSightDataSet requireDataSet(String region, String dataSetId) {
        requireId(dataSetId, "DataSetId");
        return dataSets.get(storageKey(region, dataSetId))
                .orElseThrow(() -> notFound(dataSetId, "DATA_SET"));
    }

    private QuickSightDashboard requireDashboard(String region, String dashboardId) {
        requireId(dashboardId, "DashboardId");
        return dashboards.get(storageKey(region, dashboardId))
                .orElseThrow(() -> notFound(dashboardId, "DASHBOARD"));
    }

    private QuickSightIngestion requireIngestion(String region, String dataSetId, String ingestionId) {
        requireId(ingestionId, "IngestionId");
        QuickSightIngestion ingestion = requireDataSet(region, dataSetId).getIngestions().get(ingestionId);
        if (ingestion == null) {
            throw notFound(ingestionId, "INGESTION");
        }
        return ingestion;
    }

    private QuickSightSnapshotJob requireSnapshotJob(String region, String dashboardId, String snapshotJobId) {
        requireId(snapshotJobId, "SnapshotJobId");
        QuickSightSnapshotJob job = requireDashboard(region, dashboardId).getSnapshotJobs().get(snapshotJobId);
        if (job == null) {
            throw notFound(snapshotJobId, "SNAPSHOT_JOB");
        }
        return job;
    }

    private Map<String, String> tagsFor(String region, String resourceArn) {
        return requireTagged(region, resourceArn).tags();
    }

    private TaggedResource requireTagged(String region, String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw invalid("ResourceArn is required.");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid ResourceArn: " + resourceArn);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound(resourceArn, "RESOURCE");
        }
        String resource = parsed.resource();
        if (resource.startsWith("datasource/")) {
            QuickSightDataSource source = requireDataSource(region, resource.substring("datasource/".length()));
            if (!source.getArn().equals(resourceArn)) {
                throw notFound(resourceArn, "DATA_SOURCE");
            }
            return new TaggedResource("datasource", source.getDataSourceId(), source, source.getTags());
        }
        if (resource.startsWith("dataset/")) {
            String remainder = resource.substring("dataset/".length());
            String dataSetId = remainder.contains("/") ? remainder.substring(0, remainder.indexOf('/')) : remainder;
            QuickSightDataSet dataSet = requireDataSet(region, dataSetId);
            if (!dataSet.getArn().equals(resourceArn) && !resourceArn.startsWith(dataSet.getArn() + "/")) {
                throw notFound(resourceArn, "DATA_SET");
            }
            return new TaggedResource("dataset", dataSet.getDataSetId(), dataSet, dataSet.getTags());
        }
        if (resource.startsWith("dashboard/")) {
            String remainder = resource.substring("dashboard/".length());
            String dashboardId = remainder.contains("/") ? remainder.substring(0, remainder.indexOf('/')) : remainder;
            QuickSightDashboard dashboard = requireDashboard(region, dashboardId);
            if (!dashboard.getArn().equals(resourceArn) && !resourceArn.startsWith(dashboard.getArn() + "/")) {
                throw notFound(resourceArn, "DASHBOARD");
            }
            return new TaggedResource("dashboard", dashboard.getDashboardId(), dashboard, dashboard.getTags());
        }
        throw notFound(resourceArn, "RESOURCE");
    }

    private void persistTagged(TaggedResource tagged) {
        switch (tagged.kind()) {
            case "datasource" -> dataSources.put(
                    storageKey(((QuickSightDataSource) tagged.resource()).getRegion(), tagged.id()),
                    (QuickSightDataSource) tagged.resource());
            case "dataset" -> dataSets.put(
                    storageKey(((QuickSightDataSet) tagged.resource()).getRegion(), tagged.id()),
                    (QuickSightDataSet) tagged.resource());
            case "dashboard" -> dashboards.put(
                    storageKey(((QuickSightDashboard) tagged.resource()).getRegion(), tagged.id()),
                    (QuickSightDashboard) tagged.resource());
            default -> throw invalid("Unsupported QuickSight resource type.");
        }
    }

    private ObjectNode createdResource(String arn, String id, String idField, String creationStatus) {
        ObjectNode response = envelope();
        response.put("Arn", arn);
        response.put(idField, id);
        if (creationStatus != null) {
            response.put("CreationStatus", creationStatus);
        }
        return response;
    }

    private ObjectNode envelope() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RequestId", UUID.randomUUID().toString());
        return response;
    }

    private static void copyIfPresent(ObjectNode parent, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            parent.set(field, value);
        }
    }

    private static void addOutputColumns(ArrayNode outputColumns, JsonNode physicalTableMap) {
        if (physicalTableMap == null || !physicalTableMap.isObject()) {
            return;
        }
        physicalTableMap.fields().forEachRemaining(entry -> {
            JsonNode table = entry.getValue();
            if (table == null || !table.isObject()) {
                return;
            }
            JsonNode customSql = table.get("CustomSql");
            JsonNode columns = customSql != null ? customSql.get("Columns") : null;
            if (columns == null || !columns.isArray()) {
                JsonNode relational = table.get("RelationalTable");
                columns = relational != null ? relational.get("InputColumns") : null;
            }
            if (columns == null || !columns.isArray()) {
                return;
            }
            for (JsonNode column : columns) {
                if (column == null || !column.isObject() || !column.has("Name")) {
                    continue;
                }
                ObjectNode out = outputColumns.addObject();
                out.put("Name", column.get("Name").asText());
                if (column.has("Type") && column.get("Type").isTextual()) {
                    out.put("Type", column.get("Type").asText());
                }
            }
        });
    }

    private <T> Page<T> paginate(List<T> items, String maxResults, String nextToken) {
        int limit = DEFAULT_MAX_RESULTS;
        if (maxResults != null && !maxResults.isBlank()) {
            try {
                limit = Integer.parseInt(maxResults);
            } catch (NumberFormatException e) {
                throw invalid("max-results must be an integer.");
            }
            if (limit < 1 || limit > MAX_RESULTS) {
                throw invalid("max-results must be between 1 and " + MAX_RESULTS + ".");
            }
        }
        int offset = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                offset = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw invalid("Invalid next-token.");
            }
            if (offset < 0 || offset > items.size()) {
                throw invalid("Invalid next-token.");
            }
        }
        int end = Math.min(items.size(), offset + limit);
        String token = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private String accountId(String awsAccountId) {
        if (awsAccountId == null || awsAccountId.isBlank()) {
            return regionResolver.getAccountId();
        }
        return awsAccountId;
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String arn(String region, String accountId, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, resource).toString();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null || !request.has("Tags") || request.get("Tags").isNull()) {
            return tags;
        }
        JsonNode node = request.get("Tags");
        if (!node.isArray()) {
            throw invalid("Tags must be a list of Key/Value objects.");
        }
        for (JsonNode tag : node) {
            if (tag == null || !tag.isObject()) {
                throw invalid("Tags members must be objects.");
            }
            JsonNode key = tag.get("Key");
            JsonNode value = tag.get("Value");
            if (key == null || !key.isTextual() || key.textValue().isBlank()) {
                throw invalid("Tag Key is required.");
            }
            if (value == null || !value.isTextual()) {
                throw invalid("Tag Value is required.");
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field + " must be a JSON object.");
        }
    }

    private static JsonNode requireObjectField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isObject()) {
            throw invalid(field + " is required.");
        }
        return value;
    }

    private static JsonNode optionalNode(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " is required.");
        }
        return value.textValue();
    }

    private static String requireEnum(JsonNode node, String field, Set<String> allowed) {
        String value = requireText(node, field);
        if (!allowed.contains(value)) {
            throw invalid(field + " is invalid: " + value);
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " not found: " + resourceId,
                404,
                Map.of("ResourceType", resourceType));
    }

    private static AwsException exists(String resourceId, String resourceType) {
        return new AwsException(
                "ResourceExistsException",
                resourceType + " already exists: " + resourceId,
                409,
                Map.of("ResourceType", resourceType));
    }

    private record Page<T>(List<T> items, String nextToken) {
    }

    private record TaggedResource(String kind, String id, Object resource, Map<String, String> tags) {
    }
}
