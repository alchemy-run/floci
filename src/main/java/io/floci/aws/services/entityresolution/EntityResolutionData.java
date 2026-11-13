package io.floci.aws.services.entityresolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The data Entity Resolution reads and writes: an input source's records, read from the S3 objects under its Glue
 * table's location (delimited text with the table's delimiter, quote and header settings, or JSON lines), and job
 * output written as CSV to S3.
 */
@ApplicationScoped
public class EntityResolutionData {

    private final GlueService glue;
    private final S3Service s3;
    private final ObjectMapper mapper;

    @Inject
    public EntityResolutionData(GlueService glue, S3Service s3, ObjectMapper mapper) {
        this.glue = glue;
        this.s3 = s3;
        this.mapper = mapper;
    }

    /** A Glue table an input source ARN names: {@code arn:<partition>:glue:<region>:<account>:table/<db>/<table>}. */
    record TableRef(String database, String table) {
    }

    static TableRef tableRef(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] parts = parsed.resource().split("/");
        if (!"glue".equals(parsed.service()) || parts.length != 3 || !"table".equals(parts[0])) {
            return null;
        }
        return new TableRef(parts[1], parts[2]);
    }

    /** The Glue table an input source names; ValidationException when there is none. */
    Table table(String arn) {
        TableRef ref = tableRef(arn);
        if (ref == null) {
            throw EntityResolutionStore.invalid("Input source " + arn + " is not a Glue table ARN.");
        }
        try {
            return glue.getTable(ref.database(), ref.table());
        } catch (AwsException e) {
            throw EntityResolutionStore.invalid("The Glue table " + ref.database() + "." + ref.table()
                    + " does not exist.");
        }
    }

    /** A table's column names, in order. */
    static List<String> columns(Table table) {
        List<String> names = new ArrayList<>();
        if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
            for (Column column : table.getStorageDescriptor().getColumns()) {
                names.add(column.getName());
            }
        }
        return names;
    }

    /** The S3 location of a table: its bucket and key prefix. */
    record Location(String bucket, String prefix) {
        String arn(String partition) {
            return "arn:" + partition + ":s3:::" + bucket + "/" + prefix + "*"; // partition-literal: S3 ARNs
        }
    }

    static Location location(String s3Uri) {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            return null;
        }
        String rest = s3Uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? new Location(rest, "") : new Location(rest.substring(0, slash), rest.substring(slash + 1));
    }

    /** Reads a table's records: each a map of its column values by column name. */
    List<Map<String, String>> read(Table table) {
        Location location = location(table.getStorageDescriptor() == null ? null
                : table.getStorageDescriptor().getLocation());
        if (location == null) {
            throw new IllegalStateException("The Glue table " + table.getName() + " has no S3 location.");
        }
        List<String> columns = columns(table);
        Map<String, String> serde = new LinkedHashMap<>();
        if (table.getStorageDescriptor().getSerdeInfo() != null
                && table.getStorageDescriptor().getSerdeInfo().getParameters() != null) {
            serde.putAll(table.getStorageDescriptor().getSerdeInfo().getParameters());
        }
        Map<String, String> parameters = table.getParameters() == null ? Map.of() : table.getParameters();
        String library = table.getStorageDescriptor().getSerdeInfo() == null ? ""
                : String.valueOf(table.getStorageDescriptor().getSerdeInfo().getSerializationLibrary());
        boolean json = "json".equalsIgnoreCase(parameters.get("classification"))
                || library.toLowerCase(Locale.ROOT).contains("json");
        char delimiter = first(serde.getOrDefault("field.delim", serde.getOrDefault("separatorChar", ",")), ',');
        char quote = first(serde.getOrDefault("quoteChar", "\""), '"');
        int skip = Integer.parseInt(parameters.getOrDefault("skip.header.line.count",
                serde.getOrDefault("skip.header.line.count", "0")).trim());
        List<Map<String, String>> records = new ArrayList<>();
        for (S3Object object : s3.listObjects(location.bucket(), location.prefix(), null, 1000)) {
            String name = object.getKey().substring(object.getKey().lastIndexOf('/') + 1);
            if (object.getKey().endsWith("/") || name.startsWith("_") || name.startsWith(".")) {
                continue;
            }
            byte[] data = s3.getObject(location.bucket(), object.getKey()).getData();
            String text = data == null ? "" : new String(data, StandardCharsets.UTF_8);
            String[] lines = text.split("\\r?\\n");
            for (int index = json ? 0 : skip; index < lines.length; index++) {
                if (lines[index].isBlank()) {
                    continue;
                }
                records.add(json ? jsonRecord(lines[index]) : delimitedRecord(lines[index], columns, delimiter,
                        quote));
            }
        }
        return records;
    }

    private Map<String, String> jsonRecord(String line) {
        Map<String, String> record = new LinkedHashMap<>();
        try {
            JsonNode node = mapper.readTree(line);
            node.fields().forEachRemaining(entry -> record.put(entry.getKey(),
                    entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("A record is not valid JSON: " + line, e);
        }
        return record;
    }

    static Map<String, String> delimitedRecord(String line, List<String> columns, char delimiter, char quote) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (quoted) {
                if (c == quote && index + 1 < line.length() && line.charAt(index + 1) == quote) {
                    current.append(c);
                    index++;
                } else if (c == quote) {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == quote) {
                quoted = true;
            } else if (c == delimiter) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        Map<String, String> record = new LinkedHashMap<>();
        for (int index = 0; index < columns.size() && index < values.size(); index++) {
            record.put(columns.get(index), values.get(index));
        }
        return record;
    }

    private static char first(String value, char fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        return "\\t".equals(value) ? '\t' : value.charAt(0);
    }

    /** Writes CSV rows, the first being the header, to {@code <s3Path><key>}. */
    void writeCsv(String s3Path, String key, List<List<String>> rows) {
        Location location = location(s3Path);
        if (location == null) {
            throw new IllegalStateException("The output location " + s3Path + " is not an S3 path.");
        }
        StringBuilder csv = new StringBuilder();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String cell : row) {
                String value = cell == null ? "" : cell;
                cells.add(value.contains(",") || value.contains("\"") || value.contains("\n")
                        ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
            }
            csv.append(String.join(",", cells)).append('\n');
        }
        String prefix = location.prefix().isEmpty() || location.prefix().endsWith("/") ? location.prefix()
                : location.prefix() + "/";
        s3.putObject(location.bucket(), prefix + key, csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of());
    }

    /** Whether the bucket an S3 path names exists. */
    boolean bucketExists(String s3Path) {
        Location location = location(s3Path);
        if (location == null) {
            return false;
        }
        try {
            s3.listObjects(location.bucket(), "", null, 1);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }
}
