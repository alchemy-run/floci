package io.floci.aws.services.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Code bindings: the types of a schema's components generated as source in one language and zipped, one file per
 * type. {@code Java8} gives POJOs with accessors, {@code Python36} classes with typed attributes,
 * {@code TypeScript3} interfaces and {@code Go1} structs with JSON tags.
 */
final class CodeBindings {

    static final List<String> LANGUAGES = List.of("Java8", "Python36", "TypeScript3", "Go1");

    private CodeBindings() {
    }

    static byte[] generate(String language, String schemaName, JsonNode schema) {
        Map<String, JsonNode> types = types(schema);
        String pkg = packageName(schemaName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, JsonNode> type : types.entrySet()) {
                String name = type.getKey();
                String file;
                String source;
                switch (language) {
                    case "Java8" -> {
                        file = "src/main/java/schema/" + pkg.replace('.', '/') + "/" + name + ".java";
                        source = java(pkg, name, type.getValue());
                    }
                    case "Python36" -> {
                        file = "schema/" + pkg.replace('.', '/') + "/" + snake(name) + ".py";
                        source = python(name, type.getValue());
                    }
                    case "TypeScript3" -> {
                        file = "schema/" + pkg.replace('.', '/') + "/" + name + ".ts";
                        source = typescript(name, type.getValue());
                    }
                    case "Go1" -> {
                        file = "schema/" + pkg.replace('.', '/') + "/" + snake(name) + ".go";
                        source = go(pkg.substring(pkg.lastIndexOf('.') + 1), name, type.getValue());
                    }
                    default -> throw new AwsException("BadRequestException", "Language must be one of "
                            + String.join(", ", LANGUAGES) + ".", 400);
                }
                zip.putNextEntry(new ZipEntry(file));
                zip.write(source.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** The object types a schema defines: OpenAPI 3 components, or a Draft 4 root and its definitions. */
    private static Map<String, JsonNode> types(JsonNode schema) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode components = schema.path("components").path("schemas");
        if (components.isObject()) {
            components.fields().forEachRemaining(entry -> out.put(SchemaInference.pascal(entry.getKey()),
                    entry.getValue()));
        } else {
            out.put(SchemaInference.pascal(schema.path("title").asText("Root")), schema);
            schema.path("definitions").fields().forEachRemaining(entry -> out.put(SchemaInference.pascal(
                    entry.getKey()), entry.getValue()));
        }
        return out;
    }

    private static String packageName(String schemaName) {
        String clean = schemaName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.@]", "").replace('@', '.');
        StringBuilder out = new StringBuilder();
        for (String part : clean.split("\\.")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(Character.isDigit(part.charAt(0)) ? "_" + part : part);
        }
        return out.isEmpty() ? "schema" : out.toString();
    }

    private static String refName(JsonNode property) {
        String ref = property.path("$ref").asText();
        return SchemaInference.pascal(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static String type(JsonNode property, String lang) {
        if (property.has("$ref")) {
            return refName(property);
        }
        String kind = property.path("type").asText("object");
        return switch (kind) {
            case "string" -> switch (lang) {
                case "java" -> "String";
                case "python" -> "str";
                case "go" -> "string";
                default -> "string";
            };
            case "number", "integer" -> switch (lang) {
                case "java" -> "Double";
                case "python" -> "float";
                case "go" -> "float64";
                default -> "number";
            };
            case "boolean" -> switch (lang) {
                case "java" -> "Boolean";
                case "python" -> "bool";
                case "go" -> "bool";
                default -> "boolean";
            };
            case "array" -> {
                String item = type(property.path("items"), lang);
                yield switch (lang) {
                    case "java" -> "java.util.List<" + item + ">";
                    case "python" -> "List[" + item + "]";
                    case "go" -> "[]" + item;
                    default -> item + "[]";
                };
            }
            default -> switch (lang) {
                case "java" -> "Object";
                case "python" -> "object";
                case "go" -> "interface{}";
                default -> "any";
            };
        };
    }

    private static String java(String pkg, String name, JsonNode schema) {
        StringBuilder out = new StringBuilder("package schema." + pkg + ";\n\npublic class " + name + " {\n");
        Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
        StringBuilder accessors = new StringBuilder();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String member = camel(field.getKey());
            String type = type(field.getValue(), "java");
            out.append("    private ").append(type).append(' ').append(member).append(";\n");
            String accessor = Character.toUpperCase(member.charAt(0)) + member.substring(1);
            accessors.append("\n    public ").append(type).append(" get").append(accessor).append("() {\n        return ")
                    .append(member).append(";\n    }\n\n    public void set").append(accessor).append('(').append(type)
                    .append(' ').append(member).append(") {\n        this.").append(member).append(" = ")
                    .append(member).append(";\n    }\n");
        }
        return out.append(accessors).append("}\n").toString();
    }

    private static String python(String name, JsonNode schema) {
        StringBuilder out = new StringBuilder("from typing import List\n\n\nclass " + name + "(object):\n");
        Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
        if (!fields.hasNext()) {
            out.append("    pass\n");
        }
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.append("    ").append(snake(field.getKey())).append(": ").append(type(field.getValue(), "python"))
                    .append(" = None\n");
        }
        return out.toString();
    }

    private static String typescript(String name, JsonNode schema) {
        StringBuilder out = new StringBuilder("export interface " + name + " {\n");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(key -> required.add(key.asText()));
        schema.path("properties").fields().forEachRemaining(field -> out.append("  \"").append(field.getKey())
                .append('"').append(required.contains(field.getKey()) ? "" : "?").append(": ")
                .append(type(field.getValue(), "ts")).append(";\n"));
        return out.append("}\n").toString();
    }

    private static String go(String pkg, String name, JsonNode schema) {
        StringBuilder out = new StringBuilder("package " + pkg + "\n\ntype " + name + " struct {\n");
        schema.path("properties").fields().forEachRemaining(field -> out.append("\t")
                .append(SchemaInference.pascal(field.getKey())).append(' ').append(type(field.getValue(), "go"))
                .append(" `json:\"").append(field.getKey()).append(",omitempty\"`\n"));
        return out.append("}\n").toString();
    }

    private static String camel(String key) {
        String pascal = SchemaInference.pascal(key);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }
}
