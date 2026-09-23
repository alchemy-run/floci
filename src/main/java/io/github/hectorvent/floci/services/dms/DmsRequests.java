package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request parsing shared by every DMS operation. DMS speaks AWS JSON 1.1, so a member whose JSON
 * type does not match its modelled type is a {@code SerializationException}, while a well-typed
 * but invalid value is an {@code InvalidParameterValueException}.
 */
final class DmsRequests {

    static final int DEFAULT_MAX_RECORDS = 100;
    static final int MINIMUM_MAX_RECORDS = 20;
    static final int MAXIMUM_MAX_RECORDS = 100;

    /**
     * Endpoint, replication-instance, and ResourceIdentifier names share one documented shape:
     * begin with a letter, ASCII letters, digits, and hyphens only, no trailing hyphen, and no two
     * consecutive hyphens.
     */
    private static final Pattern DMS_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*");
    private static final Set<String> RESERVED_TAG_PREFIXES = Set.of("aws:", "dms:");

    private DmsRequests() {
    }

    /** One {@code Filters} entry: a name and the values any of which may match. */
    record Filter(String name, List<String> values) {
    }

    /**
     * Reads a string member. Absent or explicitly null yields null; a member of any other JSON type
     * is a SerializationException. Coercing it instead (asText on an object yields "") would
     * silently store a wrong value.
     */
    static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw serialization(field + " must be a string.");
        }
        return node.textValue();
    }

    static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter " + field + " must be provided and must not be blank.");
        }
        return value;
    }

    static Integer integer(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw serialization(field + " must be an integer.");
        }
        return node.intValue();
    }

    static Boolean bool(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw serialization(field + " must be a boolean.");
        }
        return node.booleanValue();
    }

    /** JSON 1.1 timestamps travel as epoch seconds, possibly fractional. */
    static Instant timestamp(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw serialization(field + " must be a timestamp in epoch seconds.");
        }
        return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000.0));
    }

    static List<String> stringList(JsonNode array, String field) {
        if (!array.isArray()) {
            throw serialization(field + " must be a list of strings.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isTextual()) {
                throw serialization(field + " must be a list of strings.");
            }
            values.add(element.textValue());
        }
        return values;
    }

    /** An optional list member: null when absent, the parsed list otherwise. */
    static List<String> optionalStringList(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return stringList(node, field);
    }

    /** An optional structure member: null when absent. */
    static JsonNode object(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw serialization(field + " must be a structure.");
        }
        return node;
    }

    /**
     * DMS documents a default {@code MaxRecords} of 100 and a valid range of 20 to 100, and
     * rejects a value outside that range rather than clamping it.
     */
    static Integer maxRecords(JsonNode request) {
        Integer value = integer(request, "MaxRecords");
        if (value == null) {
            return null;
        }
        if (value < MINIMUM_MAX_RECORDS || value > MAXIMUM_MAX_RECORDS) {
            throw invalidParameter("Invalid value " + value + " for MaxRecords. Must be between "
                    + MINIMUM_MAX_RECORDS + " and " + MAXIMUM_MAX_RECORDS + ".");
        }
        return value;
    }

    /**
     * Parses {@code Filters}, rejecting any filter name the operation does not document; a null
     * {@code allowedNames} accepts any name. Values are returned as sent; each operation decides
     * how a name compares.
     */
    static List<Filter> filters(JsonNode request, Set<String> allowedNames) {
        JsonNode node = request == null ? null : request.get("Filters");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw serialization("Filters must be a list of Name and Values pairs.");
        }
        List<Filter> filters = new ArrayList<>();
        for (JsonNode filter : node) {
            if (!filter.isObject()) {
                throw serialization("Filters must be a list of Name and Values pairs.");
            }
            String name = text(filter, "Name");
            if (name == null || (allowedNames != null && !allowedNames.contains(name))) {
                throw invalidParameter("Invalid filter: " + name + ".");
            }
            JsonNode values = filter.get("Values");
            if (values == null || values.isNull()) {
                throw invalidParameter("The filter " + name + " must have values.");
            }
            if (!values.isArray()) {
                throw serialization("Filter Values must be a list of strings.");
            }
            if (values.isEmpty()) {
                throw invalidParameter("The filter " + name + " must have values.");
            }
            filters.add(new Filter(name, stringList(values, "Filter Values")));
        }
        return filters;
    }

    /**
     * Validates an endpoint or replication-instance identifier against the documented shape and
     * returns it lowercased, which is how DMS stores and echoes both.
     */
    static String dmsIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter " + field + " must be provided and must not be blank.");
        }
        if (value.length() > maxLength || !DMS_NAME.matcher(value).matches()) {
            throw invalidParameter("The parameter " + field + " must begin with a letter, must contain"
                    + " only ASCII letters, digits, and hyphens, must be at most " + maxLength
                    + " characters, and must not end with a hyphen or contain two consecutive hyphens.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * The optional friendly ARN suffix. Unlike the identifier it keeps its case, and it is
     * limited to 31 characters.
     */
    static String resourceIdentifier(JsonNode request) {
        String value = text(request, "ResourceIdentifier");
        if (value == null) {
            return null;
        }
        if (value.isEmpty() || value.length() > 31 || !DMS_NAME.matcher(value).matches()) {
            throw invalidParameter("The parameter ResourceIdentifier must be 1 to 31 characters, begin"
                    + " with a letter, contain only ASCII letters, digits, and hyphens, and must not end"
                    + " with a hyphen or contain two consecutive hyphens.");
        }
        return value;
    }

    static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isArray()) {
            throw serialization("Tags must be a list of Key and Value pairs.");
        }
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw serialization("Tags must be a list of Key and Value pairs.");
            }
            String key = text(element, "Key");
            String value = text(element, "Value");
            if (key == null || key.isEmpty() || key.length() > 128 || isReserved(key)) {
                throw invalidParameter("Tag keys must be 1-128 characters and must not start with"
                        + " \"aws:\" or \"dms:\".");
            }
            String tagValue = value == null ? "" : value;
            if (tagValue.length() > 256 || isReserved(tagValue)) {
                throw invalidParameter("Tag values must be at most 256 characters and must not start"
                        + " with \"aws:\" or \"dms:\".");
            }
            tags.put(key, tagValue);
        }
        return tags;
    }

    private static boolean isReserved(String value) {
        return RESERVED_TAG_PREFIXES.stream().anyMatch(value::startsWith);
    }

    static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException invalidCombination(String message) {
        return new AwsException("InvalidParameterCombinationException", message, 400);
    }

    static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    static AwsException resourceNotFound(String message) {
        return new AwsException("ResourceNotFoundFault", message, 400);
    }

    static AwsException alreadyExists(String message) {
        return new AwsException("ResourceAlreadyExistsFault", message, 400);
    }

    static AwsException invalidState(String message) {
        return new AwsException("InvalidResourceStateFault", message, 400);
    }
}
