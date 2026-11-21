package io.floci.aws.services.xray;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Traces built from segment documents: segments, their nested and separately sent subsegments, the services they
 * belong to and the calls between them. Trace summaries, filter scopes and service graphs are computed from them.
 */
final class XRayTraces {

    /** The segment namespace of calls made with an AWS SDK (not a partition). */
    private static final String AWS_NAMESPACE = "aws"; // partition-literal: X-Ray's segment namespace
    private static final String REMOTE_NAMESPACE = "remote";

    private XRayTraces() {
    }

    /** A service as X-Ray identifies it: its name and, when known, its origin type. */
    record ServiceKey(String name, String type) {

        boolean matches(Node node) {
            return (name == null || name.equals(node.name)) && (type == null || type.equals(node.origin));
        }
    }

    /** A segment or subsegment. */
    static final class Node {
        String id;
        String traceId;
        String name;
        String origin;
        String parentId;
        String namespace;
        boolean segment;
        boolean inProgress;
        double start;
        double end;
        boolean error;
        boolean fault;
        boolean throttle;
        String httpMethod;
        String httpUrl;
        Integer httpStatus;
        String userAgent;
        String clientIp;
        String user;
        String resourceArn;
        String accountId;
        String instanceId;
        String availabilityZone;
        final Map<String, Object> annotations = new LinkedHashMap<>();
        final List<String[]> exceptions = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        Node owner;

        double duration() {
            return Math.max(0, end - start);
        }

        /** This node and everything under it. */
        void walk(List<Node> into) {
            into.add(this);
            children.forEach(child -> child.walk(into));
        }
    }

    /** One trace: its documents parsed into segments. */
    static final class Trace {
        final String id;
        final List<Node> segments = new ArrayList<>();
        final Map<String, Node> byId = new HashMap<>();
        boolean partial;

        Trace(String id) {
            this.id = id;
        }

        double start() {
            return segments.stream().mapToDouble(n -> n.start).min().orElse(0);
        }

        double end() {
            return segments.stream().mapToDouble(n -> n.end).max().orElse(0);
        }

        double duration() {
            return Math.max(0, end() - start());
        }

        /** The entry segment: the first one whose parent isn't in the trace. */
        Node root() {
            return segments.stream().filter(n -> n.parentId == null || !byId.containsKey(n.parentId))
                    .min(Comparator.comparingDouble(n -> n.start))
                    .orElse(segments.isEmpty() ? null : segments.get(0));
        }

        List<Node> all() {
            List<Node> out = new ArrayList<>();
            segments.forEach(segment -> segment.walk(out));
            return out;
        }
    }

    // ---------- parsing ----------

    /** Parses a trace's documents. Separately sent subsegments are attached to their parents. */
    static Trace parse(ObjectMapper mapper, String traceId, List<String> documents) {
        Trace trace = new Trace(traceId);
        List<Node> detached = new ArrayList<>();
        for (String document : documents) {
            JsonNode json;
            try {
                json = mapper.readTree(document);
            } catch (Exception e) {
                continue;
            }
            boolean segment = !"subsegment".equals(json.path("type").asText());
            Node node = node(json, segment, traceId, null);
            if (segment) {
                // A later document for the same segment replaces the earlier one (in_progress updates).
                trace.segments.removeIf(existing -> existing.id.equals(node.id));
                trace.segments.add(node);
            } else {
                detached.add(node);
            }
        }
        for (Node segment : trace.segments) {
            index(trace, segment);
        }
        for (Node subsegment : detached) {
            Node parent = trace.byId.get(subsegment.parentId);
            if (parent == null) {
                trace.partial = true;
                continue;
            }
            parent.children.removeIf(existing -> existing.id.equals(subsegment.id));
            parent.children.add(subsegment);
            subsegment.owner = parent.owner != null ? parent.owner : parent;
            index(trace, subsegment);
        }
        trace.partial |= trace.all().stream().anyMatch(n -> n.inProgress);
        return trace;
    }

    private static void index(Trace trace, Node node) {
        trace.byId.put(node.id, node);
        for (Node child : node.children) {
            child.owner = node.segment ? node : node.owner;
            index(trace, child);
        }
    }

    private static Node node(JsonNode json, boolean segment, String traceId, Node owner) {
        Node node = new Node();
        node.id = json.path("id").asText();
        node.traceId = traceId;
        node.name = json.path("name").asText();
        node.origin = json.hasNonNull("origin") ? json.get("origin").asText() : null;
        node.parentId = json.hasNonNull("parent_id") ? json.get("parent_id").asText() : null;
        node.namespace = json.hasNonNull("namespace") ? json.get("namespace").asText() : null;
        node.segment = segment;
        node.inProgress = json.path("in_progress").asBoolean(false);
        node.start = json.path("start_time").asDouble();
        node.end = json.has("end_time") ? json.get("end_time").asDouble() : node.start;
        node.error = json.path("error").asBoolean(false);
        node.fault = json.path("fault").asBoolean(false);
        node.throttle = json.path("throttle").asBoolean(false);
        JsonNode http = json.path("http");
        node.httpMethod = text(http.path("request"), "method");
        node.httpUrl = text(http.path("request"), "url");
        node.userAgent = text(http.path("request"), "user_agent");
        node.clientIp = text(http.path("request"), "client_ip");
        if (http.path("response").has("status")) {
            node.httpStatus = http.path("response").get("status").asInt();
            if (node.httpStatus == 429) {
                node.throttle = true;
            }
        }
        node.user = text(json, "user");
        node.resourceArn = text(json, "resource_arn");
        node.accountId = text(json.path("aws"), "account_id");
        node.instanceId = text(json.path("aws").path("ec2"), "instance_id");
        node.availabilityZone = text(json.path("aws").path("ec2"), "availability_zone");
        json.path("annotations").fields().forEachRemaining(e -> node.annotations.put(e.getKey(),
                e.getValue().isNumber() ? e.getValue().asDouble() : e.getValue().isBoolean()
                        ? e.getValue().asBoolean() : e.getValue().asText()));
        for (JsonNode exception : json.path("cause").path("exceptions")) {
            node.exceptions.add(new String[]{exception.path("type").asText(null), exception.path("message")
                    .asText(null), String.valueOf(exception.path("remote").asBoolean(false))});
        }
        node.owner = owner;
        for (JsonNode child : json.path("subsegments")) {
            node.children.add(node(child, false, traceId, segment ? node : owner));
        }
        return node;
    }

    private static String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    // ---------- filter scopes ----------

    /** What a filter expression is evaluated against: a trace, or the part of it a service or edge selects. */
    static final class Scope {
        static final Set<String> KEYWORDS = Set.of("responsetime", "duration", "http.status", "http.url",
                "http.method", "http.useragent", "http.clientip", "user", "name");

        final Trace trace;
        final List<Node> nodes;
        final boolean whole;

        Scope(Trace trace, List<Node> nodes, boolean whole) {
            this.trace = trace;
            this.nodes = nodes;
            this.whole = whole;
        }

        static Scope of(Trace trace) {
            return new Scope(trace, trace.segments, true);
        }

        private List<Node> deep() {
            List<Node> out = new ArrayList<>();
            nodes.forEach(n -> n.walk(out));
            return out;
        }

        boolean flag(String name) {
            return switch (name) {
                case "error" -> deep().stream().anyMatch(n -> n.error);
                case "fault" -> deep().stream().anyMatch(n -> n.fault);
                case "throttle" -> deep().stream().anyMatch(n -> n.throttle);
                case "partial" -> trace.partial;
                default -> deep().stream().noneMatch(n -> n.error || n.fault || n.throttle);
            };
        }

        /** The values a keyword takes in this scope; a comparison matches when any of them does. */
        List<Object> values(String keyword) {
            List<Node> subjects = whole && trace.root() != null ? List.of(trace.root()) : nodes;
            List<Object> out = new ArrayList<>();
            switch (keyword) {
                case "duration" -> {
                    if (whole) {
                        out.add(trace.duration());
                    } else {
                        nodes.forEach(n -> out.add(n.duration()));
                    }
                }
                case "responsetime" -> subjects.forEach(n -> out.add(n.duration()));
                case "http.status" -> subjects.forEach(n -> out.add(n.httpStatus));
                case "http.url" -> subjects.forEach(n -> out.add(n.httpUrl));
                case "http.method" -> subjects.forEach(n -> out.add(n.httpMethod));
                case "http.useragent" -> subjects.forEach(n -> out.add(n.userAgent));
                case "http.clientip" -> subjects.forEach(n -> out.add(n.clientIp));
                case "user" -> deep().forEach(n -> out.add(n.user));
                case "name" -> nodes.forEach(n -> out.add(n.name));
                default -> {
                }
            }
            out.removeIf(Objects::isNull);
            return out;
        }

        List<Object> annotations(String key) {
            List<Object> out = new ArrayList<>();
            deep().forEach(n -> {
                if (n.annotations.containsKey(key)) {
                    out.add(n.annotations.get(key));
                }
            });
            return out;
        }

        /** The segments of the services the key selects, or null when the trace has none. */
        Scope service(ServiceKey key) {
            List<Node> matched = new ArrayList<>();
            for (Node node : trace.all()) {
                if ((node.segment || isRemote(node)) && key.matches(node)) {
                    matched.add(node);
                }
            }
            return matched.isEmpty() ? null : new Scope(trace, matched, false);
        }

        Scope anyService() {
            return trace.segments.isEmpty() ? null : new Scope(trace, trace.segments, false);
        }

        /** The calls from {@code source}'s segments to {@code destination}, or null when there are none. */
        Scope edge(String source, String destination) {
            List<Node> calls = new ArrayList<>();
            for (Node segment : trace.segments) {
                if (!source.equals(segment.name)) {
                    continue;
                }
                List<Node> under = new ArrayList<>();
                segment.walk(under);
                for (Node node : under) {
                    if (node != segment && destination.equals(node.name) && isRemote(node)) {
                        calls.add(node);
                    }
                }
                for (Node other : trace.segments) {
                    Node parent = other.parentId == null ? null : trace.byId.get(other.parentId);
                    if (destination.equals(other.name) && parent != null && under.contains(parent)) {
                        calls.add(other);
                    }
                }
            }
            return calls.isEmpty() ? null : new Scope(trace, calls, false);
        }
    }

    static boolean isRemote(Node node) {
        return !node.segment && (REMOTE_NAMESPACE.equals(node.namespace) || AWS_NAMESPACE.equals(node.namespace));
    }

    // ---------- summaries ----------

    static ObjectNode summary(ObjectMapper mapper, Trace trace, int revision, double matchedEventTime,
                              String account) {
        ObjectNode out = mapper.createObjectNode();
        Node root = trace.root();
        out.put("Id", trace.id).put("StartTime", trace.start()).put("Duration", round(trace.duration()))
                .put("ResponseTime", root == null ? 0 : round(root.duration()));
        List<Node> all = trace.all();
        out.put("HasFault", all.stream().anyMatch(n -> n.fault)).put("HasError", all.stream().anyMatch(n -> n.error))
                .put("HasThrottle", all.stream().anyMatch(n -> n.throttle)).put("IsPartial", trace.partial);
        if (root != null && (root.httpUrl != null || root.httpStatus != null)) {
            ObjectNode http = out.putObject("Http");
            putIfSet(http, "HttpURL", root.httpUrl);
            if (root.httpStatus != null) {
                http.put("HttpStatus", root.httpStatus);
            }
            putIfSet(http, "HttpMethod", root.httpMethod);
            putIfSet(http, "UserAgent", root.userAgent);
            putIfSet(http, "ClientIp", root.clientIp);
        }
        ObjectNode annotations = out.putObject("Annotations");
        Map<String, Map<Object, Set<Node>>> values = new TreeMap<>();
        for (Node node : all) {
            node.annotations.forEach((key, value) -> values.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(value, v -> new LinkedHashSet<>()).add(ownerOf(node)));
        }
        values.forEach((key, byValue) -> {
            ArrayNode list = annotations.putArray(key);
            byValue.forEach((value, owners) -> {
                ObjectNode entry = list.addObject();
                ObjectNode annotation = entry.putObject("AnnotationValue");
                if (value instanceof Double d) {
                    annotation.put("NumberValue", d);
                } else if (value instanceof Boolean b) {
                    annotation.put("BooleanValue", b);
                } else {
                    annotation.put("StringValue", value.toString());
                }
                ArrayNode ids = entry.putArray("ServiceIds");
                owners.forEach(owner -> ids.add(serviceId(mapper, owner, account)));
            });
        });
        ArrayNode users = out.putArray("Users");
        Map<String, Set<Node>> byUser = new TreeMap<>();
        for (Node node : all) {
            if (node.user != null) {
                byUser.computeIfAbsent(node.user, u -> new LinkedHashSet<>()).add(ownerOf(node));
            }
        }
        byUser.forEach((user, owners) -> {
            ObjectNode entry = users.addObject().put("UserName", user);
            ArrayNode ids = entry.putArray("ServiceIds");
            owners.forEach(owner -> ids.add(serviceId(mapper, owner, account)));
        });
        ArrayNode services = out.putArray("ServiceIds");
        Set<String> seen = new LinkedHashSet<>();
        for (Node node : all) {
            if ((node.segment || isRemote(node)) && seen.add(node.name + "|" + type(node))) {
                services.add(serviceId(mapper, node, account));
            }
        }
        ArrayNode arns = out.putArray("ResourceARNs");
        ArrayNode instances = out.putArray("InstanceIds");
        ArrayNode zones = out.putArray("AvailabilityZones");
        Set<String> distinct = new LinkedHashSet<>();
        for (Node node : trace.segments) {
            if (node.resourceArn != null && distinct.add("resource|" + node.resourceArn)) {
                arns.addObject().put("ARN", node.resourceArn);
            }
            if (node.instanceId != null && distinct.add("instance|" + node.instanceId)) {
                instances.addObject().put("Id", node.instanceId);
            }
            if (node.availabilityZone != null && distinct.add("zone|" + node.availabilityZone)) {
                zones.addObject().put("Name", node.availabilityZone);
            }
        }
        if (root != null) {
            out.set("EntryPoint", serviceId(mapper, root, account));
        }
        out.set("FaultRootCauses", rootCauses(mapper, trace, n -> n.fault, account));
        out.set("ErrorRootCauses", rootCauses(mapper, trace, n -> n.error || n.throttle, account));
        out.putArray("ResponseTimeRootCauses");
        out.put("Revision", revision).put("MatchedEventTime", matchedEventTime);
        return out;
    }

    /** The deepest failing entities: where the failure started rather than every caller it reached. */
    private static ArrayNode rootCauses(ObjectMapper mapper, Trace trace, Predicate<Node> failed, String account) {
        ArrayNode out = mapper.createArrayNode();
        for (Node node : trace.all()) {
            if (!failed.test(node) || node.children.stream().anyMatch(child -> failed.test(child))) {
                continue;
            }
            Node service = ownerOf(node);
            ObjectNode cause = out.addObject();
            ObjectNode entry = cause.putArray("Services").addObject().put("Name", service.name);
            entry.putArray("Names").add(service.name);
            putIfSet(entry, "Type", type(service));
            entry.put("AccountId", service.accountId != null ? service.accountId : account).put("Inferred", false);
            ArrayNode path = entry.putArray("EntityPath");
            ObjectNode entity = path.addObject().put("Name", node.name).put("Remote", isRemote(node));
            ArrayNode exceptions = entity.putArray("Exceptions");
            for (String[] exception : node.exceptions) {
                ObjectNode e = exceptions.addObject();
                putIfSet(e, "Name", exception[0]);
                putIfSet(e, "Message", exception[1]);
            }
            cause.put("ClientImpacting", true);
        }
        return out;
    }

    private static Node ownerOf(Node node) {
        return node.segment || node.owner == null ? node : node.owner;
    }

    static String type(Node node) {
        if (node.origin != null) {
            return node.origin;
        }
        if (AWS_NAMESPACE.equals(node.namespace)) {
            return "AWS::" + node.name;
        }
        return REMOTE_NAMESPACE.equals(node.namespace) ? "remote" : null;
    }

    static ObjectNode serviceId(ObjectMapper mapper, Node node, String account) {
        ObjectNode id = mapper.createObjectNode().put("Name", node.name);
        id.putArray("Names").add(node.name);
        id.put("AccountId", node.accountId != null ? node.accountId : account);
        putIfSet(id, "Type", type(node));
        return id;
    }

    private static void putIfSet(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }

    // ---------- service graphs ----------

    /** Request outcome counts for a service or an edge. */
    static final class Stats {
        long ok;
        long throttle;
        long otherError;
        long fault;
        long total;
        double responseTime;
        final Map<Double, Integer> histogram = new TreeMap<>();

        void add(Node node) {
            total++;
            responseTime += node.duration();
            histogram.merge(round(node.duration()), 1, Integer::sum);
            if (node.fault) {
                fault++;
            } else if (node.throttle) {
                throttle++;
            } else if (node.error) {
                otherError++;
            } else {
                ok++;
            }
        }

        ObjectNode render(ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode().put("OkCount", ok);
            out.putObject("ErrorStatistics").put("ThrottleCount", throttle).put("OtherCount", otherError)
                    .put("TotalCount", throttle + otherError);
            out.putObject("FaultStatistics").put("OtherCount", fault).put("TotalCount", fault);
            out.put("TotalCount", total).put("TotalResponseTime", round(responseTime));
            return out;
        }

        ArrayNode histogram(ObjectMapper mapper) {
            ArrayNode out = mapper.createArrayNode();
            histogram.forEach((value, count) -> out.addObject().put("Value", value).put("Count", count));
            return out;
        }
    }

    /** One node of a service graph and the calls it makes. */
    static final class GraphNode {
        final String name;
        final String type;
        final String account;
        int reference;
        boolean root;
        double start = Double.MAX_VALUE;
        double end;
        final Stats stats = new Stats();
        final Map<GraphNode, Stats> edges = new LinkedHashMap<>();

        GraphNode(String name, String type, String account) {
            this.name = name;
            this.type = type;
            this.account = account;
        }
    }

    /** Builds the service graph of the traces: services (and inferred downstream calls) and the edges between them. */
    static List<GraphNode> graph(Collection<Trace> traces, String account) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (Trace trace : traces) {
            Node root = trace.root();
            for (Node segment : trace.segments) {
                GraphNode service = graphNode(nodes, segment, account);
                service.stats.add(segment);
                service.start = Math.min(service.start, segment.start);
                service.end = Math.max(service.end, segment.end);
                if (segment == root) {
                    service.root = true;
                }
                Node parent = segment.parentId == null ? null : trace.byId.get(segment.parentId);
                if (parent != null) {
                    GraphNode caller = graphNode(nodes, ownerOf(parent), account);
                    caller.edges.computeIfAbsent(service, k -> new Stats()).add(parent.segment ? segment : parent);
                }
                List<Node> under = new ArrayList<>();
                segment.walk(under);
                for (Node call : under) {
                    if (!isRemote(call) || calledSegment(trace, call) != null) {
                        continue;
                    }
                    GraphNode downstream = graphNode(nodes, call, account);
                    downstream.stats.add(call);
                    downstream.start = Math.min(downstream.start, call.start);
                    downstream.end = Math.max(downstream.end, call.end);
                    service.edges.computeIfAbsent(downstream, k -> new Stats()).add(call);
                }
            }
        }
        int reference = 0;
        for (GraphNode node : nodes.values()) {
            node.reference = reference++;
        }
        return new ArrayList<>(nodes.values());
    }

    /** The segment a remote subsegment called, when that service sent its own segment. */
    private static Node calledSegment(Trace trace, Node call) {
        for (Node segment : trace.segments) {
            if (call.id.equals(segment.parentId)) {
                return segment;
            }
        }
        return null;
    }

    private static GraphNode graphNode(Map<String, GraphNode> nodes, Node node, String account) {
        String type = type(node);
        return nodes.computeIfAbsent(node.name + "|" + type, k -> new GraphNode(node.name, type,
                node.accountId != null ? node.accountId : account));
    }

    static ArrayNode render(ObjectMapper mapper, List<GraphNode> graph) {
        ArrayNode out = mapper.createArrayNode();
        for (GraphNode node : graph) {
            ObjectNode service = out.addObject().put("ReferenceId", node.reference).put("Name", node.name);
            service.putArray("Names").add(node.name);
            service.put("Root", node.root).put("AccountId", node.account);
            if (node.type != null) {
                service.put("Type", node.type);
            }
            service.put("State", "active").put("StartTime", node.start == Double.MAX_VALUE ? 0 : node.start)
                    .put("EndTime", node.end);
            ArrayNode edges = service.putArray("Edges");
            node.edges.forEach((target, stats) -> {
                ObjectNode edge = edges.addObject().put("ReferenceId", target.reference)
                        .put("StartTime", node.start).put("EndTime", node.end);
                edge.set("SummaryStatistics", stats.render(mapper));
                edge.set("ResponseTimeHistogram", stats.histogram(mapper));
                edge.putArray("Aliases");
            });
            service.set("SummaryStatistics", node.stats.render(mapper));
            service.set("DurationHistogram", node.stats.histogram(mapper));
            service.set("ResponseTimeHistogram", node.stats.histogram(mapper));
        }
        return out;
    }
}
