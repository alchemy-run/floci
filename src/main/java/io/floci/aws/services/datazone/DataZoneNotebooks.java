package io.floci.aws.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.conflict;
import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.notFound;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.oneOf;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * Amazon SageMaker Unified Studio notebooks in a DataZone project: notebooks and their cells, imports from S3,
 * exports to the project's S3 location as Jupyter ({@code IPYNB}) documents, and runs.
 * <p>
 * Notebook runs need SageMaker Unified Studio compute, which floci does not provide: a run is queued, starts and
 * then fails with that reason. Git syncs need the Git connection's repository and fail the same way. Exports to
 * {@code PDF} need a notebook renderer and fail; {@code IPYNB} exports are written.
 */
@ApplicationScoped
public class DataZoneNotebooks {

    static final String NOTEBOOKS = "notebooks";
    static final String RUNS = "notebook-runs";
    static final String EXPORTS = "notebook-exports";
    private static final String NO_COMPUTE = "Notebook compute is not available in floci.";

    private final DataZoneStore store;
    private final DataZoneService core;
    private final Instance<S3Service> s3;
    private final long transitionMillis;

    @Inject
    public DataZoneNotebooks(DataZoneStore store, DataZoneService core, Instance<S3Service> s3,
                             EmulatorConfig config) {
        this.store = store;
        this.core = core;
        this.s3 = s3;
        this.transitionMillis = config.services().datazone().transitionMillis();
    }

    private String domainId(ObjectNode input, String region, String operation, boolean write) {
        String identifier = text(input, "domainIdentifier", 64);
        return (write ? core.available(region, identifier, operation) : core.domain(region, identifier, operation))
                .path("id").asText();
    }

    // ---------------------------------------------------------------- notebooks

    private ObjectNode newNotebook(String region, String domainId, String projectId, String name, String creator,
                                   JsonNode input, ArrayNode cells) {
        for (ObjectNode existing : store.list(region, domainId, NOTEBOOKS)) {
            if (name.equals(existing.path("name").asText()) && projectId.equals(existing.path("owningProjectId")
                    .asText())) {
                throw conflict("A notebook named " + name + " already exists in project " + projectId + ".");
            }
        }
        double now = now();
        ObjectNode notebook = store.object();
        notebook.put("id", store.id());
        notebook.put("name", name);
        notebook.put("owningProjectId", projectId);
        notebook.put("domainId", domainId);
        notebook.set("cellOrder", cells);
        notebook.put("status", "ACTIVE");
        notebook.put("type", oneOf(input, "type", Set.of("DATA", "SQL"), "DATA"));
        if (present(input, "description")) {
            notebook.put("description", input.get("description").asText());
        }
        notebook.put("createdAt", now);
        notebook.put("createdBy", creator);
        notebook.put("updatedAt", now);
        notebook.put("updatedBy", creator);
        for (String field : List.of("metadata", "parameters")) {
            if (input.path(field).isObject()) {
                notebook.set(field, input.get(field).deepCopy());
            }
        }
        store.put(region, domainId, NOTEBOOKS, notebook.path("id").asText(), notebook);
        return notebook;
    }

    public synchronized ObjectNode createNotebook(ObjectNode input, String region) {
        String domainId = domainId(input, region, "CreateNotebook", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "CreateNotebook");
        return store.view(newNotebook(region, domainId, projectId, text(input, "name", 256), creator, input,
                store.array()));
    }

    private ObjectNode notebook(String region, String domainId, String id) {
        return store.require(region, domainId, NOTEBOOKS, id, "Notebook");
    }

    public synchronized ObjectNode getNotebook(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetNotebook", false);
        return store.view(notebook(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode updateNotebook(ObjectNode input, String region) {
        String domainId = domainId(input, region, "UpdateNotebook", true);
        ObjectNode notebook = notebook(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, notebook.path("owningProjectId").asText(),
                "UpdateNotebook");
        for (String field : List.of("name", "description")) {
            if (present(input, field)) {
                notebook.put(field, input.get(field).asText());
            }
        }
        if (present(input, "status")) {
            notebook.put("status", oneOf(input, "status", Set.of("ACTIVE", "ARCHIVED"), null));
        }
        if (present(input, "type")) {
            notebook.put("type", oneOf(input, "type", Set.of("DATA", "SQL"), null));
        }
        for (String field : List.of("cellOrder", "metadata", "parameters", "environmentConfiguration")) {
            if (input.has(field)) {
                notebook.set(field, input.get(field).deepCopy());
            }
        }
        notebook.put("updatedAt", now());
        notebook.put("updatedBy", editor);
        store.put(region, domainId, NOTEBOOKS, notebook.path("id").asText(), notebook);
        return store.view(notebook);
    }

    public synchronized ObjectNode deleteNotebook(ObjectNode input, String region) {
        String domainId = domainId(input, region, "DeleteNotebook", true);
        ObjectNode notebook = notebook(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, notebook.path("owningProjectId").asText(), "DeleteNotebook");
        for (ObjectNode run : store.list(region, domainId, RUNS)) {
            settleRun(region, domainId, run);
            if (notebook.path("id").asText().equals(run.path("notebookId").asText())
                    && Set.of("QUEUED", "STARTING", "RUNNING", "STOPPING").contains(run.path("status").asText())) {
                throw conflict("Notebook " + notebook.path("id").asText() + " has run " + run.path("id").asText()
                        + " in progress.");
            }
        }
        store.delete(region, domainId, NOTEBOOKS, notebook.path("id").asText());
        return store.object();
    }

    public synchronized ObjectNode listNotebooks(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListNotebooks", false);
        String projectId = text(input, "owningProjectIdentifier", 64);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode notebook : store.list(region, domainId, NOTEBOOKS)) {
            if (projectId.equals(notebook.path("owningProjectId").asText())
                    && (!present(input, "status") || input.get("status").asText().equals(notebook.path("status")
                    .asText()))
                    && (!present(input, "type") || input.get("type").asText().equals(notebook.path("type")
                    .asText()))) {
                items.add(store.view(notebook, "id", "name", "owningProjectId", "domainId", "status", "type",
                        "description", "createdAt", "createdBy", "updatedAt", "updatedBy"));
            }
        }
        String key = "NAME".equals(input.path("sortBy").asText()) ? "name"
                : "UPDATED_AT".equals(input.path("sortBy").asText()) ? "updatedAt" : "createdAt";
        Comparator<ObjectNode> order = "name".equals(key) ? Comparator.comparing(item -> item.path(key).asText())
                : Comparator.comparing(item -> item.path(key).asDouble());
        items.sort("DESCENDING".equals(input.path("sortOrder").asText()) ? order.reversed() : order);
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- imports, syncs and exports

    private static String[] s3Location(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            throw invalid("The source location must be an s3:// URI.");
        }
        String path = uri.substring("s3://".length());
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw invalid("The source location must name an object.");
        }
        return new String[] {path.substring(0, slash), path.substring(slash + 1)};
    }

    private byte[] read(String bucket, String key) {
        if (!s3.isResolvable()) {
            throw notFound("Amazon S3 is not enabled.");
        }
        try {
            return s3.get().getObject(bucket, key).getData();
        } catch (AwsException e) {
            throw invalid("Cannot read s3://" + bucket + "/" + key + ": " + e.getMessage());
        }
    }

    public synchronized ObjectNode startNotebookImport(ObjectNode input, String region) {
        String domainId = domainId(input, region, "StartNotebookImport", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "StartNotebookImport");
        JsonNode source = input.path("sourceLocation");
        ArrayNode cells = store.array();
        if (source.hasNonNull("s3")) {
            String[] location = s3Location(source.get("s3").asText());
            byte[] content = read(location[0], location[1]);
            try {
                JsonNode document = store.mapper().readTree(new String(content, StandardCharsets.UTF_8));
                for (JsonNode cell : document.path("cells")) {
                    cells.addObject();
                }
            } catch (JsonProcessingException e) {
                throw invalid("s3://" + location[0] + "/" + location[1] + " is not a Jupyter notebook.");
            }
        } else if (source.has("s3Files")) {
            String bucket = text(source.get("s3Files"), "bucket", 63);
            for (JsonNode file : source.get("s3Files").path("fileList")) {
                read(bucket, file.asText());
                cells.addObject();
            }
        } else {
            throw invalid("sourceLocation must name s3 or s3Files.");
        }
        ObjectNode notebook = newNotebook(region, domainId, projectId, text(input, "name", 256), creator, input,
                cells);
        ObjectNode response = store.view(notebook, "domainId", "owningProjectId", "name", "description", "type",
                "createdAt", "createdBy");
        response.put("notebookId", notebook.path("id").asText());
        response.put("status", "SUCCEEDED");
        response.set("sourceLocation", source.deepCopy());
        return response;
    }

    public synchronized ObjectNode startNotebookSync(ObjectNode input, String region) {
        String domainId = domainId(input, region, "StartNotebookSync", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "StartNotebookSync");
        ObjectNode notebook;
        if (present(input, "notebookId")) {
            notebook = notebook(region, domainId, input.get("notebookId").asText());
        } else {
            notebook = newNotebook(region, domainId, projectId, text(input, "name", 256), creator, input,
                    store.array());
        }
        notebook.put("status", "SYNC_FAILED");
        notebook.putObject("error").put("message", "Git repositories are not reachable from floci.");
        if (input.has("gitMetadata")) {
            notebook.set("gitMetadata", input.get("gitMetadata").deepCopy());
        }
        store.put(region, domainId, NOTEBOOKS, notebook.path("id").asText(), notebook);
        ObjectNode response = store.view(notebook, "domainId", "owningProjectId", "name", "description",
                "gitMetadata", "createdAt", "createdBy");
        response.put("notebookId", notebook.path("id").asText());
        response.put("status", "SYNC_FAILED");
        response.set("sourceLocation", input.path("sourceLocation").deepCopy());
        return response;
    }

    /** The project's S3 location: the {@code s3BucketPath} an environment of the project provisioned. */
    private String projectLocation(String region, String domainId, String projectId) {
        for (ObjectNode environment : store.list(region, domainId, DataZoneEnvironments.ENVIRONMENTS)) {
            if (!projectId.equals(environment.path("projectId").asText())) {
                continue;
            }
            for (JsonNode resource : environment.path("provisionedResources")) {
                if ("s3BucketPath".equals(resource.path("name").asText())) {
                    return resource.path("value").asText();
                }
            }
        }
        return null;
    }

    public synchronized ObjectNode startNotebookExport(ObjectNode input, String region) {
        String domainId = domainId(input, region, "StartNotebookExport", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "StartNotebookExport");
        ObjectNode notebook = notebook(region, domainId, text(input, "notebookIdentifier", 64));
        String format = oneOf(input, "fileFormat", Set.of("PDF", "IPYNB"), null);
        double now = now();
        ObjectNode export = store.object();
        export.put("id", store.id());
        export.put("domainId", domainId);
        export.put("owningProjectId", projectId);
        export.put("notebookId", notebook.path("id").asText());
        export.put("fileFormat", format);
        export.put("createdAt", now);
        export.put("createdBy", creator);
        String location = projectLocation(region, domainId, projectId);
        String failure = null;
        if ("PDF".equals(format)) {
            failure = "PDF export needs a notebook renderer, which floci does not provide.";
        } else if (location == null || !s3.isResolvable()) {
            failure = "Project " + projectId + " has no S3 location to export to.";
        } else {
            String[] target = s3Location(location.replaceAll("/+$", "") + "/notebooks/exports/"
                    + export.path("id").asText() + "/" + notebook.path("name").asText() + ".ipynb");
            ObjectNode document = store.object();
            ArrayNode cells = document.putArray("cells");
            notebook.path("cellOrder").forEach(cell -> cells.addObject().put("cell_type", "code")
                    .put("source", "").putArray("outputs"));
            document.putObject("metadata").set("datazone", store.object().put("notebookId",
                    notebook.path("id").asText()).put("type", notebook.path("type").asText()));
            document.put("nbformat", 4);
            document.put("nbformat_minor", 5);
            try {
                s3.get().putObject(target[0], target[1], document.toString().getBytes(StandardCharsets.UTF_8),
                        "application/x-ipynb+json", Map.of());
                export.putObject("outputLocation").putObject("s3").put("uri", "s3://" + target[0] + "/"
                        + target[1]);
            } catch (AwsException e) {
                failure = e.getMessage();
            }
        }
        if (failure != null) {
            export.put("status", "FAILED");
            export.putObject("error").put("message", failure.length() > 256 ? failure.substring(0, 256) : failure);
        } else {
            export.put("status", "SUCCEEDED");
        }
        export.put("completedAt", now);
        store.put(region, domainId, EXPORTS, export.path("id").asText(), export);
        ObjectNode response = store.view(export, "id", "domainId", "owningProjectId", "notebookId", "fileFormat",
                "createdAt", "createdBy");
        response.put("status", "IN_PROGRESS");
        return response;
    }

    public synchronized ObjectNode getNotebookExport(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetNotebookExport", false);
        return store.view(store.require(region, domainId, EXPORTS, text(input, "identifier", 64),
                "Notebook export"));
    }

    // ---------------------------------------------------------------- runs

    public synchronized ObjectNode startNotebookRun(ObjectNode input, String region) {
        String domainId = domainId(input, region, "StartNotebookRun", true);
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "StartNotebookRun");
        ObjectNode notebook = notebook(region, domainId, text(input, "notebookIdentifier", 64));
        if (!projectId.equals(notebook.path("owningProjectId").asText())) {
            throw invalid("Notebook " + notebook.path("id").asText() + " is not owned by project " + projectId + ".");
        }
        double now = now();
        ObjectNode run = store.object();
        run.put("id", store.id());
        run.put("domainId", domainId);
        run.put("owningProjectId", projectId);
        run.put("notebookId", notebook.path("id").asText());
        if (present(input, "scheduleIdentifier")) {
            run.put("scheduleId", input.get("scheduleIdentifier").asText());
        }
        run.put("status", "QUEUED");
        run.set("cellOrder", notebook.path("cellOrder").deepCopy());
        for (String field : List.of("metadata", "parameters", "computeConfiguration", "networkConfiguration",
                "timeoutConfiguration", "notificationConfiguration", "triggerSource")) {
            if (input.has(field)) {
                run.set(field, input.get(field).deepCopy());
            }
        }
        if (notebook.has("environmentConfiguration")) {
            run.set("environmentConfiguration", notebook.get("environmentConfiguration").deepCopy());
        }
        run.put("createdAt", now);
        run.put("createdBy", creator);
        run.put("updatedAt", now);
        run.put("updatedBy", creator);
        run.putObject("_").put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.put(region, domainId, RUNS, run.path("id").asText(), run);
        ObjectNode view = store.view(run);
        settleRun(region, domainId, run);
        return view;
    }

    /** A queued run starts, then fails: floci has no notebook compute to run its cells on. */
    private ObjectNode settleRun(String region, String domainId, ObjectNode run) {
        if ("QUEUED".equals(run.path("status").asText())
                && System.currentTimeMillis() >= run.path("_").path("readyAt").asLong()) {
            double now = now();
            run.put("startedAt", now);
            run.put("completedAt", now);
            run.put("status", "FAILED");
            run.putObject("error").put("message", NO_COMPUTE);
            run.put("updatedAt", now);
            store.put(region, domainId, RUNS, run.path("id").asText(), run);
        }
        return run;
    }

    private ObjectNode run(String region, String domainId, String id) {
        return settleRun(region, domainId, store.require(region, domainId, RUNS, id, "Notebook run"));
    }

    public synchronized ObjectNode getNotebookRun(ObjectNode input, String region) {
        String domainId = domainId(input, region, "GetNotebookRun", false);
        return store.view(run(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode stopNotebookRun(ObjectNode input, String region) {
        String domainId = domainId(input, region, "StopNotebookRun", true);
        ObjectNode run = run(region, domainId, text(input, "identifier", 64));
        String editor = core.requireMember(region, domainId, run.path("owningProjectId").asText(),
                "StopNotebookRun");
        if (!Set.of("QUEUED", "STARTING", "RUNNING").contains(run.path("status").asText())) {
            throw conflict("Notebook run " + run.path("id").asText() + " is " + run.path("status").asText() + ".");
        }
        double now = now();
        run.put("status", "STOPPED");
        run.put("completedAt", now);
        run.put("updatedAt", now);
        run.put("updatedBy", editor);
        store.put(region, domainId, RUNS, run.path("id").asText(), run);
        return store.view(run, "id", "domainId", "owningProjectId", "status");
    }

    public synchronized ObjectNode listNotebookRuns(ObjectNode input, String region) {
        String domainId = domainId(input, region, "ListNotebookRuns", false);
        String projectId = text(input, "owningProjectIdentifier", 64);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, domainId, RUNS)) {
            ObjectNode run = settleRun(region, domainId, stored);
            if (projectId.equals(run.path("owningProjectId").asText())
                    && (!present(input, "notebookIdentifier") || input.get("notebookIdentifier").asText()
                    .equals(run.path("notebookId").asText()))
                    && (!present(input, "status") || input.get("status").asText().equals(run.path("status")
                    .asText()))
                    && (!present(input, "scheduleIdentifier") || input.get("scheduleIdentifier").asText()
                    .equals(run.path("scheduleId").asText()))) {
                items.add(store.view(run, "id", "domainId", "owningProjectId", "notebookId", "scheduleId", "status",
                        "triggerSource", "createdAt", "createdBy", "updatedAt", "updatedBy", "startedAt",
                        "completedAt"));
            }
        }
        Comparator<ObjectNode> order = Comparator.comparing(item -> item.path("createdAt").asDouble());
        items.sort("ASCENDING".equals(optional(input, "sortOrder")) ? order : order.reversed());
        return store.page(items, input, "items", item -> item.path("id").asText());
    }
}
