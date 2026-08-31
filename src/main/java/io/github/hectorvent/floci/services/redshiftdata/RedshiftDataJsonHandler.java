package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.redshiftdata.model.Statement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JSON 1.1 handler for the Redshift Data API. Dispatched from
 * {@code AwsJson11Controller} under the {@code RedshiftData.} target prefix.
 */
@ApplicationScoped
public class RedshiftDataJsonHandler {

    private final RedshiftDataService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftDataJsonHandler(RedshiftDataService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "ExecuteStatement" -> ok(executeOutput(service.execute(body)));
                case "BatchExecuteStatement" -> ok(executeOutput(service.batchExecute(body)));
                case "DescribeStatement" -> ok(describeOutput(service.describe(body)));
                case "CancelStatement" -> {
                    service.cancel(body);
                    ObjectNode response = objectMapper.createObjectNode();
                    response.put("Status", true);
                    yield ok(response);
                }
                case "GetStatementResult" -> ok(resultOutput(service.getResult(body)));
                case "GetStatementResultV2" -> ok(resultV2Output(service.getResult(body)));
                case "ListStatements" -> ok(listStatementsOutput(service.listStatements(body)));
                case "ListDatabases" -> {
                    ObjectNode response = objectMapper.createObjectNode();
                    ArrayNode databases = response.putArray("Databases");
                    service.listDatabases(body).forEach(databases::add);
                    yield ok(response);
                }
                case "ListSchemas" -> {
                    ObjectNode response = objectMapper.createObjectNode();
                    ArrayNode schemas = response.putArray("Schemas");
                    service.listSchemas(body).forEach(schemas::add);
                    yield ok(response);
                }
                case "ListTables" -> ok(listTablesOutput(service.listTables(body)));
                case "DescribeTable" -> ok(describeTableOutput(service.describeTable(body)));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(
                        "RedshiftData." + action);
            };
        } catch (AwsException e) {
            return error(e);
        }
    }

    private ObjectNode executeOutput(Statement statement) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", statement.getId());
        response.put("CreatedAt", statement.getCreatedAtEpochSeconds());
        putOptional(response, "WorkgroupName", statement.getWorkgroupName());
        putOptional(response, "ClusterIdentifier", statement.getClusterIdentifier());
        putOptional(response, "Database", statement.getDatabase());
        putOptional(response, "DbUser", statement.getDbUser());
        putOptional(response, "SecretArn", statement.getSecretArn());
        return response;
    }

    private ObjectNode describeOutput(Statement statement) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", statement.getId());
        response.put("Status", statement.getStatus());
        response.put("CreatedAt", statement.getCreatedAtEpochSeconds());
        response.put("UpdatedAt", statement.getUpdatedAtEpochSeconds());
        response.put("Duration", statement.getDuration());
        response.put("HasResultSet", statement.isHasResultSet());
        response.put("ResultRows", statement.getResultRows());
        response.put("ResultSize", statement.getResultSize());
        putOptional(response, "QueryString", statement.getSql());
        putOptional(response, "WorkgroupName", statement.getWorkgroupName());
        putOptional(response, "ClusterIdentifier", statement.getClusterIdentifier());
        putOptional(response, "Database", statement.getDatabase());
        putOptional(response, "DbUser", statement.getDbUser());
        putOptional(response, "SecretArn", statement.getSecretArn());
        putOptional(response, "ResultFormat", statement.getResultFormat());
        putOptional(response, "Error", statement.getError());
        if (statement.isBatch() && !statement.getSubIds().isEmpty()) {
            ArrayNode subs = response.putArray("SubStatements");
            for (String subId : statement.getSubIds()) {
                Statement sub = service.requireStatement(subId);
                ObjectNode node = subs.addObject();
                node.put("Id", sub.getId());
                node.put("Status", sub.getStatus());
                node.put("Duration", sub.getDuration());
                node.put("CreatedAt", sub.getCreatedAtEpochSeconds());
                node.put("UpdatedAt", sub.getUpdatedAtEpochSeconds());
                node.put("HasResultSet", sub.isHasResultSet());
                node.put("ResultRows", sub.getResultRows());
                node.put("ResultSize", sub.getResultSize());
                putOptional(node, "QueryString", sub.getSql());
                putOptional(node, "Error", sub.getError());
            }
        }
        return response;
    }

    private ObjectNode resultOutput(Statement statement) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = response.putArray("Records");
        for (List<Object> row : statement.getRows()) {
            ArrayNode record = records.addArray();
            for (Object value : row) {
                record.add(field(value));
            }
        }
        addColumnMetadata(response, statement.getColumnNames());
        response.put("TotalNumRows", statement.getResultRows());
        return response;
    }

    private ObjectNode resultV2Output(Statement statement) {
        ObjectNode response = objectMapper.createObjectNode();
        String format = statement.getResultFormat() == null ? "CSV" : statement.getResultFormat();
        response.put("ResultFormat", "JSON".equals(format) ? "CSV" : format);
        ArrayNode records = response.putArray("Records");
        records.addObject().put("CSVRecords", toCsv(statement));
        addColumnMetadata(response, statement.getColumnNames());
        response.put("TotalNumRows", statement.getResultRows());
        return response;
    }

    private ObjectNode listStatementsOutput(List<Statement> listed) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode statements = response.putArray("Statements");
        for (Statement statement : listed) {
            ObjectNode node = statements.addObject();
            node.put("Id", statement.getId());
            node.put("Status", statement.getStatus());
            node.put("CreatedAt", statement.getCreatedAtEpochSeconds());
            node.put("UpdatedAt", statement.getUpdatedAtEpochSeconds());
            node.put("IsBatchStatement", statement.isBatch());
            putOptional(node, "QueryString", statement.getSql());
            putOptional(node, "StatementName", statement.getStatementName());
            putOptional(node, "ResultFormat", statement.getResultFormat());
        }
        return response;
    }

    private ObjectNode listTablesOutput(List<RedshiftDataService.TableRef> tables) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Tables");
        for (RedshiftDataService.TableRef table : tables) {
            ObjectNode node = array.addObject();
            node.put("name", table.name());
            node.put("schema", table.schema());
            node.put("type", table.type());
        }
        return response;
    }

    private ObjectNode describeTableOutput(RedshiftDataService.TableRef table) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", table.name());
        ArrayNode columns = response.putArray("ColumnList");
        for (RedshiftDataService.ColumnRef column : table.columns()) {
            ObjectNode node = columns.addObject();
            node.put("name", column.name());
            node.put("label", column.name());
            node.put("typeName", column.typeName());
            node.put("schemaName", table.schema());
            node.put("tableName", table.name());
            node.put("nullable", 1);
        }
        return response;
    }

    private void addColumnMetadata(ObjectNode response, List<String> columnNames) {
        ArrayNode metadata = response.putArray("ColumnMetadata");
        for (String name : columnNames) {
            ObjectNode column = metadata.addObject();
            column.put("name", name);
            column.put("label", name);
            column.put("typeName", "int8");
            column.put("isSigned", true);
            column.put("nullable", 1);
            column.put("precision", 19);
            column.put("scale", 0);
        }
    }

    private ObjectNode field(Object value) {
        ObjectNode field = objectMapper.createObjectNode();
        if (value == null) {
            field.put("isNull", true);
        } else if (value instanceof Boolean bool) {
            field.put("booleanValue", bool);
        } else if (value instanceof Double || value instanceof Float) {
            field.put("doubleValue", ((Number) value).doubleValue());
        } else if (value instanceof Number number) {
            field.put("longValue", number.longValue());
        } else {
            field.put("stringValue", value.toString());
        }
        return field;
    }

    private static String toCsv(Statement statement) {
        String header = String.join(",", statement.getColumnNames());
        String body = statement.getRows().stream()
                .map(row -> row.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));
        if (body.isEmpty()) {
            return header;
        }
        return header + "\n" + body;
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }

    private Response error(AwsException e) {
        if ("ResourceNotFoundException".equals(e.getErrorCode())) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("__type", "ResourceNotFoundException");
            node.put("message", e.getMessage());
            Object resourceId = e.getExtendedData() == null ? null : e.getExtendedData().get("ResourceId");
            if (resourceId != null) {
                node.put("ResourceId", resourceId.toString());
            }
            return Response.status(e.getHttpStatus())
                    .header("x-amzn-query-error", "ResourceNotFoundException;Sender")
                    .entity(node)
                    .build();
        }
        return JsonErrorResponseUtils.createErrorResponse(e);
    }
}
