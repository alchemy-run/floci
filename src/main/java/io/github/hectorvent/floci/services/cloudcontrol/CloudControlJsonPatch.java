package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 6902 JSON Patch applier for Cloud Control {@code PatchDocument} values.
 */
final class CloudControlJsonPatch {

    private CloudControlJsonPatch() {
    }

    static JsonNode apply(JsonNode target, JsonNode patch) {
        if (target == null || target.isNull()) {
            throw invalid("Target document is required.");
        }
        if (patch == null || !patch.isArray()) {
            throw invalid("PatchDocument must be a JSON array.");
        }
        JsonNode current = target.deepCopy();
        for (JsonNode operation : patch) {
            current = applyOperation(current, operation);
        }
        return current;
    }

    private static JsonNode applyOperation(JsonNode target, JsonNode operation) {
        if (operation == null || !operation.isObject()) {
            throw invalid("Each patch operation must be an object.");
        }
        String op = text(operation, "op");
        String path = text(operation, "path");
        return switch (op) {
            case "add" -> add(target, path, operation.get("value"), false);
            case "replace" -> add(target, path, operation.get("value"), true);
            case "remove" -> remove(target, path);
            default -> throw invalid("Unsupported JSON Patch op '" + op + "'.");
        };
    }

    private static JsonNode add(JsonNode target, String path, JsonNode value, boolean replace) {
        if (value == null || value.isMissingNode()) {
            throw invalid("Patch operation is missing 'value'.");
        }
        List<String> tokens = decodePointer(path);
        if (tokens.isEmpty()) {
            return value.deepCopy();
        }
        JsonNode parent = parentOf(target, tokens);
        String last = tokens.getLast();
        if (parent instanceof ObjectNode object) {
            if (replace && !object.has(last)) {
                throw invalid("Cannot replace missing path " + path + ".");
            }
            object.set(last, value.deepCopy());
            return target;
        }
        if (parent instanceof ArrayNode array) {
            int index = arrayIndex(last, array, !replace);
            if (replace) {
                array.set(index, value.deepCopy());
            } else {
                array.insert(index, value.deepCopy());
            }
            return target;
        }
        throw invalid("Cannot apply patch at path " + path + ".");
    }

    private static JsonNode remove(JsonNode target, String path) {
        List<String> tokens = decodePointer(path);
        if (tokens.isEmpty()) {
            throw invalid("Cannot remove the root document.");
        }
        JsonNode parent = parentOf(target, tokens);
        String last = tokens.getLast();
        if (parent instanceof ObjectNode object) {
            if (object.remove(last) == null) {
                throw invalid("Cannot remove missing path " + path + ".");
            }
            return target;
        }
        if (parent instanceof ArrayNode array) {
            array.remove(arrayIndex(last, array, false));
            return target;
        }
        throw invalid("Cannot remove path " + path + ".");
    }

    private static JsonNode parentOf(JsonNode root, List<String> tokens) {
        JsonNode current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            current = child(current, tokens.get(i), tokens.get(i));
        }
        return current;
    }

    private static JsonNode child(JsonNode node, String token, String display) {
        if (node instanceof ObjectNode object) {
            JsonNode child = object.get(token);
            if (child == null || child.isMissingNode()) {
                throw invalid("Path segment '" + display + "' does not exist.");
            }
            return child;
        }
        if (node instanceof ArrayNode array) {
            return array.get(arrayIndex(token, array, false));
        }
        throw invalid("Path segment '" + display + "' is not navigable.");
    }

    private static List<String> decodePointer(String path) {
        if (path == null || path.isEmpty()) {
            throw invalid("JSON Pointer path is required.");
        }
        if ("/".equals(path)) {
            return List.of();
        }
        if (!path.startsWith("/")) {
            throw invalid("JSON Pointer must start with '/'.");
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : path.substring(1).split("/", -1)) {
            tokens.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return tokens;
    }

    private static int arrayIndex(String token, ArrayNode array, boolean allowAppend) {
        if (allowAppend && "-".equals(token)) {
            return array.size();
        }
        try {
            int index = Integer.parseInt(token);
            if (index < 0 || index > array.size() || (!allowAppend && index == array.size())) {
                throw invalid("Array index " + token + " is out of range.");
            }
            return index;
        } catch (NumberFormatException e) {
            throw invalid("Invalid array index '" + token + "'.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("Patch operation is missing '" + field + "'.");
        }
        return value.asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
