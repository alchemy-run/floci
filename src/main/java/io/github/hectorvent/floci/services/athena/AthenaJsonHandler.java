package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.athena.model.CreateWorkGroupRequest;
import io.github.hectorvent.floci.services.athena.model.QueryExecution;
import io.github.hectorvent.floci.services.athena.model.QueryExecutionContext;
import io.github.hectorvent.floci.services.athena.model.ResultConfiguration;
import io.github.hectorvent.floci.services.athena.model.ResultSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
public class AthenaJsonHandler {

    private final AthenaService athenaService;
    private final ObjectMapper mapper;

    @Inject
    public AthenaJsonHandler(AthenaService athenaService, ObjectMapper mapper) {
        this.athenaService = athenaService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "StartQueryExecution" -> {
                String query = request.get("QueryString").asText();
                String workGroup = request.has("WorkGroup") ? request.get("WorkGroup").asText() : "primary";

                QueryExecutionContext context = null;
                if (request.has("QueryExecutionContext")) {
                    context = mapper.treeToValue(request.get("QueryExecutionContext"), QueryExecutionContext.class);
                }

                ResultConfiguration resultConfiguration = null;
                if (request.has("ResultConfiguration")) {
                    resultConfiguration = mapper.treeToValue(request.get("ResultConfiguration"), ResultConfiguration.class);
                }

                String id = athenaService.startQueryExecution(query, workGroup, context, resultConfiguration);
                yield Response.ok(Map.of("QueryExecutionId", id)).build();
            }
            case "GetQueryExecution" -> {
                String id = request.get("QueryExecutionId").asText();
                QueryExecution execution = athenaService.getQueryExecution(id);
                yield Response.ok(Map.of("QueryExecution", execution)).build();
            }
            case "GetQueryResults" -> {
                String id = request.get("QueryExecutionId").asText();
                ResultSet results = athenaService.getQueryResults(id);
                yield Response.ok(Map.of("ResultSet", results)).build();
            }
            case "ListQueryExecutions" -> {
                yield Response.ok(Map.of("QueryExecutionIds",
                        athenaService.listQueryExecutions().stream()
                                .map(QueryExecution::getQueryExecutionId).toList())).build();
            }
            case "StopQueryExecution" -> {
                athenaService.stopQueryExecution(request.get("QueryExecutionId").asText());
                yield Response.ok(Map.of()).build();
            }
            case "GetWorkGroup" -> {
                String name = request.has("WorkGroup") ? request.get("WorkGroup").asText() : "primary";
                yield Response.ok(Map.of("WorkGroup", athenaService.getWorkGroup(name, region))).build();
            }
            case "ListWorkGroups" -> Response.ok(Map.of("WorkGroups", athenaService.listWorkGroups(region))).build();
            case "CreateWorkGroup" -> {
                CreateWorkGroupRequest createRequest = mapper.treeToValue(request, CreateWorkGroupRequest.class);
                athenaService.createWorkGroup(createRequest, region);
                yield Response.ok(Map.of()).build();
            }
            case "ListDataCatalogs" -> Response.ok(Map.of("DataCatalogsSummary", athenaService.listDataCatalogs())).build();
            case "GetDataCatalog" -> {
                String name = request.has("Name") ? request.get("Name").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("DataCatalog", athenaService.getDataCatalog(name))).build();
            }
            case "ListDatabases" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("DatabaseList", athenaService.listDatabases(catalog))).build();
            }
            case "ListTableMetadata" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                yield Response.ok(Map.of("TableMetadataList", athenaService.listTableMetadata(catalog, database))).build();
            }
            case "GetTableMetadata" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                String database = request.path("DatabaseName").asText(request.path("Database").asText(""));
                String tableName = request.get("TableName").asText();
                yield Response.ok(Map.of("TableMetadata", athenaService.getTableMetadata(catalog, database, tableName))).build();
            }
            case "DeleteWorkGroup" -> {
                String wg = request.path("WorkGroup").asText(null);
                if (wg == null || !wg.matches("[a-zA-Z0-9._-]{1,128}")) {
                    throw new AwsException("InvalidRequestException", "WorkGroup is required.", 400);
                }
                if ("primary".equals(wg)) {
                    throw new AwsException("InvalidRequestException", "The primary workgroup cannot be deleted.", 400);
                }
                athenaService.deleteWorkGroup(wg, region, request.path("RecursiveDeleteOption").asBoolean(false));
                yield Response.ok(Map.of()).build();
            }
            case "UpdateWorkGroup" -> {
                String wg = request.path("WorkGroup").asText(null);
                if (wg == null || wg.isBlank()) {
                    throw new AwsException("InvalidRequestException", "WorkGroup is required.", 400);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> updates = request.has("ConfigurationUpdates")
                        ? mapper.convertValue(request.get("ConfigurationUpdates"), Map.class)
                        : null;
                athenaService.updateWorkGroup(
                        wg,
                        request.path("Description").asText(null),
                        request.path("State").asText(null),
                        updates,
                        region);
                yield Response.ok(Map.of()).build();
            }
            case "CreateDataCatalog" -> {
                athenaService.createDataCatalog(mapper.treeToValue(request, io.github.hectorvent.floci.services.athena.model.DataCatalog.class));
                yield Response.ok(Map.of()).build();
            }
            case "UpdateDataCatalog" -> {
                @SuppressWarnings("unchecked")
                Map<String, String> parameters = request.has("Parameters")
                        ? mapper.convertValue(request.get("Parameters"), Map.class)
                        : null;
                athenaService.updateDataCatalog(
                        request.get("Name").asText(),
                        request.path("Type").asText(null),
                        request.path("Description").asText(null),
                        parameters);
                yield Response.ok(Map.of()).build();
            }
            case "DeleteDataCatalog" -> {
                athenaService.deleteDataCatalog(request.get("Name").asText());
                yield Response.ok(Map.of()).build();
            }
            case "GetDatabase" -> {
                String catalog = request.has("CatalogName") ? request.get("CatalogName").asText() : AthenaService.DEFAULT_CATALOG;
                yield Response.ok(Map.of("Database",
                        athenaService.getDatabase(catalog, request.get("DatabaseName").asText()))).build();
            }
            case "CreateNamedQuery" -> {
                io.github.hectorvent.floci.services.athena.model.NamedQuery query =
                        mapper.treeToValue(request, io.github.hectorvent.floci.services.athena.model.NamedQuery.class);
                yield Response.ok(Map.of("NamedQueryId", athenaService.createNamedQuery(query))).build();
            }
            case "GetNamedQuery" -> Response.ok(Map.of("NamedQuery",
                    athenaService.getNamedQuery(request.get("NamedQueryId").asText()))).build();
            case "ListNamedQueries" -> Response.ok(Map.of("NamedQueryIds",
                    athenaService.listNamedQueries(request.path("WorkGroup").asText(null)))).build();
            case "UpdateNamedQuery" -> {
                athenaService.updateNamedQuery(
                        request.get("NamedQueryId").asText(),
                        request.path("Name").asText(null),
                        request.path("Description").asText(null),
                        request.path("QueryString").asText(null));
                yield Response.ok(Map.of()).build();
            }
            case "DeleteNamedQuery" -> {
                athenaService.deleteNamedQuery(request.get("NamedQueryId").asText());
                yield Response.ok(Map.of()).build();
            }
            case "BatchGetNamedQuery" -> {
                java.util.List<String> ids = mapper.convertValue(request.get("NamedQueryIds"),
                        mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                yield Response.ok(athenaService.batchGetNamedQuery(ids)).build();
            }
            case "CreatePreparedStatement" -> {
                io.github.hectorvent.floci.services.athena.model.PreparedStatement statement =
                        mapper.treeToValue(request, io.github.hectorvent.floci.services.athena.model.PreparedStatement.class);
                if (statement.getWorkGroupName() == null && request.has("WorkGroup")) {
                    statement.setWorkGroupName(request.get("WorkGroup").asText());
                }
                athenaService.createPreparedStatement(statement);
                yield Response.ok(Map.of()).build();
            }
            case "GetPreparedStatement" -> {
                String workGroup = request.path("WorkGroup").asText("primary");
                yield Response.ok(Map.of("PreparedStatement",
                        athenaService.getPreparedStatement(workGroup, request.get("StatementName").asText()))).build();
            }
            case "ListPreparedStatements" -> Response.ok(Map.of("PreparedStatements",
                    athenaService.listPreparedStatements(request.path("WorkGroup").asText(null)))).build();
            case "UpdatePreparedStatement" -> {
                io.github.hectorvent.floci.services.athena.model.PreparedStatement statement =
                        mapper.treeToValue(request, io.github.hectorvent.floci.services.athena.model.PreparedStatement.class);
                if (statement.getWorkGroupName() == null && request.has("WorkGroup")) {
                    statement.setWorkGroupName(request.get("WorkGroup").asText());
                }
                athenaService.updatePreparedStatement(statement);
                yield Response.ok(Map.of()).build();
            }
            case "DeletePreparedStatement" -> {
                athenaService.deletePreparedStatement(
                        request.path("WorkGroup").asText("primary"),
                        request.get("StatementName").asText());
                yield Response.ok(Map.of()).build();
            }
            case "BatchGetPreparedStatement" -> {
                String workGroup = request.path("WorkGroup").asText("primary");
                java.util.List<String> names = mapper.convertValue(request.get("PreparedStatementNames"),
                        mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                yield Response.ok(athenaService.batchGetPreparedStatement(workGroup, names)).build();
            }
            case "BatchGetQueryExecution" -> {
                java.util.List<String> ids = mapper.convertValue(request.get("QueryExecutionIds"),
                        mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                yield Response.ok(athenaService.batchGetQueryExecution(ids)).build();
            }
            case "GetQueryRuntimeStatistics" -> Response.ok(
                    athenaService.getQueryRuntimeStatistics(request.get("QueryExecutionId").asText())).build();
            case "TagResource" -> {
                java.util.List<io.github.hectorvent.floci.services.athena.model.WorkGroupTag> tags =
                        mapper.convertValue(request.get("Tags"),
                                mapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                        io.github.hectorvent.floci.services.athena.model.WorkGroupTag.class));
                athenaService.tagResource(request.path("ResourceARN").asText(null), tags, region);
                yield Response.ok(Map.of()).build();
            }
            case "UntagResource" -> {
                java.util.List<String> keys = mapper.convertValue(request.get("TagKeys"),
                        mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
                athenaService.untagResource(request.path("ResourceARN").asText(null), keys, region);
                yield Response.ok(Map.of()).build();
            }
            case "ListTagsForResource" -> Response.ok(Map.of("Tags",
                    athenaService.listTagsForResource(request.path("ResourceARN").asText(null), region))).build();
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
