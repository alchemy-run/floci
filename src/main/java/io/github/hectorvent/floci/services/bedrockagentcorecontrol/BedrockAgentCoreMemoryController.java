package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreEventService;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreMemoryRecordService;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Branch;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryEvent;
import io.github.hectorvent.floci.services.bedrockagentcore.model.PayloadType;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentCore memory endpoints. The operation is a literal path suffix
 * ({@code /memories/create}, {@code /memories/{id}/details}, …).
 */
@Path("/memories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreMemoryController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreMemoryController.class);

    private final BedrockAgentCoreMemoryService service;
    private final BedrockAgentCoreEventService eventService;
    private final BedrockAgentCoreMemoryRecordService recordService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreMemoryController(BedrockAgentCoreMemoryService service,
                                            BedrockAgentCoreEventService eventService,
                                            BedrockAgentCoreMemoryRecordService recordService,
                                            RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.eventService = eventService;
        this.recordService = recordService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/create")
    public Response createMemory(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Integer expiry = req.hasNonNull("eventExpiryDuration") ? req.get("eventExpiryDuration").asInt() : null;
            Memory memory = service.create(text(req, "name"), expiry, text(req, "description"),
                    text(req, "encryptionKeyArn"), text(req, "memoryExecutionRoleArn"),
                    stringMap(req.get("tags")), text(req, "clientToken"), region);
            return Response.status(202).entity(wrapped(memory, region)).build();
        } catch (Exception e) {
            return error(e, "creating memory");
        }
    }

    @GET
    @Path("/{memoryId}/details")
    public Response getMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id,
                              @QueryParam("view") String view) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(service.get(id, region), region)).build();
        } catch (Exception e) {
            return error(e, "getting memory");
        }
    }

    @PUT
    @Path("/{memoryId}/update")
    public Response updateMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Integer expiry = req.hasNonNull("eventExpiryDuration") ? req.get("eventExpiryDuration").asInt() : null;
            Memory memory = service.update(id, text(req, "description"), expiry,
                    text(req, "memoryExecutionRoleArn"), region);
            return Response.status(202).entity(wrapped(memory, region)).build();
        } catch (Exception e) {
            return error(e, "updating memory");
        }
    }

    @DELETE
    @Path("/{memoryId}/delete")
    public Response deleteMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id,
                                 @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Memory memory = service.delete(id, clientToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("memoryId", memory.getMemoryId());
            out.put("status", memory.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting memory");
        }
    }

    @POST
    @Path("/")
    public Response listMemories(@Context HttpHeaders headers,
                                 @QueryParam("maxResults") String maxResultsParam,
                                 @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Memory> result = service.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("memories");
            for (Memory memory : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("arn", service.arn(memory, region));
                node.put("id", memory.getMemoryId());
                node.put("status", memory.getStatus());
                putInstant(node, "createdAt", memory.getCreatedAt());
                putInstant(node, "updatedAt", memory.getUpdatedAt());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing memories");
        }
    }

    private ObjectNode wrapped(Memory memory, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode node = out.putObject("memory");
        node.put("arn", service.arn(memory, region));
        node.put("id", memory.getMemoryId());
        node.put("name", memory.getName());
        node.put("status", memory.getStatus());
        if (memory.getDescription() != null) {
            node.put("description", memory.getDescription());
        }
        if (memory.getEventExpiryDuration() != null) {
            node.put("eventExpiryDuration", memory.getEventExpiryDuration());
        }
        if (memory.getEncryptionKeyArn() != null) {
            node.put("encryptionKeyArn", memory.getEncryptionKeyArn());
        }
        if (memory.getMemoryExecutionRoleArn() != null) {
            node.put("memoryExecutionRoleArn", memory.getMemoryExecutionRoleArn());
        }
        putInstant(node, "createdAt", memory.getCreatedAt());
        putInstant(node, "updatedAt", memory.getUpdatedAt());
        return out;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // Memory timestamps are modeled as epoch seconds (unixTimestamp).
            node.put(field, instant.getEpochSecond());
        }
    }

    private static String text(JsonNode node, String field) {
        return BedrockAgentCoreControllerSupport.text(node, field);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        return BedrockAgentCoreControllerSupport.stringMap(node);
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }

    // ── AgentCore Memory events (data plane) ─────────────────────
    //
    // These are data-plane operations, but they live under /memories, and a JAX-RS request is
    // matched against one root resource class only: a class rooted at "/" never gets a look in
    // once this class claims the subtree. So the endpoints sit here while the behaviour stays in
    // BedrockAgentCoreEventService.

    @POST
    @Path("/{memoryId}/events")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEvent(@Context HttpHeaders headers,
                                @PathParam("memoryId") String memoryId,
                                CreateEventRequest request) {
        String region = regionResolver.resolveRegion(headers);
        try {
            CreateEventRequest body = request == null ? new CreateEventRequest() : request;
            // payload is required but may legitimately be empty, so presence is passed separately
            // from the value: null means the member was omitted, not that it was an empty list.
            MemoryEvent event = eventService.createEvent(memoryId, body.actorId(), body.sessionId(),
                    body.eventTimestamp(), body.payload(), body.payload() != null, body.branch(), region);
            // AWS answers CreateEvent with 201, not 200.
            return Response.status(201).entity(Map.of("event", event)).build();
        } catch (AwsException e) {
            return error(e, "creating event");
        }
    }

    /** ListEvents is a POST to the session path, not a GET. That is the real wire shape. */
    @POST
    @Path("/{memoryId}/actor/{actorId}/sessions/{sessionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response listEvents(@Context HttpHeaders headers,
                               @PathParam("memoryId") String memoryId,
                               @PathParam("actorId") String actorId,
                               @PathParam("sessionId") String sessionId,
                               ListEventsRequest request) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ListEventsRequest body = request == null ? new ListEventsRequest() : request;
            BedrockAgentCoreEventService.EventPage page = eventService.listEvents(memoryId, actorId, sessionId,
                    body.includePayloads(), body.maxResults(), body.nextToken(), region);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("events", page.events());
            // Only present when another page exists: an absent token ends a caller's loop.
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e, "listing events");
        }
    }

    @GET
    @Path("/{memoryId}/actor/{actorId}/sessions/{sessionId}/events/{eventId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEvent(@Context HttpHeaders headers,
                             @PathParam("memoryId") String memoryId,
                             @PathParam("actorId") String actorId,
                             @PathParam("sessionId") String sessionId,
                             @PathParam("eventId") String eventId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(Map.of("event",
                    eventService.getEvent(memoryId, actorId, sessionId, eventId, region))).build();
        } catch (AwsException e) {
            return error(e, "getting event");
        }
    }

    @DELETE
    @Path("/{memoryId}/actor/{actorId}/sessions/{sessionId}/events/{eventId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteEvent(@Context HttpHeaders headers,
                                @PathParam("memoryId") String memoryId,
                                @PathParam("actorId") String actorId,
                                @PathParam("sessionId") String sessionId,
                                @PathParam("eventId") String eventId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // DeleteEvent echoes the id rather than answering with an empty body.
            return Response.ok(Map.of("eventId",
                    eventService.deleteEvent(memoryId, actorId, sessionId, eventId, region))).build();
        } catch (AwsException e) {
            return error(e, "deleting event");
        }
    }

    @POST
    @Path("/{memoryId}/actors")
    public Response listActors(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = json(body);
            PaginatedResult<String> page = eventService.listActors(memoryId, optionalInt(request, "maxResults"),
                    text(request, "nextToken"), region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode actors = out.putArray("actorSummaries");
            page.items().forEach(actorId -> actors.addObject().put("actorId", actorId));
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing actors");
        }
    }

    @POST
    @Path("/{memoryId}/actor/{actorId}/sessions")
    public Response listSessions(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                 @PathParam("actorId") String actorId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = json(body);
            JsonNode filter = request.get("filter");
            PaginatedResult<BedrockAgentCoreEventService.SessionSummary> page = eventService.listSessions(
                    memoryId, actorId, filter == null ? null : text(filter, "eventFilter"),
                    optionalInt(request, "maxResults"), text(request, "nextToken"), region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode sessions = out.putArray("sessionSummaries");
            for (BedrockAgentCoreEventService.SessionSummary summary : page.items()) {
                ObjectNode node = sessions.addObject();
                node.put("sessionId", summary.sessionId());
                node.put("actorId", summary.actorId());
                node.put("createdAt", summary.createdAt());
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing sessions");
        }
    }

    @POST
    @Path("/{memoryId}/memoryRecords/batchCreate")
    public Response batchCreateMemoryRecords(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // BatchCreateMemoryRecords is modeled with a 201, unlike the other batch operations.
            return Response.status(201).entity(recordService.batchCreate(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "creating memory records");
        }
    }

    @POST
    @Path("/{memoryId}/memoryRecords/batchUpdate")
    public Response batchUpdateMemoryRecords(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.batchUpdate(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "updating memory records");
        }
    }

    @POST
    @Path("/{memoryId}/memoryRecords/batchDelete")
    public Response batchDeleteMemoryRecords(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.batchDelete(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "deleting memory records");
        }
    }

    /** ListMemoryRecords is a POST to the collection path; the filters travel in the body. */
    @POST
    @Path("/{memoryId}/memoryRecords")
    public Response listMemoryRecords(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.list(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "listing memory records");
        }
    }

    /** GetMemoryRecord uses the singular {@code memoryRecord} segment; DeleteMemoryRecord the plural. */
    @GET
    @Path("/{memoryId}/memoryRecord/{memoryRecordId}")
    public Response getMemoryRecord(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                    @PathParam("memoryRecordId") String memoryRecordId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.getRecord(memoryId, memoryRecordId, region)).build();
        } catch (Exception e) {
            return error(e, "getting memory record");
        }
    }

    @DELETE
    @Path("/{memoryId}/memoryRecords/{memoryRecordId}")
    public Response deleteMemoryRecord(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                       @PathParam("memoryRecordId") String memoryRecordId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("memoryRecordId", recordService.deleteRecord(memoryId, memoryRecordId, region));
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "deleting memory record");
        }
    }

    @POST
    @Path("/{memoryId}/retrieve")
    public Response retrieveMemoryRecords(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.retrieve(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "retrieving memory records");
        }
    }

    @POST
    @Path("/{memoryId}/extractionJobs")
    public Response listMemoryExtractionJobs(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(recordService.listExtractionJobs(memoryId, json(body), region)).build();
        } catch (Exception e) {
            return error(e, "listing memory extraction jobs");
        }
    }

    @POST
    @Path("/{memoryId}/extractionJobs/start")
    public Response startMemoryExtractionJob(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("jobId", recordService.startExtractionJob(memoryId, json(body), region));
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "starting memory extraction job");
        }
    }

    private JsonNode json(String body) throws Exception {
        JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!request.isObject()) {
            throw new AwsException("ValidationException", "request body must be a JSON object", 400);
        }
        return request;
    }

    private static Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt()) {
            throw new AwsException("ValidationException", field + " must be an integer", 400);
        }
        return value.asInt();
    }

    /** Request body of {@code CreateEvent}; {@code sessionId} and {@code branch} are optional. */
    public record CreateEventRequest(String actorId, String sessionId, Double eventTimestamp,
                                     List<PayloadType> payload, Branch branch) {
        public CreateEventRequest() { this(null, null, null, null, null); }
    }

    /** Request body of {@code ListEvents}. */
    public record ListEventsRequest(Boolean includePayloads, Integer maxResults, String nextToken) {
        public ListEventsRequest() { this(null, null, null); }
    }

}
