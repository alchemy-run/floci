package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apprunner.model.ObservabilityConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.ObservabilityConfiguration.TraceConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * App Runner JSON 1.0 observability configuration revisions.
 *
 * <p>Creating the same name again mints a new immutable revision. List returns
 * ACTIVE revisions only; deleted revisions linger as {@code INACTIVE} for describe.
 */
@ApplicationScoped
public class AppRunnerObservabilityConfigurationService implements Resettable {

    static final String SERVICE = "apprunner";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String VENDOR_XRAY = "AWSXRAY";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 20;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_]{3,31}");

    private final StorageBackend<String, ObservabilityConfiguration> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AppRunnerObservabilityConfigurationService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this(storageFactory.create("apprunner", "apprunner-observability-configurations.json",
                new TypeReference<Map<String, ObservabilityConfiguration>>() {
                }), regionResolver, mapper);
    }

    AppRunnerObservabilityConfigurationService(
            StorageBackend<String, ObservabilityConfiguration> store,
            RegionResolver regionResolver,
            ObjectMapper mapper) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @Override
    public void clear() {
        store.clear();
    }

    public JsonNode handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? mapper.createObjectNode()
                : request;
        if (!body.isObject()) {
            throw invalidRequest("Request body must be a JSON object.");
        }
        return switch (action) {
            case "CreateObservabilityConfiguration" -> wrap(create(body, region));
            case "DescribeObservabilityConfiguration" -> wrap(describe(body, region));
            case "DeleteObservabilityConfiguration" -> wrap(delete(body, region));
            case "ListObservabilityConfigurations" -> list(body, region);
            case "ListTagsForResource" -> listTags(body, region);
            case "TagResource" -> tag(body, region);
            case "UntagResource" -> untag(body, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: AppRunner." + action, 400);
        };
    }

    static boolean isObservabilityAction(String action, JsonNode request) {
        return switch (action) {
            case "CreateObservabilityConfiguration",
                    "DescribeObservabilityConfiguration",
                    "DeleteObservabilityConfiguration",
                    "ListObservabilityConfigurations" -> true;
            case "ListTagsForResource", "TagResource", "UntagResource" -> {
                JsonNode arn = request == null ? null : request.get("ResourceArn");
                yield arn != null && arn.isTextual() && arn.asText().contains(":observabilityconfiguration/");
            }
            default -> false;
        };
    }

    private ObservabilityConfiguration create(JsonNode request, String region) {
        String name = requireText(request, "ObservabilityConfigurationName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "ObservabilityConfigurationName must be 4-32 characters of letters, digits, hyphens, or underscores.");
        }
        TraceConfiguration trace = readTraceConfiguration(request);
        Map<String, String> tags = readTags(request);

        List<ObservabilityConfiguration> existing = configsNamed(region, name);
        int nextRevision = existing.stream()
                .map(ObservabilityConfiguration::getObservabilityConfigurationRevision)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;

        for (ObservabilityConfiguration previous : existing) {
            if (Boolean.TRUE.equals(previous.getLatest()) && STATUS_ACTIVE.equals(previous.getStatus())) {
                previous.setLatest(false);
                store.put(previous.getObservabilityConfigurationArn(), previous);
            }
        }

        String arn = "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":observabilityconfiguration/" + name + "/" + nextRevision + "/"
                + UUID.randomUUID().toString().replace("-", "");

        ObservabilityConfiguration created = new ObservabilityConfiguration();
        created.setObservabilityConfigurationArn(arn);
        created.setObservabilityConfigurationName(name);
        created.setTraceConfiguration(trace);
        created.setObservabilityConfigurationRevision(nextRevision);
        created.setLatest(true);
        created.setStatus(STATUS_ACTIVE);
        created.setCreatedAt(Instant.now().getEpochSecond());
        created.setRegion(region);
        created.setTags(tags);
        store.put(arn, created);
        return created;
    }

    private ObservabilityConfiguration describe(JsonNode request, String region) {
        return requireConfig(requireText(request, "ObservabilityConfigurationArn"), region);
    }

    private ObservabilityConfiguration delete(JsonNode request, String region) {
        ObservabilityConfiguration config = requireConfig(
                requireText(request, "ObservabilityConfigurationArn"), region);
        if (!STATUS_ACTIVE.equals(config.getStatus())) {
            throw notFound(config.getObservabilityConfigurationArn());
        }
        boolean wasLatest = Boolean.TRUE.equals(config.getLatest());
        config.setStatus(STATUS_INACTIVE);
        config.setLatest(false);
        config.setDeletedAt(Instant.now().getEpochSecond());
        store.put(config.getObservabilityConfigurationArn(), config);

        if (wasLatest) {
            configsNamed(region, config.getObservabilityConfigurationName()).stream()
                    .filter(c -> STATUS_ACTIVE.equals(c.getStatus()))
                    .max(Comparator.comparing(ObservabilityConfiguration::getObservabilityConfigurationRevision))
                    .ifPresent(nextLatest -> {
                        nextLatest.setLatest(true);
                        store.put(nextLatest.getObservabilityConfigurationArn(), nextLatest);
                    });
        }
        return config;
    }

    private JsonNode list(JsonNode request, String region) {
        String nameFilter = optionalText(request, "ObservabilityConfigurationName");
        boolean latestOnly = request.path("LatestOnly").asBoolean(false);
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);

        List<ObservabilityConfiguration> matches = new ArrayList<>();
        for (ObservabilityConfiguration config : store.values()) {
            if (!region.equals(config.getRegion())) {
                continue;
            }
            if (!STATUS_ACTIVE.equals(config.getStatus())) {
                continue;
            }
            if (nameFilter != null && !nameFilter.equals(config.getObservabilityConfigurationName())) {
                continue;
            }
            if (latestOnly && !Boolean.TRUE.equals(config.getLatest())) {
                continue;
            }
            matches.add(config);
        }
        matches.sort(Comparator
                .comparing(ObservabilityConfiguration::getObservabilityConfigurationName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ObservabilityConfiguration::getObservabilityConfigurationRevision,
                        Comparator.nullsLast(Integer::compareTo)));

        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("ObservabilityConfigurationSummaryList");
        for (ObservabilityConfiguration config : matches.subList(from, to)) {
            ObjectNode summary = summaries.addObject();
            summary.put("ObservabilityConfigurationArn", config.getObservabilityConfigurationArn());
            summary.put("ObservabilityConfigurationName", config.getObservabilityConfigurationName());
            if (config.getObservabilityConfigurationRevision() != null) {
                summary.put("ObservabilityConfigurationRevision", config.getObservabilityConfigurationRevision());
            }
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private JsonNode listTags(JsonNode request, String region) {
        ObservabilityConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", tagsNode(config.getTags()));
        return response;
    }

    private JsonNode tag(JsonNode request, String region) {
        ObservabilityConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw invalidRequest("Tags is required.");
        }
        Map<String, String> tags = new LinkedHashMap<>(config.getTags());
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = tag.path("Value").isMissingNode() || tag.path("Value").isNull()
                    ? ""
                    : tag.path("Value").asText();
            tags.put(key, value);
        }
        config.setTags(tags);
        store.put(config.getObservabilityConfigurationArn(), config);
        return mapper.createObjectNode();
    }

    private JsonNode untag(JsonNode request, String region) {
        ObservabilityConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        JsonNode keysNode = request.get("TagKeys");
        if (keysNode == null || !keysNode.isArray()) {
            throw invalidRequest("TagKeys is required.");
        }
        Map<String, String> tags = new LinkedHashMap<>(config.getTags());
        for (JsonNode key : keysNode) {
            if (key != null && key.isTextual()) {
                tags.remove(key.asText());
            }
        }
        config.setTags(tags);
        store.put(config.getObservabilityConfigurationArn(), config);
        return mapper.createObjectNode();
    }

    private ObjectNode wrap(ObservabilityConfiguration config) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ObservabilityConfiguration", mapper.valueToTree(config));
        return response;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode node = mapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = node.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return node;
    }

    private ObservabilityConfiguration requireConfig(String arn, String region) {
        ObservabilityConfiguration config = store.get(arn).orElse(null);
        if (config == null || !region.equals(config.getRegion())) {
            throw notFound(arn);
        }
        return config;
    }

    private List<ObservabilityConfiguration> configsNamed(String region, String name) {
        List<ObservabilityConfiguration> matches = new ArrayList<>();
        for (ObservabilityConfiguration config : store.values()) {
            if (region.equals(config.getRegion()) && name.equals(config.getObservabilityConfigurationName())) {
                matches.add(config);
            }
        }
        return matches;
    }

    private TraceConfiguration readTraceConfiguration(JsonNode request) {
        JsonNode trace = request.get("TraceConfiguration");
        if (trace == null || trace.isNull()) {
            return null;
        }
        if (!trace.isObject()) {
            throw invalidRequest("TraceConfiguration must be an object.");
        }
        String vendor = optionalText(trace, "Vendor");
        if (vendor == null) {
            throw invalidRequest("TraceConfiguration.Vendor is required.");
        }
        if (!VENDOR_XRAY.equals(vendor)) {
            throw invalidRequest("TraceConfiguration.Vendor must be AWSXRAY.");
        }
        return new TraceConfiguration(vendor);
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw invalidRequest("Tags must be a list.");
        }
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                continue;
            }
            String key = optionalText(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = tag.path("Value").isMissingNode() || tag.path("Value").isNull()
                    ? ""
                    : tag.path("Value").asText();
            tags.put(key, value);
        }
        return tags;
    }

    private int readMaxResults(JsonNode request) {
        JsonNode value = request.get("MaxResults");
        if (value == null || value.isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        if (!value.isNumber()) {
            throw invalidRequest("MaxResults must be an integer between 1 and 20.");
        }
        int maxResults = value.asInt();
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw invalidRequest("MaxResults must be an integer between 1 and 20.");
        }
        return maxResults;
    }

    private int readOffset(JsonNode request) {
        String token = optionalText(request, "NextToken");
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(token);
            if (offset < 0) {
                throw invalidRequest("Invalid NextToken.");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw invalidRequest("Invalid NextToken.");
        }
    }

    private String requireText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Resource with the specified ARN (" + arn + ") is not found.", 400);
    }
}
