package io.floci.aws.services.neptunegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The openCypher engine behind Neptune Analytics graphs: a Neo4j container per graph, queried through Neo4j's Query
 * API, whose typed results become Neptune's ({@code ~id}, {@code ~entityType}, {@code ~labels}, {@code ~properties}
 * for nodes, and {@code ~start}, {@code ~end}, {@code ~type} for relationships). Stopping a graph stops its container
 * and keeps its data; deleting it removes the container.
 */
@ApplicationScoped
public class NeptuneGraphEngine implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(NeptuneGraphEngine.class);
    private static final int HTTP_PORT = 7474;
    static final String QUERY_MARK = "/* floci-query:";

    /** A graph's running container. */
    record Backend(String containerId, String host, int port) {
        String queryUrl() {
            return "http://" + host + ":" + port + "/db/neo4j/query/v2";
        }
    }

    /** A query's columns and rows, as Neo4j typed them. */
    record Result(List<String> fields, List<List<JsonNode>> rows, JsonNode plan) {
    }

    private final ContainerBuilder containers;
    private final ContainerLifecycleManager lifecycle;
    private final ContainerDetector detector;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Backend> backends = new ConcurrentHashMap<>();

    @Inject
    public NeptuneGraphEngine(ContainerBuilder containers, ContainerLifecycleManager lifecycle,
                              ContainerDetector detector, EmulatorConfig config, ObjectMapper mapper) {
        this.containers = containers;
        this.lifecycle = lifecycle;
        this.detector = detector;
        this.config = config;
        this.mapper = mapper;
    }

    private EmulatorConfig.NeptuneGraphServiceConfig settings() {
        return config.services().neptuneGraph();
    }

    boolean enabled() {
        return "neo4j".equalsIgnoreCase(settings().queryEngine());
    }

    private static String key(String account, String region, String graph) {
        return account + "/" + region + "/" + graph;
    }

    private String containerName(String account, String region, String graph) {
        return ContainerStorageHelper.resourceName(config, "neptunegraph", null, account + "-" + region + "-" + graph);
    }

    /** Starts (or restarts) a graph's container and waits until it answers queries. */
    synchronized Backend start(String account, String region, String graph, int memoryGb) {
        String key = key(account, region, graph);
        Backend running = backends.get(key);
        if (running != null && ready(running)) {
            return running;
        }
        String name = containerName(account, region, graph);
        lifecycle.removeIfExists(name, true);
        // Neptune Analytics sizes a graph in m-NCUs; Neo4j's heap follows it, within what a laptop runs.
        String heap = Math.max(256, Math.min(2048, memoryGb * 32)) + "m";
        ContainerBuilder.Builder builder = containers.newContainer(settings().image()).withName(name)
                .withEnv(List.of("NEO4J_AUTH=none", "NEO4J_server_memory_heap_initial__size=" + heap,
                        "NEO4J_server_memory_heap_max__size=" + heap, "NEO4J_server_memory_pagecache_size=128m"))
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(NeptuneGraphStore.SERVICE, graph, account,
                        region));
        builder.withDockerNetwork(settings().dockerNetwork().or(() -> config.services().dockerNetwork()));
        if (!detector.isRunningInContainer()) {
            builder.withDynamicPort(HTTP_PORT);
        } else {
            builder.withExposedPort(HTTP_PORT);
        }
        ContainerSpec spec = builder.build();
        ContainerInfo info = lifecycle.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(HTTP_PORT);
        Backend backend = new Backend(info.containerId(), endpoint.host(), endpoint.port());
        backends.put(key, backend);
        await(backend, graph);
        LOG.infov("Neptune Analytics graph {0} runs on Neo4j at {1}:{2}", graph, endpoint.host(),
                String.valueOf(endpoint.port()));
        return backend;
    }

    private void await(Backend backend, String graph) {
        long deadline = System.currentTimeMillis() + settings().readySeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ready(backend)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the graph started.", e);
            }
        }
        throw new IllegalStateException("The graph " + graph + "'s Neo4j didn't answer within "
                + settings().readySeconds() + " seconds.");
    }

    private boolean ready(Backend backend) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(backend.queryUrl()))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/vnd.neo4j.query")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"statement\":\"RETURN 1\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 202;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    Backend backend(String account, String region, String graph) {
        return backends.get(key(account, region, graph));
    }

    /** Stops a graph's container, keeping its data for a restart. */
    synchronized void stop(String account, String region, String graph) {
        Backend backend = backends.remove(key(account, region, graph));
        if (backend != null) {
            try {
                lifecycle.getDockerClient().stopContainerCmd(backend.containerId()).withTimeout(10).exec();
            } catch (RuntimeException e) {
                LOG.debugv("Could not stop graph {0}'s container: {1}", graph, e.getMessage());
            }
        }
    }

    /** Starts a stopped graph's container again, with its data. */
    synchronized Backend resume(String account, String region, String graph) {
        String name = containerName(account, region, graph);
        try {
            lifecycle.getDockerClient().startContainerCmd(name).exec();
            String id = lifecycle.getDockerClient().inspectContainerCmd(name).exec().getId();
            ContainerInfo info = lifecycle.adopt(id, List.of(HTTP_PORT));
            EndpointInfo endpoint = info.getEndpoint(HTTP_PORT);
            Backend backend = new Backend(info.containerId(), endpoint.host(), endpoint.port());
            backends.put(key(account, region, graph), backend);
            await(backend, graph);
            return backend;
        } catch (RuntimeException e) {
            LOG.debugv("Graph {0}'s container couldn't be restarted, so it starts empty: {1}", graph,
                    e.getMessage());
            return null;
        }
    }

    /** Removes a graph's container and its data. */
    synchronized void remove(String account, String region, String graph) {
        Backend backend = backends.remove(key(account, region, graph));
        if (backend != null) {
            lifecycle.stopAndRemove(backend.containerId(), null, 5, true);
        } else {
            lifecycle.removeIfExists(containerName(account, region, graph), true);
        }
    }

    @Override
    public void stopManagedContainers() {
        for (String key : new ArrayList<>(backends.keySet())) {
            Backend backend = backends.remove(key);
            if (backend != null) {
                lifecycle.stopAndRemove(backend.containerId(), null, 5, true);
            }
        }
    }

    // ================================================================ queries

    /** Runs a statement, failing with the Neptune Analytics error its Neo4j error maps to. */
    Result run(Backend backend, String statement, JsonNode parameters, long timeoutMillis) {
        ObjectNode body = mapper.createObjectNode();
        body.put("statement", statement);
        if (parameters != null && parameters.isObject() && !parameters.isEmpty()) {
            body.set("parameters", parameters);
        }
        HttpResponse<String> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(backend.queryUrl()))
                    .header("Content-Type", "application/json").header("Accept", "application/vnd.neo4j.query")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (timeoutMillis > 0) {
                request.timeout(Duration.ofMillis(timeoutMillis));
            }
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw NeptuneGraphStore.unprocessable("QUERY_TIMEOUT", "The query ran past its "
                    + timeoutMillis + " ms timeout.");
        } catch (IOException e) {
            throw new AwsException("InternalServerException", "The graph's query engine is unreachable: "
                    + e.getMessage(), 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("InternalServerException", "The query was interrupted.", 500);
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new AwsException("InternalServerException", "The graph's query engine answered "
                    + response.statusCode() + ".", 500);
        }
        JsonNode errors = parsed.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw error(errors.get(0));
        }
        if (response.statusCode() >= 400) {
            throw new AwsException("InternalServerException", "The graph's query engine answered "
                    + response.statusCode() + ".", 500);
        }
        List<String> fields = new ArrayList<>();
        parsed.path("data").path("fields").forEach(field -> fields.add(field.asText()));
        List<List<JsonNode>> rows = new ArrayList<>();
        for (JsonNode values : parsed.path("data").path("values")) {
            List<JsonNode> row = new ArrayList<>();
            values.forEach(row::add);
            rows.add(row);
        }
        JsonNode plan = parsed.has("queryPlan") ? parsed.get("queryPlan") : parsed.path("profiledQueryPlan");
        return new Result(fields, rows, plan.isMissingNode() ? null : plan);
    }

    private static AwsException error(JsonNode error) {
        String code = error.path("code").asText("");
        String message = error.path("message").asText(code);
        if (code.contains("SyntaxError") || code.contains("ArgumentError") || code.contains("TypeError")
                || code.contains("ParameterMissing") || code.contains("SemanticError")) {
            return NeptuneGraphStore.validation("MALFORMED_QUERY", message);
        }
        if (code.contains("ConstraintValidationFailed") || code.contains("Schema.ConstraintValidation")) {
            return NeptuneGraphStore.validation("CONSTRAINT_VIOLATION", message);
        }
        if (code.contains("Terminated")) {
            return NeptuneGraphStore.validation("QUERY_CANCELLED", message);
        }
        if (code.contains("TransactionTimedOut")) {
            return NeptuneGraphStore.unprocessable("QUERY_TIMEOUT", message);
        }
        if (code.contains("OutOfMemory") || code.contains("MemoryPoolOutOfMemory")) {
            return NeptuneGraphStore.unprocessable("MEMORY_LIMIT_EXCEEDED", message);
        }
        if (code.contains("DeadlockDetected") || code.contains("LockClient")) {
            return NeptuneGraphStore.conflict(message);
        }
        if (code.startsWith("Neo.ClientError")) {
            return NeptuneGraphStore.validation("BAD_REQUEST", message);
        }
        return new AwsException("InternalServerException", message, 500);
    }

    /** Terminates the transaction running a query, by the mark its statement carries. */
    boolean cancel(Backend backend, String queryId) {
        Result running = run(backend, "SHOW TRANSACTIONS YIELD transactionId, currentQuery WHERE currentQuery "
                + "CONTAINS $mark RETURN transactionId", mapper.createObjectNode().put("mark", QUERY_MARK + queryId),
                10_000);
        boolean found = false;
        for (List<JsonNode> row : running.rows()) {
            String transaction = plain(row.get(0)).asText();
            run(backend, "TERMINATE TRANSACTION $id", mapper.createObjectNode().put("id", transaction), 10_000);
            found = true;
        }
        return found;
    }

    // ================================================================ values

    /** A typed Neo4j value as Neptune Analytics answers it. */
    JsonNode neptune(JsonNode typed) {
        if (typed == null || typed.isNull() || !typed.isObject() || !typed.has("$type")) {
            return typed == null ? mapper.nullNode() : typed;
        }
        JsonNode value = typed.path("_value");
        return switch (typed.path("$type").asText()) {
            case "Null" -> mapper.nullNode();
            case "Boolean" -> mapper.getNodeFactory().booleanNode(value.asBoolean());
            case "Integer" -> mapper.getNodeFactory().numberNode(Long.parseLong(value.asText()));
            case "Float" -> number(value.asText());
            case "List" -> {
                ArrayNode list = mapper.createArrayNode();
                value.forEach(item -> list.add(neptune(item)));
                yield list;
            }
            case "Map" -> {
                ObjectNode map = mapper.createObjectNode();
                value.properties().forEach(entry -> map.set(entry.getKey(), neptune(entry.getValue())));
                yield map;
            }
            case "Node" -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("~id", value.path("_element_id").asText());
                node.put("~entityType", "node");
                ArrayNode labels = node.putArray("~labels");
                value.path("_labels").forEach(labels::add);
                ObjectNode properties = node.putObject("~properties");
                value.path("_properties").properties().forEach(entry -> properties.set(entry.getKey(),
                        neptune(entry.getValue())));
                yield node;
            }
            case "Relationship" -> {
                ObjectNode relationship = mapper.createObjectNode();
                relationship.put("~id", value.path("_element_id").asText());
                relationship.put("~entityType", "relationship");
                relationship.put("~start", value.path("_start_node_element_id").asText());
                relationship.put("~end", value.path("_end_node_element_id").asText());
                relationship.put("~type", value.path("_type").asText());
                ObjectNode properties = relationship.putObject("~properties");
                value.path("_properties").properties().forEach(entry -> properties.set(entry.getKey(),
                        neptune(entry.getValue())));
                yield relationship;
            }
            case "Path" -> {
                ArrayNode path = mapper.createArrayNode();
                value.forEach(item -> path.add(neptune(item)));
                yield path;
            }
            default -> mapper.getNodeFactory().textNode(value.asText());
        };
    }

    /** A typed Neo4j value as plain JSON: graph entities as their properties. */
    JsonNode plain(JsonNode typed) {
        JsonNode converted = neptune(typed);
        if (converted.isObject() && converted.has("~entityType")) {
            return converted.path("~properties");
        }
        return converted;
    }

    private JsonNode number(String text) {
        try {
            double value = Double.parseDouble(text);
            return mapper.getNodeFactory().numberNode(value);
        } catch (NumberFormatException e) {
            return mapper.getNodeFactory().textNode(text);
        }
    }
}
