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
import io.github.hectorvent.floci.services.apprunner.model.AutoScalingConfiguration;
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
 * App Runner JSON 1.0 auto scaling configuration revisions.
 *
 * <p>Creating the same name again mints a new immutable revision. Delete with
 * {@code DeleteAllRevisions} requires the revision-less name-partial ARN and
 * marks every revision {@code inactive}. Live AWS returns lowercase statuses.
 */
@ApplicationScoped
public class AppRunnerAutoScalingConfigurationService implements Resettable {

    static final String SERVICE = "apprunner";
    private static final String DEFAULT_CONFIG_ID = "00000000000000000000000000000001";
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_RESULTS = 20;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-_]{3,31}");

    private final StorageBackend<String, AutoScalingConfiguration> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AppRunnerAutoScalingConfigurationService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this(storageFactory.create("apprunner", "apprunner-autoscaling-configurations.json",
                new TypeReference<Map<String, AutoScalingConfiguration>>() {
                }), regionResolver, mapper);
    }

    AppRunnerAutoScalingConfigurationService(
            StorageBackend<String, AutoScalingConfiguration> store,
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
            case "CreateAutoScalingConfiguration" -> wrap(create(body, region));
            case "DescribeAutoScalingConfiguration" -> wrap(describe(body, region));
            case "DeleteAutoScalingConfiguration" -> wrap(delete(body, region));
            case "ListAutoScalingConfigurations" -> list(body, region);
            case "ListTagsForResource" -> listTags(body, region);
            case "TagResource" -> tag(body, region);
            case "UntagResource" -> untag(body, region);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: AppRunner." + action, 400);
        };
    }

    static boolean isAutoScalingAction(String action, JsonNode request) {
        return switch (action) {
            case "CreateAutoScalingConfiguration",
                    "DescribeAutoScalingConfiguration",
                    "DeleteAutoScalingConfiguration",
                    "ListAutoScalingConfigurations" -> true;
            case "ListTagsForResource", "TagResource", "UntagResource" -> {
                JsonNode arn = request == null ? null : request.get("ResourceArn");
                yield arn != null && arn.isTextual() && arn.asText().contains(":autoscalingconfiguration/");
            }
            default -> false;
        };
    }

    private AutoScalingConfiguration create(JsonNode request, String region) {
        String name = requireText(request, "AutoScalingConfigurationName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidRequest(
                    "AutoScalingConfigurationName must be 4-32 characters of letters, digits, hyphens, or underscores.");
        }
        int concurrency = valueOrDefault(optionalInt(request, "MaxConcurrency"), 100, 1, 200, "MaxConcurrency");
        int min = valueOrDefault(optionalInt(request, "MinSize"), 1, 1, 25, "MinSize");
        int max = valueOrDefault(optionalInt(request, "MaxSize"), 25, 1, 25, "MaxSize");
        if (max < min) {
            throw invalidRequest("MaxSize must be greater than or equal to MinSize.");
        }
        ensureDefault(region);

        int nextRevision = configsNamed(region, name).stream()
                .mapToInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision)
                .max()
                .orElse(0) + 1;
        long now = Instant.now().getEpochSecond();
        String id = UUID.randomUUID().toString().replace("-", "");
        String arn = arn(region, name, nextRevision, id);

        AutoScalingConfiguration config = new AutoScalingConfiguration();
        config.setAutoScalingConfigurationName(name);
        config.setAutoScalingConfigurationRevision(nextRevision);
        config.setConfigurationId(id);
        config.setAutoScalingConfigurationArn(arn);
        config.setLatest(true);
        config.setStatus(AutoScalingConfiguration.STATUS_ACTIVE);
        config.setMaxConcurrency(concurrency);
        config.setMinSize(min);
        config.setMaxSize(max);
        config.setCreatedAt(now);
        config.setHasAssociatedService(false);
        config.setDefault(false);
        config.setRegion(region);
        config.setTags(readTags(request));
        store.put(arn, config);
        recomputeLatest(region, name);
        return requireConfig(arn, region);
    }

    private AutoScalingConfiguration describe(JsonNode request, String region) {
        ensureDefault(region);
        return requireConfig(requireText(request, "AutoScalingConfigurationArn"), region);
    }

    private AutoScalingConfiguration delete(JsonNode request, String region) {
        String arn = requireText(request, "AutoScalingConfigurationArn");
        boolean deleteAll = request.path("DeleteAllRevisions").asBoolean(false);
        ensureDefault(region);
        ParsedArn parsed = parseConfigArn(arn);

        if (deleteAll) {
            if (parsed.full()) {
                throw invalidRequest("You cannot specify full auto scaling configuration ARN and "
                        + "DeleteAllRevisions as true at same time");
            }
            List<AutoScalingConfiguration> active = configsNamed(region, parsed.name()).stream()
                    .filter(AutoScalingConfiguration::isActive)
                    .toList();
            if (active.isEmpty()) {
                throw notFound(arn);
            }
            if (active.stream().anyMatch(AutoScalingConfiguration::isDefault)) {
                throw invalidRequest("Cannot delete the default auto scaling configuration.");
            }
            if (active.stream().anyMatch(AutoScalingConfiguration::isHasAssociatedService)) {
                throw invalidRequest(
                        "Cannot delete an auto scaling configuration that is associated with a service.");
            }
            long now = Instant.now().getEpochSecond();
            AutoScalingConfiguration last = null;
            for (AutoScalingConfiguration revision : active) {
                last = deactivate(revision, now);
            }
            recomputeLatest(region, parsed.name());
            return last;
        }

        AutoScalingConfiguration target;
        if (parsed.full()) {
            target = requireConfig(arn, region);
        } else {
            target = configsNamed(region, parsed.name()).stream()
                    .filter(AutoScalingConfiguration::isActive)
                    .max(Comparator.comparingInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision))
                    .orElseThrow(() -> notFound(arn));
        }
        if (!target.isActive()) {
            throw notFound(arn);
        }
        if (target.isDefault()) {
            throw invalidRequest("Cannot delete the default auto scaling configuration.");
        }
        if (target.isHasAssociatedService()) {
            throw invalidRequest("Cannot delete an auto scaling configuration that is associated with a service.");
        }
        AutoScalingConfiguration deleted = deactivate(target, Instant.now().getEpochSecond());
        recomputeLatest(region, deleted.getAutoScalingConfigurationName());
        return requireConfig(deleted.getAutoScalingConfigurationArn(), region);
    }

    private JsonNode list(JsonNode request, String region) {
        ensureDefault(region);
        String nameFilter = optionalText(request, "AutoScalingConfigurationName");
        boolean latestOnly = request.path("LatestOnly").asBoolean(false);
        int maxResults = readMaxResults(request);
        int offset = readOffset(request);

        List<AutoScalingConfiguration> matches = new ArrayList<>();
        for (AutoScalingConfiguration config : store.values()) {
            if (!region.equals(config.getRegion())) {
                continue;
            }
            if (nameFilter != null && !nameFilter.equals(config.getAutoScalingConfigurationName())) {
                continue;
            }
            matches.add(config);
        }
        matches.sort(Comparator
                .comparing(AutoScalingConfiguration::getAutoScalingConfigurationName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.comparingInt(
                        AutoScalingConfiguration::getAutoScalingConfigurationRevision).reversed()));

        if (latestOnly) {
            Map<String, AutoScalingConfiguration> latest = new LinkedHashMap<>();
            for (AutoScalingConfiguration config : matches) {
                latest.putIfAbsent(config.getAutoScalingConfigurationName(), config);
            }
            matches = new ArrayList<>(latest.values());
            matches.sort(Comparator.comparing(AutoScalingConfiguration::getAutoScalingConfigurationName,
                    String.CASE_INSENSITIVE_ORDER));
        }

        int from = Math.min(offset, matches.size());
        int to = Math.min(from + maxResults, matches.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("AutoScalingConfigurationSummaryList");
        for (AutoScalingConfiguration config : matches.subList(from, to)) {
            summaries.add(toSummary(config));
        }
        if (to < matches.size()) {
            response.put("NextToken", Integer.toString(to));
        }
        return response;
    }

    private JsonNode listTags(JsonNode request, String region) {
        AutoScalingConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", tagsNode(config));
        return response;
    }

    private JsonNode tag(JsonNode request, String region) {
        AutoScalingConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || !tagsNode.isArray()) {
            throw invalidRequest("Tags is required.");
        }
        config.putTags(readTagList(tagsNode));
        store.put(config.getAutoScalingConfigurationArn(), config);
        return mapper.createObjectNode();
    }

    private JsonNode untag(JsonNode request, String region) {
        AutoScalingConfiguration config = requireConfig(requireText(request, "ResourceArn"), region);
        JsonNode keysNode = request.get("TagKeys");
        if (keysNode == null || !keysNode.isArray()) {
            throw invalidRequest("TagKeys is required.");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode key : keysNode) {
            if (key != null && key.isTextual()) {
                keys.add(key.asText());
            }
        }
        config.removeTags(keys);
        store.put(config.getAutoScalingConfigurationArn(), config);
        return mapper.createObjectNode();
    }

    private AutoScalingConfiguration deactivate(AutoScalingConfiguration config, long now) {
        config.setStatus(AutoScalingConfiguration.STATUS_INACTIVE);
        config.setDeletedAt(now);
        store.put(config.getAutoScalingConfigurationArn(), config);
        return config;
    }

    private void recomputeLatest(String region, String name) {
        List<AutoScalingConfiguration> revisions = configsNamed(region, name);
        int maxRevision = revisions.stream()
                .mapToInt(AutoScalingConfiguration::getAutoScalingConfigurationRevision)
                .max()
                .orElse(0);
        for (AutoScalingConfiguration revision : revisions) {
            boolean latest = revision.getAutoScalingConfigurationRevision() == maxRevision;
            if (revision.isLatest() != latest) {
                revision.setLatest(latest);
                store.put(revision.getAutoScalingConfigurationArn(), revision);
            }
        }
    }

    private void ensureDefault(String region) {
        boolean exists = store.values().stream()
                .anyMatch(config -> region.equals(config.getRegion())
                        && AutoScalingConfiguration.DEFAULT_NAME.equals(config.getAutoScalingConfigurationName()));
        if (exists) {
            return;
        }
        AutoScalingConfiguration config = new AutoScalingConfiguration();
        config.setAutoScalingConfigurationName(AutoScalingConfiguration.DEFAULT_NAME);
        config.setAutoScalingConfigurationRevision(1);
        config.setConfigurationId(DEFAULT_CONFIG_ID);
        config.setAutoScalingConfigurationArn(
                arn(region, AutoScalingConfiguration.DEFAULT_NAME, 1, DEFAULT_CONFIG_ID));
        config.setLatest(true);
        config.setStatus(AutoScalingConfiguration.STATUS_ACTIVE);
        config.setMaxConcurrency(100);
        config.setMinSize(1);
        config.setMaxSize(25);
        config.setCreatedAt(Instant.now().getEpochSecond());
        config.setHasAssociatedService(false);
        config.setDefault(true);
        config.setRegion(region);
        store.put(config.getAutoScalingConfigurationArn(), config);
    }

    private ObjectNode wrap(AutoScalingConfiguration config) {
        ObjectNode response = mapper.createObjectNode();
        response.set("AutoScalingConfiguration", toJson(config));
        return response;
    }

    private ObjectNode toJson(AutoScalingConfiguration config) {
        ObjectNode node = mapper.createObjectNode();
        node.put("AutoScalingConfigurationArn", config.getAutoScalingConfigurationArn());
        node.put("AutoScalingConfigurationName", config.getAutoScalingConfigurationName());
        node.put("AutoScalingConfigurationRevision", config.getAutoScalingConfigurationRevision());
        node.put("Latest", config.isLatest());
        node.put("Status", config.getStatus());
        node.put("MaxConcurrency", config.getMaxConcurrency());
        node.put("MinSize", config.getMinSize());
        node.put("MaxSize", config.getMaxSize());
        node.put("CreatedAt", config.getCreatedAt());
        if (config.getDeletedAt() != null) {
            node.put("DeletedAt", config.getDeletedAt());
        }
        node.put("HasAssociatedService", config.isHasAssociatedService());
        node.put("IsDefault", config.isDefault());
        return node;
    }

    private ObjectNode toSummary(AutoScalingConfiguration config) {
        ObjectNode node = mapper.createObjectNode();
        node.put("AutoScalingConfigurationArn", config.getAutoScalingConfigurationArn());
        node.put("AutoScalingConfigurationName", config.getAutoScalingConfigurationName());
        node.put("AutoScalingConfigurationRevision", config.getAutoScalingConfigurationRevision());
        node.put("Status", config.getStatus());
        node.put("CreatedAt", config.getCreatedAt());
        node.put("HasAssociatedService", config.isHasAssociatedService());
        node.put("IsDefault", config.isDefault());
        return node;
    }

    private ArrayNode tagsNode(AutoScalingConfiguration config) {
        ArrayNode tags = mapper.createArrayNode();
        for (Map.Entry<String, String> entry : config.getTags().entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return tags;
    }

    private AutoScalingConfiguration requireConfig(String arn, String region) {
        AutoScalingConfiguration config = store.get(arn).orElse(null);
        if (config == null || (config.getRegion() != null && !region.equals(config.getRegion()))) {
            throw notFound(arn);
        }
        return config;
    }

    private List<AutoScalingConfiguration> configsNamed(String region, String name) {
        List<AutoScalingConfiguration> matches = new ArrayList<>();
        for (AutoScalingConfiguration config : store.values()) {
            if (region.equals(config.getRegion()) && name.equals(config.getAutoScalingConfigurationName())) {
                matches.add(config);
            }
        }
        return matches;
    }

    private String arn(String region, String name, int revision, String id) {
        return "arn:aws:apprunner:" + region + ":" + regionResolver.getAccountId()
                + ":autoscalingconfiguration/" + name + "/" + revision + "/" + id;
    }

    private Map<String, String> readTags(JsonNode request) {
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!tagsNode.isArray()) {
            throw invalidRequest("Tags must be a list.");
        }
        return readTagList(tagsNode);
    }

    private Map<String, String> readTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
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

    private Integer optionalInt(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }

    private static int valueOrDefault(Integer value, int defaultValue, int min, int max, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) {
            throw invalidRequest(field + " must be between " + min + " and " + max + ".");
        }
        return resolved;
    }

    private static ParsedArn parseConfigArn(String arn) {
        int marker = arn.indexOf(":autoscalingconfiguration/");
        if (marker < 0) {
            throw invalidRequest("Invalid AutoScalingConfigurationArn.");
        }
        String rest = arn.substring(marker + ":autoscalingconfiguration/".length());
        String[] parts = rest.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            throw invalidRequest("Invalid AutoScalingConfigurationArn.");
        }
        if (parts.length == 1) {
            return new ParsedArn(parts[0], false);
        }
        if (parts.length >= 3) {
            return new ParsedArn(parts[0], true);
        }
        throw invalidRequest("Invalid AutoScalingConfigurationArn.");
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Resource with the ARN '" + arn + "' is not found.", 400);
    }

    private record ParsedArn(String name, boolean full) {
    }
}
