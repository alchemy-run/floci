package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CloudControlJsonHandler {

    private final CloudControlService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudControlJsonHandler(CloudControlService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode body = request == null || request.isNull() ? mapper.createObjectNode() : request;
        return switch (action) {
            case "ListResources" -> listResources(body, region);
            case "GetResource" -> getResource(body, region);
            case "CreateResource" -> createResource(body, region);
            case "UpdateResource" -> updateResource(body, region);
            case "DeleteResource" -> deleteResource(body, region);
            case "GetResourceRequestStatus" -> getResourceRequestStatus(body);
            case "ListResourceRequests" -> listResourceRequests(body);
            case "CancelResourceRequest" -> cancelResourceRequest(body);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listResources(JsonNode request, String region) {
        String typeName = requiredText(request, "TypeName");
        List<CloudControlService.ResourceDescription> all = service.listResources(region, typeName);
        int offset = parseOffset(request);
        int max = pageSize(request);
        int end = Math.min(offset + max, all.size());
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        ArrayNode resources = response.putArray("ResourceDescriptions");
        if (offset < all.size()) {
            for (CloudControlService.ResourceDescription resource : all.subList(offset, end)) {
                resources.add(descriptionNode(resource));
            }
        }
        if (end < all.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response getResource(JsonNode request, String region) {
        String typeName = requiredText(request, "TypeName");
        String identifier = requiredText(request, "Identifier");
        CloudControlService.ResourceDescription resource = service.getResource(region, typeName, identifier);
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        response.set("ResourceDescription", descriptionNode(resource));
        return Response.ok(response).build();
    }

    private Response createResource(JsonNode request, String region) {
        String typeName = requiredText(request, "TypeName");
        JsonNode desiredState = parseJsonValue(request.get("DesiredState"), "DesiredState");
        CloudControlService.ProgressEvent event = service.createResource(
                region, typeName, desiredState, textOrNull(request, "ClientToken"));
        return progressResponse(event);
    }

    private Response updateResource(JsonNode request, String region) {
        String typeName = requiredText(request, "TypeName");
        String identifier = requiredText(request, "Identifier");
        JsonNode patch = parseJsonValue(request.get("PatchDocument"), "PatchDocument");
        CloudControlService.ProgressEvent event = service.updateResource(
                region, typeName, identifier, patch, textOrNull(request, "ClientToken"));
        return progressResponse(event);
    }

    private Response deleteResource(JsonNode request, String region) {
        String typeName = requiredText(request, "TypeName");
        String identifier = requiredText(request, "Identifier");
        CloudControlService.ProgressEvent event = service.deleteResource(
                region, typeName, identifier, textOrNull(request, "ClientToken"));
        return progressResponse(event);
    }

    private Response getResourceRequestStatus(JsonNode request) {
        String requestToken = requiredText(request, "RequestToken");
        return progressResponse(service.getResourceRequestStatus(requestToken));
    }

    private Response listResourceRequests(JsonNode request) {
        JsonNode filter = request.path("ResourceRequestStatusFilter");
        List<String> operations = stringList(filter.get("Operations"));
        List<String> statuses = stringList(filter.get("OperationStatuses"));
        List<CloudControlService.ProgressEvent> all = service.listResourceRequests(operations, statuses);
        int offset = parseOffset(request);
        int max = pageSize(request);
        int end = Math.min(offset + max, all.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("ResourceRequestStatusSummaries");
        if (offset < all.size()) {
            for (CloudControlService.ProgressEvent event : all.subList(offset, end)) {
                summaries.add(progressNode(event));
            }
        }
        if (end < all.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response cancelResourceRequest(JsonNode request) {
        String requestToken = requiredText(request, "RequestToken");
        return progressResponse(service.cancelResourceRequest(requestToken));
    }

    private Response progressResponse(CloudControlService.ProgressEvent event) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ProgressEvent", progressNode(event));
        return Response.ok(response).build();
    }

    private ObjectNode progressNode(CloudControlService.ProgressEvent event) {
        ObjectNode node = mapper.createObjectNode();
        putIfPresent(node, "TypeName", event.typeName());
        putIfPresent(node, "Identifier", event.identifier());
        putIfPresent(node, "RequestToken", event.requestToken());
        putIfPresent(node, "Operation", event.operation());
        putIfPresent(node, "OperationStatus", event.operationStatus());
        node.put("EventTime", event.eventTime());
        putIfPresent(node, "ResourceModel", event.resourceModel());
        putIfPresent(node, "StatusMessage", event.statusMessage());
        putIfPresent(node, "ErrorCode", event.errorCode());
        return node;
    }

    private ObjectNode descriptionNode(CloudControlService.ResourceDescription resource) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Identifier", resource.identifier());
        node.put("Properties", resource.properties());
        return node;
    }

    private JsonNode parseJsonValue(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        if (node.isTextual()) {
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                throw new AwsException("InvalidRequestException", field + " is required.", 400);
            }
            try {
                return mapper.readTree(raw);
            } catch (JsonProcessingException e) {
                throw new AwsException("InvalidRequestException", field + " is not valid JSON.", 400);
            }
        }
        if (node.isObject() || node.isArray()) {
            return node;
        }
        throw new AwsException("InvalidRequestException", field + " is not valid JSON.", 400);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String requiredText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static int parseOffset(JsonNode request) {
        String nextToken = textOrNull(request, "NextToken");
        if (nextToken == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException(nextToken);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidRequestException", "NextToken is invalid.", 400);
        }
    }

    private static int pageSize(JsonNode request) {
        int requested = request.path("MaxResults").asInt(0);
        if (requested <= 0) {
            return 100;
        }
        return Math.min(requested, 100);
    }
}
