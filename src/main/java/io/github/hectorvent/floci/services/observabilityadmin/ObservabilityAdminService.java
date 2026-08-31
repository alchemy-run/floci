package io.github.hectorvent.floci.services.observabilityadmin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.observabilityadmin.model.AccountTelemetryState;
import io.github.hectorvent.floci.services.observabilityadmin.model.TelemetryRuleRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CloudWatch Observability Admin restJson1 — telemetry evaluation onboarding,
 * telemetry rules, resource telemetry listing, and enrichment status.
 */
@ApplicationScoped
public class ObservabilityAdminService {

    static final String SERVICE = "observabilityadmin";
    private static final String RESOURCE_PREFIX = "telemetry-rule/";
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_RESULTS = 1000;
    private static final String TOKEN_PREFIX = "observabilityadmin:v1:";
    private static final Pattern RULE_NAME_PATTERN = Pattern.compile("[0-9A-Za-z-_]{1,100}");

    private final StorageBackend<String, AccountTelemetryState> evaluations;
    private final StorageBackend<String, TelemetryRuleRecord> rules;
    private final RegionResolver regionResolver;

    @Inject
    public ObservabilityAdminService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        SERVICE,
                        "observabilityadmin-evaluations.json",
                        new TypeReference<Map<String, AccountTelemetryState>>() {
                        }),
                storageFactory.create(
                        SERVICE,
                        "observabilityadmin-rules.json",
                        new TypeReference<Map<String, TelemetryRuleRecord>>() {
                        }),
                regionResolver);
    }

    ObservabilityAdminService(
            StorageBackend<String, AccountTelemetryState> evaluations,
            StorageBackend<String, TelemetryRuleRecord> rules,
            RegionResolver regionResolver) {
        this.evaluations = evaluations;
        this.rules = rules;
        this.regionResolver = regionResolver;
    }

    public AccountTelemetryState getTelemetryEvaluationStatus(String region) {
        return evaluations.get(region).orElseGet(AccountTelemetryState::new);
    }

    public synchronized AccountTelemetryState startTelemetryEvaluation(String region, JsonNode request) {
        requireObject(request, "Request body");
        AccountTelemetryState state = evaluations.get(region).orElseGet(AccountTelemetryState::new);
        state.setEvaluationStatus(AccountTelemetryState.RUNNING);
        state.setHomeRegion(region);
        evaluations.put(region, state);
        return state;
    }

    public synchronized AccountTelemetryState stopTelemetryEvaluation(String region) {
        AccountTelemetryState state = evaluations.get(region).orElseGet(AccountTelemetryState::new);
        if (AccountTelemetryState.NOT_STARTED.equals(state.getEvaluationStatus())) {
            evaluations.put(region, state);
            return state;
        }
        state.setEvaluationStatus(AccountTelemetryState.STOPPED);
        evaluations.put(region, state);
        return state;
    }

    public AccountTelemetryState getTelemetryEnrichmentStatus(String region) {
        return evaluations.get(region).orElseGet(AccountTelemetryState::new);
    }

    public synchronized AccountTelemetryState startTelemetryEnrichment(String region) {
        AccountTelemetryState state = evaluations.get(region).orElseGet(AccountTelemetryState::new);
        state.setEnrichmentStatus(AccountTelemetryState.ENRICHMENT_RUNNING);
        evaluations.put(region, state);
        return state;
    }

    public synchronized AccountTelemetryState stopTelemetryEnrichment(String region) {
        AccountTelemetryState state = evaluations.get(region).orElseGet(AccountTelemetryState::new);
        state.setEnrichmentStatus(AccountTelemetryState.ENRICHMENT_STOPPED);
        evaluations.put(region, state);
        return state;
    }

    public synchronized TelemetryRuleRecord createTelemetryRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireEvaluationRunning(region);
        String name = requireText(request, "RuleName");
        validateName(name);
        JsonNode rule = requireRule(request);
        if (rules.get(ruleKey(region, name)).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "A telemetry rule named " + name + " already exists.",
                    409);
        }
        long now = Instant.now().toEpochMilli();
        TelemetryRuleRecord record = new TelemetryRuleRecord();
        record.setRuleName(name);
        record.setRuleArn(arn(region, RESOURCE_PREFIX + name));
        record.setCreatedTimeStamp(now);
        record.setLastUpdateTimeStamp(now);
        record.setHomeRegion(region);
        record.setReplicated(false);
        record.setRule(rule);
        record.setTags(readTags(request));
        rules.put(ruleKey(region, name), record);
        return record;
    }

    public TelemetryRuleRecord getTelemetryRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireRuleRecord(region, requireText(request, "RuleIdentifier"));
    }

    public synchronized TelemetryRuleRecord updateTelemetryRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        TelemetryRuleRecord record = requireRuleRecord(region, requireText(request, "RuleIdentifier"));
        record.setRule(requireRule(request));
        record.setLastUpdateTimeStamp(Instant.now().toEpochMilli());
        rules.put(ruleKey(region, record.getRuleName()), record);
        return record;
    }

    public synchronized void deleteTelemetryRule(String region, JsonNode request) {
        requireObject(request, "Request body");
        TelemetryRuleRecord record = requireRuleRecord(region, requireText(request, "RuleIdentifier"));
        rules.delete(ruleKey(region, record.getRuleName()));
    }

    public Page<TelemetryRuleRecord> listTelemetryRules(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        int maxResults = parseMaxResults(body);
        String nextToken = textOrNull(body, "NextToken");
        String prefix = textOrNull(body, "RuleNamePrefix");
        List<TelemetryRuleRecord> items = new ArrayList<>(rules.scan(key -> key.startsWith(region + "::")));
        if (prefix != null) {
            items.removeIf(item -> item.getRuleName() == null || !item.getRuleName().startsWith(prefix));
        }
        items.sort(Comparator.comparing(TelemetryRuleRecord::getRuleName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public Page<TelemetryRuleRecord> listResourceTelemetry(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        if (body != null) {
            requireObject(body, "Request body");
            parseMaxResults(body);
        }
        return new Page<>(List.of(), null);
    }

    public Map<String, String> listTagsForResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        return Map.copyOf(requireRuleRecord(region, requireText(request, "ResourceARN")).getTags());
    }

    public synchronized void tagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        TelemetryRuleRecord record = requireRuleRecord(region, requireText(request, "ResourceARN"));
        Map<String, String> tags = new LinkedHashMap<>(record.getTags());
        tags.putAll(readTags(request));
        if (tags.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        record.setTags(tags);
        rules.put(ruleKey(region, record.getRuleName()), record);
    }

    public synchronized void untagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        TelemetryRuleRecord record = requireRuleRecord(region, requireText(request, "ResourceARN"));
        Map<String, String> tags = new LinkedHashMap<>(record.getTags());
        for (String key : readStringList(request, "TagKeys", true)) {
            tags.remove(key);
        }
        record.setTags(tags);
        rules.put(ruleKey(region, record.getRuleName()), record);
    }

    private void requireEvaluationRunning(String region) {
        AccountTelemetryState state = evaluations.get(region).orElseGet(AccountTelemetryState::new);
        String status = state.getEvaluationStatus();
        if (!AccountTelemetryState.RUNNING.equals(status) && !"STARTING".equals(status)) {
            throw new AwsException(
                    "InvalidStateException",
                    "Telemetry evaluation is not enabled for this account.",
                    400);
        }
    }

    private TelemetryRuleRecord requireRuleRecord(String region, String identifier) {
        String name = ruleNameFromIdentifier(identifier);
        return rules.get(ruleKey(region, name)).orElseThrow(() -> resourceNotFound(identifier));
    }

    private String ruleNameFromIdentifier(String identifier) {
        if (identifier.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(identifier);
                if (!SERVICE.equals(parsed.service())
                        || parsed.resource() == null
                        || !parsed.resource().startsWith(RESOURCE_PREFIX)) {
                    throw resourceNotFound(identifier);
                }
                String name = parsed.resource().substring(RESOURCE_PREFIX.length());
                if (name.isBlank()) {
                    throw resourceNotFound(identifier);
                }
                return name;
            } catch (IllegalArgumentException e) {
                throw resourceNotFound(identifier);
            }
        }
        return identifier;
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String ruleKey(String region, String name) {
        return region + "::" + name;
    }

    private static void validateName(String name) {
        if (name == null || !RULE_NAME_PATTERN.matcher(name).matches()) {
            throw validation("RuleName must match [0-9A-Za-z-_]{1,100}.");
        }
    }

    private static JsonNode requireRule(JsonNode request) {
        JsonNode rule = request.get("Rule");
        requireObject(rule, "Rule");
        JsonNode telemetryType = rule.get("TelemetryType");
        if (telemetryType == null || telemetryType.isNull()
                || !telemetryType.isTextual()
                || telemetryType.textValue().isBlank()) {
            throw validation("TelemetryType is required.");
        }
        return rule.deepCopy();
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (request == null || !request.has("Tags") || request.get("Tags").isNull()) {
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

    private static List<String> readStringList(JsonNode parent, String field, boolean required) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return List.of();
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || value.isNull() || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw validation(field + " is required.");
        }
        String text = value.textValue();
        if (text == null || text.isBlank()) {
            throw validation(field + " is required.");
        }
        return text;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("MaxResults") || request.get("MaxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("MaxResults");
        if (!value.isNumber() && !value.isTextual()) {
            throw validation("MaxResults must be an integer between 1 and 1000.");
        }
        int parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw validation("MaxResults must be between 1 and 1000.");
        }
        return parsed;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
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

    private static AwsException resourceNotFound(String identifier) {
        return new AwsException(
                "ResourceNotFoundException",
                "Resource " + identifier + " not found.",
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
