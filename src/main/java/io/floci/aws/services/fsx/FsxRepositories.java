package io.floci.aws.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.fsx.FsxStore.ASSOCIATION;
import static io.floci.aws.services.fsx.FsxStore.TASK;
import static io.floci.aws.services.fsx.FsxStore.badRequest;
import static io.floci.aws.services.fsx.FsxStore.meta;
import static io.floci.aws.services.fsx.FsxStore.notFound;
import static io.floci.aws.services.fsx.FsxStore.now;
import static io.floci.aws.services.fsx.FsxStore.optional;
import static io.floci.aws.services.fsx.FsxStore.strings;
import static io.floci.aws.services.fsx.FsxStore.text;
import static io.floci.aws.services.fsx.FsxStore.view;

/**
 * Lustre data repository associations, which link a file system or cache directory to an S3 prefix (or, for a
 * cache, an NFS export), and the data repository tasks that move data between them. A task reads the S3 objects
 * under its associations' prefixes, counting what an import or export covers, and writes its report to S3; floci
 * holds no file data, so an export or release finds nothing to move.
 */
@ApplicationScoped
public class FsxRepositories {

    private static final Set<String> ENDED = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    private final FsxStore store;
    private final FsxFileSystems fileSystems;
    private final Instance<S3Service> s3;

    @Inject
    public FsxRepositories(FsxStore store, FsxFileSystems fileSystems, Instance<S3Service> s3) {
        this.store = store;
        this.fileSystems = fileSystems;
        this.s3 = s3;
    }

    // ---------------------------------------------------------------- associations

    public synchronized ObjectNode createDataRepositoryAssociation(JsonNode input, String region) {
        String fileSystemId = text(input, "FileSystemId");
        ObjectNode fileSystem = fileSystems.fileSystem(region, fileSystemId);
        String deployment = fileSystem.path("LustreConfiguration").path("DeploymentType").asText("");
        if (!"LUSTRE".equals(fileSystem.path("FileSystemType").asText())
                || !Set.of("PERSISTENT_2", "SCRATCH_2").contains(deployment)) {
            throw badRequest("Data repository associations are for Lustre PERSISTENT_2 and SCRATCH_2 file systems.");
        }
        if (fileSystem.path("LustreConfiguration").has("DataRepositoryConfiguration")) {
            throw badRequest("File system " + fileSystemId + " is linked to S3 through its ImportPath.");
        }
        String path = input.path("FileSystemPath").asText("/");
        if (!path.startsWith("/")) {
            throw badRequest("FileSystemPath must begin with a slash.");
        }
        List<ObjectNode> existing = associations(region, fileSystemId, null);
        if (existing.size() >= 8) {
            throw notFound("ServiceLimitExceeded", "A file system has at most 8 data repository associations.");
        }
        for (ObjectNode association : existing) {
            String other = association.path("FileSystemPath").asText();
            if (path.equals(other) || path.startsWith(trailing(other)) || other.startsWith(trailing(path))) {
                throw badRequest("FileSystemPath " + path + " overlaps the association " + association
                        .path("AssociationId").asText() + " at " + other + ".");
            }
        }
        String repository = text(input, "DataRepositoryPath");
        requireBucket(repository);
        ObjectNode association = association(region, fileSystemId, null);
        association.put("FileSystemPath", path).put("DataRepositoryPath", repository)
                .put("BatchImportMetaDataOnCreate", input.path("BatchImportMetaDataOnCreate").asBoolean(false))
                .put("ImportedFileChunkSize", input.path("ImportedFileChunkSize").asInt(1024));
        ObjectNode s3Config = association.putObject("S3");
        JsonNode s3Input = input.path("S3");
        s3Config.set("AutoImportPolicy", s3Input.has("AutoImportPolicy") ? s3Input.get("AutoImportPolicy").deepCopy()
                : store.mapper().createObjectNode().set("Events", store.mapper().createArrayNode()));
        s3Config.set("AutoExportPolicy", s3Input.has("AutoExportPolicy") ? s3Input.get("AutoExportPolicy").deepCopy()
                : store.mapper().createObjectNode().set("Events", store.mapper().createArrayNode()));
        association.set("Tags", store.tags(input.get("Tags")));
        store.transition(region, ASSOCIATION, association, "Lifecycle", "CREATING", "AVAILABLE");
        if (association.path("BatchImportMetaDataOnCreate").asBoolean()) {
            runTask(region, fileSystemId, null, "IMPORT_METADATA_FROM_REPOSITORY", List.of(),
                    store.mapper().createObjectNode().put("Enabled", false), null);
        }
        return wrap("Association", association);
    }

    /** A file cache's association with its S3 prefix or NFS export; returns its id. */
    String cacheAssociation(String region, ObjectNode cache, JsonNode input) {
        String cacheId = cache.path("FileCacheId").asText();
        String repository = text(input, "DataRepositoryPath");
        if (repository.startsWith("s3://")) {
            requireBucket(repository);
        } else if (!repository.startsWith("nfs://")) {
            throw badRequest("A file cache's DataRepositoryPath is an s3:// or nfs:// path.");
        }
        ObjectNode association = association(region, null, cacheId);
        association.put("FileCachePath", text(input, "FileCachePath")).put("DataRepositoryPath", repository);
        association.set("DataRepositorySubdirectories", input.path("DataRepositorySubdirectories").isArray()
                ? input.get("DataRepositorySubdirectories").deepCopy() : store.mapper().createArrayNode());
        if (input.has("NFS")) {
            association.set("NFS", input.get("NFS").deepCopy());
        }
        association.set("Tags", meta(cache).path("copyTags").asBoolean(false) ? cache.path("Tags").deepCopy()
                : store.mapper().createArrayNode());
        store.transition(region, ASSOCIATION, association, "Lifecycle", "CREATING", "AVAILABLE");
        return association.path("AssociationId").asText();
    }

    void dropAssociations(String region, String cacheId) {
        for (ObjectNode association : associations(region, null, cacheId)) {
            store.delete(region, ASSOCIATION, association.path("AssociationId").asText());
        }
    }

    private ObjectNode association(String region, String fileSystemId, String cacheId) {
        String id = store.id("dra");
        ObjectNode association = store.record("AssociationId", id);
        association.put("ResourceARN", store.arn(region, "association/" + (fileSystemId != null ? fileSystemId
                : cacheId) + "/" + id)).put("Lifecycle", "CREATING").put("CreationTime", now());
        if (fileSystemId != null) {
            association.put("FileSystemId", fileSystemId);
        }
        if (cacheId != null) {
            association.put("FileCacheId", cacheId);
        }
        return association;
    }

    public synchronized ObjectNode describeDataRepositoryAssociations(JsonNode input, String region) {
        List<String> ids = strings(input.path("AssociationIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(associationRecord(region, id));
            }
        } else {
            for (ObjectNode association : store.list(region, ASSOCIATION, record -> true)) {
                if (FsxStore.filtered(input.path("Filters"), Map.of(
                        "file-system-id", association.path("FileSystemId").asText(""),
                        "file-cache-id", association.path("FileCacheId").asText(""),
                        "data-repository-type", association.path("DataRepositoryPath").asText().startsWith("nfs://")
                                ? "NFS" : "S3"))) {
                    items.add(association);
                }
            }
        }
        return store.page(items, input, "Associations");
    }

    public synchronized ObjectNode updateDataRepositoryAssociation(JsonNode input, String region) {
        ObjectNode association = associationRecord(region, text(input, "AssociationId"));
        if (input.hasNonNull("ImportedFileChunkSize")) {
            association.put("ImportedFileChunkSize", input.get("ImportedFileChunkSize").asInt());
        }
        if (input.has("S3")) {
            ObjectNode s3Config = association.withObject("/S3");
            input.get("S3").fields().forEachRemaining(entry -> s3Config.set(entry.getKey(), entry.getValue()
                    .deepCopy()));
        }
        store.transition(region, ASSOCIATION, association, "Lifecycle", "UPDATING", "AVAILABLE");
        return wrap("Association", association);
    }

    public synchronized ObjectNode deleteDataRepositoryAssociation(JsonNode input, String region) {
        String id = text(input, "AssociationId");
        ObjectNode association = associationRecord(region, id);
        if (association.hasNonNull("FileCacheId")) {
            throw badRequest("A file cache's associations go with the cache.");
        }
        if (!input.hasNonNull("DeleteDataInFileSystem")) {
            throw badRequest("DeleteDataInFileSystem is required.");
        }
        store.transition(region, ASSOCIATION, association, "Lifecycle", "DELETING", null);
        ObjectNode response = store.mapper().createObjectNode();
        response.put("AssociationId", id).put("Lifecycle", "DELETING")
                .put("DeleteDataInFileSystem", input.get("DeleteDataInFileSystem").asBoolean());
        return response;
    }

    private ObjectNode associationRecord(String region, String id) {
        return store.get(region, ASSOCIATION, id).orElseThrow(() -> notFound("DataRepositoryAssociationNotFound",
                "Data repository association " + id + " does not exist."));
    }

    private List<ObjectNode> associations(String region, String fileSystemId, String cacheId) {
        return store.list(region, ASSOCIATION, association -> fileSystemId != null
                ? fileSystemId.equals(association.path("FileSystemId").asText(null))
                : cacheId.equals(association.path("FileCacheId").asText(null)));
    }

    // ---------------------------------------------------------------- tasks

    public synchronized ObjectNode createDataRepositoryTask(JsonNode input, String region) {
        String fileSystemId = text(input, "FileSystemId");
        ObjectNode fileSystem = fileSystems.fileSystem(region, fileSystemId);
        if (!"LUSTRE".equals(fileSystem.path("FileSystemType").asText())) {
            throw badRequest("Data repository tasks are for Lustre file systems.");
        }
        if (!"AVAILABLE".equals(fileSystem.path("Lifecycle").asText())) {
            throw badRequest("File system " + fileSystemId + " is " + fileSystem.path("Lifecycle").asText() + ".");
        }
        JsonNode report = input.path("Report");
        if (!report.has("Enabled")) {
            throw badRequest("Report is required.");
        }
        if (report.path("Enabled").asBoolean() && optional(report, "Path") != null) {
            requireBucket(report.path("Path").asText());
        }
        ObjectNode task = runTask(region, fileSystemId, null, text(input, "Type"), strings(input.path("Paths")),
                report, input.get("Tags"));
        if (input.hasNonNull("CapacityToRelease")) {
            task.put("CapacityToRelease", input.get("CapacityToRelease").asLong());
        }
        if (input.has("ReleaseConfiguration")) {
            task.set("ReleaseConfiguration", input.get("ReleaseConfiguration").deepCopy());
        }
        store.put(region, TASK, task.path("TaskId").asText(), task);
        return wrap("DataRepositoryTask", task);
    }

    /**
     * Starts a task over a file system's repositories: it counts the S3 objects under the associations' prefixes an
     * import covers, writes the report, and runs PENDING, EXECUTING and SUCCEEDED, a transition apart.
     */
    private ObjectNode runTask(String region, String fileSystemId, String cacheId, String type, List<String> paths,
                               JsonNode report, JsonNode tags) {
        ObjectNode fileSystem = fileSystems.fileSystem(region, fileSystemId);
        List<String> repositories = new ArrayList<>();
        for (ObjectNode association : associations(region, fileSystemId, null)) {
            repositories.add(association.path("DataRepositoryPath").asText());
        }
        JsonNode legacy = fileSystem.path("LustreConfiguration").path("DataRepositoryConfiguration");
        if (!legacy.isMissingNode()) {
            repositories.add(legacy.path("ImportPath").asText());
        }
        if (repositories.isEmpty()) {
            throw badRequest("File system " + fileSystemId + " is not linked to a data repository.");
        }
        for (ObjectNode running : store.list(region, TASK, task -> fileSystemId.equals(task.path("FileSystemId")
                .asText()) && !ENDED.contains(task.path("Lifecycle").asText()))) {
            throw notFound("DataRepositoryTaskExecuting", "Task " + running.path("TaskId").asText()
                    + " is running on " + fileSystemId + ".");
        }
        long count = 0;
        if ("IMPORT_METADATA_FROM_REPOSITORY".equals(type)) {
            for (String repository : repositories) {
                count += countObjects(repository, paths);
            }
        }
        String id = store.id("task");
        ObjectNode task = store.record("TaskId", id);
        double now = now();
        task.put("Lifecycle", "EXECUTING").put("Type", type).put("CreationTime", now).put("StartTime", now)
                .put("EndTime", now + store.transitionMillis() / 1000.0)
                .put("ResourceARN", store.arn(region, "task/" + id)).put("FileSystemId", fileSystemId);
        ArrayNode taskPaths = task.putArray("Paths");
        paths.forEach(taskPaths::add);
        task.putObject("Status").put("TotalCount", count).put("SucceededCount", count).put("FailedCount", 0)
                .put("LastUpdatedTime", now).put("ReleasedCapacity", 0);
        ObjectNode reportOut = task.putObject("Report");
        reportOut.put("Enabled", report.path("Enabled").asBoolean(false));
        if (report.path("Enabled").asBoolean(false)) {
            String path = report.path("Path").asText(repositories.get(0));
            reportOut.put("Path", path).put("Format", report.path("Format").asText("REPORT_CSV_20191124"))
                    .put("Scope", report.path("Scope").asText("FAILED_FILES_ONLY"));
            writeReport(path, id);
        }
        task.set("Tags", store.tags(tags));
        store.transition(region, TASK, task, "Lifecycle", "EXECUTING", "SUCCEEDED");
        return task;
    }

    public synchronized ObjectNode describeDataRepositoryTasks(JsonNode input, String region) {
        List<String> ids = strings(input.path("TaskIds"));
        List<ObjectNode> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String id : ids) {
                items.add(task(region, id));
            }
        } else {
            for (ObjectNode task : store.list(region, TASK, record -> true)) {
                if (FsxStore.filtered(input.path("Filters"), Map.of(
                        "file-system-id", task.path("FileSystemId").asText(""),
                        "task-lifecycle", task.path("Lifecycle").asText(),
                        "file-cache-id", task.path("FileCacheId").asText(""),
                        "data-repository-association-id", ""))) {
                    items.add(task);
                }
            }
        }
        return store.page(items, input, "DataRepositoryTasks");
    }

    public synchronized ObjectNode cancelDataRepositoryTask(JsonNode input, String region) {
        String id = text(input, "TaskId");
        ObjectNode task = task(region, id);
        if (ENDED.contains(task.path("Lifecycle").asText())) {
            throw notFound("DataRepositoryTaskEnded", "Task " + id + " has already ended.");
        }
        task.put("EndTime", now());
        store.transition(region, TASK, task, "Lifecycle", "CANCELING", "CANCELED");
        ObjectNode response = store.mapper().createObjectNode();
        response.put("Lifecycle", "CANCELING").put("TaskId", id);
        return response;
    }

    private ObjectNode task(String region, String id) {
        return store.get(region, TASK, id).orElseThrow(() -> notFound("DataRepositoryTaskNotFound",
                "Data repository task " + id + " does not exist."));
    }

    // ---------------------------------------------------------------- S3

    private void requireBucket(String s3Path) {
        if (!s3Path.startsWith("s3://")) {
            throw badRequest(s3Path + " is not an S3 path.");
        }
        String bucket = s3Path.substring(5).split("/", 2)[0];
        if (s3.isResolvable()) {
            try {
                s3.get().listObjects(bucket, "", null, 1);
            } catch (AwsException e) {
                throw badRequest("The S3 bucket " + bucket + " does not exist or is not accessible.");
            }
        }
    }

    private long countObjects(String repository, List<String> paths) {
        if (!s3.isResolvable() || !repository.startsWith("s3://")) {
            return 0;
        }
        String[] parts = repository.substring(5).split("/", 2);
        String prefix = parts.length > 1 ? parts[1] : "";
        long count = 0;
        try {
            for (S3Object object : s3.get().listObjects(parts[0], prefix, null, 1000)) {
                String relative = object.getKey().substring(prefix.length());
                boolean covered = paths.isEmpty() || paths.stream().anyMatch(path -> ("/" + relative.replaceFirst(
                        "^/", "")).startsWith(path.startsWith("/") ? path : "/" + path));
                if (covered && !object.getKey().endsWith("/")) {
                    count++;
                }
            }
        } catch (AwsException e) {
            return 0;
        }
        return count;
    }

    private void writeReport(String path, String taskId) {
        if (!s3.isResolvable()) {
            return;
        }
        String[] parts = path.substring(5).split("/", 2);
        String prefix = parts.length > 1 && !parts[1].isEmpty() ? (parts[1].endsWith("/") ? parts[1] : parts[1] + "/")
                : "";
        try {
            s3.get().putObject(parts[0], prefix + taskId + "/failures/report.csv",
                    "FilePath,Result,ErrorCode\n".getBytes(StandardCharsets.UTF_8), "text/csv", Map.of());
        } catch (AwsException e) {
            throw badRequest("The report path " + path + " cannot be written.");
        }
    }

    private static String trailing(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private ObjectNode wrap(String field, ObjectNode record) {
        ObjectNode response = store.mapper().createObjectNode();
        response.set(field, view(record));
        return response;
    }
}
