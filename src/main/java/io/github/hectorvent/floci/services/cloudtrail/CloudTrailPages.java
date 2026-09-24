package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.Base64;
import java.util.List;

final class CloudTrailPages {
    private CloudTrailPages() {}

    static ObjectNode page(ObjectMapper mapper, JsonNode request, String scope, String field,
                           List<ObjectNode> values, int defaultSize, int maximum, String limitError) {
        int size = request.path("MaxResults").asInt(defaultSize);
        if ((request.has("MaxResults") && (!request.path("MaxResults").isIntegralNumber()
                || !request.path("MaxResults").canConvertToInt())) || size < 1 || size > maximum) {
            throw new AwsException(limitError, "MaxResults must be between 1 and " + maximum + ".", 400);
        }
        ObjectNode query = ((ObjectNode) request).deepCopy();
        query.remove("NextToken");
        int start = 0;
        if (request.has("NextToken")) {
            try {
                JsonNode token = mapper.readTree(Base64.getUrlDecoder().decode(request.path("NextToken").asText()));
                if (!scope.equals(token.path("scope").asText()) || !query.equals(token.path("query"))) {
                    throw new IllegalArgumentException();
                }
                // A cursor identifies a real row, not an offset shifted by newly recorded calls.
                String cursor = token.path("cursor").asText();
                while (start < values.size() && !cursor.equals(identity(values.get(start)))) start++;
                if (start == values.size()) throw new IllegalArgumentException();
                start++;
            } catch (Exception e) {
                throw new AwsException("InvalidNextTokenException", "Invalid NextToken for this request.", 400);
            }
        }
        int end = Math.min(start + size, values.size());
        ObjectNode response = mapper.createObjectNode();
        response.set(field, mapper.valueToTree(values.subList(start, end)));
        if (end < values.size()) {
            ObjectNode token = mapper.createObjectNode().put("scope", scope).put("cursor", identity(values.get(end - 1)));
            token.set("query", query);
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(token.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return response;
    }

    private static String identity(ObjectNode value) {
        return value.has("EventId") ? value.path("EventId").asText() : value.path("EventDataStoreArn").asText();
    }

    static void validateTimeRange(JsonNode request) {
        for (String field : List.of("StartTime", "EndTime")) {
            if (request.has(field) && (!request.path(field).isNumber()
                    || !Double.isFinite(request.path(field).asDouble()))) {
                throw new AwsException("InvalidTimeRangeException", field + " must be an epoch timestamp.", 400);
            }
        }
        if (request.has("StartTime") && request.has("EndTime")
                && request.path("StartTime").asDouble() > request.path("EndTime").asDouble()) {
            throw new AwsException("InvalidTimeRangeException", "StartTime must not exceed EndTime.", 400);
        }
    }
}
