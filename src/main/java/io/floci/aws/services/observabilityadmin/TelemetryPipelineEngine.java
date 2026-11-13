package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CloudWatch pipelines: a configuration's checks and the processors that run its records. A configuration is one
 * {@code pipeline} with a source, up to 20 processors and its sinks. Logs pipelines take at most one primary parser,
 * first, plus up to 5 {@code parse_xml}; {@code add_entries}, {@code copy_values}, {@code lookup} and {@code grok} at
 * most once; and {@code when} conditions on the processors that support them. Metrics pipelines read and write
 * CloudWatch metrics and take attribute processors.
 */
final class TelemetryPipelineEngine {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final String MESSAGE = "@message";

    static final Set<String> LOG_SOURCES = Set.of("cloudwatch_logs", "s3", "microsoft_office365",
            "microsoft_entraid", "microsoft_windows_event", "paloaltonetworks_nextgenerationfirewall", "okta_auth0",
            "okta_sso", "crowdstrike_falcon", "github_auditlogs", "sentinelone_endpointsecurity", "servicenow_cmdb",
            "wiz_cnapp", "zscaler_internetaccess");
    static final Set<String> PRIMARY_PARSERS = Set.of("ocsf", "csv", "grok", "parse_vpc", "parse_json",
            "parse_route53", "parse_rds", "key_value", "parse_waf", "parse_postgres", "parse_cloudfront");
    static final Set<String> LOG_PROCESSORS = Set.of("parse_xml", "add_entries", "copy_values", "delete_entries",
            "move_keys", "flatten", "lookup", "geoip", "lowercase_string", "uppercase_string", "trim_string",
            "substitute_string", "truncate", "extract_value", "convert_entry_type", "date", "dissect", "list_to_map",
            "rename_keys", "select_entries", "translate", "drop_events");
    static final Set<String> METRIC_PROCESSORS = Set.of("add_attributes", "delete_attributes");
    /** Processors whose {@code when} applies to the whole processor. */
    static final Set<String> CONDITIONAL = Set.of("add_entries", "copy_values", "delete_entries", "move_keys",
            "flatten", "lowercase_string", "uppercase_string", "trim_string", "substitute_string", "truncate",
            "extract_value", "convert_entry_type", "date", "dissect", "list_to_map", "rename_keys", "select_entries",
            "translate", "grok", "drop_events", "parse_xml", "lookup", "geoip");
    /** Processors whose entries take their own {@code when} and {@code when_else}. */
    static final Set<String> ENTRY_CONDITIONAL = Set.of("add_entries", "copy_values", "move_keys", "rename_keys",
            "extract_value", "substitute_string", "geoip");
    private static final Set<String> ONCE = Set.of("add_entries", "copy_values", "lookup", "grok");
    private static final int MAX_PROCESSORS = 20;

    /** A configuration problem: its message, a reason code, and the fields it concerns. */
    record Problem(String message, String reason, Map<String, String> fields) {
    }

    /** A record a test or a source hands the pipeline: its text and whether that is JSON. */
    record Record(String data, String type) {
    }

    /** What a record became: its output, or why it couldn't be processed; both null when it was dropped. */
    record Output(Record record, String error) {
    }

    private final ObjectNode pipeline;
    private final ObjectMapper mapper;

    private TelemetryPipelineEngine(ObjectNode pipeline, ObjectMapper mapper) {
        this.pipeline = pipeline;
        this.mapper = mapper;
    }

    /**
     * Parses and checks a configuration body; {@code logGroupExists} says whether a sink's log group exists. Returns
     * null with the problems added when it isn't valid.
     */
    static TelemetryPipelineEngine parse(String body, ObjectMapper mapper, Predicate<String> logGroupExists,
                                         List<Problem> problems) {
        JsonNode root;
        try {
            root = YAML.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            problems.add(new Problem("The configuration isn't valid YAML: " + e.getOriginalMessage(), "INVALID_YAML",
                    Map.of()));
            return null;
        }
        if (root == null || !root.isObject() || !root.path("pipeline").isObject() || root.size() != 1) {
            problems.add(new Problem("The configuration must define exactly one pipeline, under the key pipeline.",
                    "INVALID_STRUCTURE", Map.of()));
            return null;
        }
        TelemetryPipelineEngine engine = new TelemetryPipelineEngine((ObjectNode) root.get("pipeline"), mapper);
        engine.check(logGroupExists, problems);
        return problems.isEmpty() ? engine : null;
    }

    static String plugin(JsonNode entry) {
        return entry != null && entry.isObject() && entry.size() == 1 ? entry.fieldNames().next() : "";
    }

    static JsonNode options(JsonNode entry) {
        JsonNode options = entry.path(plugin(entry));
        return options.isObject() ? options : ((ObjectMapper) YAML).createObjectNode();
    }

    String source() {
        return plugin(pipeline.path("source"));
    }

    JsonNode sourceOptions() {
        return options(pipeline.path("source"));
    }

    boolean metrics() {
        return "cloudwatch_metrics".equals(source());
    }

    List<JsonNode> processors() {
        List<JsonNode> list = new ArrayList<>();
        pipeline.path("processor").forEach(list::add);
        return list;
    }

    List<JsonNode> sinks() {
        List<JsonNode> list = new ArrayList<>();
        pipeline.path("sink").forEach(list::add);
        return list;
    }

    private void problem(List<Problem> problems, String message, String reason, String field) {
        problems.add(new Problem(message, reason, field == null ? Map.of() : Map.of("field", field)));
    }

    private void check(Predicate<String> logGroupExists, List<Problem> problems) {
        JsonNode source = pipeline.path("source");
        String sourceName = plugin(source);
        if (!source.isObject() || source.size() != 1) {
            problem(problems, "The pipeline must have exactly one source.", "INVALID_SOURCE", "pipeline.source");
            return;
        }
        boolean metrics = metrics();
        if (!metrics && !LOG_SOURCES.contains(sourceName)) {
            problem(problems, "The source " + sourceName + " isn't supported.", "UNSUPPORTED_SOURCE",
                    "pipeline.source");
        }
        if (("cloudwatch_logs".equals(sourceName) || "s3".equals(sourceName))
                && options(source).path("aws").path("sts_role_arn").asText("").isBlank()
                && "s3".equals(sourceName)) {
            problem(problems, "The s3 source needs aws.sts_role_arn.", "MISSING_ROLE", "pipeline.source.s3.aws");
        }
        JsonNode processors = pipeline.path("processor");
        if (!processors.isMissingNode() && !processors.isArray()) {
            problem(problems, "pipeline.processor must be a list.", "INVALID_PROCESSOR", "pipeline.processor");
        }
        List<JsonNode> list = processors();
        if (list.size() > MAX_PROCESSORS) {
            problem(problems, "A pipeline takes at most " + MAX_PROCESSORS + " processors.", "TOO_MANY_PROCESSORS",
                    "pipeline.processor");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean parsed = false;
        for (int i = 0; i < list.size(); i++) {
            JsonNode entry = list.get(i);
            String name = plugin(entry);
            String field = "pipeline.processor[" + i + "]";
            if (name.isEmpty()) {
                problem(problems, "Each processor must name one plugin.", "INVALID_PROCESSOR", field);
                continue;
            }
            counts.merge(name, 1, Integer::sum);
            JsonNode options = options(entry);
            if (metrics) {
                if (!METRIC_PROCESSORS.contains(name)) {
                    problem(problems, "The processor " + name + " isn't supported in metrics pipelines.",
                            "UNSUPPORTED_PROCESSOR", field);
                }
                if (options.has("when")) {
                    problem(problems, "Metrics pipelines don't support when.", "UNSUPPORTED_CONDITION", field);
                }
                continue;
            }
            if (PRIMARY_PARSERS.contains(name)) {
                if (i != 0) {
                    problem(problems, "The parser " + name + " must be the first processor.", "PARSER_POSITION",
                            field);
                }
                if (parsed) {
                    problem(problems, "A pipeline takes at most one parser.", "TOO_MANY_PARSERS", field);
                }
                parsed = true;
            } else if (!LOG_PROCESSORS.contains(name)) {
                problem(problems, "The processor " + name + " isn't supported.", "UNSUPPORTED_PROCESSOR", field);
                continue;
            }
            if ("parse_xml".equals(name) && !parsed) {
                problem(problems, "parse_xml must follow a parser.", "PARSER_POSITION", field);
            }
            String when = options.path("when").asText(null);
            if (when != null) {
                if (!CONDITIONAL.contains(name)) {
                    problem(problems, "The processor " + name + " doesn't support when.", "UNSUPPORTED_CONDITION",
                            field + ".when");
                } else {
                    condition(problems, when, field + ".when");
                }
            } else if ("drop_events".equals(name)) {
                problem(problems, "drop_events needs a when condition.", "MISSING_CONDITION", field);
            }
            JsonNode entries = options.path("entries");
            for (int e = 0; e < entries.size(); e++) {
                for (String key : List.of("when", "when_else")) {
                    String entryWhen = entries.get(e).path(key).asText(null);
                    if (entryWhen == null) {
                        continue;
                    }
                    if (!ENTRY_CONDITIONAL.contains(name)) {
                        problem(problems, "The entries of " + name + " don't support " + key + ".",
                                "UNSUPPORTED_CONDITION", field + ".entries[" + e + "]." + key);
                    } else {
                        condition(problems, entryWhen, field + ".entries[" + e + "]." + key);
                    }
                }
            }
        }
        counts.forEach((name, count) -> {
            if (ONCE.contains(name) && count > 1) {
                problem(problems, "A pipeline takes at most one " + name + " processor.", "DUPLICATE_PROCESSOR",
                        "pipeline.processor");
            }
            if ("parse_xml".equals(name) && count > 5) {
                problem(problems, "A pipeline takes at most 5 parse_xml processors.", "DUPLICATE_PROCESSOR",
                        "pipeline.processor");
            }
        });
        List<JsonNode> sinks = sinks();
        if (sinks.isEmpty()) {
            problem(problems, "The pipeline must have a sink.", "INVALID_SINK", "pipeline.sink");
            return;
        }
        String expected = metrics ? "cloudwatch_metrics" : "cloudwatch_logs";
        if (metrics && sinks.size() > 1) {
            problem(problems, "A metrics pipeline takes exactly one sink.", "INVALID_SINK", "pipeline.sink");
        }
        if (sinks.size() > 10) {
            problem(problems, "A pipeline takes at most 10 sinks.", "INVALID_SINK", "pipeline.sink");
        }
        for (int i = 0; i < sinks.size(); i++) {
            String name = plugin(sinks.get(i));
            String field = "pipeline.sink[" + i + "]";
            if (!expected.equals(name)) {
                problem(problems, "A " + (metrics ? "metrics" : "logs") + " pipeline's sink must be " + expected
                        + ", not " + name + ".", "INVALID_SINK", field);
                continue;
            }
            if (metrics) {
                continue;
            }
            JsonNode options = options(sinks.get(i));
            String group = options.path("log_group").asText("");
            if (group.isBlank()) {
                problem(problems, "The cloudwatch_logs sink needs a log_group.", "INVALID_SINK", field
                        + ".log_group");
            } else if ("cloudwatch_logs".equals(sourceName) && !"@original".equals(group)) {
                problem(problems, "With a cloudwatch_logs source, the sink's log_group must be @original.",
                        "INVALID_SINK", field + ".log_group");
            } else if (!"cloudwatch_logs".equals(sourceName) && "@original".equals(group)) {
                problem(problems, "@original is only for pipelines with a cloudwatch_logs source.", "INVALID_SINK",
                        field + ".log_group");
            } else if (!"@original".equals(group) && !logGroupExists.test(group)) {
                problem(problems, "The log group " + group + " doesn't exist.", "LOG_GROUP_NOT_FOUND",
                        field + ".log_group");
            }
            if (options.has("when")) {
                condition(problems, options.path("when").asText(), field + ".when");
            }
        }
    }

    private void condition(List<Problem> problems, String when, String field) {
        try {
            PipelineExpression.compile(when);
        } catch (IllegalArgumentException e) {
            problem(problems, e.getMessage(), "INVALID_CONDITION", field);
        }
    }

    /** The roles the configuration names. */
    List<String> roles() {
        List<String> roles = new ArrayList<>();
        String source = sourceOptions().path("aws").path("sts_role_arn").asText("");
        if (!source.isBlank()) {
            roles.add(source);
        }
        return roles;
    }

    /** A summary of the configuration, as ListTelemetryPipelines answers it. */
    ObjectNode summary() {
        ObjectNode summary = mapper.createObjectNode();
        summary.putArray("Sources").addObject().put("Type", source());
        ArrayNode dataSources = summary.putArray("DataSources");
        JsonNode metadata = sourceOptions().path("log_event_metadata");
        String dataSource = metadata.path("data_source_name").asText(sourceOptions().path("data_source_name")
                .asText(""));
        if (!dataSource.isEmpty()) {
            ObjectNode entry = dataSources.addObject().put("Name", dataSource);
            if (metadata.has("data_source_type")) {
                entry.put("Type", metadata.path("data_source_type").asText());
            }
        }
        ArrayNode processors = summary.putArray("Processors");
        processors().forEach(processor -> processors.add(plugin(processor)));
        summary.put("ProcessorCount", processors.size());
        ArrayNode sinks = summary.putArray("Sinks");
        sinks().forEach(sink -> sinks.add(plugin(sink)));
        return summary;
    }

    // ================================================================ running records

    /** Runs records through the processors. */
    List<Output> run(List<Record> records) {
        List<Output> outputs = new ArrayList<>();
        for (Record record : records) {
            try {
                ObjectNode event = event(record);
                boolean kept = process(event);
                outputs.add(kept ? new Output(output(event), null) : new Output(null, null));
            } catch (ProcessingException e) {
                outputs.add(new Output(null, e.getMessage()));
            }
        }
        return outputs;
    }

    /** Runs one record, returning the event it became, or null when a processor dropped it. */
    ObjectNode runOne(Record record) {
        ObjectNode event = event(record);
        return process(event) ? event : null;
    }

    /** The sink an event goes to: the first whose condition holds, or that has none. */
    JsonNode route(ObjectNode event) {
        for (JsonNode sink : sinks()) {
            String when = options(sink).path("when").asText(null);
            if (when == null || PipelineExpression.compile(when).test(event)) {
                return options(sink);
            }
        }
        return null;
    }

    private ObjectNode event(Record record) {
        ObjectNode event = mapper.createObjectNode();
        if (metrics() || "JSON".equals(record.type())) {
            try {
                JsonNode parsed = mapper.readTree(record.data() == null ? "" : record.data());
                if (parsed != null && parsed.isObject()) {
                    if (metrics()) {
                        return (ObjectNode) parsed;
                    }
                    event.setAll((ObjectNode) parsed);
                    event.put(MESSAGE, record.data());
                    return event;
                }
            } catch (JsonProcessingException e) {
                throw new ProcessingException("The record isn't valid JSON: " + e.getOriginalMessage());
            }
        }
        event.put(MESSAGE, record.data() == null ? "" : record.data());
        return event;
    }

    Record output(ObjectNode event) {
        if (metrics()) {
            return new Record(event.toString(), "JSON");
        }
        ObjectNode copy = event.deepCopy();
        copy.remove(MESSAGE);
        if (copy.isEmpty()) {
            return new Record(event.path(MESSAGE).asText(), "STRING");
        }
        return new Record(copy.toString(), "JSON");
    }

    static final class ProcessingException extends RuntimeException {
        ProcessingException(String message) {
            super(message);
        }
    }

    private boolean process(ObjectNode event) {
        List<JsonNode> list = processors();
        for (int i = 0; i < list.size(); i++) {
            JsonNode entry = list.get(i);
            String name = plugin(entry);
            JsonNode options = options(entry);
            String when = options.path("when").asText(null);
            if (when != null && !PipelineExpression.compile(when).test(event)) {
                if (i == 0 && "grok".equals(name)) {
                    return false;
                }
                continue;
            }
            if ("drop_events".equals(name)) {
                return false;
            }
            apply(name, options, event);
        }
        return true;
    }

    private void apply(String name, JsonNode options, ObjectNode event) {
        switch (name) {
            case "parse_json" -> parseJson(options, event);
            case "csv" -> csv(options, event);
            case "key_value" -> keyValue(options, event);
            case "grok" -> grok(options, event);
            case "parse_vpc" -> vpc(event);
            case "parse_xml" -> xml(options, event);
            case "add_entries" -> addEntries(options, event);
            case "copy_values" -> moveOrCopy(options, event, false);
            case "move_keys", "rename_keys" -> moveOrCopy(options, event, true);
            case "delete_entries" -> options.path("with_keys").forEach(key -> FieldPath.remove(event, key.asText()));
            case "flatten" -> flatten(options, event);
            case "lowercase_string", "uppercase_string", "trim_string" -> strings(name, options, event);
            case "substitute_string" -> substitute(options, event);
            case "truncate" -> truncate(options, event);
            case "convert_entry_type" -> convert(options, event);
            case "select_entries" -> select(options, event);
            case "list_to_map" -> listToMap(options, event);
            case "add_attributes" -> options.path("attributes").forEach(attribute -> FieldPath.set(event,
                    attribute.path("key").asText(), attribute.path("value").deepCopy()));
            case "delete_attributes" -> options.path("with_keys").forEach(key -> FieldPath.remove(event,
                    key.asText()));
            default -> throw new ProcessingException("The processor " + name + " doesn't run on Floci.");
        }
    }

    // ---------------------------------------------------------------- entries and conditions

    /** The entries of a processor that run on this event, honoring each entry's when and when_else. */
    private static List<JsonNode> entries(JsonNode options, ObjectNode event) {
        List<JsonNode> chosen = new ArrayList<>();
        boolean matched = false;
        List<JsonNode> fallbacks = new ArrayList<>();
        for (JsonNode entry : options.path("entries")) {
            if (entry.has("when_else")) {
                fallbacks.add(entry);
            } else if (entry.has("when")) {
                if (PipelineExpression.compile(entry.path("when").asText()).test(event)) {
                    chosen.add(entry);
                    matched = true;
                }
            } else {
                chosen.add(entry);
            }
        }
        if (!matched) {
            chosen.addAll(fallbacks);
        }
        return chosen;
    }

    private void addEntries(JsonNode options, ObjectNode event) {
        for (JsonNode entry : entries(options, event)) {
            String key = entry.path("key").asText();
            if (FieldPath.get(event, key).isMissingNode() || entry.path("overwrite_if_key_exists").asBoolean(false)) {
                FieldPath.set(event, key, entry.path("value").deepCopy());
            }
        }
    }

    private void moveOrCopy(JsonNode options, ObjectNode event, boolean move) {
        for (JsonNode entry : entries(options, event)) {
            String from = entry.path("from_key").asText();
            String to = entry.path("to_key").asText();
            JsonNode value = FieldPath.get(event, from);
            if (value.isMissingNode()) {
                continue;
            }
            if (!FieldPath.get(event, to).isMissingNode() && !entry.path("overwrite_if_to_key_exists")
                    .asBoolean(false)) {
                continue;
            }
            FieldPath.set(event, to, value.deepCopy());
            if (move) {
                FieldPath.remove(event, from);
            }
        }
    }

    // ---------------------------------------------------------------- parsers

    private String text(ObjectNode event, JsonNode options) {
        String source = options.path("source").asText(MESSAGE);
        JsonNode value = FieldPath.get(event, source);
        if (value.isMissingNode() || value.isNull()) {
            throw new ProcessingException("The field " + source + " is missing.");
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private void place(ObjectNode event, JsonNode options, ObjectNode parsed) {
        String destination = options.path("destination").asText(null);
        if (destination == null || destination.isBlank()) {
            event.setAll(parsed);
        } else {
            FieldPath.set(event, destination, parsed);
        }
    }

    private void parseJson(JsonNode options, ObjectNode event) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(text(event, options));
        } catch (JsonProcessingException e) {
            throw new ProcessingException("The log event isn't JSON: " + e.getOriginalMessage());
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ProcessingException("The log event isn't a JSON object.");
        }
        place(event, options, (ObjectNode) parsed);
    }

    private void csv(JsonNode options, ObjectNode event) {
        String line = text(event, options);
        char delimiter = options.path("delimiter").asText(",").charAt(0);
        char quote = options.path("quote_character").asText("\"").charAt(0);
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == quote) {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == quote) {
                    current.append(quote);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == delimiter && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        ObjectNode parsed = mapper.createObjectNode();
        JsonNode names = options.path("column_names");
        for (int i = 0; i < values.size(); i++) {
            String name = i < names.size() ? names.get(i).asText() : "column_" + (i + 1);
            parsed.put(name, values.get(i));
        }
        place(event, options, parsed);
    }

    private void keyValue(JsonNode options, ObjectNode event) {
        String line = text(event, options);
        String fields = options.path("field_delimiter").asText("&");
        String pairs = options.path("key_value_delimiter").asText("=");
        String prefix = options.path("prefix").asText("");
        ObjectNode parsed = mapper.createObjectNode();
        for (String field : line.split(Pattern.quote(fields))) {
            if (field.isEmpty()) {
                continue;
            }
            int at = field.indexOf(pairs);
            if (at < 0) {
                if (options.has("non_match_value")) {
                    parsed.put(prefix + field.trim(), options.path("non_match_value").asText());
                }
                continue;
            }
            parsed.put(prefix + field.substring(0, at).trim(), field.substring(at + pairs.length()).trim());
        }
        String destination = options.path("destination").asText(null);
        if (destination != null && !FieldPath.get(event, destination).isMissingNode()
                && !options.path("overwrite_if_destination_exists").asBoolean(false)) {
            return;
        }
        place(event, options, parsed);
    }

    private static final Map<String, String> GROK = Map.ofEntries(
            Map.entry("WORD", "\\b\\w+\\b"), Map.entry("NOTSPACE", "\\S+"), Map.entry("SPACE", "\\s*"),
            Map.entry("DATA", ".*?"), Map.entry("GREEDYDATA", ".*"), Map.entry("INT", "[+-]?\\d+"),
            Map.entry("NUMBER", "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"), Map.entry("BASE10NUM",
                    "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"), Map.entry("POSINT", "\\b[1-9]\\d*\\b"),
            Map.entry("IPV4", "(?:\\d{1,3}\\.){3}\\d{1,3}"), Map.entry("IPV6", "[0-9A-Fa-f:]*:[0-9A-Fa-f:.]+"),
            Map.entry("IP", "(?:[0-9A-Fa-f:]*:[0-9A-Fa-f:.]+|(?:\\d{1,3}\\.){3}\\d{1,3})"),
            Map.entry("HOSTNAME", "\\b[0-9A-Za-z][0-9A-Za-z-]{0,62}(?:\\.[0-9A-Za-z][0-9A-Za-z-]{0,62})*\\.?\\b"),
            Map.entry("USERNAME", "[a-zA-Z0-9._-]+"), Map.entry("USER", "[a-zA-Z0-9._-]+"),
            Map.entry("LOGLEVEL", "(?:[Aa]lert|ALERT|[Tt]race|TRACE|[Dd]ebug|DEBUG|[Nn]otice|NOTICE|[Ii]nfo|INFO"
                    + "|[Ww]arn(?:ing)?|WARN(?:ING)?|[Ee]rr(?:or)?|ERR(?:OR)?|[Cc]rit(?:ical)?|CRIT(?:ICAL)?"
                    + "|[Ff]atal|FATAL|[Ss]evere|SEVERE|EMERG(?:ENCY)?|[Ee]merg(?:ency)?)"),
            Map.entry("TIMESTAMP_ISO8601", "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:?\\d{2}(?::?\\d{2}(?:[.,]\\d+)?)?"
                    + "(?:Z|[+-]\\d{2}:?\\d{2})?"),
            Map.entry("URIPATH", "(?:/[A-Za-z0-9$.+!*'(){},~:;=@#%&_\\-]*)+"), Map.entry("QUOTEDSTRING",
                    "\"(?:[^\"\\\\]|\\\\.)*\""), Map.entry("UUID", "[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12}"),
            Map.entry("HTTPDATE", "\\d{2}/\\w{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
            Map.entry("COMMONAPACHELOG", "%{IPORHOST:clientip} %{USER:ident} %{USER:auth} \\[%{HTTPDATE:timestamp}\\]"
                    + " \"(?:%{WORD:verb} %{NOTSPACE:request}(?: HTTP/%{NUMBER:httpversion})?|%{DATA:rawrequest})\""
                    + " %{NUMBER:response} (?:%{NUMBER:bytes}|-)"),
            Map.entry("IPORHOST", "(?:(?:\\d{1,3}\\.){3}\\d{1,3}|\\b[0-9A-Za-z][0-9A-Za-z.-]*\\b)"));
    private static final Pattern GROK_TOKEN = Pattern.compile("%\\{(\\w+)(?::([\\w.@\\[\\]\"]+))?(?::\\w+)?}");

    static String grokRegex(String pattern, List<String> names, int depth) {
        if (depth > 5) {
            throw new IllegalArgumentException("Grok patterns nest too deeply.");
        }
        Matcher matcher = GROK_TOKEN.matcher(pattern);
        StringBuilder regex = new StringBuilder();
        while (matcher.find()) {
            String definition = GROK.get(matcher.group(1));
            if (definition == null) {
                throw new IllegalArgumentException("The grok pattern " + matcher.group(1) + " isn't known.");
            }
            String inner = grokRegex(definition, names, depth + 1);
            String replacement;
            if (matcher.group(2) != null) {
                names.add(matcher.group(2));
                replacement = "(?<g" + (names.size() - 1) + ">" + inner + ")";
            } else {
                replacement = "(?:" + inner + ")";
            }
            matcher.appendReplacement(regex, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(regex);
        return regex.toString();
    }

    private void grok(JsonNode options, ObjectNode event) {
        JsonNode match = options.path("match");
        if (!match.isObject() || match.size() != 1) {
            throw new ProcessingException("grok's match takes one field.");
        }
        String field = match.fieldNames().next();
        String pattern = match.path(field).path(0).asText(match.path(field).asText(""));
        JsonNode value = FieldPath.get(event, field);
        if (value.isMissingNode()) {
            value = FieldPath.get(event, MESSAGE);
        }
        List<String> names = new ArrayList<>();
        Matcher matcher;
        try {
            matcher = Pattern.compile(grokRegex(pattern, names, 0)).matcher(value.asText());
        } catch (IllegalArgumentException e) {
            throw new ProcessingException(e.getMessage());
        }
        if (!matcher.find()) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            String captured = matcher.group("g" + i);
            if (captured != null) {
                FieldPath.set(event, names.get(i), mapper.getNodeFactory().textNode(captured));
            }
        }
    }

    private static final List<String> VPC_FIELDS = List.of("version", "account_id", "interface_id", "srcaddr",
            "dstaddr", "srcport", "dstport", "protocol", "packets", "bytes", "start", "end", "action", "log_status");

    private void vpc(ObjectNode event) {
        String[] values = event.path(MESSAGE).asText().trim().split("\\s+");
        for (int i = 0; i < values.length && i < VPC_FIELDS.size(); i++) {
            event.put(VPC_FIELDS.get(i), values[i]);
        }
    }

    private void xml(JsonNode options, ObjectNode event) {
        String text = text(event, options);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            place(event, options, element(root, 0));
        } catch (Exception e) {
            event.put("@pipeline.processing.status", "error");
        }
    }

    private ObjectNode element(Element element, int depth) {
        if (depth > 25) {
            throw new ProcessingException("The XML nests deeper than 25 levels.");
        }
        ObjectNode node = mapper.createObjectNode();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            node.put(attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                boolean leaf = childElement.getElementsByTagName("*").getLength() == 0
                        && childElement.getAttributes().getLength() == 0;
                if (leaf) {
                    node.put(childElement.getTagName(), childElement.getTextContent());
                } else {
                    node.set(childElement.getTagName(), element(childElement, depth + 1));
                }
            }
        }
        return node;
    }

    // ---------------------------------------------------------------- transformations

    private void flatten(JsonNode options, ObjectNode event) {
        String source = options.path("source").asText("");
        JsonNode nested = source.isEmpty() ? event : FieldPath.get(event, source);
        if (!nested.isObject()) {
            return;
        }
        List<String> exclude = new ArrayList<>();
        options.path("exclude_keys").forEach(key -> exclude.add(key.asText()));
        ObjectNode flat = mapper.createObjectNode();
        flattenInto(flat, "", nested, exclude);
        String target = options.path("target").asText("");
        if (options.path("remove_processed_fields").asBoolean(false) && !source.isEmpty()) {
            FieldPath.remove(event, source);
        }
        if (target.isEmpty()) {
            event.setAll(flat);
        } else {
            FieldPath.set(event, target, flat);
        }
    }

    private static void flattenInto(ObjectNode flat, String prefix, JsonNode node, List<String> exclude) {
        node.properties().forEach(entry -> {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (exclude.contains(entry.getKey()) || exclude.contains(key)) {
                return;
            }
            if (entry.getValue().isObject()) {
                flattenInto(flat, key, entry.getValue(), exclude);
            } else {
                flat.set(key, entry.getValue().deepCopy());
            }
        });
    }

    private void strings(String name, JsonNode options, ObjectNode event) {
        for (JsonNode key : options.path("with_keys")) {
            JsonNode value = FieldPath.get(event, key.asText());
            if (!value.isTextual()) {
                continue;
            }
            String text = value.asText();
            String changed = switch (name) {
                case "lowercase_string" -> text.toLowerCase(Locale.ROOT);
                case "uppercase_string" -> text.toUpperCase(Locale.ROOT);
                default -> text.trim();
            };
            FieldPath.set(event, key.asText(), mapper.getNodeFactory().textNode(changed));
        }
    }

    private void substitute(JsonNode options, ObjectNode event) {
        for (JsonNode entry : entries(options, event)) {
            String key = entry.path("source").asText();
            JsonNode value = FieldPath.get(event, key);
            if (value.isTextual()) {
                FieldPath.set(event, key, mapper.getNodeFactory().textNode(value.asText()
                        .replaceAll(entry.path("from").asText(), entry.path("to").asText(""))));
            }
        }
    }

    private void truncate(JsonNode options, ObjectNode event) {
        JsonNode entries = options.path("entries").isArray() ? options.path("entries")
                : mapper.createArrayNode().add(options);
        for (JsonNode entry : entries) {
            int length = entry.path("length").asInt(Integer.MAX_VALUE);
            for (JsonNode key : entry.path("source_keys")) {
                JsonNode value = FieldPath.get(event, key.asText());
                if (value.isTextual() && value.asText().length() > length) {
                    FieldPath.set(event, key.asText(), mapper.getNodeFactory().textNode(value.asText()
                            .substring(0, length)));
                }
            }
        }
    }

    private void convert(JsonNode options, ObjectNode event) {
        List<String> keys = new ArrayList<>();
        if (options.has("key")) {
            keys.add(options.path("key").asText());
        }
        options.path("keys").forEach(key -> keys.add(key.asText()));
        String type = options.path("type").asText("string");
        for (String key : keys) {
            JsonNode value = FieldPath.get(event, key);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String text = value.asText();
            try {
                JsonNode converted = switch (type) {
                    case "integer", "long" -> mapper.getNodeFactory().numberNode(Long.parseLong(text.trim()));
                    case "double" -> mapper.getNodeFactory().numberNode(Double.parseDouble(text.trim()));
                    case "boolean" -> mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(text.trim()));
                    default -> mapper.getNodeFactory().textNode(text);
                };
                FieldPath.set(event, key, converted);
            } catch (NumberFormatException e) {
                throw new ProcessingException("The field " + key + " isn't a " + type + ".");
            }
        }
    }

    private void select(JsonNode options, ObjectNode event) {
        ObjectNode kept = mapper.createObjectNode();
        for (JsonNode key : options.path("include_keys")) {
            JsonNode value = FieldPath.get(event, key.asText());
            if (!value.isMissingNode()) {
                FieldPath.set(kept, key.asText(), value.deepCopy());
            }
        }
        if (event.has(MESSAGE)) {
            kept.set(MESSAGE, event.get(MESSAGE));
        }
        event.removeAll();
        event.setAll(kept);
    }

    private void listToMap(JsonNode options, ObjectNode event) {
        String source = options.path("source").asText();
        JsonNode list = FieldPath.get(event, source);
        if (!list.isArray()) {
            return;
        }
        String key = options.path("key").asText("key");
        String valueKey = options.path("value_key").asText(null);
        ObjectNode map = mapper.createObjectNode();
        for (JsonNode item : list) {
            if (item.has(key)) {
                map.set(item.path(key).asText(), valueKey == null ? item.deepCopy() : item.path(valueKey).deepCopy());
            }
        }
        String target = options.path("target").asText("");
        if (target.isEmpty()) {
            event.setAll(map);
        } else {
            FieldPath.set(event, target, map);
        }
    }
}
