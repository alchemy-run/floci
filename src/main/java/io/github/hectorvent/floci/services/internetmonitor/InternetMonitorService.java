package io.github.hectorvent.floci.services.internetmonitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.internetmonitor.model.Monitor;
import io.github.hectorvent.floci.services.internetmonitor.model.Query;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * CloudWatch Internet Monitor restJson1 — monitor lifecycle, health/internet
 * events, and the query interface. Tag APIs share {@code /tags/{arn}} via
 * {@link TagHandler} using ARN service {@code internetmonitor}.
 */
@ApplicationScoped
public class InternetMonitorService implements TagHandler {

    static final String SERVICE = "internetmonitor";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "internetmonitor:v1:";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,255}");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> QUERY_TYPES = Set.of(
            "MEASUREMENTS",
            "TOP_LOCATIONS",
            "TOP_LOCATION_DETAILS",
            "OVERALL_TRAFFIC_SUGGESTIONS",
            "OVERALL_TRAFFIC_SUGGESTIONS_DETAILS",
            "ROUTING_SUGGESTIONS");

    private final StorageBackend<String, Monitor> store;
    private final Map<String, Query> queries = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;

    @Inject
    public InternetMonitorService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                SERVICE,
                "internetmonitor-monitors.json",
                new TypeReference<Map<String, Monitor>>() {
                }),
                regionResolver);
    }

    InternetMonitorService(StorageBackend<String, Monitor> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    InternetMonitorService(StorageBackend<String, Monitor> store) {
        this(store, new RegionResolver("us-east-1", "000000000000"));
    }

    public synchronized Monitor createMonitor(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "MonitorName");
        validateName(name);
        String key = storageKey(region, name);
        if (store.get(key).isPresent()) {
            throw conflict(name);
        }
        Integer maxCityNetworks = optionalInteger(request, "MaxCityNetworksToMonitor");
        Integer trafficPercentage = optionalInteger(request, "TrafficPercentageToMonitor");
        if (maxCityNetworks == null && trafficPercentage == null) {
            throw validation("Specify MaxCityNetworksToMonitor or TrafficPercentageToMonitor.");
        }
        if (maxCityNetworks != null && (maxCityNetworks < 1 || maxCityNetworks > 500_000)) {
            throw validation("MaxCityNetworksToMonitor must be between 1 and 500000.");
        }
        if (trafficPercentage != null && (trafficPercentage < 0 || trafficPercentage > 100)) {
            throw validation("TrafficPercentageToMonitor must be between 0 and 100.");
        }

        String now = timestamp();
        String account = regionResolver.getAccountId();
        Monitor monitor = new Monitor();
        monitor.setMonitorName(name);
        monitor.setMonitorArn(arn(region, account, name));
        monitor.setResources(readStringList(request, "Resources"));
        monitor.setStatus("ACTIVE");
        monitor.setCreatedAt(now);
        monitor.setModifiedAt(now);
        monitor.setProcessingStatus("OK");
        monitor.setTags(readTags(request));
        monitor.setMaxCityNetworksToMonitor(maxCityNetworks);
        monitor.setTrafficPercentageToMonitor(trafficPercentage);
        monitor.setInternetMeasurementsLogDelivery(optionalObject(request, "InternetMeasurementsLogDelivery"));
        monitor.setHealthEventsConfig(optionalObject(request, "HealthEventsConfig"));
        store.put(key, monitor);
        return monitor;
    }

    public Monitor getMonitor(String region, String name) {
        return requireMonitor(region, name);
    }

    public synchronized Monitor updateMonitor(String region, String name, JsonNode request) {
        validateName(name);
        requireObject(request, "Request body");
        String key = storageKey(region, name);
        Monitor monitor = store.get(key).orElseThrow(() -> resourceNotFound("monitor", name));
        boolean changed = false;

        List<String> resources = new ArrayList<>(monitor.getResources());
        LinkedHashSet<String> unique = new LinkedHashSet<>(resources);
        if (request.has("ResourcesToAdd")) {
            unique.addAll(readStringList(request, "ResourcesToAdd"));
            changed = true;
        }
        if (request.has("ResourcesToRemove")) {
            unique.removeAll(readStringList(request, "ResourcesToRemove"));
            changed = true;
        }
        if (changed) {
            monitor.setResources(new ArrayList<>(unique));
        }

        if (request.has("Status") && !request.get("Status").isNull()) {
            String status = requireText(request, "Status");
            if (!STATUSES.contains(status)) {
                throw validation("Status must be ACTIVE or INACTIVE.");
            }
            monitor.setStatus(status);
            monitor.setProcessingStatus("ACTIVE".equals(status) ? "OK" : "INACTIVE");
            changed = true;
        }
        if (request.has("MaxCityNetworksToMonitor")) {
            Integer maxCityNetworks = optionalInteger(request, "MaxCityNetworksToMonitor");
            if (maxCityNetworks != null && (maxCityNetworks < 1 || maxCityNetworks > 500_000)) {
                throw validation("MaxCityNetworksToMonitor must be between 1 and 500000.");
            }
            monitor.setMaxCityNetworksToMonitor(maxCityNetworks);
            changed = true;
        }
        if (request.has("TrafficPercentageToMonitor")) {
            Integer trafficPercentage = optionalInteger(request, "TrafficPercentageToMonitor");
            if (trafficPercentage != null && (trafficPercentage < 0 || trafficPercentage > 100)) {
                throw validation("TrafficPercentageToMonitor must be between 0 and 100.");
            }
            monitor.setTrafficPercentageToMonitor(trafficPercentage);
            changed = true;
        }
        if (request.has("InternetMeasurementsLogDelivery")) {
            monitor.setInternetMeasurementsLogDelivery(optionalObject(request, "InternetMeasurementsLogDelivery"));
            changed = true;
        }
        if (request.has("HealthEventsConfig")) {
            monitor.setHealthEventsConfig(optionalObject(request, "HealthEventsConfig"));
            changed = true;
        }
        if (changed) {
            monitor.setModifiedAt(timestamp());
            store.put(key, monitor);
        }
        return monitor;
    }

    public synchronized void deleteMonitor(String region, String name) {
        Monitor monitor = requireMonitor(region, name);
        if (!"INACTIVE".equals(monitor.getStatus())) {
            throw validation("The monitor must be in the INACTIVE state before you can delete it.");
        }
        store.delete(storageKey(region, name));
        queries.entrySet().removeIf(entry -> name.equals(entry.getValue().getMonitorName()));
    }

    public Page<Monitor> listMonitors(String region, String maxResultsValue, String nextToken, String status) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Monitor> monitors = store.scan(key -> key.startsWith(region + "::"));
        if (status != null && !status.isBlank()) {
            monitors.removeIf(monitor -> !status.equals(monitor.getStatus()));
        }
        monitors.sort(Comparator.comparing(Monitor::getMonitorName));
        return page(monitors, maxResults, nextToken);
    }

    public List<Object> listHealthEvents(String region, String name) {
        requireMonitor(region, name);
        return List.of();
    }

    public void getHealthEvent(String region, String name, String eventId) {
        requireMonitor(region, name);
        throw resourceNotFound("health event", eventId);
    }

    public List<Object> listInternetEvents() {
        return List.of();
    }

    public void getInternetEvent(String eventId) {
        throw resourceNotFound("internet event", eventId);
    }

    public Query startQuery(String region, String name, JsonNode request) {
        requireMonitor(region, name);
        requireObject(request, "Request body");
        requireText(request, "StartTime");
        requireText(request, "EndTime");
        String queryType = requireText(request, "QueryType");
        if (!QUERY_TYPES.contains(queryType)) {
            throw validation("QueryType is not a valid Internet Monitor query type.");
        }
        Query query = new Query(UUID.randomUUID().toString(), name, "SUCCEEDED", queryType);
        queries.put(queryKey(region, name, query.getQueryId()), query);
        return query;
    }

    public Query getQueryStatus(String region, String name, String queryId) {
        requireMonitor(region, name);
        return requireQuery(region, name, queryId);
    }

    public Query getQueryResults(String region, String name, String queryId) {
        requireMonitor(region, name);
        return requireQuery(region, name, queryId);
    }

    public synchronized void stopQuery(String region, String name, String queryId) {
        requireMonitor(region, name);
        Query query = requireQuery(region, name, queryId);
        if ("SUCCEEDED".equals(query.getStatus()) || "FAILED".equals(query.getStatus())
                || "CANCELED".equals(query.getStatus())) {
            return;
        }
        query.setStatus("CANCELED");
        queries.put(queryKey(region, name, queryId), query);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireMonitorByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Monitor monitor = requireMonitorByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(monitor.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        monitor.setTags(current);
        monitor.setModifiedAt(timestamp());
        store.put(storageKey(region, monitor.getMonitorName()), monitor);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Monitor monitor = requireMonitorByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(monitor.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        monitor.setTags(current);
        monitor.setModifiedAt(timestamp());
        store.put(storageKey(region, monitor.getMonitorName()), monitor);
    }

    private Monitor requireMonitor(String region, String name) {
        validateName(name);
        return store.get(storageKey(region, name)).orElseThrow(() -> resourceNotFound("monitor", name));
    }

    private Monitor requireMonitorByArn(String region, String arn) {
        String name = monitorNameFromArn(arn);
        return requireMonitor(region, name);
    }

    private Query requireQuery(String region, String name, String queryId) {
        if (queryId == null || queryId.isBlank()) {
            throw validation("QueryId is required.");
        }
        Query query = queries.get(queryKey(region, name, queryId));
        if (query == null) {
            throw resourceNotFound("query", queryId);
        }
        return query;
    }

    static String monitorNameFromArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw resourceNotFound("monitor", arn);
            }
            String resource = parsed.resource();
            if (resource == null || !resource.startsWith("monitor/")) {
                throw resourceNotFound("monitor", arn);
            }
            String name = resource.substring("monitor/".length());
            if (name.isBlank()) {
                throw resourceNotFound("monitor", arn);
            }
            return name;
        } catch (IllegalArgumentException e) {
            throw resourceNotFound("monitor", arn);
        }
    }

    private static String arn(String region, String account, String name) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "monitor/" + name).toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String queryKey(String region, String name, String queryId) {
        return region + "::" + name + "::" + queryId;
    }

    private static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw validation("MonitorName must match [a-zA-Z0-9_.-]{1,255}.");
        }
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("Tags") || request.get("Tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("Tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("Tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (entry.getKey().isBlank() || value == null || !value.isTextual()) {
                throw validation("Tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), value.textValue());
        });
        return tags;
    }

    private static List<String> readStringList(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return List.of();
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value.deepCopy();
    }

    private static Integer optionalInteger(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isNumber()) {
            throw validation(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("MaxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("MaxResults must be an integer between 1 and 100.");
        }
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("NextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("NextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException conflict(String name) {
        return new AwsException(
                "ConflictException",
                "Monitor " + name + " already exists.",
                409);
    }

    private static AwsException resourceNotFound(String type, String id) {
        return new AwsException(
                "ResourceNotFoundException",
                "The " + type + " " + id + " does not exist.",
                404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
