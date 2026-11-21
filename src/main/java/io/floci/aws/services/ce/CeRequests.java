package io.floci.aws.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Request validation helpers shared by the Cost Explorer operations. */
final class CeRequests {

    private static final Pattern TAG_TEXT = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");

    private CeRequests() {
    }

    static ObjectNode object(JsonNode parent, String name) {
        if (parent != null && parent.get(name) instanceof ObjectNode object) {
            return object;
        }
        throw invalid(name + " must be an object.");
    }

    static String text(JsonNode parent, String name, int maximum) {
        JsonNode value = parent == null ? null : parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().length() > maximum) {
            throw invalid(name + " must be a string of at most " + maximum + " characters.");
        }
        return value.textValue();
    }

    static List<String> strings(JsonNode parent, String name, int maximum, int stringMaximum) {
        JsonNode value = parent.path(name);
        if (!value.isArray() || value.size() > maximum) {
            throw invalid(name + " must be an array of at most " + maximum + " strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().length() > stringMaximum) {
                throw invalid(name + " must contain strings of at most " + stringMaximum + " characters.");
            }
            result.add(element.textValue());
        }
        return result;
    }

    static String singleRoot(ObjectNode expression, Set<String> supported) {
        Iterator<String> fields = expression.fieldNames();
        if (expression.size() != 1 || !fields.hasNext()) {
            throw invalid("An expression must contain exactly one root operator.");
        }
        String root = fields.next();
        if (!supported.contains(root)) {
            throw invalid("Unsupported expression operator: " + root);
        }
        return root;
    }

    static boolean present(JsonNode node, String field) {
        return node != null && node.hasNonNull(field);
    }

    static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    static ObjectNode tags(ObjectMapper mapper, JsonNode request, boolean required) {
        ObjectNode tags = mapper.createObjectNode();
        if (!required && !present(request, "ResourceTags")) {
            return tags;
        }
        JsonNode input = request.path("ResourceTags");
        if (!input.isArray() || input.size() > 200) {
            throw invalid("ResourceTags must be an array of at most 200 tags.");
        }
        for (JsonNode tag : input) {
            String key = tagText(text(tag, "Key", 128), true);
            String value = tagText(text(tag, "Value", 256), false);
            if (tags.has(key)) {
                throw invalid("Each resource tag key must be unique.");
            }
            tags.put(key, value);
        }
        checkTagCount(tags);
        return tags;
    }

    static String tagText(String value, boolean key) {
        String trimmed = value.strip();
        if (!TAG_TEXT.matcher(trimmed).matches()
                || (key && (trimmed.isEmpty() || trimmed.regionMatches(true, 0, "aws:", 0, 4)))) {
            throw invalid("Resource tags must use valid characters and must not use reserved AWS keys.");
        }
        return trimmed;
    }

    static void checkTagCount(ObjectNode tags) {
        if (tags.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource may have at most 50 user tags.", 400);
        }
    }

    /** Pages {@code all} by the request's page-size and token fields, as the Cost Explorer list operations do. */
    static <T> PaginatedResult<T> page(List<T> all, Function<T, String> cursorOf, JsonNode request,
                                       String sizeField, String tokenField, int defaultSize, int maximum) {
        int size = defaultSize;
        if (present(request, sizeField)) {
            JsonNode value = request.get(sizeField);
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1
                    || value.intValue() > maximum) {
                throw invalid(sizeField + " must be between 1 and " + maximum + ".");
            }
            size = value.intValue();
        }
        String token = present(request, tokenField) ? text(request, tokenField, 8192) : null;
        return Pagination.paginate(all, cursorOf, size, token, tokenField,
                bad -> new AwsException("InvalidNextTokenException", "Invalid " + tokenField + ".", 400));
    }

    static int integer(JsonNode parent, String name, int minimum, int maximum, int fallback) {
        if (!present(parent, name)) {
            return fallback;
        }
        JsonNode value = parent.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum
                || value.intValue() > maximum) {
            throw invalid(name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value.intValue();
    }

    static String oneOf(JsonNode parent, String name, Set<String> allowed, String fallback) {
        if (!present(parent, name)) {
            if (fallback == null) {
                throw invalid(name + " is required.");
            }
            return fallback;
        }
        String value = text(parent, name, 1024);
        if (!allowed.contains(value)) {
            throw invalid(name + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return value;
    }
}
