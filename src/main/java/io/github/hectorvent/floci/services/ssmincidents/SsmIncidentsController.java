package io.github.hectorvent.floci.services.ssmincidents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssmincidents.model.IncidentRecord;
import io.github.hectorvent.floci.services.ssmincidents.model.RegionInfo;
import io.github.hectorvent.floci.services.ssmincidents.model.RelatedItem;
import io.github.hectorvent.floci.services.ssmincidents.model.ReplicationSet;
import io.github.hectorvent.floci.services.ssmincidents.model.ResponsePlan;
import io.github.hectorvent.floci.services.ssmincidents.model.TimelineEvent;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * AWS Systems Manager Incident Manager restJson1.
 *
 * <p>Literal camelCase paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all.
 * Wire names are camelCase. Errors stamp {@code X-Amzn-Errortype} so rest-json SDKs map
 * the typed union.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SsmIncidentsController {

    private final SsmIncidentsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SsmIncidentsController(SsmIncidentsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/listReplicationSets")
    @Consumes(MediaType.WILDCARD)
    public Response listReplicationSets(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode arns = response.putArray("replicationSetArns");
            for (String arn : service.listReplicationSets()) {
                arns.add(arn);
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/getReplicationSet")
    @Consumes(MediaType.WILDCARD)
    public Response getReplicationSet(@QueryParam("arn") String arn) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("replicationSet", toSet(service.getReplicationSet(arn)));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/createReplicationSet")
    public Response createReplicationSet(String body) {
        return handle(body, request -> {
            ReplicationSet set = service.createReplicationSet(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("arn", set.getArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/updateReplicationSet")
    public Response updateReplicationSet(String body) {
        return handle(body, request -> {
            service.updateReplicationSet(request);
            return ok();
        });
    }

    @POST
    @Path("/updateDeletionProtection")
    public Response updateDeletionProtection(String body) {
        return handle(body, request -> {
            service.updateDeletionProtection(request);
            return ok();
        });
    }

    @POST
    @Path("/deleteReplicationSet")
    @Consumes(MediaType.WILDCARD)
    public Response deleteReplicationSet(@QueryParam("arn") String arn, String body) {
        return handle(body, request -> {
            String resolved = arn;
            if (resolved == null || resolved.isBlank()) {
                resolved = request.path("arn").asText(null);
            }
            service.deleteReplicationSet(resolved);
            return ok();
        });
    }

    @POST
    @Path("/listResponsePlans")
    @Consumes(MediaType.WILDCARD)
    public Response listResponsePlans(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("responsePlanSummaries");
            for (ResponsePlan plan : service.listResponsePlans()) {
                ObjectNode summary = summaries.addObject();
                summary.put("arn", plan.getArn());
                summary.put("name", plan.getName());
                if (plan.getDisplayName() != null) {
                    summary.put("displayName", plan.getDisplayName());
                }
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/getResponsePlan")
    @Consumes(MediaType.WILDCARD)
    public Response getResponsePlan(@QueryParam("arn") String arn) {
        try {
            return Response.ok(toPlan(service.getResponsePlan(arn))).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/updateResponsePlan")
    public Response updateResponsePlan(String body) {
        return handle(body, request -> {
            service.updateResponsePlan(request);
            return ok();
        });
    }

    @POST
    @Path("/deleteResponsePlan")
    public Response deleteResponsePlan(String body) {
        return handle(body, request -> {
            service.deleteResponsePlan(request);
            return ok();
        });
    }

    @POST
    @Path("/createResponsePlan")
    public Response createResponsePlan(String body) {
        return handle(body, request -> {
            ResponsePlan plan = service.createResponsePlan(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("arn", plan.getArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/startIncident")
    public Response startIncident(String body) {
        return handle(body, request -> {
            IncidentRecord record = service.startIncident(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("incidentRecordArn", record.getArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/listIncidentRecords")
    @Consumes(MediaType.WILDCARD)
    public Response listIncidentRecords(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("incidentRecordSummaries");
            for (IncidentRecord record : service.listIncidentRecords()) {
                summaries.add(toSummary(record));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/getIncidentRecord")
    @Consumes(MediaType.WILDCARD)
    public Response getIncidentRecord(@QueryParam("arn") String arn) {
        try {
            IncidentRecord record = service.getIncidentRecord(arn);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("incidentRecord", toRecord(record));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/updateIncidentRecord")
    public Response updateIncidentRecord(String body) {
        return handle(body, request -> {
            service.updateIncidentRecord(request);
            return ok();
        });
    }

    @POST
    @Path("/deleteIncidentRecord")
    public Response deleteIncidentRecord(String body) {
        return handle(body, request -> {
            service.deleteIncidentRecord(request);
            return ok();
        });
    }

    @POST
    @Path("/createTimelineEvent")
    public Response createTimelineEvent(String body) {
        return handle(body, request -> {
            TimelineEvent event = service.createTimelineEvent(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("incidentRecordArn", event.getIncidentRecordArn());
            response.put("eventId", event.getEventId());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/getTimelineEvent")
    @Consumes(MediaType.WILDCARD)
    public Response getTimelineEvent(
            @QueryParam("incidentRecordArn") String incidentRecordArn,
            @QueryParam("eventId") String eventId) {
        try {
            TimelineEvent event = service.getTimelineEvent(incidentRecordArn, eventId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("event", toEvent(event));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/updateTimelineEvent")
    public Response updateTimelineEvent(String body) {
        return handle(body, request -> {
            service.updateTimelineEvent(request);
            return ok();
        });
    }

    @POST
    @Path("/deleteTimelineEvent")
    public Response deleteTimelineEvent(String body) {
        return handle(body, request -> {
            service.deleteTimelineEvent(request);
            return ok();
        });
    }

    @POST
    @Path("/listTimelineEvents")
    public Response listTimelineEvents(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("eventSummaries");
            for (TimelineEvent event : service.listTimelineEvents(request)) {
                summaries.add(toEventSummary(event));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/listRelatedItems")
    public Response listRelatedItems(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("relatedItems");
            for (RelatedItem item : service.listRelatedItems(request)) {
                items.add(toRelatedItem(item));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/updateRelatedItems")
    public Response updateRelatedItems(String body) {
        return handle(body, request -> {
            service.updateRelatedItems(request);
            return ok();
        });
    }

    @POST
    @Path("/listIncidentFindings")
    public Response listIncidentFindings(String body) {
        return handle(body, request -> {
            service.requireIncidentRecord(request, "incidentRecordArn");
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("findings");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/batchGetIncidentFindings")
    public Response batchGetIncidentFindings(String body) {
        return handle(body, request -> {
            service.requireIncidentRecord(request, "incidentRecordArn");
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("findings");
            response.putArray("errors");
            return Response.ok(response).build();
        });
    }

    private ObjectNode toSet(ReplicationSet set) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", set.getArn());
        ObjectNode regionMap = node.putObject("regionMap");
        for (Map.Entry<String, RegionInfo> entry : set.getRegionMap().entrySet()) {
            RegionInfo info = entry.getValue();
            ObjectNode region = regionMap.putObject(entry.getKey());
            if (info.getSseKmsKeyId() != null) {
                region.put("sseKmsKeyId", info.getSseKmsKeyId());
            }
            region.put("status", info.getStatus());
            if (info.getStatusMessage() != null) {
                region.put("statusMessage", info.getStatusMessage());
            }
            region.put("statusUpdateDateTime", info.getStatusUpdateDateTime());
        }
        node.put("status", set.getStatus());
        node.put("deletionProtected", set.isDeletionProtected());
        node.put("createdTime", set.getCreatedTime());
        node.put("createdBy", set.getCreatedBy());
        node.put("lastModifiedTime", set.getLastModifiedTime());
        node.put("lastModifiedBy", set.getLastModifiedBy());
        return node;
    }

    private ObjectNode toPlan(ResponsePlan plan) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", plan.getArn());
        node.put("name", plan.getName());
        if (plan.getDisplayName() != null) {
            node.put("displayName", plan.getDisplayName());
        }
        ObjectNode template = node.putObject("incidentTemplate");
        if (plan.getTitle() != null) {
            template.put("title", plan.getTitle());
        }
        template.put("impact", plan.getImpact());
        if (plan.getSummary() != null) {
            template.put("summary", plan.getSummary());
        }
        if (plan.getDedupeString() != null) {
            template.put("dedupeString", plan.getDedupeString());
        }
        return node;
    }

    private ObjectNode toRecord(IncidentRecord record) {
        ObjectNode node = toSummary(record);
        if (record.getSummary() != null) {
            node.put("summary", record.getSummary());
        }
        node.put("lastModifiedTime", record.getLastModifiedTime());
        node.put("lastModifiedBy", record.getLastModifiedBy());
        node.put("dedupeString", record.getDedupeString() == null ? "" : record.getDedupeString());
        return node;
    }

    private ObjectNode toSummary(IncidentRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", record.getArn());
        node.put("title", record.getTitle());
        node.put("status", record.getStatus());
        node.put("impact", record.getImpact());
        node.put("creationTime", record.getCreationTime());
        if (record.getResolvedTime() != null) {
            node.put("resolvedTime", record.getResolvedTime());
        }
        ObjectNode source = node.putObject("incidentRecordSource");
        source.put("createdBy", record.getCreatedBy());
        source.put("source", record.getSource());
        return node;
    }

    private ObjectNode toEvent(TimelineEvent event) {
        ObjectNode node = toEventSummary(event);
        node.put("eventData", event.getEventData());
        return node;
    }

    private ObjectNode toEventSummary(TimelineEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("incidentRecordArn", event.getIncidentRecordArn());
        node.put("eventId", event.getEventId());
        node.put("eventTime", event.getEventTime());
        node.put("eventUpdatedTime", event.getEventUpdatedTime());
        node.put("eventType", event.getEventType());
        return node;
    }

    private ObjectNode toRelatedItem(RelatedItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        if (item.getTitle() != null) {
            node.put("title", item.getTitle());
        }
        if (item.getGeneratedId() != null) {
            node.put("generatedId", item.getGeneratedId());
        }
        ObjectNode identifier = node.putObject("identifier");
        identifier.put("type", item.getType());
        if (item.getValue() != null) {
            identifier.set("value", item.getValue());
        }
        return node;
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response ok() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response error(AwsException exception) {
        Object entity;
        if (exception.getExtendedData() != null) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("__type", exception.jsonType());
            node.put("message", exception.getMessage());
            for (Map.Entry<String, Object> entry : exception.getExtendedData().entrySet()) {
                node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
            }
            entity = node;
        } else {
            entity = new AwsErrorResponse(exception.jsonType(), exception.getMessage());
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(entity)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
