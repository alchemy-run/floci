package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.redshiftdata.model.Statement;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amazon Redshift Data API ({@code RedshiftData.*}). Statements finish
 * immediately against an in-memory catalog so local binding tests can
 * round-trip execute / describe / get-result without a warehouse.
 */
@ApplicationScoped
public class RedshiftDataService implements Resettable {

    static final String DEFAULT_DATABASE = "dev";
    static final List<String> DEFAULT_SCHEMAS = List.of("information_schema", "pg_catalog", "public");
    static final String PG_CLASS = "pg_class";
    static final String PG_CATALOG = "pg_catalog";

    private static final Pattern SELECT_LITERAL = Pattern.compile(
            "^\\s*SELECT\\s+(\\d+)(?:\\s+AS\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_COUNT = Pattern.compile(
            "^\\s*SELECT\\s+count\\s*\\(\\s*\\*\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private final ConcurrentHashMap<String, Statement> statements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedHashSet<String>> workgroups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedHashSet<String>> clusters = new ConcurrentHashMap<>();

    RedshiftDataService() {
    }

    @Override
    public void clear() {
        statements.clear();
        workgroups.clear();
        clusters.clear();
    }

    public Statement execute(JsonNode request) {
        String sql = required(request, "Sql");
        String workgroup = text(request, "WorkgroupName");
        String cluster = text(request, "ClusterIdentifier");
        requireTarget(workgroup, cluster);
        registerTarget(workgroup, cluster, databaseOf(request));

        Statement statement = newStatement(request, sql, false);
        if (isLongRunning(sql)) {
            // ExecuteStatement is async on AWS; keep count(*) scans RUNNING
            // so CancelStatement can succeed before they finish.
            statement.setStatus("RUNNING");
            statements.put(statement.getId(), statement);
            return statement;
        }
        QueryResult result = evaluate(sql);
        finish(statement, result);
        statements.put(statement.getId(), statement);
        return statement;
    }

    public Statement batchExecute(JsonNode request) {
        JsonNode sqlsNode = request.get("Sqls");
        if (sqlsNode == null || !sqlsNode.isArray() || sqlsNode.isEmpty()) {
            throw validation("Sqls is a required parameter.");
        }
        String workgroup = text(request, "WorkgroupName");
        String cluster = text(request, "ClusterIdentifier");
        requireTarget(workgroup, cluster);
        registerTarget(workgroup, cluster, databaseOf(request));

        Statement parent = newStatement(request, null, true);
        parent.setSql(joinSqls(sqlsNode));
        boolean anyResult = false;
        long totalRows = 0;
        for (int i = 0; i < sqlsNode.size(); i++) {
            String sql = sqlsNode.get(i).asText();
            Statement sub = newStatement(request, sql, false);
            sub.setId(parent.getId() + ":" + (i + 1));
            sub.setParentId(parent.getId());
            QueryResult result = evaluate(sql);
            finish(sub, result);
            statements.put(sub.getId(), sub);
            parent.getSubIds().add(sub.getId());
            anyResult = anyResult || result.hasResultSet();
            totalRows += result.rows().size();
        }
        parent.setStatus("FINISHED");
        parent.setHasResultSet(anyResult);
        parent.setResultRows(totalRows);
        parent.setDuration(1_000_000L * sqlsNode.size());
        parent.setUpdatedAtEpochSeconds(parent.getCreatedAtEpochSeconds());
        statements.put(parent.getId(), parent);
        return parent;
    }

    public Statement describe(JsonNode request) {
        return requireStatement(required(request, "Id"));
    }

    public Statement cancel(JsonNode request) {
        Statement statement = requireStatement(required(request, "Id"));
        if ("FINISHED".equals(statement.getStatus())
                || "FAILED".equals(statement.getStatus())
                || "ABORTED".equals(statement.getStatus())) {
            throw validation("Could not cancel the statement because it is not running.");
        }
        statement.setStatus("ABORTED");
        statement.setUpdatedAtEpochSeconds(Instant.now().getEpochSecond());
        statement.setHasResultSet(false);
        return statement;
    }

    public Statement getResult(JsonNode request) {
        Statement statement = requireFinished(required(request, "Id"));
        if (statement.isBatch()) {
            throw validation("GetStatementResult is not supported for batch statements. "
                    + "Describe the statement and fetch a sub-statement result.");
        }
        if (!statement.isHasResultSet()) {
            throw validation("The statement does not have a result set.");
        }
        return statement;
    }

    public List<Statement> listStatements(JsonNode request) {
        String workgroup = text(request, "WorkgroupName");
        String cluster = text(request, "ClusterIdentifier");
        String status = text(request, "Status");
        String name = text(request, "StatementName");
        String database = text(request, "Database");
        List<Statement> listed = new ArrayList<>();
        for (Statement statement : statements.values()) {
            if (statement.getParentId() != null) {
                continue;
            }
            if (workgroup != null && !workgroup.equals(statement.getWorkgroupName())) {
                continue;
            }
            if (cluster != null && !cluster.equals(statement.getClusterIdentifier())) {
                continue;
            }
            if (status != null && !status.equalsIgnoreCase(statement.getStatus())) {
                continue;
            }
            if (name != null && !name.equals(statement.getStatementName())) {
                continue;
            }
            if (database != null && !database.equals(statement.getDatabase())) {
                continue;
            }
            listed.add(statement);
        }
        listed.sort((a, b) -> Long.compare(b.getCreatedAtEpochSeconds(), a.getCreatedAtEpochSeconds()));
        return listed;
    }

    public List<String> listDatabases(JsonNode request) {
        requireDatabase(request);
        Target target = resolveExistingTarget(request);
        return new ArrayList<>(target.databases());
    }

    public List<String> listSchemas(JsonNode request) {
        requireDatabase(request);
        resolveExistingTarget(request);
        String pattern = text(request, "SchemaPattern");
        List<String> schemas = new ArrayList<>();
        for (String schema : DEFAULT_SCHEMAS) {
            if (matchesLike(schema, pattern)) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    public List<TableRef> listTables(JsonNode request) {
        requireDatabase(request);
        resolveExistingTarget(request);
        String schemaPattern = text(request, "SchemaPattern");
        String tablePattern = text(request, "TablePattern");
        List<TableRef> tables = new ArrayList<>();
        for (TableRef table : defaultTables()) {
            if (matchesLike(table.schema(), schemaPattern) && matchesLike(table.name(), tablePattern)) {
                tables.add(table);
            }
        }
        return tables;
    }

    public TableRef describeTable(JsonNode request) {
        requireDatabase(request);
        resolveExistingTarget(request);
        String schema = text(request, "Schema");
        String table = text(request, "Table");
        if (table == null) {
            throw validation("Table is a required parameter.");
        }
        String resolvedSchema = schema == null ? PG_CATALOG : schema;
        for (TableRef candidate : defaultTables()) {
            if (candidate.name().equalsIgnoreCase(table)
                    && candidate.schema().equalsIgnoreCase(resolvedSchema)) {
                return candidate;
            }
        }
        throw notFound(table, "Table " + resolvedSchema + "." + table + " not found.");
    }

    Statement requireStatement(String id) {
        Statement statement = statements.get(id);
        if (statement == null) {
            throw notFound(id, "Query id " + id + " not found.");
        }
        return statement;
    }

    private Statement requireFinished(String id) {
        Statement statement = requireStatement(id);
        if (!"FINISHED".equals(statement.getStatus())) {
            throw validation("The statement is not yet available for result retrieval.");
        }
        return statement;
    }

    private Statement newStatement(JsonNode request, String sql, boolean batch) {
        long now = Instant.now().getEpochSecond();
        Statement statement = new Statement();
        statement.setId(UUID.randomUUID().toString());
        statement.setSql(sql);
        statement.setStatus("STARTED");
        statement.setWorkgroupName(text(request, "WorkgroupName"));
        statement.setClusterIdentifier(text(request, "ClusterIdentifier"));
        statement.setDatabase(databaseOf(request));
        statement.setDbUser(text(request, "DbUser"));
        statement.setSecretArn(text(request, "SecretArn"));
        statement.setResultFormat(resultFormatOf(request));
        statement.setStatementName(text(request, "StatementName"));
        statement.setBatch(batch);
        statement.setCreatedAtEpochSeconds(now);
        statement.setUpdatedAtEpochSeconds(now);
        return statement;
    }

    private void finish(Statement statement, QueryResult result) {
        statement.setStatus("FINISHED");
        statement.setHasResultSet(result.hasResultSet());
        statement.getColumnNames().clear();
        statement.getColumnNames().addAll(result.columns());
        statement.getRows().clear();
        statement.getRows().addAll(result.rows());
        statement.setResultRows(result.rows().size());
        statement.setResultSize(result.rows().size() * 8L);
        statement.setDuration(1_000_000L);
        statement.setUpdatedAtEpochSeconds(statement.getCreatedAtEpochSeconds());
    }

    static boolean isLongRunning(String sql) {
        return sql != null && SELECT_COUNT.matcher(sql).find();
    }

    static QueryResult evaluate(String sql) {
        Matcher literal = SELECT_LITERAL.matcher(sql);
        if (literal.matches()) {
            long value = Long.parseLong(literal.group(1));
            String alias = literal.group(2) == null ? "?column?" : literal.group(2);
            return new QueryResult(true, List.of(alias), List.of(List.of(value)));
        }
        if (isLongRunning(sql)) {
            return new QueryResult(true, List.of("count"), List.of(List.of(0L)));
        }
        if (sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")) {
            return new QueryResult(true, List.of("n"), List.of(List.of(1L)));
        }
        return new QueryResult(false, List.of(), List.of());
    }

    private void requireTarget(String workgroup, String cluster) {
        if (workgroup == null && cluster == null) {
            throw validation("Either ClusterIdentifier or WorkgroupName is required.");
        }
    }

    private void registerTarget(String workgroup, String cluster, String database) {
        if (workgroup != null) {
            workgroups.computeIfAbsent(workgroup, key -> new LinkedHashSet<>()).add(database);
        }
        if (cluster != null) {
            clusters.computeIfAbsent(cluster, key -> new LinkedHashSet<>()).add(database);
        }
    }

    private Target resolveExistingTarget(JsonNode request) {
        String workgroup = text(request, "WorkgroupName");
        String cluster = text(request, "ClusterIdentifier");
        requireTarget(workgroup, cluster);
        if (workgroup != null) {
            LinkedHashSet<String> databases = workgroups.get(workgroup);
            if (databases == null) {
                throw validation("Workgroup not found: " + workgroup);
            }
            return new Target(workgroup, databases);
        }
        LinkedHashSet<String> databases = clusters.get(cluster);
        if (databases == null) {
            throw validation("Cluster identifier is not valid.");
        }
        return new Target(cluster, databases);
    }

    private static void requireDatabase(JsonNode request) {
        if (text(request, "Database") == null) {
            throw validation("Database is a required parameter.");
        }
    }

    private static String databaseOf(JsonNode request) {
        String database = text(request, "Database");
        return database == null ? DEFAULT_DATABASE : database;
    }

    private static String resultFormatOf(JsonNode request) {
        String format = text(request, "ResultFormat");
        return format == null ? "JSON" : format.toUpperCase(Locale.ROOT);
    }

    private static String joinSqls(JsonNode sqls) {
        List<String> parts = new ArrayList<>();
        sqls.forEach(node -> parts.add(node.asText()));
        return String.join(";\n", parts);
    }

    private static List<TableRef> defaultTables() {
        return List.of(
                new TableRef(PG_CLASS, PG_CATALOG, "TABLE", pgClassColumns()),
                new TableRef("pg_attribute", PG_CATALOG, "TABLE", List.of(
                        column("attrelid", "oid"),
                        column("attname", "name"),
                        column("atttypid", "oid"))),
                new TableRef("pg_namespace", PG_CATALOG, "TABLE", List.of(
                        column("oid", "oid"),
                        column("nspname", "name"))));
    }

    private static List<ColumnRef> pgClassColumns() {
        return List.of(
                column("oid", "oid"),
                column("relname", "name"),
                column("relnamespace", "oid"),
                column("relkind", "char"),
                column("reltuples", "float4"));
    }

    private static ColumnRef column(String name, String typeName) {
        return new ColumnRef(name, typeName);
    }

    static boolean matchesLike(String value, String pattern) {
        if (pattern == null || pattern.isBlank() || "%".equals(pattern)) {
            return true;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|?".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        return value.matches(regex.toString());
    }

    static String required(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    static String text(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static AwsException notFound(String resourceId, String message) {
        return new AwsException("ResourceNotFoundException", message, 404,
                Map.of("ResourceId", resourceId));
    }

    record QueryResult(boolean hasResultSet, List<String> columns, List<List<Object>> rows) {
    }

    record Target(String name, Set<String> databases) {
    }

    public record TableRef(String name, String schema, String type, List<ColumnRef> columns) {
    }

    public record ColumnRef(String name, String typeName) {
    }
}
