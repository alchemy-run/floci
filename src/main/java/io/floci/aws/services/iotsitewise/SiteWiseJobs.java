package io.floci.aws.services.iotsitewise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.floci.aws.services.iotsitewise.SiteWiseStore.JOB;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.invalid;
import static io.floci.aws.services.iotsitewise.SiteWiseStore.notFound;

/**
 * Bulk import jobs: CSV files (with the columns the job names) or Parquet files (read through floci-duck) in S3,
 * imported into asset properties and data streams. A job is PENDING, then RUNNING, then COMPLETED, or
 * COMPLETED_WITH_FAILURES with each rejected row written to the error report location as CSV with its error, or
 * FAILED when no file could be read. With adaptive ingestion, rows within the last 30 days run computations and
 * notifications as ingested values do; without it, they only add history. Files can be deleted once imported.
 */
@ApplicationScoped
public class SiteWiseJobs {

    private static final Logger LOG = Logger.getLogger(SiteWiseJobs.class);
    private static final Set<String> COLUMNS = Set.of("ALIAS", "ASSET_ID", "PROPERTY_ID", "DATA_TYPE",
            "TIMESTAMP_SECONDS", "TIMESTAMP_NANO_OFFSET", "QUALITY", "VALUE");

    private final SiteWiseStore store;
    private final ObjectMapper mapper;
    private final Instance<S3Service> s3;
    private final Instance<IamService> iam;
    private final Instance<SiteWiseData> data;
    private final Instance<FlociDuckClient> duck;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iotsitewise-bulk-import");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public SiteWiseJobs(SiteWiseStore store, Instance<S3Service> s3, Instance<IamService> iam,
                        Instance<SiteWiseData> data, Instance<FlociDuckClient> duck) {
        this.store = store;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.iam = iam;
        this.data = data;
        this.duck = duck;
    }

    @PreDestroy
    void stop() {
        runner.shutdownNow();
    }

    ObjectNode createBulkImportJob(ObjectNode in, String region) {
        String account = store.account();
        String roleArn = in.get("jobRoleArn").asText();
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
            String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
            RequestScopes.callAs(account, () -> iam.get().getRole(name));
        } catch (RuntimeException e) {
            throw invalid("The role " + roleArn + " doesn't exist.");
        }
        JsonNode format = in.path("jobConfiguration").path("fileFormat");
        if (in.get("files").isEmpty() || in.get("files").size() > 100) {
            throw invalid("A job imports between 1 and 100 files.");
        }
        for (JsonNode file : in.get("files")) {
            JsonNode fileFormat = file.has("fileFormat") ? file.get("fileFormat") : format;
            if (fileFormat.has("csv")) {
                List<String> columns = new ArrayList<>();
                fileFormat.get("csv").get("columnNames").forEach(c -> columns.add(c.asText()));
                checkColumns(columns);
            } else if (!fileFormat.has("parquet") && !fileFormat.has("mp4") && !fileFormat.has("annotation")) {
                throw invalid("Each file has a format: csv, parquet, mp4 or annotation.");
            }
            if ((fileFormat.has("mp4") || fileFormat.has("annotation")) && !file.hasNonNull("alias")) {
                throw invalid("An mp4 or annotation file names the alias it imports into.");
            }
        }
        String bucket = in.get("errorReportLocation").get("bucket").asText();
        if (!RequestScopes.callAs(account, region, () -> s3.get().bucketExists(bucket))) {
            throw invalid("The error report bucket " + bucket + " doesn't exist.");
        }
        long active = store.list(account, region, JOB).stream()
                .filter(j -> Set.of("PENDING", "RUNNING").contains(j.path("jobStatus").asText())).count();
        if (active >= store.config().maxActiveBulkImportJobs()) {
            throw SiteWiseStore.limit("At most " + store.config().maxActiveBulkImportJobs()
                    + " bulk import jobs can be active at once.");
        }
        String id = SiteWiseStore.uuid();
        double now = SiteWiseStore.now();
        ObjectNode job = store.record();
        job.put("jobId", id);
        job.put("jobName", in.get("jobName").asText());
        job.put("jobStatus", "PENDING");
        job.put("jobRoleArn", roleArn);
        job.set("files", in.get("files").deepCopy());
        job.set("errorReportLocation", in.get("errorReportLocation").deepCopy());
        if (in.has("jobConfiguration")) {
            job.set("jobConfiguration", in.get("jobConfiguration").deepCopy());
        }
        job.put("jobCreationDate", now);
        job.put("jobLastUpdateDate", now);
        job.put("adaptiveIngestion", in.path("adaptiveIngestion").asBoolean(false));
        job.put("deleteFilesAfterImport", in.path("deleteFilesAfterImport").asBoolean(false));
        for (String field : List.of("datasetId", "workspaceName")) {
            if (in.hasNonNull(field)) {
                job.put(field, in.get(field).asText());
            }
        }
        store.put(account, region, JOB, id, job);
        runner.submit(() -> RequestScopes.runAs(account, region, () -> run(account, region, id)));
        ObjectNode out = mapper.createObjectNode();
        out.put("jobId", id);
        out.put("jobName", job.get("jobName").asText());
        out.put("jobStatus", "PENDING");
        return out;
    }

    private static void checkColumns(List<String> columns) {
        if (!columns.contains("TIMESTAMP_SECONDS") || !columns.contains("VALUE") || !columns.contains("DATA_TYPE")) {
            throw invalid("A CSV file has the columns TIMESTAMP_SECONDS, DATA_TYPE and VALUE.");
        }
        boolean alias = columns.contains("ALIAS");
        boolean ids = columns.contains("ASSET_ID") && columns.contains("PROPERTY_ID");
        if (!alias && !ids) {
            throw invalid("A CSV file names properties by ALIAS, or by ASSET_ID and PROPERTY_ID.");
        }
        for (String column : columns) {
            if (!COLUMNS.contains(column)) {
                throw invalid("The column " + column + " isn't one SiteWise imports.");
            }
        }
    }

    ObjectNode describeBulkImportJob(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode job = store.get(account, region, JOB, in.get("jobId").asText())
                .orElseThrow(() -> notFound("Bulk import job " + in.get("jobId").asText() + " not found."));
        return SiteWiseStore.view(job);
    }

    ObjectNode listBulkImportJobs(ObjectNode in, String region) {
        String account = store.account();
        String filter = in.path("filter").asText("ALL");
        List<ObjectNode> summaries = new ArrayList<>();
        for (ObjectNode job : store.list(account, region, JOB)) {
            if (!"ALL".equals(filter) && !filter.equals(job.get("jobStatus").asText())) {
                continue;
            }
            if (in.hasNonNull("workspaceName") && !in.get("workspaceName").asText()
                    .equals(job.path("workspaceName").asText(null))) {
                continue;
            }
            summaries.add(mapper.createObjectNode().put("id", job.get("jobId").asText())
                    .put("name", job.get("jobName").asText()).put("status", job.get("jobStatus").asText()));
        }
        ObjectNode out = mapper.createObjectNode();
        SiteWiseStore.page(summaries, in, 50, out, "jobSummaries");
        return out;
    }

    // ---------------------------------------------------------------- running

    private void status(String account, String region, String id, String status) {
        store.get(account, region, JOB, id).ifPresent(job -> {
            job.put("jobStatus", status);
            job.put("jobLastUpdateDate", SiteWiseStore.now());
            store.put(account, region, JOB, id, job);
        });
    }

    private void run(String account, String region, String id) {
        ObjectNode job = store.get(account, region, JOB, id).orElse(null);
        if (job == null) {
            return;
        }
        status(account, region, id, "RUNNING");
        StringBuilder report = new StringBuilder();
        int failedRows = 0;
        int readFiles = 0;
        boolean adaptive = job.path("adaptiveIngestion").asBoolean(false);
        JsonNode defaultFormat = job.path("jobConfiguration").path("fileFormat");
        for (JsonNode file : job.get("files")) {
            String bucket = file.get("bucket").asText();
            String key = file.get("key").asText();
            JsonNode format = file.has("fileFormat") ? file.get("fileFormat") : defaultFormat;
            if (format.has("mp4") || format.has("annotation")) {
                try {
                    clip(account, region, file, format.has("mp4"));
                    readFiles++;
                } catch (RuntimeException e) {
                    report.append(csvCell(bucket + "/" + key)).append(",,").append(csvCell(e.getMessage()))
                            .append('\n');
                }
                continue;
            }
            List<Map<String, String>> rows;
            try {
                rows = format.has("parquet") ? parquet(account, bucket, key) : csv(bucket, key, format);
                readFiles++;
            } catch (RuntimeException e) {
                report.append(csvCell(bucket + "/" + key)).append(",,").append(csvCell("The file couldn't be read: "
                        + e.getMessage())).append('\n');
                continue;
            }
            Map<String, List<Map<String, String>>> byStream = new LinkedHashMap<>();
            for (Map<String, String> row : rows) {
                String streamKey = row.getOrDefault("ALIAS", "") + "|" + row.getOrDefault("ASSET_ID", "") + "|"
                        + row.getOrDefault("PROPERTY_ID", "");
                byStream.computeIfAbsent(streamKey, k -> new ArrayList<>()).add(row);
            }
            for (List<Map<String, String>> group : byStream.values()) {
                failedRows += importRows(account, region, group, adaptive, report);
            }
            if (job.path("deleteFilesAfterImport").asBoolean(false)) {
                try {
                    s3.get().deleteObject(bucket, key);
                } catch (RuntimeException e) {
                    LOG.debugv(e, "IoT SiteWise could not delete s3://{0}/{1}", bucket, key);
                }
            }
        }
        if (!report.isEmpty()) {
            JsonNode location = job.get("errorReportLocation");
            String prefix = location.get("prefix").asText();
            String key = (prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/") + id + "/errors.csv";
            try {
                s3.get().putObject(location.get("bucket").asText(), key,
                        ("FILE_OR_ROW,COLUMN,ERROR\n" + report).getBytes(StandardCharsets.UTF_8), "text/csv",
                        Map.of());
            } catch (RuntimeException e) {
                LOG.debugv(e, "IoT SiteWise could not write the error report of job {0}", id);
            }
        }
        status(account, region, id, readFiles == 0 ? "FAILED" : failedRows > 0 || readFiles < job.get("files").size()
                ? "COMPLETED_WITH_FAILURES" : "COMPLETED");
    }

    /** Imports rows of one stream; returns how many failed, each recorded in the report. */
    private int importRows(String account, String region, List<Map<String, String>> rows, boolean adaptive,
                           StringBuilder report) {
        Map<String, String> first = rows.get(0);
        ObjectNode reference = mapper.createObjectNode();
        if (!first.getOrDefault("ALIAS", "").isEmpty()) {
            reference.put("propertyAlias", first.get("ALIAS"));
        } else {
            reference.put("assetId", first.getOrDefault("ASSET_ID", ""));
            reference.put("propertyId", first.getOrDefault("PROPERTY_ID", ""));
        }
        SiteWiseData.Stream stream;
        try {
            stream = data.get().stream(account, region, reference, true);
        } catch (AwsException e) {
            rows.forEach(row -> report.append(csvCell(String.join(",", row.values()))).append(",,")
                    .append(csvCell(e.getMessage())).append('\n'));
            return rows.size();
        }
        int failed = 0;
        long cutoff = Instant.now().getEpochSecond() - 30L * 86400;
        List<ObjectNode> points = new ArrayList<>();
        String dataType = stream.dataType();
        for (Map<String, String> row : rows) {
            try {
                String type = row.get("DATA_TYPE").toUpperCase(Locale.ROOT);
                if (dataType != null && !dataType.equals(type)) {
                    throw invalid("The property's data type is " + dataType + ", not " + type + ".");
                }
                dataType = type;
                String quality = row.getOrDefault("QUALITY", "GOOD");
                if (quality.isEmpty()) {
                    quality = "GOOD";
                }
                if (!SiteWiseData.QUALITIES.contains(quality)) {
                    throw invalid("The quality " + quality + " isn't GOOD, BAD or UNCERTAIN.");
                }
                long seconds = Long.parseLong(row.get("TIMESTAMP_SECONDS"));
                String offset = row.getOrDefault("TIMESTAMP_NANO_OFFSET", "");
                int nanos = offset.isEmpty() ? 0 : Integer.parseInt(offset);
                if (adaptive && seconds < cutoff) {
                    throw invalid("Adaptive ingestion takes data from the last 30 days.");
                }
                ObjectNode variant = data.get().variant(type, row.get("VALUE"));
                points.add(data.get().point(seconds, nanos, quality, variant));
            } catch (RuntimeException e) {
                failed++;
                report.append(csvCell(String.join(",", row.values()))).append(",,").append(csvCell(e.getMessage()))
                        .append('\n');
            }
        }
        if (points.isEmpty()) {
            return failed;
        }
        if (stream.property() != null && adaptive) {
            data.get().record(account, region, stream.asset(), stream.property(), points);
        } else if (stream.property() != null) {
            data.get().recordQuietly(account, region, stream, points);
        } else {
            data.get().recordAlias(account, region, stream, dataType, points);
        }
        return failed;
    }

    /**
     * An MP4 file is one clip of a VIDEO stream, recorded as its S3 location at the file's start time; an annotation
     * file's content is one value of an ANNOTATION stream.
     */
    private void clip(String account, String region, JsonNode file, boolean video) {
        String bucket = file.get("bucket").asText();
        String key = file.get("key").asText();
        byte[] content = s3.get().getObject(bucket, key).getData();
        if (video && (content.length < 8 || content[4] != 'f' || content[5] != 't' || content[6] != 'y'
                || content[7] != 'p')) {
            throw invalid("The file isn't an MP4 video.");
        }
        SiteWiseData.Stream stream = data.get().stream(account, region,
                mapper.createObjectNode().put("propertyAlias", file.get("alias").asText()), true);
        String type = video ? "VIDEO" : "ANNOTATION";
        if (stream.dataType() != null && !type.equals(stream.dataType())) {
            throw invalid("The stream " + file.get("alias").asText() + " holds " + stream.dataType() + ", not "
                    + type + ".");
        }
        JsonNode start = file.path("startTime");
        long seconds = start.has("timeInSeconds") ? start.get("timeInSeconds").asLong() : Instant.now().getEpochSecond();
        ObjectNode value = mapper.createObjectNode().put("stringValue", video ? "s3://" + bucket + "/" + key
                : new String(content, StandardCharsets.UTF_8));
        ObjectNode point = data.get().point(seconds, start.path("offsetInNanos").asInt(0), "GOOD", value);
        if (stream.property() != null) {
            data.get().recordQuietly(account, region, stream, List.of(point));
        } else {
            data.get().recordAlias(account, region, stream, type, List.of(point));
        }
    }

    private List<Map<String, String>> csv(String bucket, String key, JsonNode format) {
        if (!format.has("csv")) {
            throw invalid("This file format can't be read as CSV.");
        }
        List<String> columns = new ArrayList<>();
        format.get("csv").get("columnNames").forEach(c -> columns.add(c.asText()));
        String text = new String(s3.get().getObject(bucket, key).getData(), StandardCharsets.UTF_8);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            if (cells.size() != columns.size()) {
                throw invalid("A row has " + cells.size() + " columns, not " + columns.size() + ".");
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), cells.get(i).trim());
            }
            rows.add(row);
        }
        return rows;
    }

    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private List<Map<String, String>> parquet(String account, String bucket, String key) {
        String sql = "SELECT * FROM read_parquet('s3://" + bucket.replace("'", "''") + "/" + key.replace("'", "''")
                + "')";
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> row : duck.get().query(sql, null, account)) {
            Map<String, String> normalized = new HashMap<>();
            row.forEach((column, value) -> normalized.put(column.toUpperCase(Locale.ROOT),
                    value == null ? "" : String.valueOf(value)));
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String column : List.of("ALIAS", "ASSET_ID", "PROPERTY_ID", "DATA_TYPE", "TIMESTAMP_SECONDS",
                    "TIMESTAMP_NANO_OFFSET", "QUALITY", "VALUE")) {
                if (normalized.containsKey(column)) {
                    ordered.put(column, normalized.get(column));
                }
            }
            rows.add(ordered);
        }
        return rows;
    }

    private static String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
