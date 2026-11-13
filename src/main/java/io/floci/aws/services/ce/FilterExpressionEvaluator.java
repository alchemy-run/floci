package io.floci.aws.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.UsageLine;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recursive evaluator for the AWS Cost Explorer {@code Expression} filter shape:
 * {@code {And, Or, Not, Dimensions, Tags, CostCategories}}.
 * <p>
 * MatchOptions follow the AWS semantics: {@code EQUALS} (the default), {@code ABSENT} (the key has no value on the
 * line), {@code STARTS_WITH}, {@code ENDS_WITH} and {@code CONTAINS}, each combinable with {@code CASE_SENSITIVE}
 * (the default) or {@code CASE_INSENSITIVE}. {@code CostCategories} expressions are evaluated through a
 * {@link CategoryResolver} that applies the account's cost category rules to the line.
 */
final class FilterExpressionEvaluator {

    /** Resolves the value a cost category assigns to a usage line, or {@code null} when it assigns none. */
    @FunctionalInterface
    interface CategoryResolver {
        CategoryResolver NONE = (category, line) -> null;

        String value(String category, UsageLine line);
    }

    static boolean matches(JsonNode expression, UsageLine line) {
        return matches(expression, line, CategoryResolver.NONE);
    }

    static boolean matches(JsonNode expression, UsageLine line, CategoryResolver categories) {
        if (expression == null || expression.isNull() || expression.isMissingNode()) {
            return true;
        }
        JsonNode and = expression.path("And");
        if (and.isArray() && !and.isEmpty()) {
            for (JsonNode child : and) {
                if (!matches(child, line, categories)) {
                    return false;
                }
            }
            return true;
        }
        JsonNode or = expression.path("Or");
        if (or.isArray() && !or.isEmpty()) {
            for (JsonNode child : or) {
                if (matches(child, line, categories)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode not = expression.path("Not");
        if (not.isObject() && !not.isEmpty()) {
            return !matches(not, line, categories);
        }
        JsonNode dimensions = expression.path("Dimensions");
        if (dimensions.isObject() && !dimensions.isEmpty()) {
            String key = key(dimensions, "Dimensions");
            return valueMatches(dimensionValue(key, line), dimensions);
        }
        JsonNode tags = expression.path("Tags");
        if (tags.isObject() && !tags.isEmpty()) {
            String key = key(tags, "Tags");
            return valueMatches(line.tags() == null ? null : line.tags().get(key), tags);
        }
        JsonNode costCategories = expression.path("CostCategories");
        if (costCategories.isObject() && !costCategories.isEmpty()) {
            String key = key(costCategories, "CostCategories");
            return valueMatches(categories.value(key, line), costCategories);
        }
        return true;
    }

    private static String key(JsonNode node, String operator) {
        String key = node.path("Key").asText(null);
        if (key == null || key.isEmpty()) {
            throw new AwsException("ValidationException", operator + " filter requires a Key.", 400);
        }
        return key;
    }

    private static boolean valueMatches(String actual, JsonNode node) {
        Set<String> values = readValues(node.path("Values"));
        Set<String> options = readValues(node.path("MatchOptions"));
        String present = actual == null || actual.isEmpty() ? null : actual;
        if (options.contains("ABSENT")) {
            if (present == null) {
                return true;
            }
            if (values.isEmpty()) {
                return false;
            }
        }
        if (present == null) {
            return false;
        }
        if (values.isEmpty()) {
            return true;
        }
        boolean insensitive = options.contains("CASE_INSENSITIVE");
        String subject = insensitive ? present.toLowerCase(Locale.ROOT) : present;
        for (String value : values) {
            String candidate = insensitive ? value.toLowerCase(Locale.ROOT) : value;
            boolean matched;
            if (options.contains("STARTS_WITH")) {
                matched = subject.startsWith(candidate);
            } else if (options.contains("ENDS_WITH")) {
                matched = subject.endsWith(candidate);
            } else if (options.contains("CONTAINS")) {
                matched = subject.contains(candidate);
            } else {
                matched = subject.equals(candidate);
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /** Maps a Cost Explorer dimension name to the equivalent {@link UsageLine} field. */
    static String dimensionValue(String dimension, UsageLine line) {
        return switch (dimension) {
            case "SERVICE", "SERVICE_CODE" -> line.service();
            case "REGION" -> line.region();
            case "USAGE_TYPE" -> line.usageType();
            case "USAGE_TYPE_GROUP" -> usageTypeGroup(line);
            case "OPERATION" -> line.operation();
            case "RECORD_TYPE" -> line.recordType();
            case "LINKED_ACCOUNT", "PAYER_ACCOUNT", "LINKED_ACCOUNT_NAME" -> line.linkedAccountId();
            case "RESOURCE_ID" -> line.resourceId();
            case "INSTANCE_TYPE" -> line.usageType() != null && line.usageType().startsWith("BoxUsage:")
                    ? line.usageType().substring("BoxUsage:".length()) : null;
            case "PURCHASE_TYPE" -> UsageLine.RECORD_TYPE_USAGE.equals(line.recordType()) ? "On Demand Instances" : null;
            default -> null;
        };
    }

    /** The AWS usage type group a line's usage type belongs to, for the services Floci prices. */
    private static String usageTypeGroup(UsageLine line) {
        String usageType = line.usageType();
        if (usageType == null) {
            return null;
        }
        return switch (line.service() == null ? "" : line.service()) {
            case "AmazonEC2" -> usageType.startsWith("BoxUsage") ? "EC2: Running Hours" : null;
            case "AWSLambda" -> usageType.contains("Request") ? "Lambda: Requests" : "Lambda: Duration";
            case "AmazonS3" -> usageType.contains("TimedStorage") ? "S3: Storage - Standard"
                    : usageType.contains("Requests") ? "S3: API Requests - Standard" : null;
            default -> null;
        };
    }

    private static Set<String> readValues(JsonNode arr) {
        Set<String> out = new LinkedHashSet<>();
        if (arr.isArray()) {
            for (JsonNode v : arr) {
                if (!v.isNull() && !v.asText().isEmpty()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    private FilterExpressionEvaluator() {}
}
