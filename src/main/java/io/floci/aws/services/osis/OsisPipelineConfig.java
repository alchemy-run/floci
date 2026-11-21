package io.floci.aws.services.osis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An OpenSearch Ingestion pipeline configuration: Data Prepper's YAML, a {@code version} and one or more
 * sub-pipelines, each with one source, optional processors and routes, and one or more sinks. OpenSearch Ingestion
 * runs a subset of Data Prepper's plugins and manages buffers itself.
 */
final class OsisPipelineConfig {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** The sources OpenSearch Ingestion runs. */
    static final Set<String> SOURCES = Set.of("http", "otel_logs_source", "otel_metrics_source", "otel_trace_source",
            "s3", "dynamodb", "documentdb", "kafka", "kinesis", "opensearch", "pipeline", "rds", "sqs", "confluence",
            "jira", "office365", "crowdstrike", "prometheus");

    /** The processors OpenSearch Ingestion runs. */
    static final Set<String> PROCESSORS = Set.of("add_entries", "aggregate", "anomaly_detector", "aws_lambda",
            "convert_entry_type", "copy_values", "csv", "date", "decompress", "delete_entries", "dissect",
            "drop_events", "flatten", "geoip", "grok", "key_value", "list_to_map", "lowercase_string", "map_to_list",
            "ml_inference", "mutate_event", "obfuscate", "otel_metrics", "otel_trace_group", "otel_trace_raw",
            "otel_traces", "parse_ion", "parse_json", "parse_xml", "rename_keys", "select_entries", "service_map",
            "service_map_stateful", "split_event", "split_string", "string_converter", "substitute_string",
            "trace_peer_forwarder", "translate", "trim_string", "truncate", "uppercase_string", "user_agent",
            "write_json");

    /** The sinks OpenSearch Ingestion writes to. */
    static final Set<String> SINKS = Set.of("opensearch", "s3", "pipeline", "prometheus");

    /** Sources clients push to, with the port Data Prepper serves each on. */
    static final Map<String, Integer> PUSH_SOURCES = Map.of("http", 2021, "otel_trace_source", 21890,
            "otel_metrics_source", 21891, "otel_logs_source", 21892);

    /** Plugins that reach AWS, and so name a role. */
    private static final Set<String> AWS_PLUGINS = Set.of("s3", "opensearch", "dynamodb", "documentdb", "kinesis",
            "sqs", "rds", "prometheus");

    /** Top-level keys that aren't sub-pipelines. */
    private static final Set<String> RESERVED = Set.of("version", "extension", "pipeline_configurations");

    /** A push source: the sub-pipeline it feeds, its plugin, the path clients post to, and its port. */
    record PushSource(String pipeline, String plugin, String path, int port) {
    }

    /** A sink that writes to an AWS service, for {@code Destinations}. */
    record Destination(String serviceName, String endpoint) {
    }

    private final ObjectNode root;

    private OsisPipelineConfig(ObjectNode root) {
        this.root = root;
    }

    /** Parses a configuration body, failing with the problems that keep it from being one. */
    static OsisPipelineConfig parse(String body) {
        List<String> errors = new ArrayList<>();
        OsisPipelineConfig config = parse(body, errors);
        if (!errors.isEmpty()) {
            throw OsisStore.validation(String.join(" ", errors));
        }
        return config;
    }

    /** Parses and checks a configuration body, adding each problem to {@code errors}. */
    static OsisPipelineConfig parse(String body, List<String> errors) {
        JsonNode parsed;
        try {
            parsed = YAML.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            errors.add("The pipeline configuration isn't valid YAML: " + e.getOriginalMessage());
            return null;
        }
        if (parsed == null || !parsed.isObject()) {
            errors.add("The pipeline configuration must be a YAML mapping of sub-pipelines.");
            return null;
        }
        OsisPipelineConfig config = new OsisPipelineConfig((ObjectNode) parsed);
        config.check(errors);
        return errors.isEmpty() ? config : null;
    }

    private void check(List<String> errors) {
        JsonNode version = root.get("version");
        if (version != null && !"2".equals(version.asText())) {
            errors.add("The pipeline configuration version must be \"2\".");
        }
        Set<String> names = pipelines().keySet();
        if (names.isEmpty()) {
            errors.add("The pipeline configuration must define at least one sub-pipeline.");
        }
        pipelines().forEach((name, pipeline) -> {
            if (name.length() > 28 || !name.matches("[A-Za-z0-9_-]+")) {
                errors.add("Sub-pipeline name " + name + " must be 1-28 letters, digits, hyphens and underscores.");
            }
            if (!pipeline.isObject()) {
                errors.add("Sub-pipeline " + name + " must be a mapping.");
                return;
            }
            if (pipeline.has("buffer")) {
                errors.add("Sub-pipeline " + name + " can't configure a buffer: OpenSearch Ingestion manages "
                        + "buffering.");
            }
            JsonNode source = pipeline.get("source");
            if (source == null || !source.isObject() || source.size() != 1) {
                errors.add("Sub-pipeline " + name + " must have exactly one source.");
            } else {
                String plugin = source.fieldNames().next();
                checkPlugin(errors, name, "source", plugin, source.get(plugin), SOURCES, names);
            }
            JsonNode processors = pipeline.get("processor");
            if (processors != null) {
                if (!processors.isArray()) {
                    errors.add("Sub-pipeline " + name + "'s processor must be a list.");
                } else {
                    processors.forEach(processor -> plugins(errors, name, "processor", processor, PROCESSORS,
                            names));
                }
            }
            JsonNode sinks = pipeline.get("sink");
            if (sinks == null || !sinks.isArray() || sinks.isEmpty()) {
                errors.add("Sub-pipeline " + name + " must have at least one sink.");
            } else {
                sinks.forEach(sink -> plugins(errors, name, "sink", sink, SINKS, names));
            }
        });
        Set<String> paths = new LinkedHashSet<>();
        for (PushSource source : pushSources()) {
            if (!source.path().startsWith("/")) {
                errors.add("Sub-pipeline " + source.pipeline() + "'s " + source.plugin() + " source path must start "
                        + "with /.");
            } else if (!paths.add(source.path())) {
                errors.add("More than one source takes the path " + source.path() + ".");
            }
        }
    }

    private static void plugins(List<String> errors, String pipeline, String kind, JsonNode entry, Set<String> known,
                                Set<String> names) {
        if (!entry.isObject() || entry.size() != 1) {
            errors.add("Each " + kind + " of sub-pipeline " + pipeline + " must name one plugin.");
            return;
        }
        String plugin = entry.fieldNames().next();
        checkPlugin(errors, pipeline, kind, plugin, entry.get(plugin), known, names);
    }

    private static void checkPlugin(List<String> errors, String pipeline, String kind, String plugin, JsonNode options,
                                    Set<String> known, Set<String> names) {
        if (!known.contains(plugin)) {
            errors.add("The " + kind + " plugin " + plugin + " in sub-pipeline " + pipeline + " isn't supported by "
                    + "OpenSearch Ingestion.");
            return;
        }
        JsonNode settings = options == null || options.isNull() ? null : options;
        if ("pipeline".equals(plugin)) {
            String target = settings == null ? "" : settings.path("name").asText("");
            if (!names.contains(target)) {
                errors.add("The pipeline " + kind + " in sub-pipeline " + pipeline + " names " + (target.isEmpty()
                        ? "no sub-pipeline" : "the undefined sub-pipeline " + target) + ".");
            } else if (target.equals(pipeline)) {
                errors.add("Sub-pipeline " + pipeline + " can't connect to itself.");
            }
        }
        if ("s3".equals(plugin) && "sink".equals(kind)
                && (settings == null || settings.path("bucket").asText("").isBlank())) {
            errors.add("The s3 sink in sub-pipeline " + pipeline + " must name a bucket.");
        }
        if ("opensearch".equals(plugin) && "sink".equals(kind)
                && (settings == null || !settings.path("hosts").isArray() || settings.path("hosts").isEmpty())) {
            errors.add("The opensearch sink in sub-pipeline " + pipeline + " must list its hosts.");
        }
    }

    /** The sub-pipelines, by name. */
    Map<String, JsonNode> pipelines() {
        Map<String, JsonNode> pipelines = new LinkedHashMap<>();
        root.properties().forEach(entry -> {
            if (!RESERVED.contains(entry.getKey())) {
                pipelines.put(entry.getKey(), entry.getValue());
            }
        });
        return pipelines;
    }

    private static String plugin(JsonNode entry) {
        return entry != null && entry.isObject() && entry.size() == 1 ? entry.fieldNames().next() : "";
    }

    /** The sources clients push to, each with the port the rendered configuration serves it on. */
    List<PushSource> pushSources() {
        List<PushSource> sources = new ArrayList<>();
        Map<String, Integer> next = new LinkedHashMap<>(PUSH_SOURCES);
        pipelines().forEach((name, pipeline) -> {
            String plugin = plugin(pipeline.path("source"));
            Integer port = next.get(plugin);
            if (port == null) {
                return;
            }
            next.put(plugin, port + 10);
            JsonNode options = pipeline.path("source").path(plugin);
            String path = options.path("path").asText(defaultPath(plugin)).replace("${pipelineName}", name);
            sources.add(new PushSource(name, plugin, path, port));
        });
        return sources;
    }

    private static String defaultPath(String plugin) {
        return switch (plugin) {
            case "http" -> "/log/ingest";
            case "otel_logs_source" -> "/opentelemetry.proto.collector.logs.v1.LogsService/Export";
            case "otel_metrics_source" -> "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export";
            default -> "/opentelemetry.proto.collector.trace.v1.TraceService/Export";
        };
    }

    /** The roles the configuration's plugins assume. */
    Set<String> roles() {
        Set<String> roles = new LinkedHashSet<>();
        eachPlugin((plugin, options) -> {
            String role = options.path("aws").path("sts_role_arn").asText("");
            if (!role.isBlank()) {
                roles.add(role);
            }
        });
        return roles;
    }

    /** Plugins that reach AWS without naming a role, which need the pipeline's role. */
    List<String> pluginsWithoutRole() {
        List<String> plugins = new ArrayList<>();
        eachPlugin((plugin, options) -> {
            if (AWS_PLUGINS.contains(plugin) && options.path("aws").path("sts_role_arn").asText("").isBlank()
                    && !(plugin.equals("opensearch") && !options.has("aws"))) {
                plugins.add(plugin);
            }
        });
        return plugins;
    }

    /** The S3 buckets the configuration's plugins name. */
    Set<String> buckets() {
        Set<String> buckets = new LinkedHashSet<>();
        collectBuckets(root, buckets);
        return buckets;
    }

    private static void collectBuckets(JsonNode node, Set<String> buckets) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (Set.of("bucket", "s3_bucket", "bucket_name").contains(entry.getKey())
                        && entry.getValue().isTextual()) {
                    buckets.add(entry.getValue().asText());
                }
                collectBuckets(entry.getValue(), buckets);
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectBuckets(item, buckets));
        }
    }

    /** The AWS services the configuration's sinks write to. */
    List<Destination> destinations() {
        List<Destination> destinations = new ArrayList<>();
        pipelines().forEach((name, pipeline) -> pipeline.path("sink").forEach(sink -> {
            String plugin = plugin(sink);
            JsonNode options = sink.path(plugin);
            if ("opensearch".equals(plugin)) {
                boolean serverless = options.path("aws").path("serverless").asBoolean(false)
                        || options.path("hosts").path(0).asText("").contains(".aoss.");
                destinations.add(new Destination(serverless ? "OpenSearch_Serverless" : "OpenSearch",
                        options.path("hosts").path(0).asText("")));
            } else if ("s3".equals(plugin)) {
                destinations.add(new Destination("S3", options.path("bucket").asText("")));
            }
        }));
        return destinations;
    }

    private interface PluginVisitor {
        void visit(String plugin, JsonNode options);
    }

    private void eachPlugin(PluginVisitor visitor) {
        pipelines().forEach((name, pipeline) -> {
            String source = plugin(pipeline.path("source"));
            if (!source.isEmpty()) {
                visitor.visit(source, pipeline.path("source").path(source));
            }
            pipeline.path("processor").forEach(processor -> visitor.visit(plugin(processor),
                    processor.path(plugin(processor))));
            pipeline.path("sink").forEach(sink -> visitor.visit(plugin(sink), sink.path(plugin(sink))));
        });
    }

    /**
     * The Data Prepper configuration Floci runs: push sources serve plain HTTP (Floci's ingest endpoint terminates
     * the client's request) on their own ports, S3 sinks get the object key OpenSearch Ingestion defaults, the role
     * the container already holds a session for isn't assumed again, and OpenSearch hosts on Floci use its scheme.
     */
    String render(String heldRole, String flociScheme) {
        ObjectNode copy = root.deepCopy();
        copy.remove("pipeline_configurations");
        copy.remove("extension");
        Map<String, Integer> ports = new LinkedHashMap<>();
        pushSources().forEach(source -> ports.put(source.pipeline(), source.port()));
        copy.properties().forEach(entry -> {
            if (RESERVED.contains(entry.getKey()) || !entry.getValue().isObject()) {
                return;
            }
            ObjectNode pipeline = (ObjectNode) entry.getValue();
            String source = plugin(pipeline.path("source"));
            if (ports.containsKey(entry.getKey())) {
                ObjectNode options = options((ObjectNode) pipeline.get("source"), source);
                options.put("port", ports.get(entry.getKey()));
                options.put("ssl", false);
                if (source.startsWith("otel_")) {
                    options.put("unframed_requests", true);
                }
            }
            each(pipeline.path("source"), heldRole);
            pipeline.path("processor").forEach(processor -> each(processor, heldRole));
            pipeline.path("sink").forEach(sink -> {
                each(sink, heldRole);
                String plugin = plugin(sink);
                if ("s3".equals(plugin)) {
                    ObjectNode options = options((ObjectNode) sink, plugin);
                    if (!options.path("object_key").isObject()) {
                        options.putObject("object_key");
                    }
                    ObjectNode key = (ObjectNode) options.get("object_key");
                    if (!key.has("path_prefix")) {
                        key.put("path_prefix", "");
                    }
                } else if ("opensearch".equals(plugin)) {
                    ObjectNode options = options((ObjectNode) sink, plugin);
                    if (options.path("hosts").isArray()) {
                        ArrayNode hosts = (ArrayNode) options.get("hosts");
                        for (int i = 0; i < hosts.size(); i++) {
                            String host = hosts.get(i).asText();
                            if (host.contains("." + OsisStore.DNS_SUFFIX) && host.startsWith("https://")
                                    && "http".equals(flociScheme)) {
                                hosts.set(i, "http://" + host.substring("https://".length()));
                            }
                        }
                    }
                }
            });
        });
        try {
            return YAML.writeValueAsString(copy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The pipeline configuration can't be written.", e);
        }
    }

    private static ObjectNode options(ObjectNode entry, String plugin) {
        if (!entry.path(plugin).isObject()) {
            entry.putObject(plugin);
        }
        return (ObjectNode) entry.get(plugin);
    }

    private static void each(JsonNode entry, String heldRole) {
        if (!entry.isObject()) {
            return;
        }
        String plugin = plugin(entry);
        JsonNode aws = entry.path(plugin).path("aws");
        if (aws.isObject() && heldRole != null && heldRole.equals(aws.path("sts_role_arn").asText())) {
            ((ObjectNode) aws).remove("sts_role_arn");
        }
    }
}
