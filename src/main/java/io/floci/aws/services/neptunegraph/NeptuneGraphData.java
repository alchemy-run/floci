package io.floci.aws.services.neptunegraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Graph data moving in and out of a graph: the node and edge files Neptune Analytics imports (Gremlin CSV, openCypher
 * CSV, N-Triples, and Parquet rows), the snapshots and exports it writes, and loading and dumping a graph's Neo4j
 * through {@link NeptuneGraphEngine}. An imported node or edge keeps the id its source gave it as its {@code ~id}
 * property, which exports write as its id.
 */
final class NeptuneGraphData {

    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final int BATCH = 500;

    private NeptuneGraphData() {
    }

    record Node(String id, List<String> labels, ObjectNode properties) {
    }

    record Edge(String id, String from, String to, String type, ObjectNode properties) {
    }

    /** Nodes and edges, with the problems found reading them. */
    static final class Graph {
        final Map<String, Node> nodes = new LinkedHashMap<>();
        final List<Edge> edges = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        long statements;

        ObjectNode json(ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode nodeArray = out.putArray("nodes");
            for (Node node : nodes.values()) {
                ObjectNode item = nodeArray.addObject();
                item.put("id", node.id());
                ArrayNode labels = item.putArray("labels");
                node.labels().forEach(labels::add);
                item.set("properties", node.properties());
            }
            ArrayNode edgeArray = out.putArray("edges");
            for (Edge edge : edges) {
                ObjectNode item = edgeArray.addObject();
                item.put("id", edge.id());
                item.put("from", edge.from());
                item.put("to", edge.to());
                item.put("type", edge.type());
                item.set("properties", edge.properties());
            }
            return out;
        }

        static Graph of(JsonNode json) {
            Graph graph = new Graph();
            for (JsonNode node : json.path("nodes")) {
                List<String> labels = new ArrayList<>();
                node.path("labels").forEach(label -> labels.add(label.asText()));
                graph.nodes.put(node.path("id").asText(), new Node(node.path("id").asText(), labels,
                        (ObjectNode) node.path("properties")));
            }
            for (JsonNode edge : json.path("edges")) {
                graph.edges.add(new Edge(edge.path("id").asText(null), edge.path("from").asText(),
                        edge.path("to").asText(), edge.path("type").asText(), (ObjectNode) edge.path("properties")));
            }
            return graph;
        }
    }

    // ================================================================ CSV

    /** RFC 4180 records: quoted fields may hold commas, quotes (doubled) and line breaks. */
    static List<List<String>> csv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || field.length() > 0) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    /** A property column: its name, type and whether it holds a list. */
    record Column(String name, String type, boolean list) {
        static Column of(String header) {
            String name = header;
            String type = "String";
            int colon = header.lastIndexOf(':');
            if (colon > 0) {
                name = header.substring(0, colon);
                type = header.substring(colon + 1);
            }
            int cardinality = type.indexOf('(');
            if (cardinality >= 0) {
                type = type.substring(0, cardinality);
            }
            boolean list = type.endsWith("[]");
            if (list) {
                type = type.substring(0, type.length() - 2);
            }
            return new Column(name.trim(), type.trim(), list);
        }
    }

    private static JsonNode typed(ObjectMapper mapper, String type, String value) {
        String lower = type.toLowerCase(Locale.ROOT);
        try {
            return switch (lower) {
                case "bool", "boolean" -> mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value.trim()));
                case "byte", "short", "int", "integer", "long" -> mapper.getNodeFactory().numberNode(
                        Long.parseLong(value.trim()));
                case "float", "double" -> mapper.getNodeFactory().numberNode(Double.parseDouble(value.trim()));
                default -> mapper.getNodeFactory().textNode(value);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The value '" + value + "' isn't a " + type + ".");
        }
    }

    private static void property(ObjectMapper mapper, ObjectNode properties, Column column, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (column.list()) {
            ArrayNode list = properties.putArray(column.name());
            for (String item : value.split(";")) {
                if (!item.isEmpty()) {
                    list.add(typed(mapper, column.type(), item));
                }
            }
        } else {
            properties.set(column.name(), typed(mapper, column.type(), value));
        }
    }

    /** Reads a Gremlin CSV ({@code ~id}, {@code ~label}, {@code ~from}, {@code ~to}) or openCypher CSV file. */
    static void readCsv(ObjectMapper mapper, String file, String text, boolean openCypher, Graph graph) {
        List<List<String>> records = csv(text);
        if (records.isEmpty()) {
            return;
        }
        List<String> headers = records.get(0);
        int id = -1;
        int label = -1;
        int from = -1;
        int to = -1;
        List<Integer> ignored = new ArrayList<>();
        Map<Integer, Column> columns = new TreeMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i).trim();
            String bare = header.replaceAll("\\(.*\\)$", "");
            if (openCypher) {
                if (bare.endsWith(":ID")) {
                    id = i;
                } else if (bare.equals(":LABEL")) {
                    label = i;
                } else if (bare.endsWith(":START_ID")) {
                    from = i;
                } else if (bare.endsWith(":END_ID")) {
                    to = i;
                } else if (bare.equals(":TYPE")) {
                    label = i;
                } else if (bare.endsWith(":IGNORE")) {
                    ignored.add(i);
                } else {
                    columns.put(i, Column.of(header));
                }
            } else {
                switch (header) {
                    case "~id" -> id = i;
                    case "~label" -> label = i;
                    case "~from" -> from = i;
                    case "~to" -> to = i;
                    default -> columns.put(i, Column.of(header));
                }
            }
        }
        boolean edges = from >= 0 && to >= 0;
        if (!edges && id < 0) {
            graph.errors.add(file + ": the header names no " + (openCypher ? ":ID" : "~id") + " column.");
            return;
        }
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            try {
                ObjectNode properties = mapper.createObjectNode();
                for (Map.Entry<Integer, Column> column : columns.entrySet()) {
                    if (!ignored.contains(column.getKey()) && column.getKey() < record.size()) {
                        property(mapper, properties, column.getValue(), record.get(column.getKey()));
                    }
                }
                String identifier = id >= 0 && id < record.size() ? record.get(id) : null;
                String labels = label >= 0 && label < record.size() ? record.get(label) : "";
                graph.statements++;
                if (edges) {
                    if (labels.isEmpty()) {
                        throw new IllegalArgumentException("The edge has no " + (openCypher ? ":TYPE" : "~label")
                                + ".");
                    }
                    graph.edges.add(new Edge(identifier == null || identifier.isEmpty() ? null : identifier,
                            record.get(from), record.get(to), labels, properties));
                } else {
                    if (identifier == null || identifier.isEmpty()) {
                        throw new IllegalArgumentException("The node has no id.");
                    }
                    List<String> labelList = new ArrayList<>();
                    for (String one : labels.split(";")) {
                        if (!one.isBlank()) {
                            labelList.add(one.trim());
                        }
                    }
                    merge(graph, new Node(identifier, labelList, properties));
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                graph.errors.add(file + " line " + (r + 1) + ": " + e.getMessage());
            }
        }
    }

    private static void merge(Graph graph, Node node) {
        Node existing = graph.nodes.get(node.id());
        if (existing == null) {
            graph.nodes.put(node.id(), node);
            return;
        }
        for (String label : node.labels()) {
            if (!existing.labels().contains(label)) {
                existing.labels().add(label);
            }
        }
        existing.properties().setAll(node.properties());
    }

    // ================================================================ N-Triples

    /** Reads N-Triples: IRIs and blank nodes become nodes, rdf:type their labels, literals their properties. */
    static void readNTriples(ObjectMapper mapper, String file, String text, String blankPrefix, Graph graph) {
        int lineNumber = 0;
        for (String raw : text.split("\n")) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                int[] at = {0};
                String subject = term(line, at, blankPrefix);
                String predicate = term(line, at, blankPrefix);
                skip(line, at);
                if (at[0] >= line.length()) {
                    throw new IllegalArgumentException("The triple has no object.");
                }
                graph.statements++;
                Node node = node(mapper, graph, subject);
                if (line.charAt(at[0]) == '"') {
                    JsonNode literal = literal(mapper, line, at);
                    node.properties().set(predicate, literal);
                } else {
                    String object = term(line, at, blankPrefix);
                    if (RDF_TYPE.equals(predicate)) {
                        if (!node.labels().contains(object)) {
                            node.labels().add(object);
                        }
                    } else {
                        node(mapper, graph, object);
                        graph.edges.add(new Edge(null, subject, object, predicate, mapper.createObjectNode()));
                    }
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                graph.errors.add(file + " line " + lineNumber + ": " + e.getMessage());
            }
        }
    }

    private static Node node(ObjectMapper mapper, Graph graph, String id) {
        return graph.nodes.computeIfAbsent(id, key -> new Node(key, new ArrayList<>(), mapper.createObjectNode()));
    }

    private static void skip(String line, int[] at) {
        while (at[0] < line.length() && Character.isWhitespace(line.charAt(at[0]))) {
            at[0]++;
        }
    }

    private static String term(String line, int[] at, String blankPrefix) {
        skip(line, at);
        if (line.charAt(at[0]) == '<') {
            int end = line.indexOf('>', at[0]);
            if (end < 0) {
                throw new IllegalArgumentException("An IRI isn't closed.");
            }
            String iri = line.substring(at[0] + 1, end);
            at[0] = end + 1;
            return iri;
        }
        if (line.startsWith("_:", at[0])) {
            int end = at[0] + 2;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                end++;
            }
            String label = line.substring(at[0] + 2, end);
            at[0] = end;
            return blankPrefix + label;
        }
        throw new IllegalArgumentException("Expected an IRI or blank node at column " + (at[0] + 1) + ".");
    }

    private static JsonNode literal(ObjectMapper mapper, String line, int[] at) {
        StringBuilder value = new StringBuilder();
        int i = at[0] + 1;
        while (i < line.length() && line.charAt(i) != '"') {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> value.append(next);
                }
            } else {
                value.append(c);
            }
            i++;
        }
        if (i >= line.length()) {
            throw new IllegalArgumentException("A literal isn't closed.");
        }
        i++;
        String type = null;
        if (line.startsWith("^^<", i)) {
            int end = line.indexOf('>', i);
            type = line.substring(i + 3, end);
            i = end + 1;
        } else if (i < line.length() && line.charAt(i) == '@') {
            while (i < line.length() && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != '.') {
                i++;
            }
        }
        at[0] = i;
        String text = value.toString();
        if (type == null) {
            return mapper.getNodeFactory().textNode(text);
        }
        String local = type.substring(type.lastIndexOf('#') + 1);
        try {
            return switch (local) {
                case "integer", "int", "long", "short", "byte", "nonNegativeInteger", "positiveInteger" ->
                        mapper.getNodeFactory().numberNode(Long.parseLong(text));
                case "double", "float", "decimal" -> mapper.getNodeFactory().numberNode(Double.parseDouble(text));
                case "boolean" -> mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(text));
                default -> mapper.getNodeFactory().textNode(text);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The literal '" + text + "' isn't a " + local + ".");
        }
    }

    // ================================================================ Parquet rows

    /** Reads Parquet rows, named like Gremlin CSV columns. */
    static void readRows(ObjectMapper mapper, String file, List<Map<String, Object>> rows, Graph graph) {
        for (Map<String, Object> row : rows) {
            graph.statements++;
            ObjectNode properties = mapper.createObjectNode();
            String id = null;
            String label = null;
            String from = null;
            String to = null;
            for (Map.Entry<String, Object> column : row.entrySet()) {
                String name = column.getKey();
                Object value = column.getValue();
                switch (name) {
                    case "~id" -> id = value == null ? null : value.toString();
                    case "~label" -> label = value == null ? null : value.toString();
                    case "~from" -> from = value == null ? null : value.toString();
                    case "~to" -> to = value == null ? null : value.toString();
                    default -> {
                        if (value != null) {
                            properties.set(Column.of(name).name(), mapper.valueToTree(value));
                        }
                    }
                }
            }
            if (from != null && to != null) {
                if (label == null) {
                    graph.errors.add(file + ": an edge row has no ~label.");
                    continue;
                }
                graph.edges.add(new Edge(id, from, to, label, properties));
            } else if (id != null) {
                List<String> labels = new ArrayList<>();
                if (label != null) {
                    for (String one : label.split(";")) {
                        if (!one.isBlank()) {
                            labels.add(one.trim());
                        }
                    }
                }
                merge(graph, new Node(id, labels, properties));
            } else {
                graph.errors.add(file + ": a row has no ~id, or no ~from and ~to.");
            }
        }
    }

    // ================================================================ loading and dumping

    private static String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** Neo4j properties hold scalars and lists of them; anything else is kept as its JSON text. */
    private static ObjectNode storable(ObjectMapper mapper, ObjectNode properties) {
        ObjectNode out = mapper.createObjectNode();
        properties.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject() || value.isArray() && value.size() > 0 && value.get(0).isContainerNode()) {
                out.put(entry.getKey(), value.toString());
            } else if (!value.isNull()) {
                out.set(entry.getKey(), value);
            }
        });
        return out;
    }

    /**
     * Loads nodes and edges into a graph; edges whose endpoints are missing are errors. Imported nodes and edges keep
     * their source ids as {@code ~id} properties; a restored snapshot's already carry theirs.
     */
    static void load(ObjectMapper mapper, NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend, Graph graph,
                     boolean sourceIds) {
        engine.run(backend, "CREATE INDEX floci_load IF NOT EXISTS FOR (n:__FlociLoad) ON (n.`~floci`)", null, 60_000);
        Map<String, List<ObjectNode>> byLabels = new LinkedHashMap<>();
        for (Node node : graph.nodes.values()) {
            String labels = String.join("\u0000", new TreeSet<>(node.labels()));
            ObjectNode row = mapper.createObjectNode();
            row.put("id", node.id());
            ObjectNode properties = storable(mapper, node.properties());
            if (sourceIds && !properties.has("~id")) {
                properties.put("~id", node.id());
            }
            row.set("props", properties);
            byLabels.computeIfAbsent(labels, key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<ObjectNode>> group : byLabels.entrySet()) {
            StringBuilder labels = new StringBuilder(":__FlociLoad");
            for (String label : group.getKey().split("\u0000")) {
                if (!label.isEmpty()) {
                    labels.append(':').append(quote(label));
                }
            }
            batches(mapper, engine, backend, group.getValue(), "UNWIND $rows AS row CREATE (x" + labels
                    + ") SET x = row.props SET x.`~floci` = row.id");
        }
        Set<String> known = graph.nodes.keySet();
        Map<String, List<ObjectNode>> byType = new LinkedHashMap<>();
        for (Edge edge : graph.edges) {
            if (!known.contains(edge.from()) || !known.contains(edge.to())) {
                graph.errors.add("The edge " + (edge.id() == null ? edge.from() + "->" + edge.to() : edge.id())
                        + " names a node that wasn't loaded.");
                continue;
            }
            ObjectNode row = mapper.createObjectNode();
            row.put("from", edge.from());
            row.put("to", edge.to());
            ObjectNode properties = storable(mapper, edge.properties());
            if (sourceIds && edge.id() != null && !properties.has("~id")) {
                properties.put("~id", edge.id());
            }
            row.set("props", properties);
            byType.computeIfAbsent(edge.type(), key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<ObjectNode>> group : byType.entrySet()) {
            batches(mapper, engine, backend, group.getValue(), "UNWIND $rows AS row MATCH (a:__FlociLoad {`~floci`: "
                    + "row.from}), (b:__FlociLoad {`~floci`: row.to}) CREATE (a)-[r:" + quote(group.getKey())
                    + "]->(b) SET r = row.props");
        }
        engine.run(backend, "MATCH (n:__FlociLoad) REMOVE n:__FlociLoad, n.`~floci`", null, 300_000);
        engine.run(backend, "DROP INDEX floci_load IF EXISTS", null, 60_000);
    }

    private static void batches(ObjectMapper mapper, NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend,
                                List<ObjectNode> rows, String statement) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            ArrayNode batch = mapper.createArrayNode();
            rows.subList(i, Math.min(rows.size(), i + BATCH)).forEach(batch::add);
            ObjectNode parameters = mapper.createObjectNode();
            parameters.set("rows", batch);
            engine.run(backend, statement, parameters, 300_000);
        }
    }

    /** Every node and edge of a graph, by the ids queries answer for them. */
    static Graph dump(ObjectMapper mapper, NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend) {
        Graph graph = new Graph();
        NeptuneGraphEngine.Result nodes = engine.run(backend, "MATCH (n) RETURN n", null, 300_000);
        for (List<JsonNode> row : nodes.rows()) {
            JsonNode node = engine.neptune(row.get(0));
            List<String> labels = new ArrayList<>();
            node.path("~labels").forEach(label -> labels.add(label.asText()));
            ObjectNode properties = (ObjectNode) node.path("~properties");
            String id = node.path("~id").asText();
            graph.nodes.put(id, new Node(id, labels, properties));
        }
        NeptuneGraphEngine.Result edges = engine.run(backend, "MATCH ()-[r]->() RETURN r", null, 300_000);
        for (List<JsonNode> row : edges.rows()) {
            JsonNode edge = engine.neptune(row.get(0));
            graph.edges.add(new Edge(edge.path("~id").asText(), edge.path("~start").asText(), edge.path("~end")
                    .asText(), edge.path("~type").asText(), (ObjectNode) edge.path("~properties")));
        }
        return graph;
    }

    // ================================================================ export

    private static String type(JsonNode value) {
        JsonNode sample = value.isArray() && value.size() > 0 ? value.get(0) : value;
        String type = sample.isBoolean() ? "Boolean" : sample.isIntegralNumber() ? "Long" : sample.isNumber()
                ? "Double" : "String";
        return value.isArray() ? type + "[]" : type;
    }

    private static String cell(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        String text;
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(item.asText()));
            text = String.join(";", items);
        } else {
            text = value.asText();
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * Applies an export filter: the labels it names, with the properties it names (renamed to their keys, from their
     * source property names, lists picking their first item when asked).
     */
    static ObjectNode filtered(ObjectMapper mapper, ObjectNode properties, JsonNode filter) {
        if (filter == null || !filter.path("properties").isObject() || filter.path("properties").isEmpty()) {
            return properties;
        }
        ObjectNode out = mapper.createObjectNode();
        filter.path("properties").properties().forEach(entry -> {
            String source = entry.getValue().path("sourcePropertyName").asText(entry.getKey());
            JsonNode value = properties.get(source);
            if (value == null) {
                return;
            }
            if (value.isArray() && "PICK_FIRST".equals(entry.getValue().path("multiValueHandling").asText())) {
                value = value.size() > 0 ? value.get(0) : null;
            }
            if (value != null) {
                out.set(entry.getKey(), value);
            }
        });
        return out;
    }

    private static JsonNode filterFor(JsonNode filters, List<String> labels) {
        if (filters == null || !filters.isObject() || filters.isEmpty()) {
            return null;
        }
        for (String label : labels) {
            if (filters.has(label)) {
                return filters.get(label);
            }
        }
        return mapper().missingNode();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    /** The openCypher CSV files of a graph's nodes and edges, after an export filter. */
    static String[] exportCsv(ObjectMapper mapper, Graph graph, JsonNode exportFilter) {
        JsonNode vertexFilter = exportFilter == null ? null : exportFilter.path("vertexFilter");
        JsonNode edgeFilter = exportFilter == null ? null : exportFilter.path("edgeFilter");
        List<Object[]> nodes = new ArrayList<>();
        Map<String, String> nodeColumns = new LinkedHashMap<>();
        for (Node node : graph.nodes.values()) {
            JsonNode filter = filterFor(vertexFilter, node.labels());
            if (filter != null && filter.isMissingNode()) {
                continue;
            }
            JsonNode explicit = node.properties().get("~id");
            ObjectNode properties = filtered(mapper, node.properties().deepCopy(), filter);
            properties.remove("~id");
            properties.properties().forEach(entry -> nodeColumns.putIfAbsent(entry.getKey(), type(entry.getValue())));
            nodes.add(new Object[] {explicit == null ? node.id() : explicit.asText(), node, properties});
        }
        StringBuilder nodeCsv = new StringBuilder(":ID,:LABEL");
        nodeColumns.forEach((name, type) -> nodeCsv.append(',').append(cell(mapper.getNodeFactory().textNode(name
                + ":" + type))));
        nodeCsv.append('\n');
        Set<String> exported = new LinkedHashSet<>();
        for (Object[] row : nodes) {
            Node node = (Node) row[1];
            ObjectNode properties = (ObjectNode) row[2];
            exported.add(node.id());
            nodeCsv.append(cell(mapper.getNodeFactory().textNode((String) row[0]))).append(',')
                    .append(cell(mapper.getNodeFactory().textNode(String.join(";", node.labels()))));
            nodeColumns.keySet().forEach(name -> nodeCsv.append(',').append(cell(properties.get(name))));
            nodeCsv.append('\n');
        }
        Map<String, String> ids = new LinkedHashMap<>();
        for (Object[] row : nodes) {
            ids.put(((Node) row[1]).id(), (String) row[0]);
        }
        List<Object[]> edges = new ArrayList<>();
        Map<String, String> edgeColumns = new LinkedHashMap<>();
        for (Edge edge : graph.edges) {
            if (!exported.contains(edge.from()) || !exported.contains(edge.to())) {
                continue;
            }
            JsonNode filter = filterFor(edgeFilter, List.of(edge.type()));
            if (filter != null && filter.isMissingNode()) {
                continue;
            }
            JsonNode explicit = edge.properties().get("~id");
            ObjectNode properties = filtered(mapper, edge.properties().deepCopy(), filter);
            properties.remove("~id");
            properties.properties().forEach(entry -> edgeColumns.putIfAbsent(entry.getKey(), type(entry.getValue())));
            edges.add(new Object[] {explicit == null ? edge.id() : explicit.asText(), edge, properties});
        }
        StringBuilder edgeCsv = new StringBuilder(":ID,:START_ID,:END_ID,:TYPE");
        edgeColumns.forEach((name, type) -> edgeCsv.append(',').append(cell(mapper.getNodeFactory().textNode(name
                + ":" + type))));
        edgeCsv.append('\n');
        for (Object[] row : edges) {
            Edge edge = (Edge) row[1];
            ObjectNode properties = (ObjectNode) row[2];
            edgeCsv.append(cell(mapper.getNodeFactory().textNode((String) row[0]))).append(',')
                    .append(cell(mapper.getNodeFactory().textNode(ids.get(edge.from())))).append(',')
                    .append(cell(mapper.getNodeFactory().textNode(ids.get(edge.to())))).append(',')
                    .append(cell(mapper.getNodeFactory().textNode(edge.type())));
            edgeColumns.keySet().forEach(name -> edgeCsv.append(',').append(cell(properties.get(name))));
            edgeCsv.append('\n');
        }
        return new String[] {nodeCsv.toString(), edgeCsv.toString(), String.valueOf(nodes.size()),
            String.valueOf(edges.size())};
    }

    // ================================================================ summary

    static ObjectNode summary(ObjectMapper mapper, NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend,
                              boolean detailed) {
        ObjectNode out = mapper.createObjectNode();
        long nodes = single(engine, backend, "MATCH (n) RETURN count(n)");
        long edges = single(engine, backend, "MATCH ()-[r]->() RETURN count(r)");
        out.put("numNodes", nodes);
        out.put("numEdges", edges);
        ArrayNode nodeLabels = out.putArray("nodeLabels");
        engine.run(backend, "MATCH (n) UNWIND labels(n) AS label RETURN DISTINCT label ORDER BY label", null, 60_000)
                .rows().forEach(row -> nodeLabels.add(engine.plain(row.get(0))));
        ArrayNode edgeLabels = out.putArray("edgeLabels");
        engine.run(backend, "MATCH ()-[r]->() RETURN DISTINCT type(r) ORDER BY type(r)", null, 60_000).rows()
                .forEach(row -> edgeLabels.add(engine.plain(row.get(0))));
        out.put("numNodeLabels", nodeLabels.size());
        out.put("numEdgeLabels", edgeLabels.size());
        long nodeValues = properties(engine, backend, out.putArray("nodeProperties"),
                "MATCH (n) UNWIND [k IN keys(n) WHERE k <> '~id'] AS k RETURN k, count(*) ORDER BY k");
        long edgeValues = properties(engine, backend, out.putArray("edgeProperties"),
                "MATCH ()-[r]->() UNWIND [k IN keys(r) WHERE k <> '~id'] AS k RETURN k, count(*) ORDER BY k");
        out.put("numNodeProperties", out.path("nodeProperties").size());
        out.put("numEdgeProperties", out.path("edgeProperties").size());
        out.put("totalNodePropertyValues", nodeValues);
        out.put("totalEdgePropertyValues", edgeValues);
        if (detailed) {
            ArrayNode structures = out.putArray("nodeStructures");
            for (List<JsonNode> row : engine.run(backend, "MATCH (n) OPTIONAL MATCH (n)-[r]->() WITH n, "
                    + "[k IN keys(n) WHERE k <> '~id'] AS props, collect(DISTINCT type(r)) AS out RETURN props, out, "
                    + "count(DISTINCT n)", null, 60_000).rows()) {
                ObjectNode structure = structures.addObject();
                structure.put("count", engine.plain(row.get(2)).asLong());
                structure.set("nodeProperties", sorted(mapper, engine.plain(row.get(0))));
                structure.set("distinctOutgoingEdgeLabels", sorted(mapper, engine.plain(row.get(1))));
            }
            ArrayNode edgeStructures = out.putArray("edgeStructures");
            for (List<JsonNode> row : engine.run(backend, "MATCH ()-[r]->() WITH [k IN keys(r) WHERE k <> '~id'] AS "
                    + "props RETURN props, count(*)", null, 60_000).rows()) {
                ObjectNode structure = edgeStructures.addObject();
                structure.put("count", engine.plain(row.get(1)).asLong());
                structure.set("edgeProperties", sorted(mapper, engine.plain(row.get(0))));
            }
        }
        return out;
    }

    private static ArrayNode sorted(ObjectMapper mapper, JsonNode values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(value -> sorted.add(value.asText()));
        ArrayNode out = mapper.createArrayNode();
        sorted.forEach(out::add);
        return out;
    }

    private static long single(NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend, String statement) {
        List<List<JsonNode>> rows = engine.run(backend, statement, null, 60_000).rows();
        return rows.isEmpty() ? 0 : engine.plain(rows.get(0).get(0)).asLong();
    }

    private static long properties(NeptuneGraphEngine engine, NeptuneGraphEngine.Backend backend, ArrayNode out,
                                   String statement) {
        long total = 0;
        for (List<JsonNode> row : engine.run(backend, statement, null, 60_000).rows()) {
            long count = engine.plain(row.get(1)).asLong();
            out.addObject().put(engine.plain(row.get(0)).asText(), count);
            total += count;
        }
        return total;
    }
}
