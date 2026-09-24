package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

/** RFC 6902 patches are applied to a copy before any backend mutation. */
final class ResourcePatch {
    private ResourcePatch() {}

    static JsonNode apply(JsonNode current, JsonNode patch) {
        if (patch == null || !patch.isArray()) throw invalid("PatchDocument must be an array.");
        JsonNode result = current.deepCopy();
        for (JsonNode operation : patch) {
            String op = text(operation, "op");
            String path = text(operation, "path");
            switch (op) {
                case "add" -> result = set(result, path, value(operation), false);
                case "replace" -> result = set(result, path, value(operation), true);
                case "remove" -> result = remove(result, path);
                case "test" -> {
                    if (!get(result, path).equals(value(operation))) throw invalid("Patch test failed at " + path);
                }
                case "copy", "move" -> {
                    String from = text(operation, "from");
                    JsonNode value = get(result, from).deepCopy();
                    if ("move".equals(op)) {
                        if (path.startsWith(from + "/")) throw invalid("Cannot move into a descendant.");
                        if (path.equals(from)) continue;
                        result = remove(result, from);
                    }
                    result = set(result, path, value, false);
                }
                default -> throw invalid("Unsupported patch operation: " + op);
            }
        }
        if (!result.isObject()) throw invalid("A resource model must remain an object.");
        return result;
    }

    private static JsonNode value(JsonNode operation) {
        if (!operation.has("value")) throw invalid("Patch value is required.");
        return operation.get("value").deepCopy();
    }

    private static String text(JsonNode operation, String field) {
        if (!operation.path(field).isTextual()) throw invalid("Patch " + field + " must be a string.");
        return operation.path(field).asText();
    }

    private static String[] tokens(String pointer) {
        if (pointer.isEmpty()) return new String[0];
        if (!pointer.startsWith("/")) throw invalid("Invalid JSON pointer: " + pointer);
        String[] tokens = pointer.substring(1).split("/", -1);
        for (int i = 0; i < tokens.length; i++) {
            for (int j = 0; j < tokens[i].length(); j++) {
                if (tokens[i].charAt(j) == '~') {
                    if (++j == tokens[i].length() || (tokens[i].charAt(j) != '0' && tokens[i].charAt(j) != '1')) {
                        throw invalid("Invalid JSON pointer escape.");
                    }
                }
            }
            tokens[i] = tokens[i].replace("~1", "/").replace("~0", "~");
        }
        return tokens;
    }

    private static JsonNode get(JsonNode root, String pointer) {
        JsonNode node = root;
        for (String token : tokens(pointer)) {
            if (node.isArray()) node = node.get(index(token, node.size(), false));
            else if (node.isObject()) node = node.get(token);
            else throw invalid("JSON pointer does not address a container: " + pointer);
            if (node == null) throw invalid("JSON pointer does not exist: " + pointer);
        }
        return node;
    }

    private static JsonNode parent(JsonNode root, String pointer) {
        return get(root, pointer.substring(0, pointer.lastIndexOf('/')));
    }

    private static JsonNode set(JsonNode root, String pointer, JsonNode value, boolean replace) {
        String[] tokens = tokens(pointer);
        if (tokens.length == 0) return value;
        JsonNode parent = parent(root, pointer);
        String key = tokens[tokens.length - 1];
        if (parent instanceof ObjectNode object) {
            if (replace && !object.has(key)) throw invalid("Cannot replace an absent property.");
            object.set(key, value);
        } else if (parent instanceof ArrayNode array) {
            int index = index(key, array.size(), !replace);
            if (replace) array.set(index, value);
            else array.insert(index, value);
        } else throw invalid("Patch target is not a container.");
        return root;
    }

    private static JsonNode remove(JsonNode root, String pointer) {
        String[] tokens = tokens(pointer);
        if (tokens.length == 0) throw invalid("Cannot remove the resource model.");
        JsonNode parent = parent(root, pointer);
        String key = tokens[tokens.length - 1];
        if (parent instanceof ObjectNode object) {
            if (!object.has(key)) throw invalid("Cannot remove an absent property.");
            object.remove(key);
        } else if (parent instanceof ArrayNode array) array.remove(index(key, array.size(), false));
        else throw invalid("Patch target is not a container.");
        return root;
    }

    private static int index(String token, int size, boolean insert) {
        if (insert && "-".equals(token)) return size;
        if (!token.matches("0|[1-9][0-9]*")) throw invalid("Invalid array index.");
        try {
            int index = Integer.parseInt(token);
            if (index > size || (!insert && index == size)) throw invalid("Array index out of bounds.");
            return index;
        } catch (NumberFormatException e) {
            throw invalid("Invalid array index.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
