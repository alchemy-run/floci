package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ConfigResourceService {

    private static final Pattern SELECT = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(.+?)(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+([\\w.]+)(?:\\s+(ASC|DESC))?)?\\s*$");
    private static final Pattern CONDITION = Pattern.compile(
            "\\G\\s*([\\w.]+)\\s*(=|!=|<>)\\s*('(?:[^']|'')*'|true|false|-?\\d+(?:\\.\\d+)?)(?:\\s+(?i:AND)\\s+|\\s*$)");
    private static final Set<String> QUERY_FIELDS = Set.of("resourceId", "resourceType", "resourceName",
            "accountId", "awsRegion", "arn", "version", "configuration", "tags", "configurationStateId",
            "configurationItemStatus", "configurationItemCaptureTime");

    private final AwsConfigService config;
    private final RegionResolver regions;
    private final StorageFactory storage;
    private final ObjectMapper mapper;
    private Map<String, List<ObjectNode>> histories = new ConcurrentHashMap<>();
    private Map<String, ObjectNode> resourceEvaluations = new ConcurrentHashMap<>();

    @Inject
    public ConfigResourceService(AwsConfigService config, RegionResolver regions,
            StorageFactory storage, ObjectMapper mapper) {
        this.config = config;
        this.regions = regions;
        this.storage = storage;
        this.mapper = mapper;
    }

    @PostConstruct
    void initializeStorage() {
        if (storage != null) {
            histories = new StorageBackedMap<>(storage.create("config", "config-resource-history.json",
                    new TypeReference<Map<String, List<ObjectNode>>>() {}));
            resourceEvaluations = new StorageBackedMap<>(storage.create("config", "config-resource-evaluations.json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
        }
    }

    public ObjectNode putResourceConfig(String region, JsonNode request) {
        synchronized (config) {
            config.requireRunningRecorder(region);
            String type = customType(request);
            String id = required(request, "ResourceId", "ValidationException");
            required(request, "SchemaVersionId", "ValidationException");
            String configuration = required(request, "Configuration", "ValidationException");
            parseConfiguration(configuration, "ValidationException");
            if (request.has("Tags") && (!request.path("Tags").isObject()
                    || request.path("Tags").size() > 50)) {
                throw error("ValidationException", "Tags must be an object with at most 50 entries.");
            }
            for (JsonNode value : request.path("Tags")) {
                if (!value.isTextual()) {
                    throw error("ValidationException", "Tag values must be strings.");
                }
            }
            String key = resourceKey(region, type, id);
            List<ObjectNode> history = new ArrayList<>(histories.getOrDefault(key, List.of()));
            ObjectNode item = mapper.createObjectNode();
            item.put("version", "1.3");
            item.put("accountId", regions.getAccountId());
            item.put("awsRegion", region);
            item.put("resourceType", type);
            item.put("resourceId", id);
            if (request.hasNonNull("ResourceName")) {
                item.put("resourceName", request.path("ResourceName").asText());
            }
            item.put("configuration", configuration);
            item.set("tags", request.has("Tags") ? request.path("Tags").deepCopy() : mapper.createObjectNode());
            item.put("configurationItemStatus", history.isEmpty() ? "ResourceDiscovered" : "OK");
            append(history, item);
            histories.put(key, history);
            return mapper.createObjectNode();
        }
    }

    public ObjectNode deleteResourceConfig(String region, JsonNode request) {
        synchronized (config) {
            config.requireRunningRecorder(region);
            String type = customType(request);
            String id = required(request, "ResourceId", "ValidationException");
            String key = resourceKey(region, type, id);
            List<ObjectNode> history = new ArrayList<>(histories.getOrDefault(key, List.of()));
            if (!history.isEmpty() && !deleted(history.getLast())) {
                ObjectNode item = history.getLast().deepCopy();
                item.put("configurationItemStatus", "ResourceDeleted");
                item.remove(List.of("configuration", "tags"));
                append(history, item);
                histories.put(key, history);
            }
            return mapper.createObjectNode();
        }
    }

    public ObjectNode listDiscoveredResources(String region, JsonNode request) {
        String type = required(request, "resourceType", "ValidationException");
        List<String> ids = strings(request, "resourceIds", 100, "ValidationException");
        if (!ids.isEmpty() && request.hasNonNull("resourceName")) {
            throw error("ValidationException", "Specify resourceIds or resourceName, not both.");
        }
        List<ObjectNode> identifiers = new ArrayList<>();
        for (ObjectNode item : current(region, request.path("includeDeletedResources").asBoolean(false))) {
            if (!type.equals(item.path("resourceType").asText())
                    || (!ids.isEmpty() && !ids.contains(item.path("resourceId").asText()))
                    || (request.hasNonNull("resourceName")
                        && !request.path("resourceName").equals(item.path("resourceName")))) {
                continue;
            }
            ObjectNode identifier = mapper.createObjectNode();
            copy(item, identifier, "resourceType", "resourceId", "resourceName");
            if (deleted(item)) {
                identifier.set("resourceDeletionTime", item.path("configurationItemCaptureTime"));
            }
            identifiers.add(identifier);
        }
        return page(region, "discovered", request, identifiers, "resourceIdentifiers", "limit", "nextToken", 100,
                "InvalidLimitException");
    }

    public ObjectNode getDiscoveredResourceCounts(String region, JsonNode request) {
        List<String> types = strings(request, "resourceTypes", 20, "ValidationException");
        Map<String, Long> counts = new TreeMap<>();
        for (ObjectNode item : current(region, false)) {
            String type = item.path("resourceType").asText();
            if (types.isEmpty() || types.contains(type)) {
                counts.merge(type, 1L, Long::sum);
            }
        }
        List<ObjectNode> entries = new ArrayList<>();
        counts.forEach((type, count) -> entries.add(mapper.createObjectNode()
                .put("resourceType", type).put("count", count)));
        ObjectNode response = page(region, "counts", request, entries, "resourceCounts", "limit", "nextToken", 100,
                "InvalidLimitException");
        response.put("totalDiscoveredResources", counts.values().stream().mapToLong(Long::longValue).sum());
        return response;
    }

    public ObjectNode batchGetResourceConfig(String region, JsonNode request) {
        JsonNode keys = request.path("resourceKeys");
        if (!keys.isArray() || keys.isEmpty() || keys.size() > 100) {
            throw error("ValidationException", "resourceKeys must contain between 1 and 100 entries.");
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("baseConfigurationItems");
        ArrayNode unprocessed = response.putArray("unprocessedResourceKeys");
        for (JsonNode key : keys) {
            String type = required(key, "resourceType", "ValidationException");
            String id = required(key, "resourceId", "ValidationException");
            List<ObjectNode> history = histories.getOrDefault(resourceKey(region, type, id), List.of());
            if (history.isEmpty() || deleted(history.getLast())) {
                unprocessed.add(key.deepCopy());
            } else {
                ObjectNode item = history.getLast().deepCopy();
                item.remove("tags");
                items.add(item);
            }
        }
        return response;
    }

    public ObjectNode getResourceConfigHistory(String region, JsonNode request) {
        String type = required(request, "resourceType", "ValidationException");
        String id = required(request, "resourceId", "ValidationException");
        double after = timestamp(request, "earlierTime", Double.NEGATIVE_INFINITY);
        double before = timestamp(request, "laterTime", Double.POSITIVE_INFINITY);
        validateTimeWindow(after, before);
        String order = request.path("chronologicalOrder").asText("Reverse");
        if (!Set.of("Forward", "Reverse").contains(order)) {
            throw error("ValidationException", "chronologicalOrder must be Forward or Reverse.");
        }
        List<ObjectNode> history = histories.getOrDefault(resourceKey(region, type, id), List.of());
        if (history.isEmpty()) {
            throw error("ResourceNotDiscoveredException", "The resource has not been discovered.");
        }
        List<ObjectNode> items = new ArrayList<>(history.stream()
                .filter(item -> item.path("configurationItemCaptureTime").asDouble() >= after
                        && item.path("configurationItemCaptureTime").asDouble() <= before).toList());
        if ("Reverse".equals(order)) {
            Collections.reverse(items);
        }
        return page(region, "history", request, items, "configurationItems", "limit", "nextToken", 10,
                "InvalidLimitException");
    }

    public ObjectNode selectResourceConfig(String region, JsonNode request) {
        String expression = required(request, "Expression", "InvalidExpressionException");
        Matcher select = SELECT.matcher(expression);
        if (!select.matches()) {
            throw error("InvalidExpressionException", "Expected a SELECT expression.");
        }
        String projection = select.group(1).trim();
        boolean all = "*".equals(projection);
        boolean count = "COUNT(*)".equalsIgnoreCase(projection);
        List<String> fields = List.of(projection.split("\\s*,\\s*", -1));
        if (!all && !count) {
            fields.forEach(this::validateQueryField);
        }
        List<QueryCondition> conditions = new ArrayList<>();
        if (select.group(2) != null) {
            String where = select.group(2);
            Matcher condition = CONDITION.matcher(where);
            int end = 0;
            while (condition.find()) {
                validateQueryField(condition.group(1));
                String literal = condition.group(3);
                JsonNode value = literal.startsWith("'")
                        ? mapper.getNodeFactory().textNode(literal.substring(1, literal.length() - 1).replace("''", "'"))
                        : parseLiteral(literal);
                conditions.add(new QueryCondition(condition.group(1), condition.group(2), value));
                end = condition.end();
            }
            if (end != where.length() || conditions.isEmpty() || where.matches("(?is).*\\sAND\\s*$")) {
                throw error("InvalidExpressionException", "Only AND-combined equality predicates are supported.");
            }
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode item : current(region, false)) {
            ObjectNode row = item.deepCopy();
            row.set("configuration", parseConfiguration(item.path("configuration").asText(), "InvalidExpressionException"));
            if (conditions.stream().allMatch(condition -> condition.matches(row))) {
                rows.add(row);
            }
        }
        if (select.group(3) != null) {
            String field = select.group(3);
            validateQueryField(field);
            Comparator<ObjectNode> comparator = (left, right) -> compareValues(field(left, field), field(right, field));
            rows.sort("DESC".equalsIgnoreCase(select.group(4)) ? comparator.reversed() : comparator);
        }
        List<String> results = new ArrayList<>();
        if (count) {
            results.add(mapper.createObjectNode().put("COUNT(*)", rows.size()).toString());
        } else {
            for (ObjectNode row : rows) {
                ObjectNode selected = all ? row : mapper.createObjectNode();
                if (!all) {
                    for (String field : fields) {
                        JsonNode value = field(row, field);
                        if (!value.isMissingNode()) {
                            project(selected, field, value);
                        }
                    }
                }
                results.add(selected.toString());
            }
        }
        ObjectNode response = page(region, "select", request, results, "Results", "Limit", "NextToken", 25,
                "InvalidLimitException");
        ArrayNode selectedFields = response.putObject("QueryInfo").putArray("SelectFields");
        fields.forEach(field -> selectedFields.addObject().put("FieldName", field));
        return response;
    }

    public ObjectNode startResourceEvaluation(String region, JsonNode request) {
        synchronized (config) {
            String code = "InvalidParameterValueException";
            if (!"PROACTIVE".equals(request.path("EvaluationMode").asText())) {
                throw error(code, "EvaluationMode must be PROACTIVE.");
            }
            JsonNode details = request.path("ResourceDetails");
            required(details, "ResourceId", code);
            String type = required(details, "ResourceType", code);
            if (!"CFN_RESOURCE_SCHEMA".equals(details.path("ResourceConfigurationSchemaType").asText("CFN_RESOURCE_SCHEMA"))) {
                throw error(code, "ResourceConfigurationSchemaType must be CFN_RESOURCE_SCHEMA.");
            }
            JsonNode configuration = parseConfiguration(required(details, "ResourceConfiguration", code), code);
            if (request.has("EvaluationTimeout") && (!request.path("EvaluationTimeout").isIntegralNumber()
                    || request.path("EvaluationTimeout").asInt() < 1 || request.path("EvaluationTimeout").asInt() > 3600)) {
                throw error(code, "EvaluationTimeout must be between 1 and 3600 seconds.");
            }
            if (request.has("EvaluationContext")) {
                JsonNode context = request.path("EvaluationContext");
                if (!context.isObject() || (context.has("EvaluationContextIdentifier")
                        && !context.path("EvaluationContextIdentifier").isTextual())) {
                    throw error(code, "EvaluationContext must contain a string identifier.");
                }
            }
            if (request.has("ClientToken") && !request.path("ClientToken").isTextual()) {
                throw error(code, "ClientToken must be a string.");
            }
            String token = request.path("ClientToken").asText(null);
            if (token != null) {
                if (token.isBlank() || token.length() > 64) {
                    throw error(code, "ClientToken must contain between 1 and 64 characters.");
                }
                for (ObjectNode stored : evaluations(region)) {
                    if (token.equals(stored.path("request").path("ClientToken").asText(null))) {
                        if (!stored.path("request").equals(request)) {
                            throw error("IdempotentParameterMismatch", "ClientToken was used with different parameters.");
                        }
                        return mapper.createObjectNode().set("ResourceEvaluationId", stored.path("summary").path("ResourceEvaluationId"));
                    }
                }
            }
            List<ConfigRule> rules = config.describeConfigRules(region, List.of()).stream()
                    .filter(rule -> "ACTIVE".equals(rule.configRuleState()))
                    .filter(rule -> rule.evaluationModes() != null && rule.evaluationModes().stream()
                            .anyMatch(mode -> mode != null && "PROACTIVE".equals(mode.mode())))
                    .filter(rule -> rule.scope() == null || rule.scope().complianceResourceTypes() == null
                            || rule.scope().complianceResourceTypes().isEmpty()
                            || rule.scope().complianceResourceTypes().contains(type))
                    .toList();
            if (rules.isEmpty()) {
                throw error(code, "No applicable PROACTIVE Config rule is configured for this resource type.");
            }
            for (ConfigRule rule : rules) {
                if (!"AWS::S3::Bucket".equals(type) || rule.source() == null
                        || !"AWS".equals(rule.source().owner())
                        || !"S3_BUCKET_VERSIONING_ENABLED".equals(rule.source().sourceIdentifier())
                        || (rule.inputParameters() != null && !rule.inputParameters().isBlank()
                            && !"{}".equals(rule.inputParameters().trim()))
                        || (rule.scope() != null && (rule.scope().tagKey() != null
                            || rule.scope().complianceResourceId() != null))) {
                    throw error(code, "This proactive rule or scope is not supported by the local evaluator.");
                }
            }
            if (config.describeConfigurationRecorders(region, List.of()).isEmpty()) {
                throw error(code, "A configuration recorder must be configured before evaluating resources.");
            }
            Set<String> supportedProperties = Set.of("BucketName", "VersioningConfiguration", "Tags");
            for (Map.Entry<String, JsonNode> property : configuration.properties()) {
                if (!supportedProperties.contains(property.getKey())) {
                    throw error(code, "This local evaluator does not support property " + property.getKey() + ".");
                }
            }
            if (configuration.has("BucketName") && (!configuration.path("BucketName").isTextual()
                    || !configuration.path("BucketName").asText().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) {
                throw error(code, "BucketName must be a valid S3 bucket name.");
            }
            if (configuration.has("Tags")) {
                if (!configuration.path("Tags").isArray()) {
                    throw error(code, "Tags must be a CloudFormation tag list.");
                }
                for (JsonNode tag : configuration.path("Tags")) {
                    required(tag, "Key", code);
                    if (!tag.path("Value").isTextual()) {
                        throw error(code, "Tag Value must be a string.");
                    }
                }
            }
            JsonNode versioning = configuration.path("VersioningConfiguration");
            if (versioning.isObject() && (versioning.size() != 1 || !versioning.has("Status"))) {
                throw error(code, "Only VersioningConfiguration.Status is supported.");
            }
            if ((!versioning.isMissingNode() && !versioning.isObject())
                    || (versioning.has("Status") && !Set.of("Enabled", "Suspended").contains(versioning.path("Status").asText()))) {
                throw error(code, "VersioningConfiguration.Status must be Enabled or Suspended.");
            }
            String id = UUID.randomUUID().toString();
            ObjectNode summary = mapper.createObjectNode();
            summary.put("ResourceEvaluationId", id);
            summary.put("EvaluationMode", "PROACTIVE");
            summary.put("EvaluationStartTimestamp", now());
            summary.putObject("EvaluationStatus").put("Status", "SUCCEEDED");
            summary.put("Compliance", "Enabled".equals(versioning.path("Status").asText()) ? "COMPLIANT" : "NON_COMPLIANT");
            summary.set("ResourceDetails", details.deepCopy());
            if (request.has("EvaluationContext")) {
                summary.set("EvaluationContext", request.path("EvaluationContext").deepCopy());
            }
            ObjectNode stored = mapper.createObjectNode();
            stored.set("request", request.deepCopy());
            stored.set("summary", summary);
            resourceEvaluations.put(scope(region) + id, stored);
            return mapper.createObjectNode().put("ResourceEvaluationId", id);
        }
    }

    public ObjectNode getResourceEvaluationSummary(String region, JsonNode request) {
        String id = required(request, "ResourceEvaluationId", "InvalidParameterValueException");
        ObjectNode stored = resourceEvaluations.get(scope(region) + id);
        if (stored == null) {
            throw error("ResourceNotFoundException", "Resource evaluation does not exist.");
        }
        return ((ObjectNode) stored.path("summary")).deepCopy();
    }

    public ObjectNode listResourceEvaluations(String region, JsonNode request) {
        JsonNode filters = request.path("Filters");
        if ((!filters.isMissingNode() && !filters.isObject())
                || (filters.has("TimeWindow") && !filters.path("TimeWindow").isObject())
                || (filters.has("EvaluationMode") && !filters.path("EvaluationMode").isTextual())
                || (filters.has("EvaluationContextIdentifier") && !filters.path("EvaluationContextIdentifier").isTextual())) {
            throw error("InvalidParameterValueException", "Invalid resource evaluation filters.");
        }
        String mode = filters.path("EvaluationMode").asText(null);
        if (mode != null && !Set.of("PROACTIVE", "DETECTIVE").contains(mode)) {
            throw error("InvalidParameterValueException", "Invalid EvaluationMode.");
        }
        double start = timestamp(filters.path("TimeWindow"), "StartTime", Double.NEGATIVE_INFINITY);
        double end = timestamp(filters.path("TimeWindow"), "EndTime", Double.POSITIVE_INFINITY);
        validateTimeWindow(start, end);
        String context = filters.path("EvaluationContextIdentifier").asText(null);
        List<ObjectNode> results = new ArrayList<>();
        for (ObjectNode stored : evaluations(region)) {
            JsonNode summary = stored.path("summary");
            double time = summary.path("EvaluationStartTimestamp").asDouble();
            if ((mode == null || mode.equals(summary.path("EvaluationMode").asText())) && time >= start && time <= end
                    && (context == null || context.equals(summary.path("EvaluationContext")
                            .path("EvaluationContextIdentifier").asText(null)))) {
                ObjectNode entry = mapper.createObjectNode();
                copy(summary, entry, "ResourceEvaluationId", "EvaluationMode", "EvaluationStartTimestamp");
                results.add(entry);
            }
        }
        results.sort(Comparator.comparingDouble((ObjectNode entry) -> entry.path("EvaluationStartTimestamp").asDouble())
                .thenComparing(entry -> entry.path("ResourceEvaluationId").asText()));
        return page(region, "evaluations", request, results, "ResourceEvaluations", "Limit", "NextToken", 100,
                "InvalidParameterValueException");
    }

    private List<ObjectNode> evaluations(String region) {
        return resourceEvaluations.entrySet().stream().filter(entry -> entry.getKey().startsWith(scope(region)))
                .map(Map.Entry::getValue).toList();
    }

    private List<ObjectNode> current(String region, boolean includeDeleted) {
        return histories.entrySet().stream().filter(entry -> entry.getKey().startsWith(scope(region)))
                .map(entry -> entry.getValue().getLast()).filter(item -> includeDeleted || !deleted(item))
                .sorted(Comparator.comparing((ObjectNode item) -> item.path("resourceType").asText())
                        .thenComparing(item -> item.path("resourceId").asText())).toList();
    }

    private void append(List<ObjectNode> history, ObjectNode item) {
        item.put("configurationStateId", Long.toString(history.size() + 1L));
        item.put("configurationItemCaptureTime", now());
        history.add(item);
    }

    private String scope(String region) {
        return regions.getAccountId() + "|" + region + "|";
    }

    private String resourceKey(String region, String type, String id) {
        return scope(region) + type + "|" + id;
    }

    private String customType(JsonNode request) {
        String type = required(request, "ResourceType", "ValidationException");
        if (!type.matches("[A-Za-z0-9]+::[A-Za-z0-9]+::[A-Za-z0-9]+") || type.startsWith("AWS::")) {
            throw error("ValidationException", "ResourceType must identify a custom resource type.");
        }
        return type;
    }

    private JsonNode parseConfiguration(String value, String code) {
        try {
            JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw error(code, "Resource configuration must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw error(code, "Resource configuration is not valid JSON.");
        }
    }

    private JsonNode parseLiteral(String literal) {
        try {
            return mapper.readTree(literal);
        } catch (JsonProcessingException exception) {
            throw error("InvalidExpressionException", "Invalid query literal.");
        }
    }

    private void validateQueryField(String name) {
        if (!name.matches("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)*")
                || !QUERY_FIELDS.contains(name.split("\\.")[0])) {
            throw error("InvalidExpressionException", "Unsupported query field: " + name);
        }
    }

    private static JsonNode field(JsonNode node, String path) {
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        return node;
    }

    private void project(ObjectNode target, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        for (int index = 0; index < parts.length - 1; index++) {
            JsonNode child = target.path(parts[index]);
            target = child instanceof ObjectNode object ? object : target.putObject(parts[index]);
        }
        target.set(parts[parts.length - 1], value.deepCopy());
    }

    private static int compareValues(JsonNode left, JsonNode right) {
        return left.isNumber() && right.isNumber()
                ? Double.compare(left.asDouble(), right.asDouble()) : left.asText().compareTo(right.asText());
    }

    private record QueryCondition(String name, String operator, JsonNode value) {
        boolean matches(JsonNode row) {
            JsonNode actual = field(row, name);
            if (actual.isMissingNode()) {
                return false;
            }
            boolean equal = actual.equals(value) || (actual.isNumber() && value.isNumber()
                    && actual.decimalValue().compareTo(value.decimalValue()) == 0);
            return "=".equals(operator) ? equal : !equal;
        }
    }

    private <T> ObjectNode page(String region, String operation, JsonNode request, List<T> items, String field,
            String limitField, String tokenField, int defaultLimit, String limitError) {
        int limit = defaultLimit;
        if (request.hasNonNull(limitField)) {
            JsonNode value = request.path(limitField);
            if (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > 100) {
                throw error(limitError, "Limit must be between 0 and 100.");
            }
            if (value.asInt() != 0) {
                limit = value.asInt();
            }
        }
        ObjectNode filters = ((ObjectNode) request).deepCopy();
        filters.remove(List.of(limitField, tokenField));
        String signature = UUID.nameUUIDFromBytes((scope(region) + operation + canonical(filters))
                .getBytes(StandardCharsets.UTF_8)).toString();
        int offset = 0;
        if (request.hasNonNull(tokenField)) {
            try {
                String token = new String(Base64.getUrlDecoder().decode(request.path(tokenField).asText()), StandardCharsets.UTF_8);
                String[] parts = token.split(":", -1);
                if (parts.length != 2 || !signature.equals(parts[0])) {
                    throw new IllegalArgumentException();
                }
                offset = Integer.parseInt(parts[1]);
                if (offset < 0 || offset > items.size()) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw error("InvalidNextTokenException", "The supplied pagination token is invalid for this request.");
            }
        }
        int end = Math.min(offset + limit, items.size());
        ObjectNode response = mapper.createObjectNode();
        response.set(field, mapper.valueToTree(items.subList(offset, end)));
        if (end < items.size()) {
            response.put(tokenField, Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((signature + ":" + end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    private String canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            return fields.toString();
        }
        return value.toString();
    }

    private static String required(JsonNode request, String field, String code) {
        JsonNode value = request.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw error(code, field + " must be specified.");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode request, String field, int max, String code) {
        List<String> result = new ArrayList<>();
        if (request.hasNonNull(field)) {
            if (!request.path(field).isArray() || request.path(field).size() > max) {
                throw error(code, field + " must be a list of at most " + max + " entries.");
            }
            for (JsonNode value : request.path(field)) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw error(code, field + " entries must be nonempty strings.");
                }
                result.add(value.asText());
            }
        }
        return result;
    }

    private static double timestamp(JsonNode request, String field, double fallback) {
        if (!request.hasNonNull(field)) {
            return fallback;
        }
        if (!request.path(field).isNumber() || !Double.isFinite(request.path(field).asDouble())) {
            throw error("InvalidTimeRangeException", field + " must be an epoch timestamp.");
        }
        return request.path(field).asDouble();
    }

    private static void validateTimeWindow(double start, double end) {
        if (start > end) {
            throw error("InvalidTimeRangeException", "Start time must not be after end time.");
        }
    }

    private static boolean deleted(JsonNode item) {
        return "ResourceDeleted".equals(item.path("configurationItemStatus").asText());
    }

    private static void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.path(field).deepCopy());
            }
        }
    }

    private static double now() {
        Instant now = Instant.now();
        return now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
    }

    private static AwsException error(String code, String message) {
        return new AwsException(code, message, 400);
    }
}
