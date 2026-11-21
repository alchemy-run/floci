package io.floci.aws.services.observabilityadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A field of a pipeline event by path: dot-separated names ({@code user.role}), with bracketed, quoted names for
 * names that contain dots ({@code resource.attributes["service.name"]}). A key that exists as written at the top level
 * is that key.
 */
final class FieldPath {

    private FieldPath() {
    }

    static List<String> parts(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                i++;
            } else if (c == '[') {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                int end = path.indexOf(']', i);
                if (end < 0) {
                    end = path.length();
                }
                String inner = path.substring(i + 1, end).trim();
                if (inner.length() >= 2 && (inner.startsWith("\"") || inner.startsWith("'"))) {
                    inner = inner.substring(1, inner.length() - 1);
                }
                parts.add(inner);
                i = end + 1;
            } else {
                current.append(c);
                i++;
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    static JsonNode get(ObjectNode event, String path) {
        if (event.has(path)) {
            return event.get(path);
        }
        JsonNode node = event;
        for (String part : parts(path)) {
            node = node.path(part);
        }
        return node == null ? MissingNode.getInstance() : node;
    }

    static void set(ObjectNode event, String path, JsonNode value) {
        List<String> parts = parts(path);
        if (parts.isEmpty()) {
            return;
        }
        ObjectNode node = event;
        for (int i = 0; i < parts.size() - 1; i++) {
            JsonNode child = node.get(parts.get(i));
            if (child == null || !child.isObject()) {
                child = node.putObject(parts.get(i));
            }
            node = (ObjectNode) child;
        }
        node.set(parts.get(parts.size() - 1), value);
    }

    static void remove(ObjectNode event, String path) {
        if (event.has(path)) {
            event.remove(path);
            return;
        }
        List<String> parts = parts(path);
        JsonNode node = event;
        for (int i = 0; i < parts.size() - 1; i++) {
            node = node.path(parts.get(i));
        }
        if (node instanceof ObjectNode object && !parts.isEmpty()) {
            object.remove(parts.get(parts.size() - 1));
        }
    }
}
