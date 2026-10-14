package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared CloudFormation-schema view of the live SSM parameter store. */
@ApplicationScoped
public class SsmResourceBackend {
    public static final String TYPE = "AWS::SSM::Parameter";
    private static final Set<String> FIELDS = Set.of("Name", "Type", "Value", "Description", "Tags",
            "Tier", "DataType", "AllowedPattern");
    private final SsmService ssm;
    private final ObjectMapper mapper;

    public SsmResourceBackend(SsmService ssm, ObjectMapper mapper) {
        this.ssm = ssm;
        this.mapper = mapper;
    }

    public ObjectNode read(String name, String region) {
        try {
            return model(ssm.getParameter(name, region));
        } catch (AwsException e) {
            if ("ParameterNotFound".equals(e.getErrorCode())) return null;
            throw e;
        }
    }

    public List<ObjectNode> list(String region) {
        return ssm.describeParameters(region).stream().map(this::model).toList();
    }

    public ObjectNode write(String name, JsonNode desired, boolean update, String region) {
        if (desired == null || !desired.isObject()) throw invalid("Desired state must be an object.");
        desired.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw invalid("Unsupported SSM property: " + field);
        });
        if (name == null || name.isBlank()) throw invalid("Name is required.");
        if (desired.has("Name") && !name.equals(desired.path("Name").asText())) {
            throw invalid("Name is immutable.");
        }
        String type = required(desired, "Type");
        if (!Set.of("String", "StringList").contains(type)) {
            throw invalid("AWS::SSM::Parameter supports String and StringList.");
        }
        String value = required(desired, "Value");
        Map<String, String> tags = new LinkedHashMap<>();
        if (desired.has("Tags")) {
            if (!desired.path("Tags").isObject()) throw invalid("Tags must be an object.");
            desired.path("Tags").fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual()) throw invalid("Tag values must be strings.");
                tags.put(e.getKey(), e.getValue().asText());
            });
        }
        if (update && read(name, region) == null) {
            throw new AwsException("ResourceNotFoundException", "Parameter " + name + " was not found.", 404);
        }
        ssm.putParameter(name, value, type, optional(desired, "Description"), update, region,
                update ? null : tags, desired.path("Tier").asText("Standard"), null,
                desired.path("AllowedPattern").asText(""), desired.path("DataType").asText("text"));
        if (update) {
            List<String> removed = ssm.listTagsForResource(name, region).keySet().stream()
                    .filter(key -> !tags.containsKey(key)).toList();
            if (!removed.isEmpty()) ssm.removeTagsFromResource(name, removed, region);
            if (!tags.isEmpty()) ssm.addTagsToResource(name, tags, region);
        }
        ObjectNode actual = read(name, region);
        if (actual == null) {
            throw new AwsException("ResourceNotFoundException", "Parameter disappeared after the write.", 404);
        }
        return actual;
    }

    public void delete(String name, String region) {
        ssm.deleteParameter(name, region);
    }

    private ObjectNode model(Parameter parameter) {
        ObjectNode result = mapper.createObjectNode();
        result.put("Name", parameter.getName());
        result.put("Type", parameter.getType());
        result.put("Value", parameter.getValue());
        put(result, "Description", parameter.getDescription());
        put(result, "Tier", parameter.getTier());
        put(result, "DataType", parameter.getDataType());
        put(result, "AllowedPattern", parameter.getAllowedPattern());
        result.set("Tags", mapper.valueToTree(parameter.getTags() == null ? Map.of() : parameter.getTags()));
        return result;
    }

    private void put(ObjectNode node, String key, String value) {
        if (value != null && !value.isEmpty()) node.put(key, value);
    }

    private static String required(JsonNode node, String key) {
        String value = optional(node, key);
        if (value == null || value.isEmpty()) throw invalid(key + " is required.");
        return value;
    }

    private static String optional(JsonNode node, String key) {
        if (!node.has(key)) return null;
        if (!node.path(key).isTextual()) throw invalid(key + " must be a string.");
        return node.path(key).asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
