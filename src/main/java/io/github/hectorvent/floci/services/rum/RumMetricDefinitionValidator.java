package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rum.model.RumMetricDefinition;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates {@code MetricDefinitionRequest} structures the way CloudWatch RUM does.
 *
 * <p>Shape and length constraints from the API model reject the whole request with a
 * {@code ValidationException} ({@link #parse}). The extended/custom-metric business rules are
 * reported per definition ({@link #semanticError}), because {@code BatchCreateRumMetricDefinitions}
 * accepts the valid definitions of a batch and returns an error entry for each invalid one.
 */
final class RumMetricDefinitionValidator {

    static final String CLOUDWATCH = "CloudWatch";
    static final String EVIDENTLY = "Evidently";
    private static final String EXTENDED_NAMESPACE = "AWS/RUM";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile(".*[a-zA-Z0-9-._/#:]+");
    private static final Pattern DIMENSION_NAME_PATTERN = Pattern.compile("(?!:).*[^\\s].*");

    private record ExtendedMetric(String valueKey, String eventType) {
    }

    private static final Map<String, ExtendedMetric> EXTENDED_METRICS = Map.ofEntries(
            Map.entry("PerformanceNavigationDuration",
                    new ExtendedMetric("event_details.duration", "com.amazon.rum.performance_navigation_event")),
            Map.entry("PerformanceResourceDuration",
                    new ExtendedMetric("event_details.duration", "com.amazon.rum.performance_resource_event")),
            Map.entry("NavigationSatisfiedTransaction",
                    new ExtendedMetric(null, "com.amazon.rum.performance_navigation_event")),
            Map.entry("NavigationToleratedTransaction",
                    new ExtendedMetric(null, "com.amazon.rum.performance_navigation_event")),
            Map.entry("NavigationFrustratedTransaction",
                    new ExtendedMetric(null, "com.amazon.rum.performance_navigation_event")),
            Map.entry("WebVitalsCumulativeLayoutShift",
                    new ExtendedMetric("event_details.value", "com.amazon.rum.cumulative_layout_shift_event")),
            Map.entry("WebVitalsFirstInputDelay",
                    new ExtendedMetric("event_details.value", "com.amazon.rum.first_input_delay_event")),
            Map.entry("WebVitalsLargestContentfulPaint",
                    new ExtendedMetric("event_details.value", "com.amazon.rum.largest_contentful_paint_event")),
            Map.entry("JsErrorCount", new ExtendedMetric(null, "com.amazon.rum.js_error_event")),
            Map.entry("HttpErrorCount", new ExtendedMetric(null, "com.amazon.rum.http_event")),
            Map.entry("SessionCount", new ExtendedMetric(null, "com.amazon.rum.session_start_event")),
            Map.entry("PageViewCount", new ExtendedMetric(null, "com.amazon.rum.page_view_event")),
            Map.entry("Http4xxCount", new ExtendedMetric(null, "com.amazon.rum.http_event")),
            Map.entry("Http5xxCount", new ExtendedMetric(null, "com.amazon.rum.http_event")));

    private static final Map<String, String> EXTENDED_DIMENSIONS = Map.of(
            "metadata.pageId", "PageId",
            "metadata.browserName", "BrowserName",
            "metadata.deviceType", "DeviceType",
            "metadata.osName", "OSName",
            "metadata.countryCode", "CountryCode",
            "event_details.fileType", "FileType");

    private static final Set<String> EVENT_FIELDS = Set.of(
            "account_id", "application_Id", "application_version", "application_name", "batch_id",
            "event_details", "event_id", "event_interaction", "event_timestamp", "event_type",
            "event_version", "log_stream", "metadata", "sessionId", "user_details", "userId");

    private RumMetricDefinitionValidator() {
    }

    /** Parses one definition, enforcing the API model's shape and length constraints. */
    static RumMetricDefinition parse(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
        String name = requiredText(node, "Name", field);
        requireLength(name, 1, 255, field + ".Name");
        String valueKey = optionalText(node, "ValueKey", field);
        requireLength(valueKey, 1, 280, field + ".ValueKey");
        String unitLabel = optionalText(node, "UnitLabel", field);
        requireLength(unitLabel, 1, 256, field + ".UnitLabel");
        String eventPattern = optionalText(node, "EventPattern", field);
        requireLength(eventPattern, 0, 4000, field + ".EventPattern");
        String namespace = optionalText(node, "Namespace", field);
        requireLength(namespace, 1, 237, field + ".Namespace");
        if (namespace != null && !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw validation(field + ".Namespace must match .*[a-zA-Z0-9-._/#:]+");
        }

        Map<String, String> dimensionKeys = null;
        JsonNode dimensions = node.get("DimensionKeys");
        if (dimensions != null && !dimensions.isNull()) {
            if (!dimensions.isObject() || dimensions.size() > 29) {
                throw validation(field + ".DimensionKeys must be a map with at most 29 entries.");
            }
            dimensionKeys = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> entries = dimensions.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.isEmpty() || key.length() > 280 || !value.isTextual()
                        || value.textValue().isEmpty() || value.textValue().length() > 255
                        || !DIMENSION_NAME_PATTERN.matcher(value.textValue()).matches()) {
                    throw validation(field + ".DimensionKeys contains an invalid entry.");
                }
                dimensionKeys.put(key, value.textValue());
            }
        }
        return new RumMetricDefinition(null, name, valueKey, unitLabel, dimensionKeys, eventPattern, namespace);
    }

    /**
     * Returns why {@code definition} is not a valid extended or custom metric for
     * {@code destination}, or {@code null} when it is valid.
     */
    static String semanticError(RumMetricDefinition definition, String destination) {
        Map<String, String> dimensions = definition.getDimensionKeys() == null
                ? Map.of()
                : definition.getDimensionKeys();
        if (!dimensions.isEmpty() && !CLOUDWATCH.equals(destination)) {
            return "DimensionKeys can only be used for metrics sent to CloudWatch.";
        }

        JsonNode pattern = null;
        String rawPattern = definition.getEventPattern();
        if (rawPattern != null && !rawPattern.isEmpty()) {
            try {
                pattern = MAPPER.readTree(rawPattern);
            } catch (Exception e) {
                return "EventPattern must be a valid JSON object.";
            }
            if (pattern == null || !pattern.isObject()) {
                return "EventPattern must be a valid JSON object.";
            }
        }

        String namespace = definition.getNamespace();
        boolean extended = namespace == null || EXTENDED_NAMESPACE.equals(namespace);
        return extended
                ? extendedMetricError(definition, pattern, dimensions)
                : customMetricError(definition, pattern, dimensions, namespace);
    }

    private static String extendedMetricError(RumMetricDefinition definition, JsonNode pattern,
                                              Map<String, String> dimensions) {
        ExtendedMetric metric = EXTENDED_METRICS.get(definition.getName());
        if (metric == null) {
            return "Name " + definition.getName() + " is not a valid extended metric; custom metrics require a "
                    + "Namespace.";
        }
        if (pattern == null) {
            return "EventPattern is required for extended metrics.";
        }
        if (metric.valueKey() == null
                ? definition.getValueKey() != null
                : !metric.valueKey().equals(definition.getValueKey())) {
            return "ValueKey for " + definition.getName() + " must be "
                    + (metric.valueKey() == null ? "null" : metric.valueKey()) + ".";
        }
        JsonNode eventType = pattern.get("event_type");
        if (eventType == null || !eventType.isArray() || eventType.size() != 1
                || !metric.eventType().equals(eventType.get(0).asText(null))) {
            return "EventPattern for " + definition.getName() + " must include {\"event_type\":[\""
                    + metric.eventType() + "\"]}.";
        }
        for (Map.Entry<String, String> dimension : dimensions.entrySet()) {
            if (!dimension.getValue().equals(EXTENDED_DIMENSIONS.get(dimension.getKey()))) {
                return "DimensionKeys entry \"" + dimension.getKey() + "\": \"" + dimension.getValue()
                        + "\" is not valid for extended metrics.";
            }
            if (!patternContainsPath(pattern, dimension.getKey())) {
                return "Dimension " + dimension.getKey() + " must be present in EventPattern.";
            }
        }
        return null;
    }

    private static String customMetricError(RumMetricDefinition definition, JsonNode pattern,
                                            Map<String, String> dimensions, String namespace) {
        if (namespace.startsWith("AWS/")) {
            return "Namespace cannot start with AWS/.";
        }
        if (definition.getValueKey() != null && !EVENT_FIELDS.contains(firstLevelKey(definition.getValueKey()))) {
            return "ValueKey must reference a RUM event field.";
        }
        for (String dimensionKey : dimensions.keySet()) {
            if (!EVENT_FIELDS.contains(firstLevelKey(dimensionKey))) {
                return "DimensionKeys must reference RUM event fields.";
            }
            if (pattern == null || !patternContainsPath(pattern, dimensionKey)) {
                return "Dimension " + dimensionKey + " must be present in EventPattern.";
            }
        }
        if (pattern != null) {
            Iterator<String> fields = pattern.fieldNames();
            while (fields.hasNext()) {
                if (!EVENT_FIELDS.contains(fields.next())) {
                    return "EventPattern keys must be RUM event fields.";
                }
            }
            JsonNode eventDetails = pattern.get("event_details");
            JsonNode eventType = pattern.get("event_type");
            if (eventDetails != null && !isEmpty(eventDetails) && (eventType == null || isEmpty(eventType))) {
                return "EventPattern with event_details must also contain an event_type.";
            }
            if (!valueListsHaveOneValue(pattern)) {
                return "Every JSON array in EventPattern must contain only one value.";
            }
        }
        return null;
    }

    private static boolean isEmpty(JsonNode node) {
        return node.isNull() || ((node.isArray() || node.isObject()) && node.isEmpty())
                || (node.isTextual() && node.textValue().isEmpty());
    }

    /** Pattern fields map to value lists; operator objects inside a list are not descended into. */
    private static boolean valueListsHaveOneValue(JsonNode object) {
        Iterator<JsonNode> values = object.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (value.isArray() && value.size() != 1) {
                return false;
            }
            if (value.isObject() && !valueListsHaveOneValue(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean patternContainsPath(JsonNode pattern, String path) {
        JsonNode current = pattern;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return false;
            }
            current = current.get(segment);
        }
        return current != null && !current.isNull();
    }

    private static String firstLevelKey(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static String requiredText(JsonNode node, String member, String field) {
        JsonNode value = node.get(member);
        if (value == null || !value.isTextual()) {
            throw validation(field + "." + member + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String member, String field) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + "." + member + " must be a string.");
        }
        return value.textValue();
    }

    private static void requireLength(String value, int min, int max, String field) {
        if (value != null && (value.length() < min || value.length() > max)) {
            throw validation(field + " must contain between " + min + " and " + max + " characters.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
