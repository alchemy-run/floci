package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class CloudControlJsonHandler {

    private final CloudControlService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudControlJsonHandler(CloudControlService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        return switch (action) {
            case "ListResources" -> listResources(request, region, accountId);
            case "GetResource" -> getResource(request, region, accountId);
            case "CreateResource" -> progressResponse(
                    service.createResource(region, accountId, required(request, "TypeName"),
                            request.path("DesiredState").asText(null)));
            case "DeleteResource" -> progressResponse(
                    service.deleteResource(region, accountId, required(request, "TypeName"), required(request, "Identifier")));
            case "UpdateResource" -> progressResponse(service.updateResource(region, accountId,
                    required(request, "TypeName"), required(request, "Identifier"), required(request, "PatchDocument")));
            case "GetResourceRequestStatus" -> progressResponse(
                    service.requestStatus(region, accountId, requestToken(request)));
            case "CancelResourceRequest" -> progressResponse(
                    service.cancelRequest(region, accountId, requestToken(request)));
            case "ListResourceRequests" -> listRequests(request, region, accountId);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response getResource(JsonNode request, String region, String accountId) {
        String typeName = required(request, "TypeName");
        String identifier = required(request, "Identifier");
        CloudControlService.ResourceDescription resource = service.getResource(region, accountId, typeName, identifier);
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        ObjectNode desc = response.putObject("ResourceDescription");
        desc.put("Identifier", resource.identifier());
        desc.put("Properties", resource.properties());
        return Response.ok(response).build();
    }

    private Response progressResponse(CloudControlService.ProgressEvent event) {
        ObjectNode response = mapper.createObjectNode();
        response.set("ProgressEvent", progressNode(event));
        return Response.ok(response).build();
    }

    private ObjectNode progressNode(CloudControlService.ProgressEvent event) {
        ObjectNode pe = mapper.createObjectNode();
        pe.put("TypeName", event.typeName());
        if (event.identifier() != null) pe.put("Identifier", event.identifier());
        Double eventTime = service.eventTime(event);
        if (eventTime != null) pe.put("EventTime", eventTime);
        pe.put("RequestToken", event.requestToken());
        pe.put("Operation", event.operation());
        pe.put("OperationStatus", event.operationStatus());
        if (event.statusMessage() != null) {
            pe.put("StatusMessage", event.statusMessage());
        }
        if (event.resourceModel() != null) {
            pe.put("ResourceModel", event.resourceModel());
        }
        if (event.errorCode() != null) pe.put("ErrorCode", event.errorCode());
        return pe;
    }

    private Response listRequests(JsonNode request, String region, String accountId) {
        var filter = request.path("ResourceRequestStatusFilter");
        var events = service.listRequests(region, accountId, strings(filter.path("Operations")),
                strings(filter.path("OperationStatuses")));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode summaries = response.putArray("ResourceRequestStatusSummaries");
        int start = offset(request, events.size());
        int end = Math.min(events.size(), start + pageSize(request));
        for (var event : events.subList(start, end)) summaries.add(progressNode(event));
        if (end < events.size()) response.put("NextToken", Integer.toString(end));
        return Response.ok(response).build();
    }

    private java.util.List<String> strings(JsonNode node) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (!node.isMissingNode() && !node.isArray()) {
            throw new AwsException("InvalidRequestException", "Request filters must be arrays.", 400);
        }
        node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private int offset(JsonNode request, int size) {
        try {
            int offset = Integer.parseInt(request.path("NextToken").asText("0"));
            if (offset < 0 || offset > size) throw new NumberFormatException();
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidRequestException", "Invalid NextToken.", 400);
        }
    }

    private int pageSize(JsonNode request) {
        int size = request.path("MaxResults").asInt(100);
        if (size < 1 || size > 100) throw new AwsException("InvalidRequestException", "Invalid MaxResults.", 400);
        return size;
    }

    /**
     * Compatibility entry point for direct callers that do not have request context.
     */
    public Response handle(String action, JsonNode request, String region) {
        return handle(action, request, region, "000000000000");
    }

    /**
     * InvalidRequestException is the code Cloud Control declares for a malformed request, so it is
     * what an SDK maps onto a typed exception. ValidationException is not in the service model.
     */
    private String required(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return value;
    }

    /**
     * GetResourceRequestStatus declares only RequestTokenNotFoundException, so an absent token
     * reports that rather than the InvalidRequestException the other operations declare.
     */
    private String requestToken(JsonNode request) {
        String value = request.path("RequestToken").asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("RequestTokenNotFoundException", "RequestToken is required.", 404);
        }
        return value;
    }

    private Response listResources(JsonNode request, String region, String accountId) {
        String typeName = required(request, "TypeName");
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        ArrayNode resources = response.putArray("ResourceDescriptions");
        var listed = service.listResources(region, accountId, typeName).stream()
                .sorted(java.util.Comparator.comparing(CloudControlService.ResourceDescription::identifier)).toList();
        int start = offset(request, listed.size());
        int end = Math.min(listed.size(), start + pageSize(request));
        if (end < listed.size()) response.put("NextToken", Integer.toString(end));
        for (CloudControlService.ResourceDescription resource : listed.subList(start, end)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("Identifier", resource.identifier());
            node.put("Properties", resource.properties());
            resources.add(node);
        }
        return Response.ok(response).build();
    }
}
