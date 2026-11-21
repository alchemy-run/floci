package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.inject.Instance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads a DataBrew dataset from S3 or the Glue Data Catalog and writes job outputs back to S3.
 * <p>
 * An S3 input names one object, a folder (a key ending in {@code /}) or a key with {@code <parameter>} placeholders;
 * {@code PathOptions.FilesLimit} picks the newest or oldest files and a parameter with {@code CreateColumn} adds its
 * matched value as a column. CSV and JSON (optionally gzip-compressed) are parsed here; Parquet is read and written
 * through floci-duck. A Data Catalog input reads the table's S3 location.
 */
final class DataBrewData {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Instance<S3Service> s3;
    private final Instance<GlueService> glue;
    private final Instance<FlociDuckClient> duck;
    private final ObjectMapper mapper;

    DataBrewData(Instance<S3Service> s3, Instance<GlueService> glue, Instance<FlociDuckClient> duck,
                 ObjectMapper mapper) {
        this.s3 = s3;
        this.glue = glue;
        this.duck = duck;
        this.mapper = mapper;
    }

    /** The S3 objects a dataset reads, with the values of any path parameters each one matched. */
    record Source(String bucket, String key, Map<String, String> parameters) {}

    List<Source> sources(JsonNode dataset) {
        JsonNode input = dataset.path("Input");
        if (input.has("S3InputDefinition")) {
            JsonNode location = input.get("S3InputDefinition");
            return s3Sources(location.path("Bucket").asText(), location.path("Key").asText(""),
                    dataset.path("PathOptions"));
        }
        if (input.has("DataCatalogInputDefinition")) {
            JsonNode catalog = input.get("DataCatalogInputDefinition");
            if (!glue.isResolvable()) {
                throw new IllegalArgumentException("The Glue Data Catalog is not available.");
            }
            Table table;
            try {
                table = glue.get().getTable(catalog.path("DatabaseName").asText(), catalog.path("TableName").asText());
            } catch (AwsException e) {
                throw new IllegalArgumentException("Data Catalog table " + catalog.path("DatabaseName").asText() + "."
                        + catalog.path("TableName").asText() + " was not found.");
            }
            StorageDescriptor descriptor = table.getStorageDescriptor();
            String location = descriptor == null ? null : descriptor.getLocation();
            if (location == null || !location.startsWith("s3://")) {
                throw new IllegalArgumentException("The Data Catalog table has no S3 location.");
            }
            String path = location.substring("s3://".length());
            int slash = path.indexOf('/');
            String bucket = slash < 0 ? path : path.substring(0, slash);
            String prefix = slash < 0 ? "" : path.substring(slash + 1);
            return s3Sources(bucket, prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/",
                    dataset.path("PathOptions"));
        }
        throw new IllegalArgumentException("Floci reads DataBrew datasets from S3 and the Glue Data Catalog; "
                + "database and Data Exchange inputs are not supported.");
    }

    private List<Source> s3Sources(String bucket, String key, JsonNode pathOptions) {
        S3Service store = s3.get();
        if (!store.bucketExists(bucket)) {
            throw new IllegalArgumentException("S3 bucket " + bucket + " does not exist.");
        }
        List<Source> sources = new ArrayList<>();
        List<S3Object> objects = new ArrayList<>();
        Map<String, Map<String, String>> parameters = new LinkedHashMap<>();
        if (key.contains("<")) {
            Matcher placeholders = Pattern.compile("<([^>]+)>").matcher(key);
            StringBuilder regex = new StringBuilder();
            List<String> names = new ArrayList<>();
            int last = 0;
            while (placeholders.find()) {
                regex.append(Pattern.quote(key.substring(last, placeholders.start())));
                regex.append("([^/]+)");
                names.add(placeholders.group(1));
                last = placeholders.end();
            }
            regex.append(Pattern.quote(key.substring(last)));
            String prefix = key.substring(0, key.indexOf('<'));
            Pattern pattern = Pattern.compile(regex.toString());
            for (S3Object object : store.listObjects(bucket, prefix, null, 100000)) {
                Matcher matcher = pattern.matcher(object.getKey());
                if (matcher.matches()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int index = 0; index < names.size(); index++) {
                        values.put(names.get(index), matcher.group(index + 1));
                    }
                    if (filterMatches(values, pathOptions.path("Parameters"))) {
                        objects.add(object);
                        parameters.put(object.getKey(), values);
                    }
                }
            }
        } else if (key.isEmpty() || key.endsWith("/")) {
            for (S3Object object : store.listObjects(bucket, key, null, 100000)) {
                if (!object.getKey().endsWith("/") && object.getSize() > 0) {
                    objects.add(object);
                }
            }
        } else {
            try {
                objects.add(store.getObject(bucket, key));
            } catch (AwsException e) {
                throw new IllegalArgumentException("S3 object s3://" + bucket + "/" + key + " does not exist.");
            }
        }
        JsonNode limit = pathOptions.path("FilesLimit");
        if (limit.has("MaxFiles")) {
            Comparator<S3Object> order = Comparator.comparing(S3Object::getLastModified);
            objects.sort("ASCENDING".equals(limit.path("Order").asText("DESCENDING")) ? order : order.reversed());
            objects = new ArrayList<>(objects.subList(0, Math.min(objects.size(), limit.get("MaxFiles").asInt())));
        }
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("No S3 objects match s3://" + bucket + "/" + key + ".");
        }
        for (S3Object object : objects) {
            sources.add(new Source(bucket, object.getKey(), parameters.getOrDefault(object.getKey(), Map.of())));
        }
        return sources;
    }

    /** A path parameter's {@code Filter}: its expression compares the matched value with {@code ValuesMap}. */
    private static boolean filterMatches(Map<String, String> values, JsonNode definitions) {
        for (Map.Entry<String, String> value : values.entrySet()) {
            JsonNode filter = definitions.path(value.getKey()).path("Filter");
            if (!filter.has("Expression")) {
                continue;
            }
            String expression = filter.get("Expression").asText().strip();
            Matcher matcher = Pattern.compile("(?i)^(==|!=|>=|<=|>|<|starts_with|ends_with|contains|matches)\\s*"
                    + "(:[A-Za-z0-9_]+)$").matcher(expression);
            if (!matcher.matches()) {
                continue;
            }
            String expected = filter.path("ValuesMap").path(matcher.group(2)).asText();
            String actual = value.getValue();
            int comparison = DataBrewRecipes.numeric(actual) && DataBrewRecipes.numeric(expected)
                    ? Double.compare(Double.parseDouble(actual), Double.parseDouble(expected))
                    : actual.compareTo(expected);
            boolean ok = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "==" -> comparison == 0;
                case "!=" -> comparison != 0;
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                case "starts_with" -> actual.startsWith(expected);
                case "ends_with" -> actual.endsWith(expected);
                case "contains" -> actual.contains(expected);
                default -> Pattern.compile(expected).matcher(actual).find();
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    DataBrewTable read(JsonNode dataset, String accountId) {
        DataBrewTable table = DataBrewTable.empty();
        JsonNode createColumns = dataset.path("PathOptions").path("Parameters");
        for (Source source : sources(dataset)) {
            DataBrewTable part = readObject(dataset, source, accountId);
            for (Map.Entry<String, String> parameter : source.parameters().entrySet()) {
                if (createColumns.path(parameter.getKey()).path("CreateColumn").asBoolean(false)) {
                    if (!part.columns.contains(parameter.getKey())) {
                        part.columns.add(parameter.getKey());
                    }
                    part.rows.forEach(row -> row.put(parameter.getKey(), parameter.getValue()));
                }
            }
            table.append(part);
        }
        for (Map<String, String> row : table.rows) {
            for (String column : table.columns) {
                row.putIfAbsent(column, null);
            }
        }
        return table;
    }

    private DataBrewTable readObject(JsonNode dataset, Source source, String accountId) {
        String format = dataset.hasNonNull("Format") ? dataset.get("Format").asText() : formatOf(source.key());
        JsonNode options = dataset.path("FormatOptions");
        switch (format) {
            case "CSV" -> {
                String delimiter = options.path("Csv").path("Delimiter").asText(",");
                boolean header = options.path("Csv").path("HeaderRow").asBoolean(true);
                return DataBrewTable.readCsv(text(source), delimiter.isEmpty() ? ',' : delimiter.charAt(0), header);
            }
            case "JSON" -> {
                return DataBrewTable.readJson(mapper, text(source), options.path("Json").path("MultiLine")
                        .asBoolean(false));
            }
            case "PARQUET" -> {
                if (!duck.isResolvable() || !duck.get().isAvailable()) {
                    throw new IllegalArgumentException("Reading Parquet needs floci-duck, which is not available.");
                }
                String uri = "s3://" + source.bucket() + "/" + source.key();
                List<Map<String, Object>> rows = duck.get().query("SELECT * FROM read_parquet('"
                        + uri.replace("'", "''") + "')", null, accountId);
                List<String> columns = new ArrayList<>();
                List<Map<String, String>> converted = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, String> cells = new LinkedHashMap<>();
                    row.forEach((column, value) -> {
                        if (!columns.contains(column)) {
                            columns.add(column);
                        }
                        cells.put(column, value == null ? null : value.toString());
                    });
                    converted.add(cells);
                }
                return new DataBrewTable(columns, converted);
            }
            default -> throw new IllegalArgumentException("Floci reads CSV, JSON and Parquet datasets, not " + format
                    + ".");
        }
    }

    private String text(Source source) {
        byte[] data = s3.get().getObject(source.bucket(), source.key()).getData();
        if (source.key().endsWith(".gz") || (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b)) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    static String formatOf(String key) {
        String name = key.toLowerCase(Locale.ROOT).replaceAll("\\.(gz|gzip|snappy)$", "");
        if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
            return "JSON";
        }
        if (name.endsWith(".parquet")) {
            return "PARQUET";
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return "EXCEL";
        }
        if (name.endsWith(".orc")) {
            return "ORC";
        }
        return "CSV";
    }

    /** Writes a recipe job's result to one output; returns the keys it wrote. */
    List<String> write(DataBrewTable table, JsonNode output, String jobName, Instant startedOn, String accountId) {
        String bucket = output.path("Location").path("Bucket").asText();
        String key = output.path("Location").path("Key").asText("");
        String prefix = key.isEmpty() || key.endsWith("/") ? key : key + "/";
        S3Service store = s3.get();
        if (!store.bucketExists(bucket)) {
            throw new IllegalArgumentException("Output bucket " + bucket + " does not exist.");
        }
        if (output.path("Overwrite").asBoolean(false)) {
            for (S3Object existing : store.listObjects(bucket, prefix, null, 100000)) {
                store.deleteObject(bucket, existing.getKey());
            }
        }
        String format = output.path("Format").asText("CSV");
        String compression = output.path("CompressionFormat").asText(null);
        List<String> partitionColumns = new ArrayList<>();
        output.path("PartitionColumns").forEach(column -> partitionColumns.add(column.asText()));
        for (String column : partitionColumns) {
            if (!table.columns.contains(column)) {
                throw new IllegalArgumentException("Partition column " + column + " does not exist.");
            }
        }
        Map<String, DataBrewTable> partitions = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows) {
            StringBuilder path = new StringBuilder(prefix);
            for (String column : partitionColumns) {
                path.append(column).append('=').append(row.get(column) == null ? "__HIVE_DEFAULT_PARTITION__"
                        : row.get(column)).append('/');
            }
            partitions.computeIfAbsent(path.toString(), p -> new DataBrewTable(dataColumns(table, partitionColumns),
                    new ArrayList<>())).rows.add(row);
        }
        if (partitions.isEmpty()) {
            partitions.put(prefix, new DataBrewTable(dataColumns(table, partitionColumns), new ArrayList<>()));
        }
        int files = Math.max(1, output.path("MaxOutputFiles").asInt(1));
        List<String> written = new ArrayList<>();
        String base = jobName + "_" + STAMP.format(startedOn);
        for (Map.Entry<String, DataBrewTable> partition : partitions.entrySet()) {
            DataBrewTable part = partition.getValue();
            int chunk = Math.max(1, (int) Math.ceil(part.rows.size() / (double) files));
            int index = 0;
            for (int start = 0; start < Math.max(1, part.rows.size()); start += chunk) {
                DataBrewTable slice = new DataBrewTable(part.columns,
                        new ArrayList<>(part.rows.subList(start, Math.min(part.rows.size(), start + chunk))));
                String name = partition.getKey() + base + String.format("_part%05d", index++);
                written.add(writeFile(slice, output, format, compression, bucket, name, accountId));
            }
        }
        return written;
    }

    private static List<String> dataColumns(DataBrewTable table, List<String> partitionColumns) {
        List<String> columns = new ArrayList<>(table.columns);
        columns.removeAll(partitionColumns);
        return columns;
    }

    private String writeFile(DataBrewTable table, JsonNode output, String format, String compression, String bucket,
                             String name, String accountId) {
        S3Service store = s3.get();
        switch (format) {
            case "CSV", "JSON" -> {
                String text = "CSV".equals(format)
                        ? table.toCsv(output.path("FormatOptions").path("Csv").path("Delimiter").asText(",").charAt(0),
                                true)
                        : table.toJsonLines(mapper);
                String extension = "CSV".equals(format) ? ".csv" : ".json";
                boolean gzip = "GZIP".equals(compression);
                if (compression != null && !gzip) {
                    throw new IllegalArgumentException("Floci compresses CSV and JSON outputs with GZIP only, not "
                            + compression + ".");
                }
                String key = name + extension + (gzip ? ".gz" : "");
                store.putObject(bucket, key, gzip ? DataBrewTable.gzip(text) : text.getBytes(StandardCharsets.UTF_8),
                        "CSV".equals(format) ? "text/csv" : "application/json", Map.of());
                return key;
            }
            case "PARQUET", "GLUEPARQUET" -> {
                if (!duck.isResolvable() || !duck.get().isAvailable()) {
                    throw new IllegalArgumentException("Writing Parquet needs floci-duck, which is not available.");
                }
                String staging = name + ".staging.csv";
                store.putObject(bucket, staging, table.toCsv(',', true).getBytes(StandardCharsets.UTF_8), "text/csv",
                        Map.of());
                String codec = compression == null ? "SNAPPY" : compression;
                if (!List.of("SNAPPY", "GZIP", "ZSTD", "BROTLI", "LZ4").contains(codec)) {
                    throw new IllegalArgumentException("Parquet outputs support SNAPPY, GZIP, ZSTD, BROTLI and LZ4, not "
                            + codec + ".");
                }
                String key = name + "." + codec.toLowerCase(Locale.ROOT) + ".parquet";
                try {
                    duck.get().execute("COPY (SELECT * FROM read_csv('s3://" + bucket + "/" + staging.replace("'", "''")
                            + "', header = true, all_varchar = true)) TO 's3://" + bucket + "/" + key.replace("'", "''")
                            + "' (FORMAT PARQUET, COMPRESSION " + codec + ")", null, null, accountId);
                } finally {
                    store.deleteObject(bucket, staging);
                }
                return key;
            }
            default -> throw new IllegalArgumentException("Floci writes CSV, JSON and Parquet outputs, not " + format
                    + ".");
        }
    }

    void putJson(String bucket, String key, JsonNode document) {
        S3Service store = s3.get();
        if (!store.bucketExists(bucket)) {
            throw new IllegalArgumentException("Output bucket " + bucket + " does not exist.");
        }
        try {
            store.putObject(bucket, key, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document),
                    "application/json", Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
