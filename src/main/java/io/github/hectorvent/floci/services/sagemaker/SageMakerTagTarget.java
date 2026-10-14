package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** The live tag map of one SageMaker resource and how to persist it after a change. */
record SageMakerTagTarget(Map<String, String> tags, Runnable persist) {

    /** Applies {@code AddTags}, {@code ListTags} or {@code DeleteTags} and returns the response. */
    ObjectNode apply(String action, JsonNode request, ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        switch (action) {
            case "AddTags" -> {
                tags.putAll(SageMakerService.tagsFromList(request.path("Tags")));
                persist.run();
                out.set("Tags", render(mapper));
            }
            case "DeleteTags" -> {
                request.path("TagKeys").forEach(k -> tags.remove(k.asText()));
                persist.run();
            }
            default -> out.set("Tags", render(mapper));
        }
        return out;
    }

    private ArrayNode render(ObjectMapper mapper) {
        ArrayNode out = mapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode n = out.addObject();
            n.put("Key", k);
            n.put("Value", v);
        });
        return out;
    }
}
