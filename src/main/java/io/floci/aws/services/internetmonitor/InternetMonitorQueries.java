package io.floci.aws.services.internetmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The query interface over a monitor's stored measurements: a query is queued, runs on a worker, and keeps its result
 * rows, aligned with its fields, for {@code GetQueryResults}.
 */
@ApplicationScoped
public class InternetMonitorQueries implements Resettable {

    private static final Map<String, List<String>> FIELDS = Map.of(
            "MEASUREMENTS", List.of("timestamp", "availability", "performance", "bytes_in", "bytes_out", "rtt_p50",
                    "rtt_p90", "rtt_p95"),
            "TOP_LOCATION_DETAILS", List.of("timestamp", "current_service", "current_fbl", "best_ec2_fbl",
                    "best_ec2_region", "best_cf_fbl"),
            "OVERALL_TRAFFIC_SUGGESTIONS", List.of("current_aws_location", "proposed_aws_location", "average_fbl",
                    "traffic", "optimized_traffic_excluding_cf", "optimized_traffic_including_cf"),
            "OVERALL_TRAFFIC_SUGGESTIONS_DETAILS", List.of("aws_location", "city", "metro", "subdivision", "country",
                    "asn", "traffic", "current_aws_location", "fbl_data"),
            "ROUTING_SUGGESTIONS", List.of("dns_resolver_ip", "dns_resolver_asn", "dns_resolver_isp",
                    "ipv4_prefixes", "current_aws_location", "current_latency", "proposed_aws_location",
                    "proposed_latency"));
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("timestamp", "timestamp"), Map.entry("availability", "float"),
            Map.entry("performance", "float"), Map.entry("bytes_in", "bigint"), Map.entry("bytes_out", "bigint"),
            Map.entry("rtt_p50", "float"), Map.entry("rtt_p90", "float"), Map.entry("rtt_p95", "float"),
            Map.entry("asn", "bigint"), Map.entry("traffic", "float"), Map.entry("current_fbl", "float"),
            Map.entry("best_ec2", "float"), Map.entry("best_ec2_fbl", "float"), Map.entry("best_cf_fbl", "float"),
            Map.entry("average_fbl", "float"), Map.entry("optimized_traffic_excluding_cf", "float"),
            Map.entry("optimized_traffic_including_cf", "float"), Map.entry("current_latency", "float"),
            Map.entry("proposed_latency", "float"), Map.entry("dns_resolver_asn", "bigint"));
    private static final Set<String> LOCATION_FILTERS = Set.of("aws_location", "asn", "city", "metro", "subdivision",
            "country", "geo", "proposed_aws_location");
    private static final Set<String> ROUTING_FILTERS = Set.of("current_aws_location", "proposed_aws_location",
            "ipv4_prefix", "monitor_arn", "dns_resolver_ip", "dns_resolver_isp", "dns_resolver_asn");
    private static final List<String> GEOS = List.of("city", "metro", "subdivision", "country");

    /** A query: its owner, status, and once finished its fields and rows. */
    static final class Query {
        final String account;
        final String region;
        final String monitor;
        volatile String status = "QUEUED";
        volatile List<String> fields = List.of();
        volatile List<List<String>> rows = List.of();

        Query(String account, String region, String monitor) {
            this.account = account;
            this.region = region;
            this.monitor = monitor;
        }
    }

    private final InternetMonitorStore store;
    private final InternetMonitorService service;
    private final Map<String, Query> queries = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "internetmonitor-queries");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public InternetMonitorQueries(InternetMonitorStore store, InternetMonitorService service) {
        this.store = store;
        this.service = service;
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    @Override
    public void clear() {
        queries.clear();
    }

    public ObjectNode startQuery(JsonNode in, String region) {
        String account = store.account();
        String monitorName = in.path("MonitorName").asText();
        if (in.hasNonNull("LinkedAccountId") && !account.equals(in.path("LinkedAccountId").asText())) {
            throw new AwsException("AccessDeniedException", "The account " + in.path("LinkedAccountId").asText()
                    + " is not linked to this monitoring account.", 403);
        }
        service.monitor(account, region, monitorName);
        Instant start = InternetMonitorStore.instant(in.path("StartTime"), "StartTime");
        Instant end = InternetMonitorStore.instant(in.path("EndTime"), "EndTime");
        if (!start.isBefore(end)) {
            throw InternetMonitorStore.validation("StartTime must be before EndTime.");
        }
        String type = in.path("QueryType").asText();
        List<Filter> filters = filters(in.path("FilterParameters"), type);
        Query query = new Query(account, region, monitorName);
        String id = UUID.randomUUID().toString();
        queries.put(id, query);
        workers.submit(() -> run(query, region, type, start, end, filters));
        return store.mapper().createObjectNode().put("QueryId", id);
    }

    public ObjectNode getQueryStatus(String monitorName, String queryId, String region) {
        return store.mapper().createObjectNode().put("Status", query(monitorName, queryId, region).status);
    }

    public ObjectNode getQueryResults(String monitorName, String queryId, String token, Integer maxResults,
                                      String region) {
        Query query = query(monitorName, queryId, region);
        if (!"SUCCEEDED".equals(query.status)) {
            throw InternetMonitorStore.validation("The query " + queryId + " has not succeeded; its status is "
                    + query.status + ".");
        }
        ObjectNode out = store.mapper().createObjectNode();
        ArrayNode fields = out.putArray("Fields");
        for (String field : query.fields) {
            fields.addObject().put("Name", field).put("Type", TYPES.getOrDefault(field, "string"));
        }
        List<JsonNode> rows = new ArrayList<>();
        for (List<String> row : query.rows) {
            ArrayNode values = store.mapper().createArrayNode();
            row.forEach(values::add);
            rows.add(values);
        }
        InternetMonitorStore.page(rows, token, maxResults == null ? 1000 : maxResults, out, "Data");
        return out;
    }

    public ObjectNode stopQuery(String monitorName, String queryId, String region) {
        Query query = query(monitorName, queryId, region);
        synchronized (query) {
            if ("QUEUED".equals(query.status) || "RUNNING".equals(query.status)) {
                query.status = "CANCELED";
            }
        }
        return store.mapper().createObjectNode();
    }

    private Query query(String monitorName, String queryId, String region) {
        String account = store.account();
        service.monitor(account, region, monitorName);
        Query query = queries.get(queryId);
        if (query == null || !query.account.equals(account) || !query.region.equals(region)
                || !query.monitor.equals(monitorName)) {
            throw InternetMonitorStore.validation("The query " + queryId + " does not exist.");
        }
        return query;
    }

    // ---------------------------------------------------------------- filters

    record Filter(String field, boolean equals, List<String> values) {
        boolean admits(String value) {
            return values.contains(value) == equals;
        }
    }

    private static List<Filter> filters(JsonNode parameters, String type) {
        List<Filter> filters = new ArrayList<>();
        boolean routing = "ROUTING_SUGGESTIONS".equals(type);
        boolean geo = false;
        for (JsonNode parameter : parameters) {
            String field = parameter.path("Field").asText();
            Set<String> allowed = routing ? ROUTING_FILTERS : LOCATION_FILTERS;
            if (!allowed.contains(field)) {
                throw InternetMonitorStore.validation("The filter field " + field + " is not valid for "
                        + type + " queries.");
            }
            if ("geo".equals(field)) {
                if (!"TOP_LOCATIONS".equals(type)) {
                    throw InternetMonitorStore.validation("The geo filter is only allowed on TOP_LOCATIONS queries.");
                }
                geo = true;
                for (JsonNode value : parameter.path("Values")) {
                    if (!GEOS.contains(value.asText())) {
                        throw InternetMonitorStore.validation("geo must be city, metro, subdivision or country.");
                    }
                }
            }
            if ("proposed_aws_location".equals(field) && !routing
                    && !"OVERALL_TRAFFIC_SUGGESTIONS_DETAILS".equals(type)) {
                throw InternetMonitorStore.validation("The proposed_aws_location filter is only allowed on "
                        + "OVERALL_TRAFFIC_SUGGESTIONS_DETAILS and ROUTING_SUGGESTIONS queries.");
            }
            List<String> values = new ArrayList<>();
            parameter.path("Values").forEach(value -> values.add(value.asText()));
            filters.add(new Filter(field, !"NOT_EQUALS".equals(parameter.path("Operator").asText("EQUALS")),
                    values));
        }
        if ("TOP_LOCATIONS".equals(type) && !geo) {
            throw InternetMonitorStore.validation("TOP_LOCATIONS queries require a geo filter.");
        }
        return filters;
    }

    // ---------------------------------------------------------------- running

    private void run(Query query, String region, String type, Instant start, Instant end, List<Filter> filters) {
        synchronized (query) {
            if (!"QUEUED".equals(query.status)) {
                return;
            }
            query.status = "RUNNING";
        }
        try {
            List<Measured> measured = measured(query.account, region, query.monitor, start, end, filters);
            String geo = filters.stream().filter(filter -> "geo".equals(filter.field()))
                    .flatMap(filter -> filter.values().stream()).findFirst().orElse("country");
            List<String> fields = "TOP_LOCATIONS".equals(type) ? topLocationFields(geo) : FIELDS.get(type);
            List<List<String>> rows = switch (type) {
                case "MEASUREMENTS" -> measurements(measured);
                case "TOP_LOCATIONS" -> topLocations(measured, geo);
                case "TOP_LOCATION_DETAILS" -> topLocationDetails(measured);
                case "OVERALL_TRAFFIC_SUGGESTIONS" -> suggestions(measured);
                case "OVERALL_TRAFFIC_SUGGESTIONS_DETAILS" -> suggestionDetails(measured, filters);
                // Routing suggestions come from the DNS resolvers AWS sees; Floci's viewers reach it directly.
                default -> List.of();
            };
            synchronized (query) {
                if ("RUNNING".equals(query.status)) {
                    query.fields = fields;
                    query.rows = rows;
                    query.status = "SUCCEEDED";
                }
            }
        } catch (RuntimeException e) {
            query.status = "FAILED";
        }
    }

    /** One stored row with its window's start. */
    private record Measured(Instant timestamp, JsonNode row) {
    }

    private List<Measured> measured(String account, String region, String monitor, Instant start, Instant end,
                                    List<Filter> filters) {
        EmulatorConfig.InternetMonitorServiceConfig.ClientLocationConfig location = store.config().clientLocation();
        Map<String, String> client = Map.of("city", location.city(), "metro", location.metro(),
                "subdivision", location.subdivision(), "country", location.country(),
                "asn", String.valueOf(location.asNumber()));
        List<Measured> measured = new ArrayList<>();
        for (ObjectNode window : store.list(account, region + "/window/" + monitor + "/")) {
            Instant timestamp = Instant.parse(window.path("Timestamp").asText());
            if (timestamp.isBefore(start) || !timestamp.isBefore(end)) {
                continue;
            }
            rows:
            for (JsonNode row : window.path("Rows")) {
                for (Filter filter : filters) {
                    String value = switch (filter.field()) {
                        case "aws_location" -> row.path("serviceLocation").asText();
                        case "city", "metro", "subdivision", "country", "asn" -> client.get(filter.field());
                        default -> null;
                    };
                    if (value != null && !filter.admits(value)) {
                        continue rows;
                    }
                }
                measured.add(new Measured(timestamp, row));
            }
        }
        measured.sort(Comparator.comparing(Measured::timestamp));
        return measured;
    }

    private static List<List<String>> measurements(List<Measured> measured) {
        Map<Instant, List<JsonNode>> byWindow = new LinkedHashMap<>();
        measured.forEach(m -> byWindow.computeIfAbsent(m.timestamp(), ignored -> new ArrayList<>()).add(m.row()));
        List<List<String>> rows = new ArrayList<>();
        byWindow.forEach((timestamp, group) -> {
            long requests = group.stream().mapToLong(row -> row.path("requests").asLong()).sum();
            rows.add(List.of(timestamp.toString(),
                    number(weighted(group, "availability", requests)),
                    number(weighted(group, "performance", requests)),
                    String.valueOf(group.stream().mapToLong(row -> row.path("bytesIn").asLong()).sum()),
                    String.valueOf(group.stream().mapToLong(row -> row.path("bytesOut").asLong()).sum()),
                    number(weighted(group, "rttP50", requests)), number(weighted(group, "rttP90", requests)),
                    number(weighted(group, "rttP95", requests))));
        });
        return rows;
    }

    private static List<String> topLocationFields(String geo) {
        List<String> fields = new ArrayList<>(List.of("aws_location"));
        fields.addAll(GEOS.subList(GEOS.indexOf(geo), GEOS.size()));
        fields.addAll(List.of("asn", "availability", "performance", "bytes_in", "bytes_out", "current_fbl",
                "best_ec2", "best_ec2_region", "best_cf_fbl"));
        return fields;
    }

    private List<List<String>> topLocations(List<Measured> measured, String geo) {
        Map<String, String> client = clientFields();
        Map<String, List<JsonNode>> byService = byService(measured);
        String cloudFront = fbl(byService.get("CloudFront"));
        Map.Entry<String, String> bestRegion = bestRegion(byService);
        List<List<String>> rows = new ArrayList<>();
        byService.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, List<JsonNode>> e) -> bytes(e.getValue()))
                        .reversed())
                .forEach(e -> {
                    long requests = e.getValue().stream().mapToLong(row -> row.path("requests").asLong()).sum();
                    List<String> row = new ArrayList<>(List.of(e.getKey()));
                    for (String part : GEOS.subList(GEOS.indexOf(geo), GEOS.size())) {
                        row.add(client.get(part));
                    }
                    row.addAll(List.of(client.get("asn"), number(weighted(e.getValue(), "availability", requests)),
                            number(weighted(e.getValue(), "performance", requests)),
                            String.valueOf(e.getValue().stream().mapToLong(r -> r.path("bytesIn").asLong()).sum()),
                            String.valueOf(e.getValue().stream().mapToLong(r -> r.path("bytesOut").asLong()).sum()),
                            fbl(e.getValue()), bestRegion == null ? "" : bestRegion.getValue(),
                            bestRegion == null ? "" : bestRegion.getKey(), cloudFront));
                    rows.add(row);
                });
        return rows;
    }

    private List<List<String>> topLocationDetails(List<Measured> measured) {
        Map<Instant, List<Measured>> byHour = new LinkedHashMap<>();
        measured.forEach(m -> byHour.computeIfAbsent(m.timestamp().truncatedTo(ChronoUnit.HOURS),
                ignored -> new ArrayList<>()).add(m));
        List<List<String>> rows = new ArrayList<>();
        byHour.forEach((hour, group) -> {
            Map<String, List<JsonNode>> byService = byService(group);
            Map.Entry<String, String> bestRegion = bestRegion(byService);
            String current = byService.entrySet().stream()
                    .max(Comparator.comparingLong(e -> bytes(e.getValue()))).map(Map.Entry::getKey).orElse("");
            rows.add(List.of(hour.toString(), current, fbl(byService.get(current)),
                    bestRegion == null ? "" : bestRegion.getValue(), bestRegion == null ? "" : bestRegion.getKey(),
                    fbl(byService.get("CloudFront"))));
        });
        return rows;
    }

    /** Each measured location's traffic against every measured location it could move to. */
    private List<List<String>> suggestions(List<Measured> measured) {
        Map<String, List<JsonNode>> byService = byService(measured);
        long totalBytes = byService.values().stream().mapToLong(InternetMonitorQueries::bytes).sum();
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> current : byService.entrySet()) {
            double currentFbl = parse(fbl(current.getValue()));
            double traffic = totalBytes == 0 ? 0 : 100.0 * bytes(current.getValue()) / totalBytes;
            for (Map.Entry<String, List<JsonNode>> proposed : byService.entrySet()) {
                double proposedFbl = parse(fbl(proposed.getValue()));
                boolean faster = proposedFbl < currentFbl;
                boolean cloudFront = "CloudFront".equals(proposed.getKey());
                rows.add(List.of(current.getKey(), proposed.getKey(), fbl(proposed.getValue()), number(traffic),
                        number(faster && !cloudFront ? traffic : 0), number(faster ? traffic : 0)));
            }
        }
        return rows;
    }

    private List<List<String>> suggestionDetails(List<Measured> measured, List<Filter> filters) {
        Map<String, String> client = clientFields();
        Map<String, List<JsonNode>> byService = byService(measured);
        long totalBytes = byService.values().stream().mapToLong(InternetMonitorQueries::bytes).sum();
        ObjectNode fblData = store.mapper().createObjectNode();
        byService.forEach((location, rows) -> fblData.put(location, parse(fbl(rows))));
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> current : byService.entrySet()) {
            for (String proposed : byService.keySet()) {
                boolean admitted = filters.stream().filter(filter -> "proposed_aws_location".equals(filter.field()))
                        .allMatch(filter -> filter.admits(proposed));
                if (!admitted) {
                    continue;
                }
                rows.add(List.of(proposed, client.get("city"), client.get("metro"), client.get("subdivision"),
                        client.get("country"), client.get("asn"),
                        number(totalBytes == 0 ? 0 : 100.0 * bytes(current.getValue()) / totalBytes),
                        current.getKey(), fblData.toString()));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> clientFields() {
        EmulatorConfig.InternetMonitorServiceConfig.ClientLocationConfig location = store.config().clientLocation();
        return Map.of("city", location.city(), "metro", location.metro(), "subdivision", location.subdivision(),
                "country", location.country(), "asn", String.valueOf(location.asNumber()));
    }

    private static Map<String, List<JsonNode>> byService(List<Measured> measured) {
        Map<String, List<JsonNode>> byService = new LinkedHashMap<>();
        measured.forEach(m -> byService.computeIfAbsent(m.row().path("serviceLocation").asText(),
                ignored -> new ArrayList<>()).add(m.row()));
        return byService;
    }

    /** The Region with the lowest measured time to first byte, and that time. */
    private static Map.Entry<String, String> bestRegion(Map<String, List<JsonNode>> byService) {
        return byService.entrySet().stream().filter(e -> !"CloudFront".equals(e.getKey()))
                .min(Comparator.comparingDouble(e -> parse(fbl(e.getValue()))))
                .map(e -> Map.entry(e.getKey(), fbl(e.getValue()))).orElse(null);
    }

    private static String fbl(List<JsonNode> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        long requests = rows.stream().mapToLong(row -> row.path("requests").asLong()).sum();
        return number(weighted(rows, "ttfb", requests));
    }

    private static double weighted(List<JsonNode> rows, String field, long requests) {
        if (requests == 0) {
            return 0;
        }
        double sum = 0;
        for (JsonNode row : rows) {
            sum += row.path(field).asDouble() * row.path("requests").asLong();
        }
        return InternetMonitorMeasurements.round(sum / requests);
    }

    private static long bytes(List<JsonNode> rows) {
        return rows.stream().mapToLong(row -> row.path("bytesIn").asLong() + row.path("bytesOut").asLong()).sum();
    }

    private static double parse(String value) {
        return value.isEmpty() ? Double.MAX_VALUE : Double.parseDouble(value);
    }

    private static String number(double value) {
        return String.valueOf(InternetMonitorMeasurements.round(value));
    }
}
