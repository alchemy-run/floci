package io.floci.aws.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * S3 Batch Operations jobs. Creating a job reads its manifest (a CSV object, an S3 Inventory
 * {@code manifest.json}, or a listing of the source bucket that the job's manifest generator
 * filters), then waits in {@code Suspended} for confirmation when {@code ConfirmationRequired}, or
 * runs at once. Running applies the operation to every object in the manifest: tagging, ACLs,
 * copies, restores, legal holds, retention, checksums or a Lambda invocation per object. The job then
 * ends {@code Complete} (or {@code Failed} when every task failed), with a completion report in the
 * report bucket when one is enabled. A job that hasn't finished can be cancelled, and its priority
 * changed.
 */
@ApplicationScoped
public class S3BatchJobService implements Resettable {
    private static final Set<String> TERMINAL = Set.of("Complete", "Failed", "Cancelled");

    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final S3Service s3;
    private final Instance<IamService> iam;
    private final Instance<LambdaService> lambda;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public S3BatchJobService(StorageFactory storageFactory, S3Service s3, Instance<IamService> iam,
                             Instance<LambdaService> lambda, RegionResolver regionResolver, ObjectMapper mapper) {
        this.jobs = storageFactory.create("s3", "s3control-jobs.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.s3 = s3;
        this.iam = iam;
        this.lambda = lambda;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    public synchronized ObjectNode create(ObjectNode in, String region) {
        String token = in.path("ClientRequestToken").asText(null);
        if (token == null || token.isEmpty()) {
            throw invalid("ClientRequestToken is required.");
        }
        for (ObjectNode existing : jobs.scan(k -> k.startsWith(region + "::"))) {
            if (token.equals(existing.path("clientToken").asText(null))) {
                return mapper.createObjectNode().put("JobId", existing.path("JobId").asText());
            }
        }
        String role = in.path("RoleArn").asText(null);
        if (!roleExists(role)) {
            throw invalid("The IAM role " + role + " doesn't exist or can't be assumed by S3 Batch Operations.");
        }
        JsonNode operation = in.path("Operation");
        if (!operation.isObject() || operation.size() != 1) {
            throw invalid("Operation must name exactly one operation.");
        }
        if (in.has("Manifest") == in.has("ManifestGenerator")) {
            throw invalid("Specify exactly one of Manifest and ManifestGenerator.");
        }
        JsonNode report = in.path("Report");
        if (report.path("Enabled").asBoolean(false) && (!report.has("Bucket") || !report.has("ReportScope"))) {
            throw invalid("An enabled Report needs a Bucket and a ReportScope.");
        }
        long priority = in.path("Priority").asLong(-1);
        if (priority < 0 || priority > Integer.MAX_VALUE) {
            throw invalid("Priority must be between 0 and 2147483647.");
        }
        String jobId = UUID.randomUUID().toString();
        ObjectNode job = mapper.createObjectNode();
        job.put("JobId", jobId);
        job.put("ConfirmationRequired", in.path("ConfirmationRequired").asBoolean(false));
        if (in.hasNonNull("Description")) {
            job.put("Description", in.get("Description").asText());
        }
        job.put("JobArn", AwsArnUtils.Arn.of("s3", region, regionResolver.getAccountId(), "job/" + jobId).toString());
        job.put("Status", "New");
        for (String field : List.of("Manifest", "ManifestGenerator", "Operation", "Report")) {
            if (in.has(field)) {
                job.set(field, in.get(field).deepCopy());
            }
        }
        job.put("Priority", priority);
        job.put("CreationTime", Instant.now().toString());
        job.put("RoleArn", role);
        job.put("clientToken", token);
        ArrayNode tags = job.putArray("Tags");
        in.path("Tags").forEach(tags::add);
        String key = region + "::" + jobId;
        try {
            List<String[]> tasks = manifest(job);
            ArrayNode stored = job.putArray("tasks");
            tasks.forEach(t -> stored.addArray().add(t[0]).add(t[1]).add(t[2]));
            job.putObject("ProgressSummary").put("TotalNumberOfTasks", tasks.size()).put("NumberOfTasksSucceeded", 0)
                    .put("NumberOfTasksFailed", 0).putObject("Timers").put("ElapsedTimeInActiveSeconds", 0);
            if (job.path("ConfirmationRequired").asBoolean()) {
                job.put("Status", "Suspended");
                job.put("SuspendedDate", Instant.now().toString());
                job.put("SuspendedCause", "AWAITING_CONFIRMATION");
                jobs.put(key, job);
            } else {
                jobs.put(key, job);
                run(key, job);
            }
        } catch (AwsException e) {
            fail(job, "ManifestReadFailure", e.getMessage());
            jobs.put(key, job);
        }
        return mapper.createObjectNode().put("JobId", jobId);
    }

    /** The manifest's (bucket, key, version) tasks. */
    private List<String[]> manifest(ObjectNode job) {
        List<String[]> tasks = new ArrayList<>();
        if (job.has("ManifestGenerator")) {
            JsonNode generator = job.path("ManifestGenerator").path("S3JobManifestGenerator");
            String bucket = bucketOf(generator.path("SourceBucket").asText(""));
            JsonNode filter = generator.path("Filter");
            for (S3Object object : s3.listObjects(bucket, null, null, 1_000_000)) {
                if (!object.getKey().endsWith("/") && matches(object, filter)) {
                    tasks.add(new String[]{bucket, object.getKey(), ""});
                }
            }
            if (generator.path("EnableManifestOutput").asBoolean(false)) {
                writeGeneratedManifest(job, tasks, generator.path("ManifestOutputLocation"));
            }
            return tasks;
        }
        JsonNode manifest = job.path("Manifest");
        String format = manifest.path("Spec").path("Format").asText("");
        String objectArn = manifest.path("Location").path("ObjectArn").asText("");
        if (!objectArn.startsWith("arn:")) { // partition-literal: an S3 object ARN, checked by shape before parse
            throw invalid("Manifest.Location.ObjectArn must be an S3 object ARN.");
        }
        String resource = AwsArnUtils.parse(objectArn).resource();
        String bucket = resource.substring(0, resource.indexOf('/'));
        String key = resource.substring(resource.indexOf('/') + 1);
        S3Object object = s3.getObject(bucket, key);
        String expected = manifest.path("Location").path("ETag").asText(null);
        if (expected != null && object.getETag() != null
                && !expected.replace("\"", "").equals(object.getETag().replace("\"", ""))) {
            throw invalid("The manifest's ETag doesn't match the object.");
        }
        String body = new String(object.getData(), StandardCharsets.UTF_8);
        if ("S3InventoryReport_CSV_20161130".equals(format)) {
            JsonNode inventory;
            try {
                inventory = mapper.readTree(body);
            } catch (Exception e) {
                throw invalid("The inventory manifest is not valid JSON.");
            }
            for (JsonNode file : inventory.path("files")) {
                String data = new String(s3.getObject(bucket, file.path("key").asText()).getData(),
                        StandardCharsets.UTF_8);
                tasks.addAll(csv(data, List.of("Bucket", "Key", "VersionId")));
            }
            return tasks;
        }
        List<String> fields = new ArrayList<>();
        manifest.path("Spec").path("Fields").forEach(f -> fields.add(f.asText()));
        return csv(body, fields.isEmpty() ? List.of("Bucket", "Key") : fields);
    }

    private static List<String[]> csv(String body, List<String> fields) {
        List<String[]> tasks = new ArrayList<>();
        int bucketIndex = fields.indexOf("Bucket");
        int keyIndex = fields.indexOf("Key");
        int versionIndex = fields.indexOf("VersionId");
        for (String line : body.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            String bucket = unquote(columns[Math.max(0, bucketIndex)]);
            String key = URLDecoder.decode(unquote(columns[Math.max(1, keyIndex)]), StandardCharsets.UTF_8);
            String version = versionIndex >= 0 && versionIndex < columns.length ? unquote(columns[versionIndex]) : "";
            tasks.add(new String[]{bucket, key, version});
        }
        return tasks;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }

    private static boolean matches(S3Object object, JsonNode filter) {
        if (filter.isMissingNode()) {
            return true;
        }
        Instant modified = object.getLastModified();
        if (filter.hasNonNull("CreatedAfter") && modified != null
                && !modified.isAfter(Instant.parse(filter.get("CreatedAfter").asText()))) {
            return false;
        }
        if (filter.hasNonNull("CreatedBefore") && modified != null
                && !modified.isBefore(Instant.parse(filter.get("CreatedBefore").asText()))) {
            return false;
        }
        long size = object.getSize();
        if (filter.has("ObjectSizeGreaterThanBytes") && size <= filter.get("ObjectSizeGreaterThanBytes").asLong()) {
            return false;
        }
        if (filter.has("ObjectSizeLessThanBytes") && size >= filter.get("ObjectSizeLessThanBytes").asLong()) {
            return false;
        }
        JsonNode constraint = filter.path("KeyNameConstraint");
        if (!any(constraint.path("MatchAnyPrefix"), p -> object.getKey().startsWith(p))
                || !any(constraint.path("MatchAnySuffix"), p -> object.getKey().endsWith(p))
                || !any(constraint.path("MatchAnySubstring"), p -> object.getKey().contains(p))) {
            return false;
        }
        JsonNode classes = filter.path("MatchAnyStorageClass");
        String storageClass = object.getStorageClass() == null ? "STANDARD" : object.getStorageClass();
        return any(classes, storageClass::equals);
    }

    private static boolean any(JsonNode values, Predicate<String> test) {
        if (!values.isArray() || values.isEmpty()) {
            return true;
        }
        for (JsonNode value : values) {
            if (test.test(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private void writeGeneratedManifest(ObjectNode job, List<String[]> tasks, JsonNode location) {
        String bucket = bucketOf(location.path("Bucket").asText(""));
        String prefix = location.path("ManifestPrefix").asText("");
        StringBuilder csv = new StringBuilder();
        tasks.forEach(t -> csv.append(t[0]).append(',').append(t[1]).append('\n'));
        String key = (prefix.isEmpty() ? "" : prefix + "/") + "job-" + job.path("JobId").asText() + "/manifest.csv";
        S3Object written = s3.putObject(bucket, key, csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of());
        job.putObject("GeneratedManifestDescriptor").put("Format", "S3InventoryReport_CSV_20211130")
                .putObject("Location").put("ObjectArn", AwsArnUtils.Arn.global(regionResolver.getPartition(), "s3", "", bucket + "/" + key)
                        .toString()).put("ETag", written.getETag() == null ? "" : written.getETag());
    }

    /** Runs every task, writes the report, and settles the job. */
    private void run(String key, ObjectNode job) {
        job.put("Status", "Active");
        Instant started = Instant.now();
        JsonNode operation = job.path("Operation");
        String name = operation.fieldNames().next();
        JsonNode spec = operation.get(name);
        int succeeded = 0;
        int failed = 0;
        List<String> report = new ArrayList<>();
        String scope = job.path("Report").path("ReportScope").asText("AllTasks");
        for (JsonNode task : job.withArray("tasks")) {
            String bucket = task.get(0).asText();
            String objectKey = task.get(1).asText();
            String version = task.get(2).asText();
            String outcome;
            String error = "";
            try {
                apply(name, spec, bucket, objectKey, version.isEmpty() ? null : version);
                succeeded++;
                outcome = "succeeded";
            } catch (AwsException e) {
                failed++;
                outcome = "failed";
                error = e.getErrorCode() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                failed++;
                outcome = "failed";
                error = "InternalError: " + e.getMessage();
            }
            if ("AllTasks".equals(scope) || "failed".equals(outcome)) {
                report.add(String.join(",", bucket, objectKey, version, outcome, "succeeded".equals(outcome) ? "200"
                        : "400", error.replace(",", ";"), ""));
            }
        }
        ObjectNode progress = (ObjectNode) job.get("ProgressSummary");
        progress.put("NumberOfTasksSucceeded", succeeded);
        progress.put("NumberOfTasksFailed", failed);
        ((ObjectNode) progress.get("Timers")).put("ElapsedTimeInActiveSeconds",
                Math.max(0, Instant.now().getEpochSecond() - started.getEpochSecond()));
        if (job.path("Report").path("Enabled").asBoolean(false)) {
            writeReport(job, report);
        }
        int total = progress.path("TotalNumberOfTasks").asInt();
        if (total > 0 && failed == total) {
            fail(job, "AllTasksFailed", "Every task of the job failed.");
        } else {
            job.put("Status", "Complete");
            job.put("TerminationDate", Instant.now().toString());
        }
        jobs.put(key, job);
    }

    private void apply(String operation, JsonNode spec, String bucket, String key, String version) {
        switch (operation) {
            case "S3PutObjectTagging" -> {
                Map<String, String> tags = new LinkedHashMap<>();
                spec.path("TagSet").forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText("")));
                s3.putObjectTagging(bucket, key, version, tags);
            }
            case "S3DeleteObjectTagging" -> s3.deleteObjectTagging(bucket, key, version);
            case "S3PutObjectCopy" -> {
                String target = bucketOf(spec.path("TargetResource").asText(""));
                String prefix = spec.path("TargetKeyPrefix").asText("");
                s3.copyObject(bucket, key, target, prefix + key, version);
            }
            case "S3PutObjectAcl" -> s3.putObjectAcl(bucket, key, version, null,
                    spec.path("AccessControlPolicy").path("CannedAccessControlList").asText("private"),
                    null, null, null, null, null);
            case "S3InitiateRestoreObject" -> s3.restoreObject(bucket, key, version,
                    "<RestoreRequest><Days>" + spec.path("ExpirationInDays").asInt(1) + "</Days></RestoreRequest>");
            case "S3PutObjectLegalHold" -> s3.putObjectLegalHold(bucket, key, version,
                    spec.path("LegalHold").path("Status").asText("OFF"));
            case "S3PutObjectRetention" -> s3.putObjectRetention(bucket, key, version,
                    spec.path("Retention").path("Mode").asText(null),
                    spec.path("Retention").hasNonNull("RetainUntilDate")
                            ? Instant.parse(spec.path("Retention").get("RetainUntilDate").asText()) : null,
                    spec.path("BypassGovernanceRetention").asBoolean(false));
            case "S3ComputeObjectChecksum" -> s3.getObject(bucket, key);
            case "LambdaInvoke" -> invoke(spec, bucket, key, version);
            default -> throw new AwsException("OperationNotSupported", "Floci does not run " + operation
                    + " batch tasks.", 400);
        }
    }

    private void invoke(JsonNode spec, String bucket, String key, String version) {
        if (!lambda.isResolvable()) {
            throw new AwsException("LambdaUnavailable", "Lambda is not enabled.", 500);
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("invocationSchemaVersion", spec.path("InvocationSchemaVersion").asText("1.0"));
        event.put("invocationId", UUID.randomUUID().toString());
        event.putObject("job").put("id", UUID.randomUUID().toString());
        ObjectNode task = event.putArray("tasks").addObject();
        task.put("taskId", UUID.randomUUID().toString());
        task.put("s3Key", key);
        task.put("s3VersionId", version);
        task.put("s3BucketArn", AwsArnUtils.Arn.global(regionResolver.getPartition(), "s3", "", bucket).toString());
        String functionArn = spec.path("FunctionArn").asText("");
        String region = AwsArnUtils.isArn(functionArn) ? AwsArnUtils.parse(functionArn).region()
                : regionResolver.getDefaultRegion();
        lambda.get().invoke(region, functionArn, event.toString().getBytes(StandardCharsets.UTF_8),
                InvocationType.RequestResponse);
    }

    private void writeReport(ObjectNode job, List<String> rows) {
        JsonNode report = job.path("Report");
        String bucket = bucketOf(report.path("Bucket").asText(""));
        String prefix = report.path("Prefix").asText("");
        String base = (prefix.isEmpty() ? "" : prefix + "/") + "job-" + job.path("JobId").asText() + "/";
        String file = "results/" + UUID.randomUUID() + ".csv";
        s3.putObject(bucket, base + file, String.join("\n", rows).getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of());
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("Format", "Report_CSV_20180820");
        manifest.put("ReportCreationDate", Instant.now().toString());
        manifest.putArray("Results").addObject().put("TaskExecutionStatus", "all").put("Bucket", bucket)
                .put("Key", base + file);
        s3.putObject(bucket, base + "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8),
                "application/json", Map.of());
    }

    private void fail(ObjectNode job, String code, String reason) {
        job.put("Status", "Failed");
        job.put("TerminationDate", Instant.now().toString());
        job.putArray("FailureReasons").addObject().put("FailureCode", code).put("FailureReason", reason);
    }

    public ObjectNode describe(String jobId, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Job", view(require(jobId, region)));
        return out;
    }

    private ObjectNode view(ObjectNode job) {
        ObjectNode copy = job.deepCopy();
        copy.remove(List.of("clientToken", "tasks", "Tags"));
        return copy;
    }

    public ObjectNode list(ObjectNode in, String region) {
        List<String> statuses = new ArrayList<>();
        in.path("JobStatuses").forEach(s -> statuses.add(s.asText()));
        List<ObjectNode> all = jobs.scan(k -> k.startsWith(region + "::")).stream()
                .filter(job -> statuses.isEmpty() || statuses.contains(job.path("Status").asText()))
                .sorted(Comparator.comparing((ObjectNode job) -> job.path("CreationTime").asText()).reversed())
                .toList();
        int max = in.path("MaxResults").asInt(1000);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(in.get("NextToken").asText());
            } catch (NumberFormatException e) {
                throw invalid("The NextToken is not valid.");
            }
        }
        int end = Math.min(all.size(), start + Math.max(1, Math.min(1000, max)));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Jobs");
        for (ObjectNode job : all.subList(Math.min(start, end), end)) {
            ObjectNode item = list.addObject();
            item.put("JobId", job.path("JobId").asText());
            if (job.has("Description")) {
                item.put("Description", job.path("Description").asText());
            }
            item.put("Operation", job.path("Operation").fieldNames().next());
            item.put("Priority", job.path("Priority").asLong());
            item.put("Status", job.path("Status").asText());
            item.put("CreationTime", job.path("CreationTime").asText());
            if (job.has("TerminationDate")) {
                item.put("TerminationDate", job.path("TerminationDate").asText());
            }
            if (job.has("ProgressSummary")) {
                item.set("ProgressSummary", job.get("ProgressSummary").deepCopy());
            }
        }
        if (end < all.size()) {
            out.put("NextToken", Integer.toString(end));
        }
        return out;
    }

    public synchronized ObjectNode updatePriority(String jobId, long priority, String region) {
        ObjectNode job = require(jobId, region);
        if (TERMINAL.contains(job.path("Status").asText())) {
            throw invalid("Changing the priority of a " + job.path("Status").asText() + " job is not allowed: job "
                    + "status forbidden.");
        }
        if (priority < 0 || priority > Integer.MAX_VALUE) {
            throw invalid("Priority must be between 0 and 2147483647.");
        }
        job.put("Priority", priority);
        jobs.put(region + "::" + jobId, job);
        return mapper.createObjectNode().put("JobId", jobId).put("Priority", priority);
    }

    public synchronized ObjectNode updateStatus(String jobId, String requested, String reason, String region) {
        ObjectNode job = require(jobId, region);
        String status = job.path("Status").asText();
        String key = region + "::" + jobId;
        if ("Ready".equals(requested) && "Suspended".equals(status)) {
            job.put("Status", "Ready");
            job.remove(List.of("SuspendedDate", "SuspendedCause"));
            putReason(job, reason);
            jobs.put(key, job);
            run(key, job);
        } else if ("Cancelled".equals(requested) && !TERMINAL.contains(status)) {
            job.put("Status", "Cancelled");
            job.put("TerminationDate", Instant.now().toString());
            putReason(job, reason);
            jobs.put(key, job);
        } else {
            throw invalid("Requested transition from " + status + " to " + requested + " is not allowed: job status "
                    + "forbidden.");
        }
        ObjectNode out = mapper.createObjectNode().put("JobId", jobId).put("Status", job.path("Status").asText());
        if (job.has("StatusUpdateReason")) {
            out.put("StatusUpdateReason", job.path("StatusUpdateReason").asText());
        }
        return out;
    }

    private static void putReason(ObjectNode job, String reason) {
        if (reason != null && !reason.isEmpty()) {
            job.put("StatusUpdateReason", reason);
        }
    }

    public ObjectNode tagging(String jobId, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", require(jobId, region).withArray("Tags").deepCopy());
        return out;
    }

    public synchronized void putTagging(String jobId, JsonNode tags, String region) {
        ObjectNode job = require(jobId, region);
        ArrayNode list = job.putArray("Tags");
        tags.forEach(list::add);
        jobs.put(region + "::" + jobId, job);
    }

    public synchronized void deleteTagging(String jobId, String region) {
        ObjectNode job = require(jobId, region);
        job.putArray("Tags");
        jobs.put(region + "::" + jobId, job);
    }

    private ObjectNode require(String jobId, String region) {
        return jobs.get(region + "::" + jobId).orElseThrow(() -> new AwsException("NotFoundException",
                "The job " + jobId + " was not found.", 404));
    }

    private boolean roleExists(String roleArn) {
        if (roleArn == null || !AwsArnUtils.isArnFor(roleArn, "iam") || !iam.isResolvable()) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iam.get().findRole(arn.accountId(), name).filter(role -> IamService.roleArnMatches(roleArn, role))
                .isPresent();
    }

    private static String bucketOf(String arnOrName) {
        if (AwsArnUtils.isArnFor(arnOrName, "s3")) {
            String resource = AwsArnUtils.parse(arnOrName).resource();
            int slash = resource.indexOf('/');
            return slash < 0 ? resource : resource.substring(0, slash);
        }
        return arnOrName;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequest", message, 400);
    }

    @Override
    public void clear() {
        jobs.clear();
    }
}
