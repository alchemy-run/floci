package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CsvParser;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.*;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);
    public static final String DEFAULT_CATALOG = "AwsDataCatalog";
    private static final String DEFAULT_OUTPUT_BUCKET = "floci-athena-results";
    private static final String DEFAULT_WORKGROUP = "primary";
    private static final String DEFAULT_ENGINE_VERSION = "Athena engine version 3";
    private static final Pattern SIMPLE_SELECT_LITERAL = Pattern.compile(
            "^\\s*SELECT\\s+(\\d+)(?:\\s+AS\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final StorageBackend<String, QueryExecution> queryStore;
    private final StorageBackend<String, WorkGroup> workGroupStore;
    private final StorageBackend<String, DataCatalog> dataCatalogStore;
    private final StorageBackend<String, NamedQuery> namedQueryStore;
    private final StorageBackend<String, PreparedStatement> preparedStatementStore;
    private final FlociDuckClient duckClient;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final RegionResolver regionResolver;
    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Integer> queryStateSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResultSet> queryResults = new ConcurrentHashMap<>();

    @Inject
    public AthenaService(StorageFactory storageFactory,
                         FlociDuckClient duckClient,
                         GlueService glueService,
                         S3Service s3Service,
                         EmulatorConfig config,
                         Vertx vertx,
                         RegionResolver regionResolver,
                         EventBridgeService eventBridgeService,
                         ObjectMapper objectMapper) {
        this.queryStore = storageFactory.create("athena", "queries.json",
                new TypeReference<>() {});
        this.workGroupStore = storageFactory.create("athena", "workgroups.json",
                new TypeReference<>() {});
        this.dataCatalogStore = storageFactory.create("athena", "datacatalogs.json",
                new TypeReference<>() {});
        this.namedQueryStore = storageFactory.create("athena", "named_queries.json",
                new TypeReference<>() {});
        this.preparedStatementStore = storageFactory.create("athena", "prepared_statements.json",
                new TypeReference<>() {});
        this.duckClient = duckClient;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.config = config;
        this.vertx = vertx;
        this.regionResolver = regionResolver;
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    public String startQueryExecution(String query,
                                      String workGroup,
                                      QueryExecutionContext context,
                                      ResultConfiguration resultConfiguration) {
        String id = UUID.randomUUID().toString();
        String database = context != null && context.getDatabase() != null ? context.getDatabase() : "default";
        QueryExecutionContext resolvedContext = context != null ? context : new QueryExecutionContext();
        resolvedContext.setDatabase(database);
        if (resolvedContext.getCatalog() == null || resolvedContext.getCatalog().isBlank()) {
            resolvedContext.setCatalog(DEFAULT_CATALOG);
        }

        // AWS reports the result CSV object itself as the OutputLocation, not a
        // directory prefix — the same key is written and returned to the client.
        String outputLocation = resolveOutputLocation(resultConfiguration, id);
        ResultConfiguration resolvedResult = new ResultConfiguration(outputLocation);

        QueryExecution execution = new QueryExecution(id, query, workGroup, resolvedResult, resolvedContext);
        queryStore.put(id, execution);
        publishQueryStateChange(execution, null, QueryExecutionState.QUEUED);

        transitionQueryState(execution, QueryExecutionState.QUEUED, QueryExecutionState.RUNNING);

        Optional<String> literal = simpleSelectLiteral(query);
        if (literal.isPresent()) {
            ResultSet results = literalResultSet(literal.get());
            queryResults.put(id, results);
            writeLiteralCsv(outputLocation, literal.get());
            transitionQueryState(execution, QueryExecutionState.RUNNING, QueryExecutionState.SUCCEEDED);
            LOG.infov("Query {0} succeeded (literal SELECT)", id);
            return id;
        }

        if (config.services().athena().mock()) {
            transitionQueryState(execution, QueryExecutionState.RUNNING, QueryExecutionState.SUCCEEDED);
            LOG.infov("Query {0} accepted (mock mode)", id);
            return id;
        }

        // Submit async — caller gets the ID immediately while execution runs in background
        vertx.executeBlocking(() -> {
            String setupDdl = buildGlueDdl(database);
            ensureOutputBucket(outputLocation);
            duckClient.execute(query, setupDdl, outputLocation);
            return null;
        }).onSuccess(v -> {
            transitionQueryState(execution, QueryExecutionState.RUNNING, QueryExecutionState.SUCCEEDED);
            LOG.infov("Query {0} succeeded", id);
        }).onFailure(e -> {
            execution.getStatus().setStateChangeReason(e.getMessage());
            transitionQueryState(execution, QueryExecutionState.RUNNING, QueryExecutionState.FAILED);
            LOG.warnv("Query {0} failed: {1}", id, e.getMessage());
        });

        return id;
    }

    public QueryExecution getQueryExecution(String id) {
        return queryStore.get(id)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Query execution not found: " + id, 400));
    }

    public List<QueryExecution> listQueryExecutions() {
        return queryStore.scan(k -> true);
    }

    public void stopQueryExecution(String id) {
        QueryExecution execution = getQueryExecution(id);
        QueryExecutionState state = execution.getStatus().getState();
        if (state == QueryExecutionState.SUCCEEDED
                || state == QueryExecutionState.FAILED
                || state == QueryExecutionState.CANCELLED) {
            return;
        }
        transitionQueryState(execution, state, QueryExecutionState.CANCELLED);
    }

    public WorkGroup createWorkGroup(CreateWorkGroupRequest request, String region) {
        validateWorkGroupName(request.getName());
        if (DEFAULT_WORKGROUP.equals(request.getName())) {
            throw new AwsException("InvalidRequestException",
                    DEFAULT_WORKGROUP + " workGroup could not be created", 400);
        }
        String key = workGroupKey(region, request.getName());
        if (workGroupStore.get(key).isPresent()) {
            throw new AwsException("InvalidRequestException", "WorkGroup already exists", 400);
        }

        WorkGroup workGroup = new WorkGroup();
        workGroup.setName(request.getName());
        workGroup.setDescription(request.getDescription());
        workGroup.setState("ENABLED");
        workGroup.setCreationTime(Instant.now());
        workGroup.setTags(normalizeTags(request.getTags()));
        workGroup.setConfiguration(normalizeWorkGroupConfiguration(request.getConfiguration()));
        workGroupStore.put(key, workGroup);
        return workGroup;
    }

    public Map<String, Object> getWorkGroup(String name, String region) {
        String resolved = name == null || name.isBlank() ? DEFAULT_WORKGROUP : name;
        if (DEFAULT_WORKGROUP.equals(resolved)) {
            return primaryWorkGroupSummary();
        }
        WorkGroup workGroup = workGroupStore.get(workGroupKey(region, resolved))
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "WorkGroup " + resolved + " is not found.", 400));
        return toWorkGroupDetail(workGroup);
    }

    public void deleteWorkGroup(String name, String region, boolean recursive) {
        if (recursive) {
            namedQueryStore.scan(k -> true).stream()
                    .filter(q -> name.equals(q.getWorkGroup()))
                    .map(NamedQuery::getNamedQueryId)
                    .forEach(namedQueryStore::delete);
            preparedStatementStore.scan(k -> true).stream()
                    .filter(s -> name.equals(s.getWorkGroupName()))
                    .map(s -> preparedStatementKey(s.getWorkGroupName(), s.getStatementName()))
                    .forEach(preparedStatementStore::delete);
        }
        workGroupStore.delete(workGroupKey(region, name));
    }

    public void deleteWorkGroup(String name, String region) {
        deleteWorkGroup(name, region, false);
    }

    public void updateWorkGroup(String name, String description, String state,
                                Map<String, Object> configurationUpdates, String region) {
        if (DEFAULT_WORKGROUP.equals(name)) {
            throw new AwsException("InvalidRequestException", "The primary workgroup cannot be updated.", 400);
        }
        String key = workGroupKey(region, name);
        WorkGroup workGroup = workGroupStore.get(key)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "WorkGroup " + name + " is not found.", 400));
        if (description != null) {
            workGroup.setDescription(description);
        }
        if (state != null) {
            workGroup.setState(state);
        }
        if (configurationUpdates != null && !configurationUpdates.isEmpty()) {
            applyWorkGroupUpdates(workGroup, configurationUpdates);
        }
        workGroupStore.put(key, workGroup);
    }

    public List<Map<String, Object>> listWorkGroups(String region) {
        List<Map<String, Object>> workGroups = new ArrayList<>();
        workGroups.add(primaryWorkGroupSummary());
        workGroups.addAll(workGroupStore.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(WorkGroup::getName))
                .map(this::toWorkGroupSummary)
                .toList());
        return workGroups;
    }

    public List<Map<String, Object>> listDataCatalogs() {
        List<Map<String, Object>> catalogs = new ArrayList<>();
        catalogs.add(Map.of("CatalogName", DEFAULT_CATALOG, "Type", "GLUE"));
        catalogs.addAll(dataCatalogStore.scan(k -> true).stream()
                .sorted(Comparator.comparing(DataCatalog::getName))
                .map(catalog -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("CatalogName", catalog.getName());
                    summary.put("Type", catalog.getType());
                    return summary;
                })
                .toList());
        return catalogs;
    }

    public Map<String, Object> getDataCatalog(String name) {
        String resolved = name == null || name.isBlank() ? DEFAULT_CATALOG : name;
        if (DEFAULT_CATALOG.equals(resolved)) {
            return Map.of("Name", DEFAULT_CATALOG, "Type", "GLUE");
        }
        DataCatalog catalog = dataCatalogStore.get(resolved)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "DataCatalog " + resolved + " is not found.", 400));
        return toDataCatalogDetail(catalog);
    }

    public void createDataCatalog(DataCatalog catalog) {
        if (catalog.getName() == null || catalog.getName().isBlank()) {
            throw new AwsException("InvalidRequestException", "Name is required", 400);
        }
        if (DEFAULT_CATALOG.equals(catalog.getName())) {
            throw new AwsException("InvalidRequestException", "AwsDataCatalog already exists", 400);
        }
        if (dataCatalogStore.get(catalog.getName()).isPresent()) {
            throw new AwsException("InvalidRequestException", "DataCatalog already exists", 400);
        }
        if (catalog.getParameters() == null) {
            catalog.setParameters(new LinkedHashMap<>());
        }
        dataCatalogStore.put(catalog.getName(), catalog);
    }

    public void updateDataCatalog(String name, String type, String description, Map<String, String> parameters) {
        DataCatalog catalog = dataCatalogStore.get(name)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "DataCatalog " + name + " is not found.", 400));
        if (type != null) {
            catalog.setType(type);
        }
        if (description != null) {
            catalog.setDescription(description);
        }
        if (parameters != null) {
            catalog.setParameters(new LinkedHashMap<>(parameters));
        }
        dataCatalogStore.put(name, catalog);
    }

    public void deleteDataCatalog(String name) {
        if (DEFAULT_CATALOG.equals(name)) {
            throw new AwsException("InvalidRequestException", "AwsDataCatalog cannot be deleted", 400);
        }
        if (dataCatalogStore.get(name).isEmpty()) {
            throw new AwsException("InvalidRequestException", "DataCatalog " + name + " is not found.", 400);
        }
        dataCatalogStore.delete(name);
    }

    public Map<String, Object> getDatabase(String catalog, String name) {
        Database database = glueService.getDatabase(name);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("Name", database.getName());
        if (database.getDescription() != null) {
            detail.put("Description", database.getDescription());
        }
        if (database.getParameters() != null) {
            detail.put("Parameters", database.getParameters());
        }
        return detail;
    }

    public String createNamedQuery(NamedQuery query) {
        if (query.getClientRequestToken() != null && !query.getClientRequestToken().isBlank()) {
            Optional<NamedQuery> existing = namedQueryStore.scan(k -> true).stream()
                    .filter(q -> query.getClientRequestToken().equals(q.getClientRequestToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get().getNamedQueryId();
            }
        }
        if (query.getWorkGroup() == null || query.getWorkGroup().isBlank()) {
            query.setWorkGroup(DEFAULT_WORKGROUP);
        }
        query.setNamedQueryId(UUID.randomUUID().toString());
        namedQueryStore.put(query.getNamedQueryId(), query);
        return query.getNamedQueryId();
    }

    public NamedQuery getNamedQuery(String id) {
        return namedQueryStore.get(id)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "NamedQuery " + id + " does not exist", 400));
    }

    public List<String> listNamedQueries(String workGroup) {
        String wg = workGroup == null || workGroup.isBlank() ? DEFAULT_WORKGROUP : workGroup;
        return namedQueryStore.scan(k -> true).stream()
                .filter(q -> wg.equals(q.getWorkGroup()))
                .map(NamedQuery::getNamedQueryId)
                .toList();
    }

    public void updateNamedQuery(String id, String name, String description, String queryString) {
        NamedQuery query = getNamedQuery(id);
        if (name != null) {
            query.setName(name);
        }
        if (description != null) {
            query.setDescription(description);
        }
        if (queryString != null) {
            query.setQueryString(queryString);
        }
        namedQueryStore.put(id, query);
    }

    public void deleteNamedQuery(String id) {
        if (namedQueryStore.get(id).isEmpty()) {
            throw new AwsException("InvalidRequestException", "NamedQuery " + id + " does not exist", 400);
        }
        namedQueryStore.delete(id);
    }

    public Map<String, Object> batchGetNamedQuery(List<String> ids) {
        List<NamedQuery> found = new ArrayList<>();
        List<Map<String, Object>> unprocessed = new ArrayList<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            Optional<NamedQuery> query = namedQueryStore.get(id);
            if (query.isPresent()) {
                found.add(query.get());
            } else {
                unprocessed.add(Map.of("NamedQueryId", id));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("NamedQueries", found);
        out.put("UnprocessedNamedQueryIds", unprocessed);
        return out;
    }

    public void createPreparedStatement(PreparedStatement statement) {
        if (statement.getWorkGroupName() == null || statement.getWorkGroupName().isBlank()) {
            statement.setWorkGroupName(DEFAULT_WORKGROUP);
        }
        statement.setLastModifiedTime(Instant.now());
        preparedStatementStore.put(preparedStatementKey(statement.getWorkGroupName(), statement.getStatementName()),
                statement);
    }

    public PreparedStatement getPreparedStatement(String workGroup, String statementName) {
        return preparedStatementStore.get(preparedStatementKey(workGroup, statementName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Prepared statement not found: " + statementName, 400));
    }

    public List<Map<String, Object>> listPreparedStatements(String workGroup) {
        String wg = workGroup == null || workGroup.isBlank() ? DEFAULT_WORKGROUP : workGroup;
        return preparedStatementStore.scan(k -> true).stream()
                .filter(s -> wg.equals(s.getWorkGroupName()))
                .sorted(Comparator.comparing(PreparedStatement::getStatementName))
                .map(s -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("StatementName", s.getStatementName());
                    if (s.getLastModifiedTime() != null) {
                        summary.put("LastModifiedTime", s.getLastModifiedTime().getEpochSecond());
                    }
                    return summary;
                })
                .toList();
    }

    public void updatePreparedStatement(PreparedStatement statement) {
        PreparedStatement existing = getPreparedStatement(statement.getWorkGroupName(), statement.getStatementName());
        if (statement.getQueryStatement() != null) {
            existing.setQueryStatement(statement.getQueryStatement());
        }
        if (statement.getDescription() != null) {
            existing.setDescription(statement.getDescription());
        }
        existing.setLastModifiedTime(Instant.now());
        preparedStatementStore.put(preparedStatementKey(existing.getWorkGroupName(), existing.getStatementName()),
                existing);
    }

    public void deletePreparedStatement(String workGroup, String statementName) {
        getPreparedStatement(workGroup, statementName);
        preparedStatementStore.delete(preparedStatementKey(workGroup, statementName));
    }

    public Map<String, Object> batchGetPreparedStatement(String workGroup, List<String> names) {
        List<PreparedStatement> found = new ArrayList<>();
        List<Map<String, Object>> unprocessed = new ArrayList<>();
        for (String name : names == null ? List.<String>of() : names) {
            Optional<PreparedStatement> statement = preparedStatementStore.get(preparedStatementKey(workGroup, name));
            if (statement.isPresent()) {
                found.add(statement.get());
            } else {
                unprocessed.add(Map.of("StatementName", name));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("PreparedStatements", found);
        out.put("UnprocessedPreparedStatementNames", unprocessed);
        return out;
    }

    public Map<String, Object> batchGetQueryExecution(List<String> ids) {
        List<QueryExecution> found = new ArrayList<>();
        List<Map<String, Object>> unprocessed = new ArrayList<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            Optional<QueryExecution> execution = queryStore.get(id);
            if (execution.isPresent()) {
                found.add(execution.get());
            } else {
                unprocessed.add(Map.of("QueryExecutionId", id));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("QueryExecutions", found);
        out.put("UnprocessedQueryExecutionIds", unprocessed);
        return out;
    }

    public Map<String, Object> getQueryRuntimeStatistics(String id) {
        getQueryExecution(id);
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("QueryQueueTimeInMillis", 0L);
        timeline.put("QueryPlanningTimeInMillis", 0L);
        timeline.put("EngineExecutionTimeInMillis", 1L);
        timeline.put("ServiceProcessingTimeInMillis", 0L);
        timeline.put("TotalExecutionTimeInMillis", 1L);
        return Map.of("QueryRuntimeStatistics", Map.of("Timeline", timeline));
    }

    public void tagResource(String resourceArn, List<WorkGroupTag> tags, String region) {
        TaggedAthenaResource resource = resolveTaggedResource(resourceArn, region);
        Map<String, String> merged = tagsToMap(resource.getTags());
        for (WorkGroupTag tag : tags == null ? List.<WorkGroupTag>of() : tags) {
            if (tag != null && tag.getKey() != null) {
                merged.put(tag.getKey(), tag.getValue());
            }
        }
        resource.setTags(mapToTags(merged));
        resource.persist();
    }

    public void untagResource(String resourceArn, List<String> keys, String region) {
        TaggedAthenaResource resource = resolveTaggedResource(resourceArn, region);
        Map<String, String> merged = tagsToMap(resource.getTags());
        if (keys != null) {
            keys.forEach(merged::remove);
        }
        resource.setTags(mapToTags(merged));
        resource.persist();
    }

    public List<WorkGroupTag> listTagsForResource(String resourceArn, String region) {
        return resolveTaggedResource(resourceArn, region).getTags();
    }

    public List<Map<String, Object>> listDatabases(String catalog) {
        return glueService.getDatabases().stream()
                .map(Database::getName)
                .sorted()
                .map(name -> Map.<String, Object>of("Name", name))
                .toList();
    }

    public List<Map<String, Object>> listTableMetadata(String catalog, String database) {
        return glueService.getTables(database).stream()
                .sorted(Comparator.comparing(Table::getName))
                .map(table -> tableMetadata(catalog, database, table))
                .toList();
    }

    public Map<String, Object> getTableMetadata(String catalog, String database, String tableName) {
        return tableMetadata(catalog, database, glueService.getTable(database, tableName));
    }

    public ResultSet getQueryResults(String id) {
        QueryExecution execution = getQueryExecution(id);

        if (execution.getStatus().getState() != QueryExecutionState.SUCCEEDED) {
            throw new AwsException("InvalidRequestException", "Query has not succeeded yet", 400);
        }

        ResultSet cached = queryResults.get(id);
        if (cached != null) {
            return cached;
        }

        if (config.services().athena().mock()
                || execution.getResultConfiguration() == null
                || execution.getResultConfiguration().getOutputLocation() == null) {
            return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));
        }

        return readResultsFromS3(execution.getResultConfiguration().getOutputLocation(), id);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static Optional<String> simpleSelectLiteral(String query) {
        if (query == null) {
            return Optional.empty();
        }
        Matcher matcher = SIMPLE_SELECT_LITERAL.matcher(query);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static ResultSet literalResultSet(String value) {
        ResultSet.ColumnInfo column = new ResultSet.ColumnInfo("_col0", "integer");
        ResultSet.Row header = new ResultSet.Row(List.of(new ResultSet.Datum("_col0")));
        ResultSet.Row data = new ResultSet.Row(List.of(new ResultSet.Datum(value)));
        return new ResultSet(List.of(header, data), new ResultSet.ResultSetMetadata(List.of(column)));
    }

    private void writeLiteralCsv(String outputLocation, String value) {
        try {
            ensureOutputBucket(outputLocation);
            String bucket = extractBucket(outputLocation);
            String key = extractKey(outputLocation);
            if (bucket == null || key == null || key.isBlank()) {
                return;
            }
            byte[] csv = ("_col0\n" + value + "\n").getBytes(StandardCharsets.UTF_8);
            s3Service.putObject(bucket, key, csv, "text/csv", Map.of());
        } catch (Exception e) {
            LOG.debugv("Could not write literal SELECT result to {0}: {1}", outputLocation, e.getMessage());
        }
    }

    private String buildGlueDdl(String contextDatabase) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Database database : glueService.getDatabases()) {
                String dbName = database.getName();
                sb.append(AthenaGlueDdl.createSchema(dbName));
                for (Table table : glueService.getTables(dbName)) {
                    String location = table.getStorageDescriptor() != null
                            ? table.getStorageDescriptor().getLocation()
                            : null;
                    if (location == null || location.isBlank()) {
                        continue;
                    }
                    List<String> files = listDataFiles(location);
                    // AWS does not fail a query in database A because database B
                    // has a table whose S3 location bucket is missing. Skip those
                    // tables instead of handing DuckDB a fallback glob that 404s.
                    if (AthenaGlueDdl.skipUnreadableLocation(files, s3BucketExists(extractBucket(location)))) {
                        continue;
                    }
                    String selectSql = AthenaGlueDdl.selectFromFiles(
                            table, files, AthenaGlueDdl.globForPrefix(location));
                    if (selectSql == null) {
                        continue;
                    }
                    sb.append(AthenaGlueDdl.createView(dbName, table.getName(), selectSql));
                    if (dbName.equals(contextDatabase)) {
                        sb.append(AthenaGlueDdl.createUnqualifiedView(table.getName(), selectSql));
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not inject Glue DDL for database {0}: {1}", contextDatabase, e.getMessage());
        }
        return sb.toString();
    }

    private boolean s3BucketExists(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return false;
        }
        try {
            s3Service.headBucket(bucket);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> listDataFiles(String location) {
        String bucket = extractBucket(location);
        String prefix = extractKey(location);
        if (bucket == null) {
            return List.of();
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        try {
            return s3Service.listObjects(bucket, prefix, null, 1000).stream()
                    .map(S3Object::getKey)
                    .filter(key -> key != null && !key.endsWith("/"))
                    .map(key -> "s3://" + bucket + "/" + key)
                    .toList();
        } catch (Exception e) {
            LOG.debugv("Could not list S3 objects at {0}: {1}", location, e.getMessage());
            return List.of();
        }
    }

    private void transitionQueryState(QueryExecution execution,
                                      QueryExecutionState previous,
                                      QueryExecutionState next) {
        execution.getStatus().setState(next);
        if (next == QueryExecutionState.SUCCEEDED
                || next == QueryExecutionState.FAILED
                || next == QueryExecutionState.CANCELLED) {
            execution.getStatus().setCompletionDateTime(Instant.now());
        }
        queryStore.put(execution.getQueryExecutionId(), execution);
        // Publish off the caller stack. StartQueryExecution is often nested
        // inside a Lambda Function URL invoke; a synchronous EventBridge→Lambda
        // delivery of the same function re-enters the executor and drops the
        // in-flight HTTP socket.
        publishQueryStateChange(execution, previous, next);
    }

    private void publishQueryStateChange(QueryExecution execution,
                                         QueryExecutionState previous,
                                         QueryExecutionState current) {
        if (eventBridgeService == null || objectMapper == null) {
            return;
        }
        String queryId = execution.getQueryExecutionId();
        String statementType = execution.getStatementType() != null
                ? execution.getStatementType() : "DML";
        String workGroup = execution.getWorkGroup() != null
                ? execution.getWorkGroup() : DEFAULT_WORKGROUP;
        int sequence = queryStateSequence.merge(queryId, 1, Integer::sum);
        String region = regionResolver.getRegion();
        Runnable publish = () -> {
            try {
                ObjectNode detail = objectMapper.createObjectNode();
                detail.put("versionId", "0");
                detail.put("currentState", current.name());
                if (previous != null) {
                    detail.put("previousState", previous.name());
                }
                detail.put("statementType", statementType);
                detail.put("queryExecutionId", queryId);
                detail.put("workgroupName", workGroup);
                detail.put("sequenceNumber", String.valueOf(sequence));

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("Source", "aws.athena");
                entry.put("DetailType", "Athena Query State Change");
                entry.put("Detail", objectMapper.writeValueAsString(detail));
                eventBridgeService.putEvents(List.of(entry), region);
            } catch (Exception e) {
                LOG.warnv("Failed to publish Athena Query State Change for {0}: {1}",
                        queryId, e.getMessage());
            }
        };
        if (vertx != null) {
            vertx.runOnContext(ignored -> publish.run());
        } else {
            publish.run();
        }
    }

    private String resolveOutputLocation(ResultConfiguration rc, String queryId) {
        String base = (rc != null && rc.getOutputLocation() != null && !rc.getOutputLocation().isBlank())
                ? rc.getOutputLocation()
                : "s3://" + DEFAULT_OUTPUT_BUCKET + "/results/";
        return base.endsWith("/") ? base + queryId + ".csv" : base + "/" + queryId + ".csv";
    }

    private WorkGroupConfiguration normalizeWorkGroupConfiguration(CreateWorkGroupConfigurationRequest configuration) {
        WorkGroupConfiguration normalized = defaultWorkGroupConfiguration();
        if (configuration == null) {
            return normalized;
        }

        if (configuration.getResultConfiguration() != null) {
            ResultConfiguration result = new ResultConfiguration(
                    configuration.getResultConfiguration().getOutputLocation());
            result.setEncryptionConfiguration(configuration.getResultConfiguration().getEncryptionConfiguration());
            normalized.setResultConfiguration(result);
        }
        if (configuration.getEnforceWorkGroupConfiguration() != null) {
            normalized.setEnforceWorkGroupConfiguration(configuration.getEnforceWorkGroupConfiguration());
        }
        if (configuration.getPublishCloudWatchMetricsEnabled() != null) {
            normalized.setPublishCloudWatchMetricsEnabled(configuration.getPublishCloudWatchMetricsEnabled());
        }
        if (configuration.getRequesterPaysEnabled() != null) {
            normalized.setRequesterPaysEnabled(configuration.getRequesterPaysEnabled());
        }
        if (configuration.getBytesScannedCutoffPerQuery() != null) {
            normalized.setBytesScannedCutoffPerQuery(configuration.getBytesScannedCutoffPerQuery());
        }
        if (configuration.getEngineVersion() != null) {
            String selectedEngineVersion = configuration.getEngineVersion().getSelectedEngineVersion();
            boolean hasSelectedEngineVersion = selectedEngineVersion != null && !selectedEngineVersion.isBlank();

            if (hasSelectedEngineVersion) {
                QueryExecution.EngineVersion engineVersion = new QueryExecution.EngineVersion();
                engineVersion.setSelectedEngineVersion(selectedEngineVersion);
                engineVersion.setEffectiveEngineVersion(resolveEffectiveEngineVersion(selectedEngineVersion));
                normalized.setEngineVersion(engineVersion);
            }
        }
        return normalized;
    }

    private String resolveEffectiveEngineVersion(String selectedEngineVersion) {
        if (selectedEngineVersion == null || selectedEngineVersion.isBlank() || "AUTO".equals(selectedEngineVersion)) {
            return DEFAULT_ENGINE_VERSION;
        }
        return selectedEngineVersion;
    }

    private WorkGroupConfiguration defaultWorkGroupConfiguration() {
        WorkGroupConfiguration configuration = new WorkGroupConfiguration();
        configuration.setResultConfiguration(new ResultConfiguration("s3://" + DEFAULT_OUTPUT_BUCKET + "/results/"));
        configuration.setEnforceWorkGroupConfiguration(false);
        configuration.setPublishCloudWatchMetricsEnabled(false);
        configuration.setRequesterPaysEnabled(false);
        configuration.setEngineVersion(defaultEngineVersion());
        return configuration;
    }

    private QueryExecution.EngineVersion defaultEngineVersion() {
        QueryExecution.EngineVersion engineVersion = new QueryExecution.EngineVersion();
        engineVersion.setSelectedEngineVersion(DEFAULT_ENGINE_VERSION);
        engineVersion.setEffectiveEngineVersion(DEFAULT_ENGINE_VERSION);
        return engineVersion;
    }

    private Map<String, Object> primaryWorkGroupSummary() {
        return Map.of(
                "Name", DEFAULT_WORKGROUP,
                "State", "ENABLED",
                "Configuration", Map.of(
                        "EngineVersion", Map.of(
                                "SelectedEngineVersion", DEFAULT_ENGINE_VERSION,
                                "EffectiveEngineVersion", DEFAULT_ENGINE_VERSION
                        ),
                        "ResultConfiguration", Map.of("OutputLocation", "s3://" + DEFAULT_OUTPUT_BUCKET + "/results/"),
                        "EnforceWorkGroupConfiguration", false,
                        "PublishCloudWatchMetricsEnabled", false,
                        "RequesterPaysEnabled", false
                )
        );
    }

    private Map<String, Object> toWorkGroupDetail(WorkGroup workGroup) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("Name", workGroup.getName());
        detail.put("State", workGroup.getState());
        if (workGroup.getDescription() != null) {
            detail.put("Description", workGroup.getDescription());
        }
        if (workGroup.getCreationTime() != null) {
            detail.put("CreationTime", workGroup.getCreationTime().getEpochSecond());
        }
        if (workGroup.getConfiguration() != null) {
            detail.put("Configuration", workGroup.getConfiguration());
        }
        return detail;
    }

    private Map<String, Object> toWorkGroupSummary(WorkGroup workGroup) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Name", workGroup.getName());
        result.put("State", workGroup.getState());
        return result;
    }

    private List<WorkGroupTag> normalizeTags(List<WorkGroupTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(tag -> new WorkGroupTag(tag.getKey(), tag.getValue()))
                .toList();
    }

    private String workGroupKey(String region, String name) {
        return region + ":" + name;
    }

    private String preparedStatementKey(String workGroup, String statementName) {
        return workGroup + ":" + statementName;
    }

    @SuppressWarnings("unchecked")
    private void applyWorkGroupUpdates(WorkGroup workGroup, Map<String, Object> updates) {
        WorkGroupConfiguration configuration = workGroup.getConfiguration() != null
                ? workGroup.getConfiguration()
                : defaultWorkGroupConfiguration();
        if (updates.get("EnforceWorkGroupConfiguration") instanceof Boolean enforce) {
            configuration.setEnforceWorkGroupConfiguration(enforce);
        }
        if (updates.get("PublishCloudWatchMetricsEnabled") instanceof Boolean publish) {
            configuration.setPublishCloudWatchMetricsEnabled(publish);
        }
        if (updates.get("RequesterPaysEnabled") instanceof Boolean requesterPays) {
            configuration.setRequesterPaysEnabled(requesterPays);
        }
        if (Boolean.TRUE.equals(updates.get("RemoveBytesScannedCutoffPerQuery"))) {
            configuration.setBytesScannedCutoffPerQuery(null);
        } else if (updates.get("BytesScannedCutoffPerQuery") instanceof Number cutoff) {
            configuration.setBytesScannedCutoffPerQuery(cutoff.longValue());
        }
        if (updates.get("EngineVersion") instanceof Map<?, ?> engine) {
            Object selected = engine.get("SelectedEngineVersion");
            if (selected instanceof String selectedVersion) {
                QueryExecution.EngineVersion version = new QueryExecution.EngineVersion();
                version.setSelectedEngineVersion(selectedVersion);
                version.setEffectiveEngineVersion(resolveEffectiveEngineVersion(selectedVersion));
                configuration.setEngineVersion(version);
            }
        }
        if (updates.get("ResultConfigurationUpdates") instanceof Map<?, ?> resultUpdates) {
            ResultConfiguration result = configuration.getResultConfiguration() != null
                    ? configuration.getResultConfiguration()
                    : new ResultConfiguration();
            Object output = resultUpdates.get("OutputLocation");
            if (output instanceof String location) {
                result.setOutputLocation(location);
            }
            if (resultUpdates.get("EncryptionConfiguration") instanceof Map<?, ?> encryption) {
                ResultConfiguration.EncryptionConfiguration enc = new ResultConfiguration.EncryptionConfiguration();
                Object option = encryption.get("EncryptionOption");
                Object kms = encryption.get("KmsKey");
                if (option instanceof String encryptionOption) {
                    enc.setEncryptionOption(encryptionOption);
                }
                if (kms instanceof String kmsKey) {
                    enc.setKmsKey(kmsKey);
                }
                result.setEncryptionConfiguration(enc);
            }
            configuration.setResultConfiguration(result);
        }
        workGroup.setConfiguration(configuration);
    }

    private Map<String, Object> toDataCatalogDetail(DataCatalog catalog) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("Name", catalog.getName());
        detail.put("Type", catalog.getType());
        if (catalog.getDescription() != null) {
            detail.put("Description", catalog.getDescription());
        }
        if (catalog.getParameters() != null) {
            detail.put("Parameters", catalog.getParameters());
        }
        return detail;
    }

    private TaggedAthenaResource resolveTaggedResource(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidRequestException", "ResourceARN is required", 400);
        }
        int workgroupIdx = resourceArn.indexOf(":workgroup/");
        if (workgroupIdx >= 0) {
            String name = resourceArn.substring(workgroupIdx + ":workgroup/".length());
            if (DEFAULT_WORKGROUP.equals(name)) {
                throw new AwsException("InvalidRequestException", "The primary workgroup cannot be tagged.", 400);
            }
            WorkGroup workGroup = workGroupStore.get(workGroupKey(region, name))
                    .orElseThrow(() -> new AwsException("InvalidRequestException",
                            "WorkGroup " + name + " is not found.", 400));
            return new TaggedAthenaResource() {
                @Override public List<WorkGroupTag> getTags() { return workGroup.getTags(); }
                @Override public void setTags(List<WorkGroupTag> tags) { workGroup.setTags(tags); }
                @Override public void persist() { workGroupStore.put(workGroupKey(region, name), workGroup); }
            };
        }
        int catalogIdx = resourceArn.indexOf(":datacatalog/");
        if (catalogIdx >= 0) {
            String name = resourceArn.substring(catalogIdx + ":datacatalog/".length());
            DataCatalog catalog = dataCatalogStore.get(name)
                    .orElseThrow(() -> new AwsException("InvalidRequestException",
                            "DataCatalog " + name + " is not found.", 400));
            return new TaggedAthenaResource() {
                @Override public List<WorkGroupTag> getTags() { return catalog.getTags(); }
                @Override public void setTags(List<WorkGroupTag> tags) { catalog.setTags(tags); }
                @Override public void persist() { dataCatalogStore.put(name, catalog); }
            };
        }
        throw new AwsException("InvalidRequestException", "Unsupported resource ARN: " + resourceArn, 400);
    }

    private Map<String, String> tagsToMap(List<WorkGroupTag> tags) {
        Map<String, String> map = new LinkedHashMap<>();
        if (tags != null) {
            for (WorkGroupTag tag : tags) {
                if (tag != null && tag.getKey() != null) {
                    map.put(tag.getKey(), tag.getValue());
                }
            }
        }
        return map;
    }

    private List<WorkGroupTag> mapToTags(Map<String, String> tags) {
        return tags.entrySet().stream()
                .map(e -> new WorkGroupTag(e.getKey(), e.getValue()))
                .toList();
    }

    private interface TaggedAthenaResource {
        List<WorkGroupTag> getTags();
        void setTags(List<WorkGroupTag> tags);
        void persist();
    }

    private void validateWorkGroupName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "WorkGroup name is required", 400);
        }
        if (!name.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new AwsException("InvalidRequestException", "Invalid WorkGroup name: " + name, 400);
        }
    }

    private void ensureOutputBucket(String s3Path) {
        String bucket = extractBucket(s3Path);
        if (bucket != null) {
            try {
                s3Service.createBucket(bucket, config.defaultRegion());
            } catch (Exception ignored) {}
        }
    }

    private ResultSet readResultsFromS3(String outputLocation, String queryId) {
        try {
            String bucket = extractBucket(outputLocation);
            String prefix = extractKey(outputLocation);
            if (bucket == null) {
                return emptyResultSet();
            }

            List<S3Object> objects = s3Service.listObjects(bucket, prefix, null, 10);
            Optional<S3Object> csv = objects.stream()
                    .filter(o -> o.getKey().endsWith(".csv"))
                    .findFirst()
                    .map(o -> s3Service.getObject(bucket, o.getKey()));

            if (csv.isEmpty()) {
                return emptyResultSet();
            }

            return parseCsv(csv.get().getData());
        } catch (Exception e) {
            LOG.warnv("Could not read query results for {0}: {1}", queryId, e.getMessage());
            return emptyResultSet();
        }
    }

    private ResultSet parseCsv(byte[] data) {
        List<ResultSet.Row> rows = new ArrayList<>();
        List<ResultSet.ColumnInfo> columns = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return emptyResultSet();
            }

            String[] headers = CsvParser.parseLine(headerLine).toArray(String[]::new);
            for (String h : headers) {
                columns.add(new ResultSet.ColumnInfo(DEFAULT_CATALOG, "", "", h, "varchar"));
            }

            // Header row is included in GetQueryResults per AWS spec
            rows.add(toRow(headers));

            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(toRow(CsvParser.parseLine(line).toArray(String[]::new)));
            }
        } catch (Exception e) {
            LOG.debugv("CSV parse error: {0}", e.getMessage());
        }

        return new ResultSet(rows, new ResultSet.ResultSetMetadata(columns));
    }

    private Map<String, Object> tableMetadata(String catalog, String database, Table table) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Name", table.getName());
        metadata.put("CreateTime", (table.getCreateTime() != null ? table.getCreateTime() : Instant.now()).getEpochSecond());
        metadata.put("LastAccessTime", (table.getLastAccessTime() != null ? table.getLastAccessTime() : Instant.now()).getEpochSecond());
        metadata.put("TableType", table.getTableType() != null ? table.getTableType() : "EXTERNAL_TABLE");
        metadata.put("Columns", athenaColumns(table));
        metadata.put("Parameters", table.getParameters() != null ? table.getParameters() : Map.of());
        metadata.put("PartitionKeys", athenaColumns(table.getPartitionKeys()));
        return metadata;
    }

    private List<Map<String, String>> athenaColumns(Table table) {
        if (table.getStorageDescriptor() == null) {
            return List.of();
        }
        return athenaColumns(table.getStorageDescriptor().getColumns());
    }

    private List<Map<String, String>> athenaColumns(List<Column> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream()
                .map(column -> Map.of(
                        "Name", column.getName(),
                        "Type", glueTypeToAthena(column)
                ))
                .toList();
    }

    private String glueTypeToAthena(Column column) {
        String type = column.getType() == null ? "string" : column.getType().toLowerCase(Locale.ROOT);
        if (type.equals("string") || type.equals("char") || type.equals("varchar")
                || type.startsWith("struct<") || type.startsWith("array<") || type.startsWith("map<")) {
            return "varchar";
        }
        return type;
    }

    private ResultSet.Row toRow(String[] values) {
        List<ResultSet.Datum> data = new ArrayList<>();
        for (String v : values) {
            data.add(new ResultSet.Datum(v));
        }
        return new ResultSet.Row(data);
    }

    private String extractBucket(String s3Path) {
        if (s3Path == null || !s3Path.startsWith("s3://")) {
            return null;
        }
        String without = s3Path.substring(5);
        int slash = without.indexOf('/');
        return slash < 0 ? without : without.substring(0, slash);
    }

    private String extractKey(String s3Path) {
        if (s3Path == null || !s3Path.startsWith("s3://")) {
            return "";
        }
        String without = s3Path.substring(5);
        int slash = without.indexOf('/');
        return slash < 0 ? "" : without.substring(slash + 1);
    }

    private ResultSet emptyResultSet() {
        return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));
    }
}
