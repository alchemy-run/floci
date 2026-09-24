package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Validates stored control-plane definitions; it does not evaluate rules, alerting, or anomaly models. */
final class ApsConfigurationValidator {

    private static final int MAX_DEFINITION_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private ApsConfigurationValidator() {}

    static ObjectNode request(Map<String, Object> request) {
        return JSON.valueToTree(request == null ? Map.of() : request);
    }

    static ObjectNode object(JsonNode value, String field) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(field + " must be an object");
        }
        return object;
    }

    static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a nonempty string");
        }
        return value.textValue();
    }

    static long integer(JsonNode value, String field, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) {
            throw invalid(field + " must be an integer between " + min + " and " + max);
        }
        return value.longValue();
    }

    static void stringMap(JsonNode value, String field, boolean tags) {
        ObjectNode map = object(value, field);
        map.fields().forEachRemaining(entry -> {
            if (entry.getKey().isEmpty() || !entry.getValue().isTextual()) {
                throw invalid(field + " must map nonempty names to strings");
            }
            if (tags && entry.getKey().regionMatches(true, 0, "aws:", 0, 4)) {
                throw invalid("Tag keys must not begin with aws:");
            }
        });
    }

    static JsonNode definition(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > (MAX_DEFINITION_BYTES + 2) / 3 * 4) {
            throw invalid("data must contain a base64 encoded YAML definition no larger than 1 MiB");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_DEFINITION_BYTES) {
                throw invalid("The YAML definition must not exceed 1 MiB");
            }
            String yaml = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return yaml(yaml);
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw invalid("data must be base64 encoded UTF-8 YAML");
        }
    }

    private static JsonNode yaml(String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_DEFINITION_BYTES);
        try {
            Object document = new Yaml(new SafeConstructor(options)).load(source);
            return object(JSON.valueToTree(document), "YAML definition");
        } catch (YAMLException | IllegalArgumentException e) {
            throw invalid("The YAML definition is invalid");
        }
    }

    static void ruleGroups(String encoded) {
        JsonNode document = definition(encoded);
        JsonNode groups = document.path("groups");
        if (!groups.isArray()) {
            throw invalid("A rule groups definition must contain a groups array");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode group : groups) {
            String name = text(group.path("name"), "groups.name");
            if (!names.add(name) || !group.path("rules").isArray()) {
                throw invalid("Each group must have a unique name and a rules array");
            }
            for (JsonNode rule : group.path("rules")) {
                object(rule, "rule");
                if (rule.has("record") == rule.has("alert")) {
                    throw invalid("Each rule must have exactly one of record or alert");
                }
                text(rule.get(rule.has("record") ? "record" : "alert"), "rule name");
                text(rule.path("expr"), "rule.expr");
                for (String field : List.of("labels", "annotations")) {
                    if (rule.has(field)) {
                        stringMap(rule.get(field), field, false);
                    }
                }
            }
        }
    }

    static void alertManager(String encoded) {
        JsonNode outer = definition(encoded);
        JsonNode config = yaml(text(outer.path("alertmanager_config"), "alertmanager_config"));
        JsonNode receivers = config.path("receivers");
        if (!receivers.isArray() || receivers.isEmpty()) {
            throw invalid("Alertmanager must define at least one receiver");
        }
        Set<String> names = new HashSet<>();
        for (JsonNode receiver : receivers) {
            if (!names.add(text(receiver.path("name"), "receiver.name"))) {
                throw invalid("Receiver names must be unique");
            }
        }
        ObjectNode route = object(config.path("route"), "route");
        if (!names.contains(text(route.path("receiver"), "route.receiver"))) {
            throw invalid("The root route must reference a configured receiver");
        }
        validateRoutes(route, names, 0);
    }

    private static void validateRoutes(ObjectNode route, Set<String> receivers, int depth) {
        if (depth > 64) {
            throw invalid("Alertmanager routes are nested too deeply");
        }
        if (route.has("receiver") && !receivers.contains(text(route.get("receiver"), "route.receiver"))) {
            throw invalid("Every route receiver must exist in receivers");
        }
        if (route.has("routes")) {
            if (!route.get("routes").isArray()) {
                throw invalid("routes must be an array");
            }
            for (JsonNode child : route.get("routes")) {
                validateRoutes(object(child, "route"), receivers, depth + 1);
            }
        }
    }

    static void logGroup(String arn, String region, String accountId) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"logs".equals(parsed.service()) || !region.equals(parsed.region())
                    || !accountId.equals(parsed.accountId()) || !parsed.resource().startsWith("log-group:")
                    || !parsed.resource().endsWith(":*") || parsed.resource().length() <= "log-group::*".length()) {
                throw invalid("logGroupArn must identify a log group in the workspace account and region, ending in :*");
            }
        } catch (IllegalArgumentException e) {
            throw invalid("logGroupArn must be a valid CloudWatch Logs ARN");
        }
    }

    static void queryDestinations(JsonNode destinations, String region, String accountId) {
        if (destinations == null || !destinations.isArray() || destinations.isEmpty()) {
            throw invalid("destinations must be a nonempty array");
        }
        Set<String> arns = new HashSet<>();
        for (JsonNode destination : destinations) {
            object(destination, "destination");
            String arn = text(destination.path("cloudWatchLogs").path("logGroupArn"), "logGroupArn");
            logGroup(arn, region, accountId);
            if (!arns.add(arn)) {
                throw invalid("Log destinations must be unique");
            }
            integer(destination.path("filters").path("qspThreshold"), "qspThreshold", 0, Long.MAX_VALUE);
        }
    }

    static void workspaceConfiguration(ObjectNode request) {
        if (request.has("retentionPeriodInDays")) {
            integer(request.get("retentionPeriodInDays"), "retentionPeriodInDays", 1, 1095);
        }
        for (String field : List.of("outOfOrderTimeWindowInSeconds", "ruleQueryOffsetInSeconds")) {
            if (request.has(field)) {
                integer(request.get(field), field, 0, Integer.MAX_VALUE);
            }
        }
        if (request.has("limitsPerLabelSet")) {
            JsonNode entries = request.get("limitsPerLabelSet");
            if (!entries.isArray()) {
                throw invalid("limitsPerLabelSet must be an array");
            }
            Set<Map<String, String>> seen = new HashSet<>();
            for (JsonNode entry : entries) {
                ObjectNode labels = object(entry.path("labelSet"), "labelSet");
                stringMap(labels, "labelSet", false);
                Map<String, String> canonical = new TreeMap<>();
                labels.fields().forEachRemaining(label -> canonical.put(label.getKey(), label.getValue().textValue()));
                if (!seen.add(canonical)) {
                    throw invalid("limitsPerLabelSet must not contain duplicate label sets");
                }
                ObjectNode limits = object(entry.path("limits"), "limits");
                if (limits.has("maxSeries")) {
                    integer(limits.get("maxSeries"), "maxSeries", 0, Long.MAX_VALUE);
                }
            }
        }
    }

    static void policy(String document) {
        if (document == null || document.isBlank() || document.getBytes(StandardCharsets.UTF_8).length > 20 * 1024) {
            throw invalid("policyDocument must be a nonempty JSON policy no larger than 20 KiB");
        }
        try {
            ObjectNode policy = object(JSON.readTree(document), "policyDocument");
            JsonNode statements = policy.path("Statement");
            List<JsonNode> entries;
            if (statements.isObject()) {
                entries = List.of(statements);
            } else if (statements.isArray() && !statements.isEmpty()) {
                entries = JSON.convertValue(statements, JSON.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
            } else {
                throw invalid("The policy must contain one or more statements");
            }
            for (JsonNode statement : entries) {
                object(statement, "Statement");
                if (!Set.of("Allow", "Deny").contains(statement.path("Effect").asText())) {
                    throw invalid("Policy statements must specify Allow or Deny");
                }
                for (String field : List.of("Action", "Resource", "Principal")) {
                    String inverse = "Not" + field;
                    if (statement.has(field) == statement.has(inverse)) {
                        throw invalid("A policy statement requires exactly one of " + field + " or " + inverse);
                    }
                    JsonNode value = statement.get(statement.has(field) ? field : inverse);
                    if ("Principal".equals(field) && value.isObject() && !value.isEmpty()) {
                        value.fields().forEachRemaining(principal -> strings(principal.getValue(), "Principal"));
                    } else {
                        strings(value, field);
                    }
                }
                if (statement.has("Condition")) {
                    object(statement.get("Condition"), "Condition");
                }
            }
        } catch (JsonProcessingException e) {
            throw invalid("policyDocument must be valid JSON");
        }
    }

    private static void strings(JsonNode value, String field) {
        if (value.isArray() && !value.isEmpty()) {
            for (JsonNode item : value) {
                text(item, field);
            }
        } else {
            text(value, field);
        }
    }

    static void anomalyDetector(ObjectNode request, boolean create) {
        if (create) {
            String alias = text(request.get("alias"), "alias");
            if (alias.length() > 100) {
                throw invalid("alias must not exceed 100 characters");
            }
        }
        ObjectNode configuration = object(request.path("configuration"), "configuration");
        if (configuration.size() != 1 || !configuration.has("randomCutForest")) {
            throw invalid("configuration must contain exactly one randomCutForest configuration");
        }
        ObjectNode forest = object(configuration.get("randomCutForest"), "randomCutForest");
        text(forest.path("query"), "randomCutForest.query");
        for (String field : List.of("shingleSize", "sampleSize")) {
            if (forest.has(field)) {
                integer(forest.get(field), field, 1, Integer.MAX_VALUE);
            }
        }
        for (String field : List.of("ignoreNearExpectedFromAbove", "ignoreNearExpectedFromBelow")) {
            if (forest.has(field)) {
                ObjectNode tolerance = object(forest.get(field), field);
                if (tolerance.size() != 1 || tolerance.has("amount") == tolerance.has("ratio")) {
                    throw invalid(field + " requires exactly one amount or ratio");
                }
                JsonNode value = tolerance.get(tolerance.has("amount") ? "amount" : "ratio");
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0) {
                    throw invalid(field + " must be finite and nonnegative");
                }
            }
        }
        if (request.has("evaluationIntervalInSeconds")) {
            integer(request.get("evaluationIntervalInSeconds"), "evaluationIntervalInSeconds", 1, Integer.MAX_VALUE);
        }
        if (request.has("missingDataAction")) {
            ObjectNode action = object(request.get("missingDataAction"), "missingDataAction");
            if (action.size() != 1 || action.has("skip") == action.has("markAsAnomaly")
                    || !action.elements().next().isBoolean()) {
                throw invalid("missingDataAction requires exactly one boolean skip or markAsAnomaly");
            }
        }
        for (String field : List.of("labels", "tags")) {
            if (request.has(field)) {
                stringMap(request.get(field), field, "tags".equals(field));
            }
        }
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
