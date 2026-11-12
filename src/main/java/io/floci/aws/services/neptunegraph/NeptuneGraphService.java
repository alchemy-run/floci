package io.floci.aws.services.neptunegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Amazon Neptune Analytics. A graph is {@code CREATING} while its {@link NeptuneGraphEngine} container starts, then
 * {@code AVAILABLE}; it stops and starts, resets, updates and deletes through transitional states, taking a final
 * snapshot unless told not to. Snapshots hold a graph's nodes and edges, and restore into new graphs. Import tasks
 * load Gremlin CSV, openCypher CSV, N-Triples or Parquet files from S3 through their role, into a graph they create or
 * an empty one; export tasks write a graph's nodes and edges to S3 as openCypher CSV or Parquet. ExecuteQuery runs
 * openCypher, and running queries can be listed and cancelled. Private graph endpoints are kept per VPC.
 */
@ApplicationScoped
public class NeptuneGraphService {

    private static final Logger LOG = Logger.getLogger(NeptuneGraphService.class);
    private static final Pattern GRAPH_NAME = Pattern.compile("[a-z](?:[a-z0-9]|-(?!-)){0,62}");
    private static final Set<Integer> MEMORY = Set.of(16, 32, 64, 128, 256, 384, 512, 768, 1024, 2048, 3072, 4096);
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "DELETED");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** A query running on a graph. */
    record Running(String id, String graph, String query, long started, boolean[] cancelling) {
    }

    private final NeptuneGraphStore store;
    private final ObjectMapper mapper;
    private final NeptuneGraphEngine engine;
    private final Provider<S3Service> s3;
    private final Provider<IamService> iam;
    private final Provider<FlociDuckClient> duck;
    private final Vertx vertx;
    private final Map<String, Running> queries = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "neptunegraph-tasks");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public NeptuneGraphService(NeptuneGraphStore store, NeptuneGraphEngine engine, Provider<S3Service> s3,
                               Provider<IamService> iam, Provider<FlociDuckClient> duck, Vertx vertx) {
        this.store = store;
        this.mapper = store.mapper();
        this.engine = engine;
        this.s3 = s3;
        this.iam = iam;
        this.duck = duck;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Runs work after the transition delay, off the event loop. */
    private void later(Runnable task) {
        vertx.setTimer(Math.max(1, store.config().transitionMillis()), id -> worker.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warnv("Neptune Analytics work failed: {0}", e.getMessage());
            }
        }));
    }

    private static String id(String prefix) {
        return prefix + NeptuneGraphStore.random(10, ALPHABET);
    }

    private static String required(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw NeptuneGraphStore.validation(member + " is required.");
        }
        return value;
    }

    private static ObjectNode view(ObjectNode record) {
        return NeptuneGraphStore.view(record);
    }

    // ================================================================ graphs

    /** A graph by its id, or by its name. */
    ObjectNode graph(String region, String identifier) {
        ObjectNode graph = store.get(region, NeptuneGraphStore.GRAPH, identifier).orElse(null);
        if (graph == null) {
            for (ObjectNode candidate : store.list(region, NeptuneGraphStore.GRAPH)) {
                if (identifier.equals(candidate.path("name").asText())) {
                    graph = candidate;
                }
            }
        }
        if (graph == null) {
            throw NeptuneGraphStore.notFound("The graph " + identifier + " doesn't exist.");
        }
        return graph;
    }

    private void save(String account, String region, ObjectNode graph) {
        store.putForAccount(account, region, NeptuneGraphStore.GRAPH, graph.path("id").asText(), graph);
    }

    private ObjectNode reload(String account, String region, String graphId) {
        return store.getForAccount(account, region, NeptuneGraphStore.GRAPH, graphId).orElse(null);
    }

    private void status(String account, String region, String graphId, String status, String reason) {
        ObjectNode graph = reload(account, region, graphId);
        if (graph != null) {
            graph.put("status", status);
            if (reason == null) {
                graph.remove("statusReason");
            } else {
                graph.put("statusReason", reason);
            }
            save(account, region, graph);
        }
    }

    private static void checkGraphSettings(JsonNode in, boolean create) {
        if (create || in.has("provisionedMemory")) {
            int memory = in.path("provisionedMemory").asInt(-1);
            if (!MEMORY.contains(memory)) {
                throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "provisionedMemory must be one of 16, 32, 64, "
                        + "128, 256, 384, 512, 768, 1024, 2048, 3072 or 4096 m-NCUs.");
            }
        }
        if (in.has("replicaCount") && (in.get("replicaCount").asInt() < 0 || in.get("replicaCount").asInt() > 2)) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "replicaCount must be from 0 to 2.");
        }
        int dimension = in.path("vectorSearchConfiguration").path("dimension").asInt(1);
        if (dimension < 1 || dimension > 65535) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "The vector dimension must be from 1 to 65535.");
        }
    }

    private ObjectNode newGraph(JsonNode in, String region, int memory) {
        String name = required(in, "graphName");
        if (!GRAPH_NAME.matcher(name).matches() || name.endsWith("-")) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "graphName must start with a lowercase letter and "
                    + "have up to 63 lowercase letters, digits and single hyphens.");
        }
        for (ObjectNode existing : store.list(region, NeptuneGraphStore.GRAPH)) {
            if (name.equals(existing.path("name").asText())) {
                throw NeptuneGraphStore.conflict("A graph named " + name + " already exists.");
            }
        }
        String id = id("g-");
        ObjectNode graph = store.record();
        graph.put("id", id);
        graph.put("name", name);
        graph.put("arn", store.arn(region, "graph/" + id));
        graph.put("status", "CREATING");
        graph.put("createTime", NeptuneGraphStore.epoch());
        graph.put("provisionedMemory", memory);
        graph.put("endpoint", id + "." + region + ".neptune-graph." + NeptuneGraphStore.DNS_SUFFIX);
        graph.put("publicConnectivity", in.path("publicConnectivity").asBoolean(false));
        if (in.path("vectorSearchConfiguration").isObject()) {
            graph.set("vectorSearchConfiguration", in.get("vectorSearchConfiguration").deepCopy());
        }
        graph.put("replicaCount", in.path("replicaCount").asInt(1));
        NeptuneGraphStore.copy(in, graph, "kmsKeyIdentifier");
        graph.put("deletionProtection", in.path("deletionProtection").asBoolean(true));
        graph.put("buildNumber", "1");
        NeptuneGraphStore.addTags(graph, in.path("tags"));
        store.put(region, NeptuneGraphStore.GRAPH, id, graph);
        return graph;
    }

    /** Starts a graph's engine, then loads a snapshot or import into it, then makes it available. */
    private void provision(String account, String region, String graphId, Runnable afterStart) {
        later(() -> {
            ObjectNode graph = reload(account, region, graphId);
            if (graph == null) {
                return;
            }
            try {
                if (engine.enabled()) {
                    RequestScopes.runAs(account, region, () -> engine.start(account, region, graphId,
                            graph.path("provisionedMemory").asInt(16)));
                }
                if (afterStart != null) {
                    afterStart.run();
                }
                ObjectNode current = reload(account, region, graphId);
                if (current != null && !"FAILED".equals(current.path("status").asText())) {
                    status(account, region, graphId, "AVAILABLE", null);
                }
            } catch (RuntimeException e) {
                status(account, region, graphId, "FAILED", "The graph couldn't start: " + e.getMessage());
            }
        });
    }

    public ObjectNode createGraph(JsonNode in, String region) {
        checkGraphSettings(in, true);
        ObjectNode graph = newGraph(in, region, in.path("provisionedMemory").asInt());
        provision(store.account(), region, graph.path("id").asText(), null);
        return view(graph);
    }

    public ObjectNode getGraph(JsonNode in, String region) {
        return view(graph(region, required(in, "graphIdentifier")));
    }

    public ObjectNode listGraphs(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode graph : store.list(region, NeptuneGraphStore.GRAPH)) {
            ObjectNode summary = mapper.createObjectNode();
            NeptuneGraphStore.copy(graph, summary, "id", "name", "arn", "status", "provisionedMemory",
                    "publicConnectivity", "endpoint", "replicaCount", "kmsKeyIdentifier", "deletionProtection");
            items.add(summary);
        }
        ObjectNode out = mapper.createObjectNode();
        NeptuneGraphStore.page(items, in, out, "graphs", 100);
        return out;
    }

    private static void available(ObjectNode graph) {
        String status = graph.path("status").asText();
        if (!"AVAILABLE".equals(status)) {
            throw NeptuneGraphStore.conflict("The graph " + graph.path("id").asText() + " is " + status + ".");
        }
    }

    public ObjectNode updateGraph(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        available(graph);
        checkGraphSettings(in, false);
        NeptuneGraphStore.copy(in, graph, "publicConnectivity", "provisionedMemory", "deletionProtection");
        graph.put("status", "UPDATING");
        String account = store.account();
        save(account, region, graph);
        String graphId = graph.path("id").asText();
        later(() -> status(account, region, graphId, "AVAILABLE", null));
        return view(graph);
    }

    public ObjectNode deleteGraph(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        if (graph.path("deletionProtection").asBoolean()) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "The graph " + graph.path("id").asText()
                    + " has deletion protection; turn it off with UpdateGraph first.");
        }
        String status = graph.path("status").asText();
        if (!Set.of("AVAILABLE", "STOPPED", "FAILED").contains(status)) {
            throw NeptuneGraphStore.conflict("The graph " + graph.path("id").asText() + " is " + status + ".");
        }
        boolean snapshot = !in.path("skipSnapshot").asBoolean(false) && !"FAILED".equals(status);
        String account = store.account();
        String graphId = graph.path("id").asText();
        String name = graph.path("name").asText();
        graph.put("status", "DELETING");
        save(account, region, graph);
        later(() -> {
            if (snapshot) {
                snapshot(account, region, graphId, name + "-final-snapshot-" + System.currentTimeMillis() / 1000,
                        null, false);
            }
            engine.remove(account, region, graphId);
            for (ObjectNode endpoint : store.listForAccount(account, region, NeptuneGraphStore.ENDPOINT)) {
                if (graphId.equals(endpoint.path("_").path("graphId").asText())) {
                    store.deleteForAccount(account, region, NeptuneGraphStore.ENDPOINT, graphId + "/"
                            + endpoint.path("vpcId").asText());
                }
            }
            store.deleteForAccount(account, region, NeptuneGraphStore.GRAPH, graphId);
        });
        return view(graph);
    }

    public ObjectNode stopGraph(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        available(graph);
        String account = store.account();
        String graphId = graph.path("id").asText();
        graph.put("status", "STOPPING");
        save(account, region, graph);
        later(() -> {
            engine.stop(account, region, graphId);
            status(account, region, graphId, "STOPPED", null);
        });
        return view(graph);
    }

    public ObjectNode startGraph(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        if (!"STOPPED".equals(graph.path("status").asText())) {
            throw NeptuneGraphStore.conflict("Only a STOPPED graph starts; " + graph.path("id").asText() + " is "
                    + graph.path("status").asText() + ".");
        }
        String account = store.account();
        String graphId = graph.path("id").asText();
        int memory = graph.path("provisionedMemory").asInt(16);
        graph.put("status", "STARTING");
        save(account, region, graph);
        later(() -> {
            try {
                if (engine.enabled() && engine.resume(account, region, graphId) == null) {
                    engine.start(account, region, graphId, memory);
                }
                status(account, region, graphId, "AVAILABLE", null);
            } catch (RuntimeException e) {
                status(account, region, graphId, "FAILED", "The graph couldn't start: " + e.getMessage());
            }
        });
        return view(graph);
    }

    public ObjectNode resetGraph(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        available(graph);
        boolean snapshot = !in.path("skipSnapshot").asBoolean(false);
        String account = store.account();
        String graphId = graph.path("id").asText();
        String name = graph.path("name").asText();
        graph.put("status", "RESETTING");
        save(account, region, graph);
        later(() -> {
            if (snapshot) {
                snapshot(account, region, graphId, name + "-reset-snapshot-" + System.currentTimeMillis() / 1000,
                        null, false);
            }
            NeptuneGraphEngine.Backend backend = engine.backend(account, region, graphId);
            if (backend != null) {
                engine.run(backend, "MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS", null,
                        600_000);
            }
            status(account, region, graphId, "AVAILABLE", null);
        });
        return view(graph);
    }

    // ================================================================ snapshots

    ObjectNode snapshotRecord(String region, String id) {
        return store.get(region, NeptuneGraphStore.SNAPSHOT, id).orElseThrow(() -> NeptuneGraphStore.notFound(
                "The graph snapshot " + id + " doesn't exist."));
    }

    /** Takes a snapshot of a graph's nodes and edges; when it's its own request, the graph is SNAPSHOTTING meanwhile. */
    private ObjectNode snapshot(String account, String region, String graphId, String name, JsonNode tags,
                                boolean marksGraph) {
        String id = id("gs-");
        ObjectNode snapshot = store.record();
        snapshot.put("id", id);
        snapshot.put("name", name);
        snapshot.put("arn", store.arnFor(account, region, "graph-snapshot/" + id));
        snapshot.put("sourceGraphId", graphId);
        snapshot.put("snapshotCreateTime", NeptuneGraphStore.epoch());
        snapshot.put("status", "CREATING");
        ObjectNode graph = reload(account, region, graphId);
        if (graph != null) {
            NeptuneGraphStore.copy(graph, snapshot, "kmsKeyIdentifier");
            NeptuneGraphStore.meta(snapshot).put("provisionedMemory", graph.path("provisionedMemory").asInt(16));
            if (graph.has("vectorSearchConfiguration")) {
                NeptuneGraphStore.meta(snapshot).set("vectorSearchConfiguration", graph.get(
                        "vectorSearchConfiguration").deepCopy());
            }
        }
        if (tags != null) {
            NeptuneGraphStore.addTags(snapshot, tags);
        }
        store.putForAccount(account, region, NeptuneGraphStore.SNAPSHOT, id, snapshot);
        Runnable take = () -> {
            NeptuneGraphEngine.Backend backend = engine.backend(account, region, graphId);
            NeptuneGraphData.Graph data = backend == null ? new NeptuneGraphData.Graph()
                    : NeptuneGraphData.dump(mapper, engine, backend);
            store.putData(account, region, id, data.json(mapper).toString());
            ObjectNode current = store.getForAccount(account, region, NeptuneGraphStore.SNAPSHOT, id).orElse(null);
            if (current != null) {
                current.put("status", "AVAILABLE");
                store.putForAccount(account, region, NeptuneGraphStore.SNAPSHOT, id, current);
            }
            if (marksGraph) {
                status(account, region, graphId, "AVAILABLE", null);
            }
        };
        if (marksGraph) {
            later(take);
        } else {
            take.run();
        }
        return snapshot;
    }

    public ObjectNode createGraphSnapshot(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        String name = required(in, "snapshotName");
        if (!GRAPH_NAME.matcher(name).matches()) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "snapshotName must start with a lowercase letter "
                    + "and have up to 63 lowercase letters, digits and single hyphens.");
        }
        for (ObjectNode existing : store.list(region, NeptuneGraphStore.SNAPSHOT)) {
            if (name.equals(existing.path("name").asText())) {
                throw NeptuneGraphStore.conflict("A graph snapshot named " + name + " already exists.");
            }
        }
        available(graph);
        String account = store.account();
        graph.put("status", "SNAPSHOTTING");
        save(account, region, graph);
        return view(snapshot(account, region, graph.path("id").asText(), name, in.path("tags"), true));
    }

    public ObjectNode getGraphSnapshot(JsonNode in, String region) {
        return view(snapshotRecord(region, required(in, "snapshotIdentifier")));
    }

    public ObjectNode listGraphSnapshots(JsonNode in, String region) {
        String graphId = in.path("graphIdentifier").asText(null);
        if (graphId != null) {
            graphId = graph(region, graphId).path("id").asText();
        }
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode snapshot : store.list(region, NeptuneGraphStore.SNAPSHOT)) {
            if (graphId == null || graphId.equals(snapshot.path("sourceGraphId").asText())) {
                items.add(view(snapshot));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NeptuneGraphStore.page(items, in, out, "graphSnapshots", 100);
        return out;
    }

    public ObjectNode deleteGraphSnapshot(JsonNode in, String region) {
        String id = required(in, "snapshotIdentifier");
        ObjectNode snapshot = snapshotRecord(region, id);
        if (!"AVAILABLE".equals(snapshot.path("status").asText()) && !"FAILED".equals(snapshot.path("status")
                .asText())) {
            throw NeptuneGraphStore.conflict("The graph snapshot " + id + " is " + snapshot.path("status").asText()
                    + ".");
        }
        String account = store.account();
        snapshot.put("status", "DELETING");
        store.put(region, NeptuneGraphStore.SNAPSHOT, id, snapshot);
        later(() -> {
            store.deleteData(account, region, id);
            store.deleteForAccount(account, region, NeptuneGraphStore.SNAPSHOT, id);
        });
        return view(snapshot);
    }

    public ObjectNode restoreGraphFromSnapshot(JsonNode in, String region) {
        String snapshotId = required(in, "snapshotIdentifier");
        ObjectNode snapshot = snapshotRecord(region, snapshotId);
        if (!"AVAILABLE".equals(snapshot.path("status").asText())) {
            throw NeptuneGraphStore.conflict("The graph snapshot " + snapshotId + " is "
                    + snapshot.path("status").asText() + ".");
        }
        ObjectNode settings = in.deepCopy();
        if (!settings.has("provisionedMemory")) {
            settings.put("provisionedMemory", NeptuneGraphStore.meta(snapshot).path("provisionedMemory").asInt(16));
        }
        if (NeptuneGraphStore.meta(snapshot).has("vectorSearchConfiguration")) {
            settings.set("vectorSearchConfiguration", NeptuneGraphStore.meta(snapshot).get(
                    "vectorSearchConfiguration").deepCopy());
        }
        checkGraphSettings(settings, true);
        ObjectNode graph = newGraph(settings, region, settings.path("provisionedMemory").asInt());
        graph.put("sourceSnapshotId", snapshotId);
        NeptuneGraphStore.copy(snapshot, graph, "kmsKeyIdentifier");
        String account = store.account();
        save(account, region, graph);
        String graphId = graph.path("id").asText();
        provision(account, region, graphId, () -> {
            NeptuneGraphEngine.Backend backend = engine.backend(account, region, graphId);
            String data = store.data(account, region, snapshotId).orElse(null);
            if (backend != null && data != null) {
                try {
                    NeptuneGraphData.load(mapper, engine, backend, NeptuneGraphData.Graph.of(mapper.readTree(data)),
                            false);
                } catch (IOException e) {
                    throw new IllegalStateException("The snapshot's data can't be read.", e);
                }
            }
        });
        return view(graph);
    }

    // ================================================================ roles and S3

    /** Why a role can't be assumed by Neptune Analytics, or null when it can. */
    private String role(String account, String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, "iam") || !AwsArnUtils.parse(arn).resource().startsWith("role/")) {
            return "The role " + arn + " isn't a role ARN.";
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principal = ServicePrincipals.of(NeptuneGraphStore.SERVICE);
        try {
            IamRole role = RequestScopes.callAs(account, region, () -> iam.get().getRole(name));
            String trust = role.getAssumeRolePolicyDocument() == null ? "" : URLDecoder.decode(
                    role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
            return trust.contains(principal) ? null : "The role " + arn + " doesn't trust " + principal + ".";
        } catch (AwsException e) {
            return "Neptune Analytics couldn't assume the role " + arn + ": " + e.getMessage();
        }
    }

    private static String[] s3Uri(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            return null;
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        return slash < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
    }

    // ================================================================ import tasks

    ObjectNode importTask(String region, String id) {
        return store.get(region, NeptuneGraphStore.IMPORT, id).orElseThrow(() -> NeptuneGraphStore.notFound(
                "The import task " + id + " doesn't exist."));
    }

    private ObjectNode newImport(JsonNode in, String region, String graphId) {
        String source = required(in, "source");
        if (s3Uri(source) == null) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "source must be an s3:// URI.");
        }
        String roleArn = required(in, "roleArn");
        String format = in.path("format").asText(null);
        if (format != null && !Set.of("CSV", "OPEN_CYPHER", "PARQUET", "NTRIPLES").contains(format)) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "format must be CSV, OPEN_CYPHER, PARQUET or "
                    + "NTRIPLES.");
        }
        String id = id("t-");
        ObjectNode task = store.record();
        task.put("graphId", graphId);
        task.put("taskId", id);
        task.put("source", source);
        if (format != null) {
            task.put("format", format);
        }
        if ("PARQUET".equals(format)) {
            task.put("parquetType", "COLUMNAR");
        }
        task.put("roleArn", roleArn);
        task.put("status", "INITIALIZING");
        NeptuneGraphStore.copy(in, task, "importOptions");
        task.put("attemptNumber", 1);
        NeptuneGraphStore.meta(task).put("failOnError", in.path("failOnError").asBoolean(true));
        NeptuneGraphStore.meta(task).put("arn", store.arn(region, "import-task/" + id));
        store.put(region, NeptuneGraphStore.IMPORT, id, task);
        return task;
    }

    private void taskStatus(String account, String region, String kind, String id, String status,
                            Consumer<ObjectNode> change) {
        ObjectNode task = store.getForAccount(account, region, kind, id).orElse(null);
        if (task == null) {
            return;
        }
        task.put("status", status);
        if (change != null) {
            change.accept(task);
        }
        store.putForAccount(account, region, kind, id, task);
    }

    private boolean cancelled(String account, String region, String kind, String id) {
        ObjectNode task = store.getForAccount(account, region, kind, id).orElse(null);
        return task == null || "CANCELLING".equals(task.path("status").asText());
    }

    /** Reads an import's source files into a graph, by the task's format (or each file's extension). */
    private NeptuneGraphData.Graph read(String account, String region, ObjectNode task) {
        String[] source = s3Uri(task.path("source").asText());
        String format = task.path("format").asText(null);
        String blank = "http://aws.amazon.com/neptune/vocab/v01/BNode/" + task.path("taskId").asText() + "#";
        List<S3Object> objects = RequestScopes.callAs(account, region, () -> s3.get().listObjects(source[0],
                source[1], null, 100_000));
        NeptuneGraphData.Graph graph = new NeptuneGraphData.Graph();
        for (S3Object object : objects) {
            String key = object.getKey();
            if (key.endsWith("/")) {
                continue;
            }
            String file = "s3://" + source[0] + "/" + key;
            String type = format != null ? format : key.endsWith(".nt") ? "NTRIPLES" : key.endsWith(".parquet")
                    ? "PARQUET" : "CSV";
            if ("PARQUET".equals(type)) {
                List<Map<String, Object>> rows = duck.get().query("SELECT * FROM read_parquet('"
                        + file.replace("'", "''") + "')", null, account);
                NeptuneGraphData.readRows(mapper, file, rows, graph);
                continue;
            }
            String text = new String(RequestScopes.callAs(account, region, () -> s3.get().getObject(source[0], key)
                    .getData()), StandardCharsets.UTF_8);
            switch (type) {
                case "NTRIPLES" -> NeptuneGraphData.readNTriples(mapper, file, text, blank, graph);
                case "OPEN_CYPHER" -> NeptuneGraphData.readCsv(mapper, file, text, true, graph);
                default -> NeptuneGraphData.readCsv(mapper, file, text, false, graph);
            }
        }
        if (objects.isEmpty()) {
            graph.errors.add("The source " + task.path("source").asText() + " has no files.");
        }
        return graph;
    }

    /** Runs an import task: analyzes its files, then loads them into its graph. */
    private void runImport(String account, String region, String taskId, boolean creates) {
        ObjectNode task = store.getForAccount(account, region, NeptuneGraphStore.IMPORT, taskId).orElse(null);
        if (task == null) {
            return;
        }
        String graphId = task.path("graphId").asText();
        double started = NeptuneGraphStore.epoch();
        String problem = role(account, region, task.path("roleArn").asText());
        if (problem != null) {
            fail(account, region, taskId, graphId, problem, creates);
            return;
        }
        taskStatus(account, region, NeptuneGraphStore.IMPORT, taskId, "ANALYZING_DATA", current -> details(current,
                started, 0, 0, 0, null));
        NeptuneGraphData.Graph data;
        try {
            data = read(account, region, task);
        } catch (RuntimeException e) {
            fail(account, region, taskId, graphId, "The source couldn't be read: " + e.getMessage(), creates);
            return;
        }
        if (cancelled(account, region, NeptuneGraphStore.IMPORT, taskId)) {
            taskStatus(account, region, NeptuneGraphStore.IMPORT, taskId, "CANCELLED", null);
            status(account, region, graphId, creates ? "FAILED" : "AVAILABLE", creates ? "The import was cancelled."
                    : null);
            return;
        }
        boolean failOnError = NeptuneGraphStore.meta(task).path("failOnError").asBoolean(true);
        if (failOnError && !data.errors.isEmpty()) {
            fail(account, region, taskId, graphId, String.join(" ", data.errors.subList(0, Math.min(5,
                    data.errors.size()))), creates);
            return;
        }
        taskStatus(account, region, NeptuneGraphStore.IMPORT, taskId, "IMPORTING", current -> details(current,
                started, 0, 0, 0, null));
        status(account, region, graphId, "IMPORTING", null);
        try {
            NeptuneGraphEngine.Backend backend = engine.backend(account, region, graphId);
            if (backend != null) {
                NeptuneGraphData.load(mapper, engine, backend, data, true);
            }
        } catch (AwsException e) {
            fail(account, region, taskId, graphId, "The data couldn't be loaded: " + e.getMessage(), creates);
            return;
        }
        int errors = data.errors.size();
        String errorText = errors == 0 ? null : String.join(" ", data.errors.subList(0, Math.min(5, errors)));
        taskStatus(account, region, NeptuneGraphStore.IMPORT, taskId, "SUCCEEDED", current -> details(current,
                started, errors, data.statements, data.nodes.size() + data.edges.size(), errorText));
        status(account, region, graphId, "AVAILABLE", null);
    }

    private static void details(ObjectNode task, double started, int errors, long statements, long entries,
                                String errorText) {
        ObjectNode detail = task.putObject("importTaskDetails");
        detail.put("status", task.path("status").asText());
        detail.put("startTime", started);
        detail.put("timeElapsedSeconds", Math.round(NeptuneGraphStore.epoch() - started));
        detail.put("progressPercentage", "SUCCEEDED".equals(task.path("status").asText()) ? 100 : 0);
        detail.put("errorCount", errors);
        if (errorText != null) {
            detail.put("errorDetails", errorText);
        }
        detail.put("statementCount", statements);
        detail.put("dictionaryEntryCount", entries);
    }

    private void fail(String account, String region, String taskId, String graphId, String reason, boolean creates) {
        taskStatus(account, region, NeptuneGraphStore.IMPORT, taskId, "FAILED", task -> task.put("statusReason",
                reason));
        status(account, region, graphId, creates ? "FAILED" : "AVAILABLE", creates ? reason : null);
    }

    public ObjectNode createGraphUsingImportTask(JsonNode in, String region) {
        ObjectNode settings = in.deepCopy();
        int memory = in.path("minProvisionedMemory").asInt(16);
        settings.put("provisionedMemory", memory);
        checkGraphSettings(settings, true);
        int max = in.path("maxProvisionedMemory").asInt(1024);
        if (max < memory || !MEMORY.contains(max)) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "maxProvisionedMemory must be an allowed size at "
                    + "least minProvisionedMemory.");
        }
        ObjectNode graph = newGraph(settings, region, memory);
        String graphId = graph.path("id").asText();
        ObjectNode task = newImport(in, region, graphId);
        String account = store.account();
        String taskId = task.path("taskId").asText();
        provision(account, region, graphId, () -> runImport(account, region, taskId, true));
        return view(task);
    }

    public ObjectNode startImportTask(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        available(graph);
        String account = store.account();
        String graphId = graph.path("id").asText();
        NeptuneGraphEngine.Backend backend = engine.backend(account, region, graphId);
        if (backend != null) {
            List<List<JsonNode>> rows = engine.run(backend, "MATCH (n) RETURN count(n) > 0", null, 30_000).rows();
            if (!rows.isEmpty() && engine.plain(rows.get(0).get(0)).asBoolean()) {
                throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "StartImportTask loads into an empty graph; "
                        + "reset the graph first.");
            }
        }
        for (ObjectNode existing : store.list(region, NeptuneGraphStore.IMPORT)) {
            if (graphId.equals(existing.path("graphId").asText()) && !TERMINAL.contains(existing.path("status")
                    .asText())) {
                throw NeptuneGraphStore.conflict("The graph " + graphId + " already has an import task running.");
            }
        }
        ObjectNode task = newImport(in, region, graphId);
        graph.put("status", "IMPORTING");
        save(account, region, graph);
        String taskId = task.path("taskId").asText();
        later(() -> runImport(account, region, taskId, false));
        return view(task);
    }

    public ObjectNode getImportTask(JsonNode in, String region) {
        return view(importTask(region, required(in, "taskIdentifier")));
    }

    public ObjectNode listImportTasks(JsonNode in, String region) {
        String graphId = in.path("graphIdentifier").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode task : store.list(region, NeptuneGraphStore.IMPORT)) {
            if (graphId == null || graphId.equals(task.path("graphId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                NeptuneGraphStore.copy(task, summary, "graphId", "taskId", "source", "format", "parquetType",
                        "roleArn", "status");
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NeptuneGraphStore.page(items, in, out, "tasks", 100);
        return out;
    }

    public ObjectNode cancelImportTask(JsonNode in, String region) {
        String id = required(in, "taskIdentifier");
        ObjectNode task = importTask(region, id);
        if (TERMINAL.contains(task.path("status").asText())) {
            throw NeptuneGraphStore.conflict("The import task " + id + " is " + task.path("status").asText() + ".");
        }
        task.put("status", "CANCELLING");
        store.put(region, NeptuneGraphStore.IMPORT, id, task);
        return view(task);
    }

    // ================================================================ export tasks

    ObjectNode exportTask(String region, String id) {
        return store.get(region, NeptuneGraphStore.EXPORT, id).orElseThrow(() -> NeptuneGraphStore.notFound(
                "The export task " + id + " doesn't exist."));
    }

    public ObjectNode startExportTask(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        available(graph);
        String destination = required(in, "destination");
        if (s3Uri(destination) == null) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "destination must be an s3:// URI.");
        }
        String format = required(in, "format");
        if (!Set.of("CSV", "PARQUET").contains(format)) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "format must be CSV or PARQUET.");
        }
        String id = id("t-");
        ObjectNode task = store.record();
        task.put("graphId", graph.path("id").asText());
        task.put("roleArn", required(in, "roleArn"));
        task.put("taskId", id);
        task.put("status", "INITIALIZING");
        task.put("format", format);
        task.put("destination", destination);
        task.put("kmsKeyIdentifier", required(in, "kmsKeyIdentifier"));
        if ("PARQUET".equals(format)) {
            task.put("parquetType", "COLUMNAR");
        }
        NeptuneGraphStore.copy(in, task, "exportFilter");
        NeptuneGraphStore.addTags(task, in.path("tags"));
        store.put(region, NeptuneGraphStore.EXPORT, id, task);
        String account = store.account();
        later(() -> runExport(account, region, id));
        return view(task);
    }

    private void runExport(String account, String region, String taskId) {
        ObjectNode task = store.getForAccount(account, region, NeptuneGraphStore.EXPORT, taskId).orElse(null);
        if (task == null) {
            return;
        }
        double started = NeptuneGraphStore.epoch();
        String problem = role(account, region, task.path("roleArn").asText());
        if (problem != null) {
            taskStatus(account, region, NeptuneGraphStore.EXPORT, taskId, "FAILED", t -> t.put("statusReason",
                    problem));
            return;
        }
        taskStatus(account, region, NeptuneGraphStore.EXPORT, taskId, "EXPORTING", null);
        try {
            NeptuneGraphEngine.Backend backend = engine.backend(account, region, task.path("graphId").asText());
            NeptuneGraphData.Graph data = backend == null ? new NeptuneGraphData.Graph()
                    : NeptuneGraphData.dump(mapper, engine, backend);
            String[] files = NeptuneGraphData.exportCsv(mapper, data, task.path("exportFilter"));
            if (cancelled(account, region, NeptuneGraphStore.EXPORT, taskId)) {
                taskStatus(account, region, NeptuneGraphStore.EXPORT, taskId, "CANCELLED", null);
                return;
            }
            String[] destination = s3Uri(task.path("destination").asText());
            String base = (destination[1].isEmpty() || destination[1].endsWith("/") ? destination[1]
                    : destination[1] + "/") + taskId + "/";
            write(account, region, destination[0], base + "nodes/nodes.csv", files[0]);
            write(account, region, destination[0], base + "edges/edges.csv", files[1]);
            if ("PARQUET".equals(task.path("format").asText())) {
                for (String kind : List.of("nodes", "edges")) {
                    String csv = "s3://" + destination[0] + "/" + base + kind + "/" + kind + ".csv";
                    String parquet = "s3://" + destination[0] + "/" + base + kind + "/" + kind + ".parquet";
                    duck.get().execute("COPY (SELECT * FROM read_csv('" + csv + "', header = true)) TO '" + parquet
                            + "' (FORMAT PARQUET)", null, null, account);
                    RequestScopes.runAs(account, region, () -> s3.get().deleteObject(destination[0], base + kind + "/"
                            + kind + ".csv"));
                }
            }
            long vertices = Long.parseLong(files[2]);
            long edges = Long.parseLong(files[3]);
            taskStatus(account, region, NeptuneGraphStore.EXPORT, taskId, "SUCCEEDED", current -> {
                ObjectNode detail = current.putObject("exportTaskDetails");
                detail.put("startTime", started);
                detail.put("timeElapsedSeconds", Math.round(NeptuneGraphStore.epoch() - started));
                detail.put("progressPercentage", 100);
                detail.put("numVerticesWritten", vertices);
                detail.put("numEdgesWritten", edges);
            });
        } catch (RuntimeException e) {
            taskStatus(account, region, NeptuneGraphStore.EXPORT, taskId, "FAILED", t -> t.put("statusReason",
                    "The export failed: " + e.getMessage()));
        }
    }

    private void write(String account, String region, String bucket, String key, String body) {
        RequestScopes.runAs(account, region, () -> s3.get().putObject(bucket, key, body.getBytes(
                StandardCharsets.UTF_8), "text/csv", Map.of()));
    }

    public ObjectNode getExportTask(JsonNode in, String region) {
        return view(exportTask(region, required(in, "taskIdentifier")));
    }

    public ObjectNode listExportTasks(JsonNode in, String region) {
        String graphId = in.path("graphIdentifier").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode task : store.list(region, NeptuneGraphStore.EXPORT)) {
            if (graphId == null || graphId.equals(task.path("graphId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                NeptuneGraphStore.copy(task, summary, "graphId", "roleArn", "taskId", "status", "format",
                        "destination", "kmsKeyIdentifier", "parquetType", "statusReason");
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NeptuneGraphStore.page(items, in, out, "tasks", 100);
        return out;
    }

    public ObjectNode cancelExportTask(JsonNode in, String region) {
        String id = required(in, "taskIdentifier");
        ObjectNode task = exportTask(region, id);
        if (TERMINAL.contains(task.path("status").asText())) {
            throw NeptuneGraphStore.conflict("The export task " + id + " is " + task.path("status").asText() + ".");
        }
        task.put("status", "CANCELLING");
        store.put(region, NeptuneGraphStore.EXPORT, id, task);
        return view(task);
    }

    // ================================================================ private endpoints

    public ObjectNode createPrivateGraphEndpoint(JsonNode in, String region) {
        ObjectNode graph = graph(region, required(in, "graphIdentifier"));
        String graphId = graph.path("id").asText();
        String vpc = in.path("vpcId").asText("vpc-default");
        if (store.get(region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc).isPresent()) {
            throw NeptuneGraphStore.conflict("The graph " + graphId + " already has an endpoint in " + vpc + ".");
        }
        ObjectNode endpoint = store.record();
        endpoint.put("vpcId", vpc);
        ArrayNode subnets = endpoint.putArray("subnetIds");
        in.path("subnetIds").forEach(subnets::add);
        endpoint.put("status", "CREATING");
        endpoint.put("vpcEndpointId", "vpce-" + NeptuneGraphStore.random(17, "0123456789abcdef"));
        NeptuneGraphStore.meta(endpoint).put("graphId", graphId);
        ArrayNode groups = NeptuneGraphStore.meta(endpoint).putArray("securityGroups");
        in.path("vpcSecurityGroupIds").forEach(groups::add);
        store.put(region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc, endpoint);
        String account = store.account();
        later(() -> store.getForAccount(account, region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc).ifPresent(
                current -> {
                    current.put("status", "AVAILABLE");
                    store.putForAccount(account, region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc, current);
                }));
        return view(endpoint);
    }

    private ObjectNode endpoint(String region, String graphId, String vpc) {
        return store.get(region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc).orElseThrow(() ->
                NeptuneGraphStore.notFound("The graph " + graphId + " has no endpoint in " + vpc + "."));
    }

    public ObjectNode getPrivateGraphEndpoint(JsonNode in, String region) {
        String graphId = graph(region, required(in, "graphIdentifier")).path("id").asText();
        return view(endpoint(region, graphId, required(in, "vpcId")));
    }

    public ObjectNode listPrivateGraphEndpoints(JsonNode in, String region) {
        String graphId = graph(region, required(in, "graphIdentifier")).path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode endpoint : store.list(region, NeptuneGraphStore.ENDPOINT)) {
            if (graphId.equals(endpoint.path("_").path("graphId").asText())) {
                items.add(view(endpoint));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        NeptuneGraphStore.page(items, in, out, "privateGraphEndpoints", 100);
        return out;
    }

    public ObjectNode deletePrivateGraphEndpoint(JsonNode in, String region) {
        String graphId = graph(region, required(in, "graphIdentifier")).path("id").asText();
        String vpc = required(in, "vpcId");
        ObjectNode endpoint = endpoint(region, graphId, vpc);
        endpoint.put("status", "DELETING");
        store.put(region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc, endpoint);
        String account = store.account();
        later(() -> store.deleteForAccount(account, region, NeptuneGraphStore.ENDPOINT, graphId + "/" + vpc));
        return view(endpoint);
    }

    // ================================================================ queries

    /** The graph a data-plane request names: an unknown one is access denied, as its endpoint doesn't exist. */
    private ObjectNode queryable(String region, String identifier) {
        ObjectNode graph;
        try {
            graph = graph(region, identifier);
        } catch (AwsException e) {
            throw NeptuneGraphStore.denied("The graph " + identifier + " doesn't exist or isn't accessible.");
        }
        if (!"AVAILABLE".equals(graph.path("status").asText())) {
            throw NeptuneGraphStore.validation("UNSUPPORTED_OPERATION", "The graph " + identifier + " is "
                    + graph.path("status").asText() + ".");
        }
        return graph;
    }

    private NeptuneGraphEngine.Backend backend(ObjectNode graph, String region) {
        NeptuneGraphEngine.Backend backend = engine.backend(store.account(), region, graph.path("id").asText());
        if (backend == null) {
            throw NeptuneGraphStore.validation("UNSUPPORTED_OPERATION", engine.enabled()
                    ? "The graph's query engine isn't running." : "Floci runs no openCypher engine for graphs "
                    + "(floci.services.neptune-graph.query-engine is none).");
        }
        return backend;
    }

    public ObjectNode executeQuery(JsonNode in, String region) {
        ObjectNode graph = queryable(region, required(in, "graphIdentifier"));
        if (!"OPEN_CYPHER".equals(in.path("language").asText("OPEN_CYPHER"))) {
            throw NeptuneGraphStore.validation("UNSUPPORTED_OPERATION", "Neptune Analytics queries are openCypher.");
        }
        String query = required(in, "queryString");
        String result = query(graph, region, query, in.path("parameters"), in.path("explainMode").asText(null),
                in.path("queryTimeoutMilliseconds").asLong(0));
        ObjectNode out = mapper.createObjectNode();
        out.put("payload", Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));
        return out;
    }

    /** Runs openCypher on a graph and answers Neptune Analytics' result document (or the plan, when explained). */
    String query(ObjectNode graph, String region, String query, JsonNode parameters, String explain, long timeout) {
        NeptuneGraphEngine.Backend backend = backend(graph, region);
        String id = UUID.randomUUID().toString();
        String graphId = graph.path("id").asText();
        String marked = NeptuneGraphEngine.QUERY_MARK + id + " */ " + (explain == null ? "" : "EXPLAIN ") + query;
        queries.put(id, new Running(id, graphId, query, System.currentTimeMillis(), new boolean[1]));
        try {
            NeptuneGraphEngine.Result result = engine.run(backend, marked, parameters, timeout);
            if (explain != null) {
                return result.plan() == null ? "{}" : result.plan().toPrettyString();
            }
            ObjectNode out = mapper.createObjectNode();
            ArrayNode results = out.putArray("results");
            for (List<JsonNode> row : result.rows()) {
                ObjectNode item = results.addObject();
                for (int i = 0; i < result.fields().size(); i++) {
                    item.set(result.fields().get(i), engine.neptune(row.get(i)));
                }
            }
            return out.toString();
        } finally {
            queries.remove(id);
        }
    }

    private static ObjectNode querySummary(ObjectMapper mapper, Running running) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", running.id());
        out.put("queryString", running.query());
        out.put("waited", 0);
        out.put("elapsed", (int) (System.currentTimeMillis() - running.started()));
        out.put("state", running.cancelling()[0] ? "CANCELLING" : "RUNNING");
        return out;
    }

    public ObjectNode listQueries(JsonNode in, String region) {
        ObjectNode graph = queryable(region, required(in, "graphIdentifier"));
        int max = in.path("maxResults").asInt(10);
        String state = in.path("state").asText("ALL");
        ArrayNode items = mapper.createArrayNode();
        for (Running running : queries.values()) {
            ObjectNode summary = querySummary(mapper, running);
            if (graph.path("id").asText().equals(running.graph()) && items.size() < max
                    && ("ALL".equals(state) || state.equals(summary.path("state").asText()))) {
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("queries", items);
        return out;
    }

    private Running running(ObjectNode graph, String queryId) {
        Running running = queries.get(queryId);
        if (running == null || !graph.path("id").asText().equals(running.graph())) {
            throw NeptuneGraphStore.notFound("No query " + queryId + " is running.");
        }
        return running;
    }

    public ObjectNode getQuery(JsonNode in, String region) {
        ObjectNode graph = queryable(region, required(in, "graphIdentifier"));
        return querySummary(mapper, running(graph, required(in, "queryId")));
    }

    public ObjectNode cancelQuery(JsonNode in, String region) {
        ObjectNode graph = queryable(region, required(in, "graphIdentifier"));
        Running running = running(graph, required(in, "queryId"));
        running.cancelling()[0] = true;
        engine.cancel(backend(graph, region), running.id());
        return mapper.createObjectNode();
    }

    public ObjectNode getGraphSummary(JsonNode in, String region) {
        ObjectNode graph;
        try {
            graph = queryable(region, required(in, "graphIdentifier"));
        } catch (AwsException e) {
            if ("AccessDeniedException".equals(e.getErrorCode())) {
                throw NeptuneGraphStore.notFound("The graph " + in.path("graphIdentifier").asText()
                        + " doesn't exist.");
            }
            throw e;
        }
        NeptuneGraphEngine.Backend backend = engine.backend(store.account(), region, graph.path("id").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("version", "v1");
        out.put("lastStatisticsComputationTime", NeptuneGraphStore.epoch());
        boolean detailed = "DETAILED".equals(in.path("mode").asText("BASIC"));
        out.set("graphSummary", backend == null ? emptySummary(detailed)
                : NeptuneGraphData.summary(mapper, engine, backend, detailed));
        return out;
    }

    private ObjectNode emptySummary(boolean detailed) {
        ObjectNode summary = mapper.createObjectNode();
        for (String count : List.of("numNodes", "numEdges", "numNodeLabels", "numEdgeLabels", "numNodeProperties",
                "numEdgeProperties", "totalNodePropertyValues", "totalEdgePropertyValues")) {
            summary.put(count, 0);
        }
        for (String list : List.of("nodeLabels", "edgeLabels", "nodeProperties", "edgeProperties")) {
            summary.putArray(list);
        }
        if (detailed) {
            summary.putArray("nodeStructures");
            summary.putArray("edgeStructures");
        }
        return summary;
    }

    // ================================================================ tags

    private Object[] tagged(String region, String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (RuntimeException e) {
            throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "The ARN " + arn + " isn't valid.");
        }
        int slash = resource.indexOf('/');
        String type = slash < 0 ? resource : resource.substring(0, slash);
        String id = slash < 0 ? "" : resource.substring(slash + 1);
        String kind = switch (type) {
            case "graph" -> NeptuneGraphStore.GRAPH;
            case "graph-snapshot" -> NeptuneGraphStore.SNAPSHOT;
            case "export-task" -> NeptuneGraphStore.EXPORT;
            case "import-task" -> NeptuneGraphStore.IMPORT;
            default -> throw NeptuneGraphStore.validation("ILLEGAL_ARGUMENT", "The ARN " + arn
                    + " doesn't name a Neptune Analytics resource.");
        };
        ObjectNode record = store.get(region, kind, id).orElseThrow(() -> NeptuneGraphStore.notFound("The resource "
                + arn + " doesn't exist."));
        return new Object[] {kind, id, record};
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "resourceArn"));
        NeptuneGraphStore.addTags((ObjectNode) tagged[2], in.path("tags"));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        Object[] tagged = tagged(region, required(in, "resourceArn"));
        in.path("tagKeys").forEach(key -> NeptuneGraphStore.tags((ObjectNode) tagged[2]).remove(key.asText()));
        store.put(region, (String) tagged[0], (String) tagged[1], (ObjectNode) tagged[2]);
        return mapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", NeptuneGraphStore.tags((ObjectNode) tagged(region, required(in, "resourceArn"))[2])
                .deepCopy());
        return out;
    }
}
