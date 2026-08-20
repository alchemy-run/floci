package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DuckDB DDL for Glue tables. Athena SQL uses {@code database.table};
 * views are created qualified (and unqualified for the query context db).
 * Hive/Athena CSV tables do not have a header row unless
 * {@code skip.header.line.count} / {@code has_header} say otherwise.
 */
final class AthenaGlueDdl {

    private AthenaGlueDdl() {}

    static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static String createSchema(String database) {
        return "CREATE SCHEMA IF NOT EXISTS " + quoteIdent(database) + ";\n";
    }

    static String createView(String database, String table, String selectSql) {
        return "CREATE OR REPLACE VIEW " + quoteIdent(database) + "." + quoteIdent(table)
                + " AS " + selectSql + ";\n";
    }

    static String createUnqualifiedView(String table, String selectSql) {
        return "CREATE OR REPLACE VIEW " + quoteIdent(table) + " AS " + selectSql + ";\n";
    }

    static String selectFromFiles(Table table, List<String> s3Paths, String fallbackGlob) {
        List<String> paths = (s3Paths == null || s3Paths.isEmpty())
                ? (fallbackGlob == null || fallbackGlob.isBlank() ? List.of() : List.of(fallbackGlob))
                : s3Paths;
        if (paths.isEmpty()) {
            return null;
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("SELECT * FROM ").append(readExpression(table, paths.get(i)));
        }
        return sql.toString();
    }

    static String readExpression(Table table, String path) {
        String fn = inferReadFunction(table);
        String escaped = escapeSql(path);
        if ("read_parquet".equals(fn)) {
            return "read_parquet('" + escaped + "', union_by_name = true)";
        }
        if ("read_json_auto".equals(fn)) {
            return "read_json_auto('" + escaped + "')";
        }
        boolean header = hasHeader(table);
        String delim = delimiter(table);
        String columns = columnSpec(table);
        if (columns != null) {
            return "read_csv('" + escaped + "', header = " + header
                    + ", delim = '" + escapeSql(delim) + "', columns = {" + columns + "})";
        }
        return "read_csv_auto('" + escaped + "', header = " + header + ")";
    }

    static String inferReadFunction(Table table) {
        if (table.getStorageDescriptor() == null) {
            return "read_csv";
        }
        String format = table.getStorageDescriptor().getInputFormat();
        String serde = table.getStorageDescriptor().getSerdeInfo() != null
                ? table.getStorageDescriptor().getSerdeInfo().getSerializationLibrary()
                : null;
        if (containsIgnoreCase(format, "parquet") || containsIgnoreCase(serde, "parquet")) {
            return "read_parquet";
        }
        if (containsIgnoreCase(format, "json") || containsIgnoreCase(serde, "json")) {
            return "read_json_auto";
        }
        return "read_csv";
    }

    static boolean hasHeader(Table table) {
        String skip = parameter(table, "skip.header.line.count");
        if (skip != null) {
            try {
                return Integer.parseInt(skip.trim()) > 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return "true".equalsIgnoreCase(parameter(table, "has_header"));
    }

    static String delimiter(Table table) {
        String delim = parameter(table, "field.delim");
        if (isBlank(delim)) {
            delim = parameter(table, "separatorChar");
        }
        if (isBlank(delim)) {
            delim = parameter(table, "serialization.format");
        }
        return isBlank(delim) ? "," : delim;
    }

    static String columnSpec(Table table) {
        if (table.getStorageDescriptor() == null || table.getStorageDescriptor().getColumns() == null
                || table.getStorageDescriptor().getColumns().isEmpty()) {
            return null;
        }
        StringBuilder spec = new StringBuilder();
        for (Column column : table.getStorageDescriptor().getColumns()) {
            if (column == null || column.getName() == null || column.getName().isBlank()) {
                continue;
            }
            if (spec.length() > 0) {
                spec.append(", ");
            }
            spec.append("'").append(escapeSql(column.getName())).append("': '")
                    .append(escapeSql(duckType(column.getType()))).append("'");
        }
        return spec.length() == 0 ? null : spec.toString();
    }

    static String duckType(String glueType) {
        String type = glueType == null ? "string" : glueType.toLowerCase(Locale.ROOT);
        if (type.equals("int") || type.equals("integer")) {
            return "INTEGER";
        }
        if (type.equals("bigint") || type.equals("long")) {
            return "BIGINT";
        }
        if (type.equals("smallint") || type.equals("tinyint")) {
            return "INTEGER";
        }
        if (type.equals("double") || type.equals("float") || type.equals("real")) {
            return "DOUBLE";
        }
        if (type.equals("boolean") || type.equals("bool")) {
            return "BOOLEAN";
        }
        if (type.equals("timestamp") || type.equals("datetime")) {
            return "TIMESTAMP";
        }
        if (type.equals("date")) {
            return "DATE";
        }
        return "VARCHAR";
    }

    /**
     * AWS does not fail a query against one Glue database because another
     * database has a table whose S3 location bucket is gone. Skip that table
     * rather than emitting a DuckDB glob that 404s.
     */
    static boolean skipUnreadableLocation(List<String> listedFiles, boolean bucketExists) {
        return (listedFiles == null || listedFiles.isEmpty()) && !bucketExists;
    }

    static String globForPrefix(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        return location.endsWith("/") ? location + "**" : location + "/**";
    }

    private static String parameter(Table table, String key) {
        StorageDescriptor sd = table.getStorageDescriptor();
        if (sd != null && sd.getParameters() != null) {
            String value = sd.getParameters().get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        if (sd != null && sd.getSerdeInfo() != null && sd.getSerdeInfo().getParameters() != null) {
            String value = sd.getSerdeInfo().getParameters().get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        Map<String, String> tableParams = table.getParameters();
        if (tableParams != null) {
            String value = tableParams.get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private static boolean containsIgnoreCase(String str, String sub) {
        return str != null && str.toLowerCase(Locale.ROOT).contains(sub);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
