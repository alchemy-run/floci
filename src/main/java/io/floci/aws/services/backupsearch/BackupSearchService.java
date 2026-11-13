package io.floci.aws.services.backupsearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.backup.BackupIndexes;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AWS Backup Search: search jobs that scan the backup indexes of AWS Backup recovery points ({@link BackupIndexes})
 * for S3 objects or EBS files matching item filters, the per-backup status of each scan, and export jobs that
 * write a search's results to S3 as CSV. A job runs in the background: {@code RUNNING} while it scans, then
 * {@code COMPLETED}, or {@code STOPPED} when stopped first. Jobs and exports can't be deleted, as on AWS.
 */
@ApplicationScoped
public class BackupSearchService implements Resettable {
    private static final long SCAN_DELAY_MILLIS = 750;
    private static final int MAX_RUNNING_JOBS = 5;
    private static final int MAX_RESULTS = 1000;

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/backupsearch-request-shapes.json", "Backup Search request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final AccountAwareStorageBackend<ObjectNode> exports;
    private final BackupIndexes indexes;
    private final Instance<S3Service> s3;
    private final Instance<KmsService> kms;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backup-search-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public BackupSearchService(StorageFactory storageFactory, BackupIndexes indexes, Instance<S3Service> s3,
                               Instance<KmsService> kms, RegionResolver regionResolver, ObjectMapper mapper) {
        this.jobs = storageFactory.create("backupsearch", "backup-search-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.exports = storageFactory.create("backupsearch", "backup-search-export-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.indexes = indexes;
        this.s3 = s3;
        this.kms = kms;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    static void check(String operation, JsonNode request, String... members) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < members.length; i += 2) {
            if (members[i + 1] != null) {
                checked.put(members[i], members[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Search jobs ────────────────────────────────────────────────────────

    public synchronized ObjectNode startSearchJob(JsonNode in, String region) {
        check("StartSearchJob", in);
        if (in.hasNonNull("ClientToken")) {
            Optional<ObjectNode> repeated = scan(jobs, region).stream()
                    .filter(j -> in.path("ClientToken").asText().equals(j.path("ClientToken").asText())).findFirst();
            if (repeated.isPresent()) {
                return startedJob(repeated.get());
            }
        }
        long running = scan(jobs, region).stream().filter(j -> "RUNNING".equals(j.path("Status").asText())).count();
        if (running >= MAX_RUNNING_JOBS) {
            throw new AwsException("ServiceQuotaExceededException", "The account has the maximum of "
                    + MAX_RUNNING_JOBS + " search jobs running.", 402);
        }
        JsonNode scope = in.path("SearchScope");
        List<String> types = new ArrayList<>();
        scope.path("BackupResourceTypes").forEach(t -> types.add(t.asText()));
        if (types.size() != 1) {
            throw validation("SearchScope.BackupResourceTypes must name one resource type, S3 or EBS.");
        }
        JsonNode filters = in.path("ItemFilters");
        if ("S3".equals(types.get(0)) && filters.has("EBSItemFilters")
                || "EBS".equals(types.get(0)) && filters.has("S3ItemFilters")) {
            throw validation("ItemFilters must match the scope's resource type, " + types.get(0) + ".");
        }
        if (in.hasNonNull("EncryptionKeyArn") && kms.isResolvable()
                && kms.get().findKeyArn(in.path("EncryptionKeyArn").asText(), region).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The KMS key " + in.path("EncryptionKeyArn").asText()
                    + " doesn't exist.", 404);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode job = mapper.createObjectNode();
        job.put("SearchJobIdentifier", id);
        job.put("SearchJobArn", arn(region, "search-job/" + id));
        if (in.hasNonNull("Name")) {
            job.put("Name", in.path("Name").asText());
        }
        if (in.hasNonNull("EncryptionKeyArn")) {
            job.put("EncryptionKeyArn", in.path("EncryptionKeyArn").asText());
        }
        job.put("Status", "RUNNING");
        job.set("SearchScope", scope.deepCopy());
        job.set("ItemFilters", filters.isObject() ? filters.deepCopy() : mapper.createObjectNode());
        job.put("CreationTime", now());
        if (in.hasNonNull("ClientToken")) {
            job.put("ClientToken", in.path("ClientToken").asText());
        }
        ObjectNode tags = job.putObject("Tags");
        in.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        List<ObjectNode> scanned = inScope(scope, region);
        long items = scanned.stream().mapToLong(i -> i.path("Items").size()).sum();
        job.putObject("SearchScopeSummary").put("TotalRecoveryPointsToScanCount", scanned.size())
                .put("TotalItemsToScanCount", items);
        job.putObject("CurrentSearchProgress").put("RecoveryPointsScannedCount", 0).put("ItemsScannedCount", 0)
                .put("ItemsMatchedCount", 0);
        ArrayNode backups = job.putArray("Backups");
        for (ObjectNode index : scanned) {
            ObjectNode backup = backups.addObject();
            backup.put("Status", "RUNNING");
            backup.put("ResourceType", index.path("ResourceType").asText());
            backup.put("BackupResourceArn", index.path("RecoveryPointArn").asText());
            backup.put("SourceResourceArn", index.path("SourceResourceArn").asText());
            backup.put("IndexCreationTime", index.path("IndexCreationDate").asDouble());
            backup.put("BackupCreationTime", index.path("BackupCreationDate").asDouble());
        }
        job.putArray("Results");
        jobs.put(region + "/" + id, job);
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> runSearch(id, region)), SCAN_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        return startedJob(job);
    }

    private ObjectNode startedJob(ObjectNode job) {
        return mapper.createObjectNode().put("SearchJobArn", job.path("SearchJobArn").asText())
                .put("CreationTime", job.path("CreationTime").asDouble())
                .put("SearchJobIdentifier", job.path("SearchJobIdentifier").asText());
    }

    /** The indexes a scope covers: active, of its resource type, and matching its ARNs, tags and creation times. */
    private List<ObjectNode> inScope(JsonNode scope, String region) {
        String type = scope.path("BackupResourceTypes").path(0).asText();
        List<String> sources = strings(scope.path("SourceResourceArns"));
        List<String> backups = strings(scope.path("BackupResourceArns"));
        JsonNode created = scope.path("BackupResourceCreationTime");
        double after = created.path("CreatedAfter").asDouble(Double.NEGATIVE_INFINITY);
        double before = created.path("CreatedBefore").asDouble(Double.POSITIVE_INFINITY);
        JsonNode tags = scope.path("BackupResourceTags");
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode index : indexes.all(region)) {
            double backupTime = index.path("BackupCreationDate").asDouble();
            boolean tagged = true;
            for (Map.Entry<String, JsonNode> tag : iterable(tags)) {
                tagged &= tag.getValue().asText().equals(index.path("Tags").path(tag.getKey()).asText(null));
            }
            if ("ACTIVE".equals(index.path("IndexStatus").asText()) && type.equals(index.path("ResourceType").asText())
                    && (sources.isEmpty() || sources.contains(index.path("SourceResourceArn").asText()))
                    && (backups.isEmpty() || backups.contains(index.path("RecoveryPointArn").asText()))
                    && backupTime > after && backupTime < before && tagged) {
                out.add(index);
            }
        }
        out.sort(Comparator.comparing(i -> i.path("BackupCreationDate").asDouble()));
        return out;
    }

    private void runSearch(String id, String region) {
        ObjectNode job;
        synchronized (this) {
            job = jobs.get(region + "/" + id).orElse(null);
            if (job == null || !List.of("RUNNING", "STOPPING").contains(job.path("Status").asText())) {
                return;
            }
        }
        JsonNode filters = job.path("ItemFilters");
        String type = job.path("SearchScope").path("BackupResourceTypes").path(0).asText();
        List<ObjectNode> results = new ArrayList<>();
        long scannedItems = 0;
        int scannedPoints = 0;
        for (JsonNode backup : job.path("Backups")) {
            Optional<ObjectNode> index = indexes.get(backup.path("BackupResourceArn").asText());
            if (index.isEmpty()) {
                ((ObjectNode) backup).put("Status", "FAILED");
                ((ObjectNode) backup).put("StatusMessage", "The recovery point's backup index was deleted.");
                continue;
            }
            for (JsonNode item : index.get().path("Items")) {
                scannedItems++;
                if (results.size() < MAX_RESULTS && matches(item, filters, type)) {
                    results.add(result(item, index.get(), type));
                }
            }
            ((ObjectNode) backup).put("Status", "COMPLETED");
            scannedPoints++;
        }
        synchronized (this) {
            ObjectNode current = jobs.get(region + "/" + id).orElse(null);
            if (current == null) {
                return;
            }
            current.set("Backups", job.path("Backups"));
            ArrayNode stored = current.putArray("Results");
            results.forEach(stored::add);
            current.putObject("CurrentSearchProgress").put("RecoveryPointsScannedCount", scannedPoints)
                    .put("ItemsScannedCount", scannedItems).put("ItemsMatchedCount", results.size());
            if ("RUNNING".equals(current.path("Status").asText())) {
                current.put("Status", "COMPLETED");
                current.put("CompletionTime", now());
            } else if ("STOPPING".equals(current.path("Status").asText())) {
                current.put("Status", "STOPPED");
                current.put("CompletionTime", now());
            }
            jobs.put(region + "/" + id, current);
        }
    }

    /** Whether an item matches the job's filters: any filter may match, and every condition of a filter must. */
    static boolean matches(JsonNode item, JsonNode filters, String type) {
        JsonNode list = filters.path("S3".equals(type) ? "S3ItemFilters" : "EBSItemFilters");
        if (!list.isArray() || list.isEmpty()) {
            return true;
        }
        for (JsonNode filter : list) {
            boolean all = true;
            if ("S3".equals(type)) {
                all &= strings(filter.path("ObjectKeys"), item.path("ObjectKey").asText());
                all &= longs(filter.path("Sizes"), item.path("ObjectSize").asLong());
                all &= times(filter.path("CreationTimes"), item.path("CreationTime").asDouble());
                all &= strings(filter.path("VersionIds"), item.path("VersionId").asText());
                all &= strings(filter.path("ETags"), item.path("ETag").asText());
            } else {
                all &= strings(filter.path("FilePaths"), item.path("FilePath").asText());
                all &= longs(filter.path("Sizes"), item.path("FileSize").asLong());
                all &= times(filter.path("CreationTimes"), item.path("CreationTime").asDouble());
                all &= times(filter.path("LastModificationTimes"), item.path("LastModifiedTime").asDouble());
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** Whether a value meets any of the string conditions (none means any value). */
    private static boolean strings(JsonNode conditions, String value) {
        if (!conditions.isArray() || conditions.isEmpty()) {
            return true;
        }
        for (JsonNode condition : conditions) {
            String target = condition.path("Value").asText();
            boolean ok = switch (condition.path("Operator").asText("EQUALS_TO")) {
                case "NOT_EQUALS_TO" -> !value.equals(target);
                case "CONTAINS" -> value.contains(target);
                case "DOES_NOT_CONTAIN" -> !value.contains(target);
                case "BEGINS_WITH" -> value.startsWith(target);
                case "ENDS_WITH" -> value.endsWith(target);
                case "DOES_NOT_BEGIN_WITH" -> !value.startsWith(target);
                case "DOES_NOT_END_WITH" -> !value.endsWith(target);
                default -> value.equals(target);
            };
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static boolean longs(JsonNode conditions, long value) {
        if (!conditions.isArray() || conditions.isEmpty()) {
            return true;
        }
        for (JsonNode condition : conditions) {
            if (compare(condition.path("Operator").asText("EQUALS_TO"), Long.compare(value,
                    condition.path("Value").asLong()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean times(JsonNode conditions, double value) {
        if (!conditions.isArray() || conditions.isEmpty()) {
            return true;
        }
        for (JsonNode condition : conditions) {
            if (compare(condition.path("Operator").asText("EQUALS_TO"), Double.compare(Math.floor(value),
                    Math.floor(condition.path("Value").asDouble())))) {
                return true;
            }
        }
        return false;
    }

    private static boolean compare(String operator, int comparison) {
        return switch (operator) {
            case "NOT_EQUALS_TO" -> comparison != 0;
            case "LESS_THAN_EQUAL_TO" -> comparison <= 0;
            case "GREATER_THAN_EQUAL_TO" -> comparison >= 0;
            default -> comparison == 0;
        };
    }

    private ObjectNode result(JsonNode item, ObjectNode index, String type) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode body = result.putObject("S3".equals(type) ? "S3ResultItem" : "EBSResultItem");
        body.put("BackupResourceArn", index.path("RecoveryPointArn").asText());
        body.put("SourceResourceArn", index.path("SourceResourceArn").asText());
        body.put("BackupVaultName", index.path("BackupVaultName").asText());
        item.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        return result;
    }

    public synchronized ObjectNode getSearchJob(String id, String region) {
        check("GetSearchJob", null, "SearchJobIdentifier", id);
        ObjectNode job = requireJob(id, region);
        ObjectNode out = mapper.createObjectNode();
        copy(job, out, "Name", "SearchScopeSummary", "CurrentSearchProgress", "StatusMessage", "EncryptionKeyArn",
                "CompletionTime", "Status", "SearchScope", "ItemFilters", "CreationTime", "SearchJobIdentifier",
                "SearchJobArn");
        return out;
    }

    public synchronized ObjectNode listSearchJobs(String status, Integer maxResults, String nextToken, String region) {
        check("ListSearchJobs", null, "ByStatus", status);
        List<ObjectNode> matching = scan(jobs, region).stream()
                .filter(j -> status == null || status.equals(j.path("Status").asText()))
                .sorted(Comparator.comparing(j -> -j.path("CreationTime").asDouble())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("SearchJobs");
        for (ObjectNode job : page(matching, maxResults, nextToken, out)) {
            ObjectNode summary = list.addObject();
            copy(job, summary, "SearchJobIdentifier", "SearchJobArn", "Name", "Status", "CreationTime",
                    "CompletionTime", "SearchScopeSummary", "StatusMessage");
        }
        return out;
    }

    public synchronized ObjectNode listSearchJobResults(String id, Integer maxResults, String nextToken,
                                                        String region) {
        check("ListSearchJobResults", null, "SearchJobIdentifier", id);
        ObjectNode job = requireJob(id, region);
        List<JsonNode> results = new ArrayList<>();
        job.path("Results").forEach(results::add);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Results");
        page(results, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode listSearchJobBackups(String id, Integer maxResults, String nextToken,
                                                        String region) {
        check("ListSearchJobBackups", null, "SearchJobIdentifier", id);
        ObjectNode job = requireJob(id, region);
        List<JsonNode> backups = new ArrayList<>();
        job.path("Backups").forEach(backups::add);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Results");
        page(backups, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    /** Stops a running job, keeping the results it has; any other job can't be stopped. */
    public synchronized void stopSearchJob(String id, String region) {
        check("StopSearchJob", null, "SearchJobIdentifier", id);
        ObjectNode job = requireJob(id, region);
        if (!"RUNNING".equals(job.path("Status").asText())) {
            throw new AwsException("ConflictException", "The search job is " + job.path("Status").asText()
                    + "; only a running search job can be stopped.", 409);
        }
        job.put("Status", "STOPPING");
        jobs.put(region + "/" + id, job);
    }

    // ── Export jobs ────────────────────────────────────────────────────────

    /**
     * Exports a completed or stopped search job's results to S3 as CSV, at
     * {@code <prefix>/<export job id>/results.csv}.
     */
    public synchronized ObjectNode startExportJob(JsonNode in, String region) {
        check("StartSearchResultExportJob", in);
        if (in.hasNonNull("ClientToken")) {
            Optional<ObjectNode> repeated = scan(exports, region).stream()
                    .filter(e -> in.path("ClientToken").asText().equals(e.path("ClientToken").asText())).findFirst();
            if (repeated.isPresent()) {
                return mapper.createObjectNode().put("ExportJobArn", repeated.get().path("ExportJobArn").asText())
                        .put("ExportJobIdentifier", repeated.get().path("ExportJobIdentifier").asText());
            }
        }
        ObjectNode job = requireJob(in.path("SearchJobIdentifier").asText(), region);
        String status = job.path("Status").asText();
        if (!List.of("COMPLETED", "STOPPED").contains(status)) {
            throw new AwsException("ConflictException", "Export is only supported for search job with "
                    + "COMPLETED,STOPPED statuses. SearchJob has status " + status, 409);
        }
        JsonNode specification = in.path("ExportSpecification").path("s3ExportSpecification");
        String bucket = specification.path("DestinationBucket").asText();
        if (s3.isResolvable() && !s3.get().bucketExists(bucket)) {
            throw new AwsException("ResourceNotFoundException", "The destination bucket " + bucket + " doesn't exist.",
                    404);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode export = mapper.createObjectNode();
        export.put("ExportJobIdentifier", id);
        export.put("ExportJobArn", arn(region, "search-export-job/" + id));
        export.put("Status", "RUNNING");
        export.put("CreationTime", now());
        export.set("ExportSpecification", in.path("ExportSpecification").deepCopy());
        export.put("SearchJobArn", job.path("SearchJobArn").asText());
        if (in.hasNonNull("ClientToken")) {
            export.put("ClientToken", in.path("ClientToken").asText());
        }
        ObjectNode tags = export.putObject("Tags");
        in.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        exports.put(region + "/" + id, export);
        String account = regionResolver.getAccountId();
        worker.schedule(() -> RequestScopes.runAs(account, region, () -> runExport(id, region)), SCAN_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        return mapper.createObjectNode().put("ExportJobArn", export.path("ExportJobArn").asText())
                .put("ExportJobIdentifier", id);
    }

    private void runExport(String id, String region) {
        ObjectNode export;
        ObjectNode job;
        synchronized (this) {
            export = exports.get(region + "/" + id).orElse(null);
            if (export == null) {
                return;
            }
            String searchId = AwsArnUtils.parse(export.path("SearchJobArn").asText()).resource()
                    .substring("search-job/".length());
            job = jobs.get(region + "/" + searchId).orElse(null);
        }
        JsonNode specification = export.path("ExportSpecification").path("s3ExportSpecification");
        String prefix = specification.path("DestinationPrefix").asText("");
        String key = (prefix.isEmpty() ? "" : prefix.endsWith("/") ? prefix : prefix + "/") + id + "/results.csv";
        try {
            StringBuilder csv = new StringBuilder();
            boolean s3Results = job != null && "S3".equals(job.path("SearchScope").path("BackupResourceTypes")
                    .path(0).asText());
            List<String> columns = s3Results
                    ? List.of("BackupResourceArn", "SourceResourceArn", "BackupVaultName", "ObjectKey", "ObjectSize",
                            "CreationTime", "ETag", "VersionId")
                    : List.of("BackupResourceArn", "SourceResourceArn", "BackupVaultName", "FileSystemIdentifier",
                            "FilePath", "FileSize", "CreationTime", "LastModifiedTime");
            csv.append(String.join(",", columns)).append('\n');
            if (job != null) {
                for (JsonNode result : job.path("Results")) {
                    JsonNode item = result.path(s3Results ? "S3ResultItem" : "EBSResultItem");
                    List<String> cells = new ArrayList<>();
                    for (String column : columns) {
                        cells.add(csvCell(item.path(column)));
                    }
                    csv.append(String.join(",", cells)).append('\n');
                }
            }
            s3.get().putObject(specification.path("DestinationBucket").asText(), key,
                    csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
            export.put("Status", "COMPLETED");
        } catch (AwsException e) {
            export.put("Status", "FAILED");
            export.put("StatusMessage", e.getMessage());
        }
        export.put("CompletionTime", now());
        synchronized (this) {
            exports.put(region + "/" + id, export);
        }
    }

    private static String csvCell(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        String text = value.isNumber() && value.isFloatingPointNumber()
                ? Instant.ofEpochMilli((long) (value.asDouble() * 1000)).toString() : value.asText();
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    public synchronized ObjectNode getExportJob(String id, String region) {
        check("GetSearchResultExportJob", null, "ExportJobIdentifier", id);
        ObjectNode export = exports.get(region + "/" + id).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The export job " + id + " doesn't exist.", 404));
        ObjectNode out = mapper.createObjectNode();
        copy(export, out, "ExportJobIdentifier", "ExportJobArn", "Status", "CreationTime", "CompletionTime",
                "StatusMessage", "ExportSpecification", "SearchJobArn");
        return out;
    }

    public synchronized ObjectNode listExportJobs(String status, String searchJobId, Integer maxResults,
                                                  String nextToken, String region) {
        check("ListSearchResultExportJobs", null, "Status", status, "SearchJobIdentifier", searchJobId);
        String searchArn = searchJobId == null ? null : requireJob(searchJobId, region).path("SearchJobArn").asText();
        List<ObjectNode> matching = scan(exports, region).stream()
                .filter(e -> status == null || status.equals(e.path("Status").asText()))
                .filter(e -> searchArn == null || searchArn.equals(e.path("SearchJobArn").asText()))
                .sorted(Comparator.comparing(e -> -e.path("CreationTime").asDouble())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ExportJobs");
        for (ObjectNode export : page(matching, maxResults, nextToken, out)) {
            ObjectNode summary = list.addObject();
            copy(export, summary, "ExportJobIdentifier", "ExportJobArn", "Status", "CreationTime", "CompletionTime",
                    "StatusMessage", "SearchJobArn");
        }
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** Whether an ARN names a search job or an export job, whose tags this service keeps. */
    public static boolean owns(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "backup")) {
            return false;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        return resource.startsWith("search-job/") || resource.startsWith("search-export-job/");
    }

    public synchronized Map<String, String> listTags(String arn, String region) {
        return mapper.convertValue(tagged(arn, region).getValue().path("Tags"),
                new TypeReference<Map<String, String>>() {});
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        Map.Entry<String, ObjectNode> tagged = tagged(arn, region);
        tags.forEach(tagged.getValue().withObject("Tags")::put);
        store(arn).put(tagged.getKey(), tagged.getValue());
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        Map.Entry<String, ObjectNode> tagged = tagged(arn, region);
        keys.forEach(tagged.getValue().withObject("Tags")::remove);
        store(arn).put(tagged.getKey(), tagged.getValue());
    }

    private Map.Entry<String, ObjectNode> tagged(String arn, String region) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        String key = (parsed.region().isEmpty() ? region : parsed.region()) + "/"
                + resource.substring(resource.indexOf('/') + 1);
        return Map.entry(key, store(arn).get(key).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The resource " + arn + " doesn't exist.", 404)));
    }

    private AccountAwareStorageBackend<ObjectNode> store(String arn) {
        return AwsArnUtils.parse(arn).resource().startsWith("search-job/") ? jobs : exports;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode requireJob(String id, String region) {
        return jobs.get(region + "/" + id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The search job " + id + " doesn't exist.", 404));
    }

    private List<ObjectNode> scan(AccountAwareStorageBackend<ObjectNode> store, String region) {
        return store.scan(k -> k.startsWith(region + "/"));
    }

    private static <T> List<T> page(List<T> all, Integer maxResults, String nextToken, ObjectNode out) {
        int max = maxResults == null ? 1000 : maxResults;
        if (max < 1 || max > 1000) {
            throw validation("MaxResults must be between 1 and 1000.");
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw validation("The NextToken isn't valid.");
            }
            if (start < 0 || start > all.size()) {
                throw validation("The NextToken isn't valid.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("NextToken", String.valueOf(end));
        }
        return new ArrayList<>(all.subList(start, end));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode object) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        object.fields().forEachRemaining(out::add);
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("backup", region, regionResolver.getAccountId(), resource).toString();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        jobs.clear();
        exports.clear();
    }
}
