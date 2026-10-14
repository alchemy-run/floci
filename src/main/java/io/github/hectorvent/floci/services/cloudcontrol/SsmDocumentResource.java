package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cloud Control view of {@code AWS::SSM::Document} over the live SSM document store, following
 * the resource type schema: {@code Name} is the create-only primary identifier, {@code Content}
 * reads back as the stored string, {@code Tags} is a Key/Value list, and the write-only
 * {@code UpdateMethod} is never part of the read model.
 */
@ApplicationScoped
public class SsmDocumentResource {
    public static final String TYPE = "AWS::SSM::Document";
    private static final Set<String> FIELDS = Set.of("Name", "Content", "DocumentType", "DocumentFormat",
            "TargetType", "VersionName", "Tags", "UpdateMethod", "Attachments", "Requires");

    private final SsmService ssm;
    private final ObjectMapper mapper;

    public SsmDocumentResource(SsmService ssm, ObjectMapper mapper) {
        this.ssm = ssm;
        this.mapper = mapper;
    }

    /** Self-owned documents only, like ListResources against a real account. */
    public List<ObjectNode> list(String region) {
        return ssm.listDocuments(region, Map.of()).stream().map(this::model).toList();
    }

    public String create(JsonNode desired, String region) {
        validate(desired);
        String name = desired.hasNonNull("Name") ? desired.path("Name").asText()
                : "cloudcontrol-document-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String format = desired.path("DocumentFormat").asText("JSON");
        ssm.createDocument(name, content(desired, format), desired.path("DocumentType").asText("Command"),
                format, desired.path("TargetType").asText(null), tags(desired), region);
        return name;
    }

    /** Converges the document to {@code desired}; {@code observed} is the current read model. */
    public void update(String name, JsonNode observed, JsonNode desired, String region) {
        validate(desired);
        if (desired.has("Name") && !name.equals(desired.path("Name").asText())) {
            throw notUpdatable(name, "Name");
        }
        if (!Objects.equals(text(observed, "DocumentType"), text(desired, "DocumentType"))
                && desired.hasNonNull("DocumentType")) {
            throw notUpdatable(name, "DocumentType");
        }
        String format = desired.path("DocumentFormat").asText(observed.path("DocumentFormat").asText("JSON"));
        boolean contentChanged = !Objects.equals(observed.get("Content"), desired.get("Content"))
                || !Objects.equals(observed.path("DocumentFormat").asText("JSON"), format)
                || !Objects.equals(text(observed, "TargetType"), text(desired, "TargetType"));
        if (contentChanged) {
            // The handler applies a changed Content as an UpdateDocument call, so resending the
            // stored content in another form is rejected by SSM as DuplicateDocumentContent.
            String content = content(desired, format);
            if ("NewVersion".equals(desired.path("UpdateMethod").asText("Replace"))) {
                ssm.updateDocument(name, content, region);
            } else {
                SsmDocument current = ssm.getDocument(name, region);
                ssm.deleteDocument(name, region);
                ssm.createDocument(name, content, current.getDocumentType(), format,
                        desired.path("TargetType").asText(null), current.getTags(), region);
            }
        }
        Map<String, String> desiredTags = tags(desired);
        Map<String, String> observedTags = ssm.getDocument(name, region).getTags();
        List<String> removed = observedTags.keySet().stream().filter(k -> !desiredTags.containsKey(k)).toList();
        Map<String, String> upsert = new LinkedHashMap<>();
        desiredTags.forEach((k, v) -> {
            if (!v.equals(observedTags.get(k))) upsert.put(k, v);
        });
        if (!removed.isEmpty() || !upsert.isEmpty()) {
            ssm.tagDocument(name, upsert, removed, region);
        }
    }

    public void delete(String name, String region) {
        try {
            ssm.deleteDocument(name, region);
        } catch (AwsException e) {
            if ("InvalidDocument".equals(e.getErrorCode())) {
                throw new AwsException("ResourceNotFoundException",
                        "Resource " + name + " of type " + TYPE + " was not found.", 404);
            }
            throw e;
        }
    }

    private ObjectNode model(SsmDocument document) {
        ObjectNode model = mapper.createObjectNode();
        model.put("Name", document.getName());
        put(model, "DocumentType", document.getDocumentType());
        put(model, "DocumentFormat", document.getDocumentFormat());
        put(model, "TargetType", document.getTargetType());
        put(model, "Content", document.getContent());
        if (!document.getTags().isEmpty()) {
            ArrayNode tags = model.putArray("Tags");
            document.getTags().forEach((key, value) -> tags.addObject().put("Key", key).put("Value", value));
        }
        return model;
    }

    private void validate(JsonNode desired) {
        if (desired == null || !desired.isObject()) throw invalid("Desired state must be an object.");
        List<String> unsupported = new ArrayList<>();
        desired.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) unsupported.add(field);
        });
        if (!unsupported.isEmpty()) throw invalid("Unsupported properties for " + TYPE + ": " + unsupported);
        JsonNode content = desired.get("Content");
        if (content == null || content.isNull() || !(content.isObject() || content.isTextual())
                || (content.isTextual() && content.asText().isBlank())) {
            throw invalid("#: required key [Content] not found");
        }
        String method = desired.path("UpdateMethod").asText("Replace");
        if (!Set.of("Replace", "NewVersion").contains(method)) {
            throw invalid("#/UpdateMethod: " + method + " is not a valid enum value");
        }
        JsonNode tags = desired.get("Tags");
        if (tags != null && !tags.isArray()) throw invalid("#/Tags: expected type: JSONArray");
    }

    private String content(JsonNode desired, String format) {
        JsonNode content = desired.get("Content");
        if (content.isTextual()) return content.asText();
        try {
            return "JSON".equals(format)
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content)
                    : mapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw invalid("Content is not valid JSON.");
        }
    }

    private static Map<String, String> tags(JsonNode desired) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : desired.path("Tags")) {
            String key = tag.path("Key").asText(null);
            if (key == null || key.isBlank()) throw invalid("#/Tags: Key is required");
            tags.put(key, tag.path("Value").asText(""));
        }
        return tags;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value != null && !value.isEmpty()) node.put(key, value);
    }

    private static AwsException notUpdatable(String name, String property) {
        return new AwsException("NotUpdatableException", "Resource of type '" + TYPE + "' with identifier '"
                + name + "' cannot update the create-only property " + property + ".", 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
