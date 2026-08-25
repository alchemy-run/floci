package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcore.model.AgentCoreSession;
import io.github.hectorvent.floci.services.bedrockagentcore.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Browser;
import io.github.hectorvent.floci.services.bedrockagentcore.model.CodeInterpreter;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Gateway;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryEvent;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryRecordItem;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bedrock AgentCore restJson1 — control plane plus data-plane bindings.
 *
 * <p>Literal {@code /browsers}, {@code /memories}, {@code /code-interpreters},
 * {@code /gateways} and {@code /runtimes} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code bedrock-agentcore}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreController {

    private static final String EVENT_STREAM = "application/vnd.amazon.eventstream";
    private static final String TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private final BedrockAgentCoreService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreController(
            BedrockAgentCoreService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/browsers")
    public Response createBrowser(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            Browser browser = service.createBrowser(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserId", browser.getBrowserId());
            response.put("browserArn", browser.getBrowserArn());
            response.put("createdAt", browser.getCreatedAt());
            response.put("status", browser.getStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/browsers")
    @Consumes(MediaType.WILDCARD)
    public Response listBrowsers(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("type") String type) {
        return handle(() -> {
            BedrockAgentCoreService.Page<Browser> page = service.listBrowsers(
                    regionResolver.resolveRegion(headers), maxResults, nextToken, type);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("browserSummaries");
            for (Browser browser : page.items()) {
                summaries.add(toBrowserSummary(browser));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/browsers/{browserId}")
    @Consumes(MediaType.WILDCARD)
    public Response getBrowser(@Context HttpHeaders headers, @PathParam("browserId") String browserId) {
        return handle(() -> {
            Browser browser = service.getBrowser(regionResolver.resolveRegion(headers), browserId);
            return Response.ok(toBrowserDetail(browser)).build();
        });
    }

    @DELETE
    @Path("/browsers/{browserId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBrowser(
            @Context HttpHeaders headers,
            @PathParam("browserId") String browserId,
            @QueryParam("clientToken") String clientToken) {
        return handle(() -> {
            Browser browser = service.deleteBrowser(regionResolver.resolveRegion(headers), browserId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserId", browser.getBrowserId());
            response.put("status", browser.getStatus());
            response.put("lastUpdatedAt", browser.getLastUpdatedAt());
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/browsers/{browserIdentifier}/sessions/start")
    public Response startBrowserSession(
            @Context HttpHeaders headers,
            @PathParam("browserIdentifier") String browserIdentifier,
            String body) {
        return handle(() -> {
            AgentCoreSession session = service.startBrowserSession(
                    regionResolver.resolveRegion(headers), browserIdentifier, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserIdentifier", browserIdentifier);
            response.put("sessionId", session.getSessionId());
            response.put("createdAt", session.getCreatedAt());
            ObjectNode streams = response.putObject("streams");
            ObjectNode automation = streams.putObject("automationStream");
            automation.put("streamEndpoint", "ws://localhost:4566/browser-stream/" + session.getSessionId());
            automation.put("streamStatus", "ENABLED");
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/browsers/{browserIdentifier}/sessions/get")
    @Consumes(MediaType.WILDCARD)
    public Response getBrowserSession(
            @Context HttpHeaders headers,
            @PathParam("browserIdentifier") String browserIdentifier,
            @QueryParam("sessionId") String sessionId) {
        return handle(() -> {
            AgentCoreSession session = service.getBrowserSession(
                    regionResolver.resolveRegion(headers), browserIdentifier, sessionId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserIdentifier", browserIdentifier);
            response.put("sessionId", session.getSessionId());
            if (session.getName() != null) {
                response.put("name", session.getName());
            }
            response.put("createdAt", session.getCreatedAt());
            if (session.getSessionTimeoutSeconds() != null) {
                response.put("sessionTimeoutSeconds", session.getSessionTimeoutSeconds());
            }
            response.put("status", session.getStatus());
            if (session.getLastUpdatedAt() != null) {
                response.put("lastUpdatedAt", session.getLastUpdatedAt());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/browsers/{browserIdentifier}/sessions/list")
    public Response listBrowserSessions(
            @Context HttpHeaders headers,
            @PathParam("browserIdentifier") String browserIdentifier,
            String body) {
        return handle(() -> {
            List<AgentCoreSession> sessions = service.listBrowserSessions(
                    regionResolver.resolveRegion(headers), browserIdentifier, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("items");
            for (AgentCoreSession session : sessions) {
                items.add(toSessionSummary(browserIdentifier, session));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/browsers/{browserIdentifier}/sessions/invoke")
    public Response invokeBrowser(
            @Context HttpHeaders headers,
            @PathParam("browserIdentifier") String browserIdentifier,
            @HeaderParam("x-amzn-browser-session-id") String sessionId,
            String body) {
        return handle(() -> {
            AgentCoreSession session = service.requireReadyBrowserSession(
                    regionResolver.resolveRegion(headers), browserIdentifier, sessionId);
            JsonNode request = parse(body);
            JsonNode action = request.has("action") ? request.get("action") : request;
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode result = response.putObject("result");
            if (action != null && action.has("screenshot")) {
                ObjectNode screenshot = result.putObject("screenshot");
                screenshot.put("status", "SUCCESS");
                screenshot.put("data", TINY_PNG);
            } else {
                String member = action != null && action.fieldNames().hasNext()
                        ? action.fieldNames().next()
                        : "screenshot";
                ObjectNode actionResult = result.putObject(member);
                actionResult.put("status", "SUCCESS");
            }
            return Response.ok(response)
                    .header("x-amzn-browser-session-id", session.getSessionId())
                    .build();
        });
    }

    @PUT
    @Path("/browsers/{browserIdentifier}/sessions/stop")
    public Response stopBrowserSession(
            @Context HttpHeaders headers,
            @PathParam("browserIdentifier") String browserIdentifier,
            @QueryParam("sessionId") String sessionId) {
        return handle(() -> {
            AgentCoreSession session = service.stopBrowserSession(
                    regionResolver.resolveRegion(headers), browserIdentifier, sessionId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserIdentifier", browserIdentifier);
            response.put("sessionId", session.getSessionId());
            response.put("lastUpdatedAt", session.getLastUpdatedAt());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/create")
    public Response createMemory(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            MemoryResource memory = service.createMemory(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("memory", toMemory(memory));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories")
    public Response listMemories(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            BedrockAgentCoreService.Page<MemoryResource> page =
                    service.listMemories(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode memories = response.putArray("memories");
            for (MemoryResource memory : page.items()) {
                ObjectNode summary = memories.addObject();
                summary.put("arn", memory.getArn());
                summary.put("id", memory.getId());
                summary.put("status", memory.getStatus());
                summary.put("createdAt", memory.getCreatedAt());
                summary.put("updatedAt", memory.getUpdatedAt());
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/memories/{memoryId}/details")
    @Consumes(MediaType.WILDCARD)
    public Response getMemory(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId) {
        return handle(() -> {
            MemoryResource memory = service.getMemory(regionResolver.resolveRegion(headers), memoryId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("memory", toMemory(memory));
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/memories/{memoryId}/update")
    public Response updateMemory(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> {
            MemoryResource memory =
                    service.updateMemory(regionResolver.resolveRegion(headers), memoryId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("memory", toMemory(memory));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/memories/{memoryId}/delete")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMemory(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId) {
        return handle(() -> {
            MemoryResource memory = service.deleteMemory(regionResolver.resolveRegion(headers), memoryId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("memoryId", memory.getId());
            response.put("status", memory.getStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/events")
    public Response createEvent(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> {
            MemoryEvent event = service.createEvent(regionResolver.resolveRegion(headers), memoryId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("event", toEvent(event));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/actor/{actorId}/sessions/{sessionId}")
    public Response listEvents(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("actorId") String actorId,
            @PathParam("sessionId") String sessionId) {
        return handle(() -> {
            List<MemoryEvent> events = service.listEvents(
                    regionResolver.resolveRegion(headers), memoryId, actorId, sessionId);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("events");
            for (MemoryEvent event : events) {
                array.add(toEvent(event));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/memories/{memoryId}/actor/{actorId}/sessions/{sessionId}/events/{eventId}")
    @Consumes(MediaType.WILDCARD)
    public Response getEvent(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("actorId") String actorId,
            @PathParam("sessionId") String sessionId,
            @PathParam("eventId") String eventId) {
        return handle(() -> {
            MemoryEvent event = service.getEvent(
                    regionResolver.resolveRegion(headers), memoryId, actorId, sessionId, eventId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("event", toEvent(event));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/memories/{memoryId}/actor/{actorId}/sessions/{sessionId}/events/{eventId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEvent(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("actorId") String actorId,
            @PathParam("sessionId") String sessionId,
            @PathParam("eventId") String eventId) {
        return handle(() -> {
            String deleted = service.deleteEvent(
                    regionResolver.resolveRegion(headers), memoryId, actorId, sessionId, eventId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("eventId", deleted);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/actors")
    public Response listActors(@Context HttpHeaders headers, @PathParam("memoryId") String memoryId) {
        return handle(() -> {
            List<String> actors = service.listActors(regionResolver.resolveRegion(headers), memoryId);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("actorSummaries");
            for (String actorId : actors) {
                summaries.addObject().put("actorId", actorId);
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/actor/{actorId}/sessions")
    public Response listSessions(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("actorId") String actorId) {
        return handle(() -> {
            List<MemoryEvent> sessions =
                    service.listSessions(regionResolver.resolveRegion(headers), memoryId, actorId);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("sessionSummaries");
            for (MemoryEvent event : sessions) {
                ObjectNode summary = summaries.addObject();
                summary.put("sessionId", event.getSessionId());
                summary.put("actorId", event.getActorId());
                summary.put("createdAt", event.getEventTimestamp());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/memoryRecords/batchCreate")
    public Response batchCreateMemoryRecords(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> Response.ok(toBatchResult(
                service.batchCreateRecords(regionResolver.resolveRegion(headers), memoryId, parse(body)))).build());
    }

    @POST
    @Path("/memories/{memoryId}/memoryRecords/batchUpdate")
    public Response batchUpdateMemoryRecords(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> Response.ok(toBatchResult(
                service.batchUpdateRecords(regionResolver.resolveRegion(headers), memoryId, parse(body)))).build());
    }

    @POST
    @Path("/memories/{memoryId}/memoryRecords/batchDelete")
    public Response batchDeleteMemoryRecords(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> Response.ok(toBatchResult(
                service.batchDeleteRecords(regionResolver.resolveRegion(headers), memoryId, parse(body)))).build());
    }

    @POST
    @Path("/memories/{memoryId}/memoryRecords")
    public Response listMemoryRecords(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> {
            List<MemoryRecordItem> records = service.listMemoryRecords(
                    regionResolver.resolveRegion(headers), memoryId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("memoryRecordSummaries");
            for (MemoryRecordItem record : records) {
                summaries.add(toRecordSummary(record));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/retrieve")
    public Response retrieveMemoryRecords(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> {
            List<MemoryRecordItem> records = service.retrieveMemoryRecords(
                    regionResolver.resolveRegion(headers), memoryId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("memoryRecordSummaries");
            for (MemoryRecordItem record : records) {
                summaries.add(toRecordSummary(record));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/memories/{memoryId}/memoryRecord/{memoryRecordId}")
    @Consumes(MediaType.WILDCARD)
    public Response getMemoryRecord(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("memoryRecordId") String memoryRecordId) {
        return handle(() -> {
            MemoryRecordItem record = service.getMemoryRecord(
                    regionResolver.resolveRegion(headers), memoryId, memoryRecordId);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("memoryRecord", toRecord(record));
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/memories/{memoryId}/memoryRecords/{memoryRecordId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMemoryRecord(
            @Context HttpHeaders headers,
            @PathParam("memoryId") String memoryId,
            @PathParam("memoryRecordId") String memoryRecordId) {
        return handle(() -> {
            String deleted = service.deleteMemoryRecord(
                    regionResolver.resolveRegion(headers), memoryId, memoryRecordId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("memoryRecordId", deleted);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/extractionJobs")
    public Response listExtractionJobs(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId) {
        return handle(() -> {
            List<JsonNode> jobs = service.listExtractionJobs(regionResolver.resolveRegion(headers), memoryId);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode array = response.putArray("jobs");
            for (JsonNode job : jobs) {
                array.add(job);
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/memories/{memoryId}/extractionJobs/start")
    public Response startExtractionJob(
            @Context HttpHeaders headers, @PathParam("memoryId") String memoryId, String body) {
        return handle(() -> {
            String jobId = service.startExtractionJob(
                    regionResolver.resolveRegion(headers), memoryId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jobId", jobId);
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/code-interpreters")
    public Response createCodeInterpreter(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            CodeInterpreter interpreter =
                    service.createCodeInterpreter(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterId", interpreter.getCodeInterpreterId());
            response.put("codeInterpreterArn", interpreter.getCodeInterpreterArn());
            response.put("createdAt", interpreter.getCreatedAt());
            response.put("status", interpreter.getStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/code-interpreters")
    @Consumes(MediaType.WILDCARD)
    public Response listCodeInterpreters(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("type") String type) {
        return handle(() -> {
            BedrockAgentCoreService.Page<CodeInterpreter> page = service.listCodeInterpreters(
                    regionResolver.resolveRegion(headers), maxResults, nextToken, type);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode summaries = response.putArray("codeInterpreterSummaries");
            for (CodeInterpreter interpreter : page.items()) {
                ObjectNode summary = summaries.addObject();
                summary.put("codeInterpreterId", interpreter.getCodeInterpreterId());
                summary.put("codeInterpreterArn", interpreter.getCodeInterpreterArn());
                if (interpreter.getName() != null) {
                    summary.put("name", interpreter.getName());
                }
                if (interpreter.getDescription() != null) {
                    summary.put("description", interpreter.getDescription());
                }
                summary.put("status", interpreter.getStatus());
                summary.put("createdAt", interpreter.getCreatedAt());
                if (interpreter.getLastUpdatedAt() != null) {
                    summary.put("lastUpdatedAt", interpreter.getLastUpdatedAt());
                }
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/code-interpreters/{codeInterpreterId}")
    @Consumes(MediaType.WILDCARD)
    public Response getCodeInterpreter(
            @Context HttpHeaders headers, @PathParam("codeInterpreterId") String codeInterpreterId) {
        return handle(() -> {
            CodeInterpreter interpreter =
                    service.getCodeInterpreter(regionResolver.resolveRegion(headers), codeInterpreterId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterId", interpreter.getCodeInterpreterId());
            response.put("codeInterpreterArn", interpreter.getCodeInterpreterArn());
            response.put("name", interpreter.getName());
            if (interpreter.getDescription() != null) {
                response.put("description", interpreter.getDescription());
            }
            if (interpreter.getExecutionRoleArn() != null) {
                response.put("executionRoleArn", interpreter.getExecutionRoleArn());
            }
            JsonNode network = interpreter.getNetworkConfiguration();
            if (network != null) {
                response.set("networkConfiguration", network);
            } else {
                response.putObject("networkConfiguration").put("networkMode", "SANDBOX");
            }
            if (interpreter.getCertificates() != null) {
                response.set("certificates", interpreter.getCertificates());
            }
            response.put("status", interpreter.getStatus());
            if (interpreter.getFailureReason() != null) {
                response.put("failureReason", interpreter.getFailureReason());
            }
            response.put("createdAt", interpreter.getCreatedAt());
            response.put("lastUpdatedAt", interpreter.getLastUpdatedAt());
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/code-interpreters/{codeInterpreterId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCodeInterpreter(
            @Context HttpHeaders headers, @PathParam("codeInterpreterId") String codeInterpreterId) {
        return handle(() -> {
            CodeInterpreter interpreter =
                    service.deleteCodeInterpreter(regionResolver.resolveRegion(headers), codeInterpreterId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterId", interpreter.getCodeInterpreterId());
            response.put("status", interpreter.getStatus());
            response.put("lastUpdatedAt", interpreter.getLastUpdatedAt());
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/runtimes{slash: [/]?}")
    public Response createAgentRuntime(@Context HttpHeaders headers, String body) {
        return handle(() -> {
            AgentRuntime runtime = service.createAgentRuntime(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("agentRuntimeArn", runtime.getAgentRuntimeArn());
            response.put("agentRuntimeId", runtime.getAgentRuntimeId());
            response.put("agentRuntimeVersion", runtime.getAgentRuntimeVersion());
            response.put("createdAt", runtime.getCreatedAt());
            response.put("status", runtime.getStatus());
            if (runtime.getWorkloadIdentityDetails() != null) {
                response.set("workloadIdentityDetails", runtime.getWorkloadIdentityDetails());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/runtimes{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response listAgentRuntimes(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            BedrockAgentCoreService.Page<AgentRuntime> page = service.listAgentRuntimes(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("agentRuntimes");
            for (AgentRuntime runtime : page.items()) {
                items.add(toRuntimeSummary(runtime));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/runtimes/{agentRuntimeId}{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response getAgentRuntime(
            @Context HttpHeaders headers, @PathParam("agentRuntimeId") String agentRuntimeId) {
        return handle(() -> Response.ok(toRuntimeDetail(
                service.getAgentRuntime(regionResolver.resolveRegion(headers), agentRuntimeId))).build());
    }

    @PUT
    @Path("/runtimes/{agentRuntimeId}{slash: [/]?}")
    public Response updateAgentRuntime(
            @Context HttpHeaders headers,
            @PathParam("agentRuntimeId") String agentRuntimeId,
            String body) {
        return handle(() -> {
            AgentRuntime runtime = service.updateAgentRuntime(
                    regionResolver.resolveRegion(headers), agentRuntimeId, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("agentRuntimeArn", runtime.getAgentRuntimeArn());
            response.put("agentRuntimeId", runtime.getAgentRuntimeId());
            response.put("agentRuntimeVersion", runtime.getAgentRuntimeVersion());
            response.put("createdAt", runtime.getCreatedAt());
            response.put("lastUpdatedAt", runtime.getLastUpdatedAt());
            response.put("status", runtime.getStatus());
            if (runtime.getWorkloadIdentityDetails() != null) {
                response.set("workloadIdentityDetails", runtime.getWorkloadIdentityDetails());
            }
            return Response.ok(response).build();
        });
    }

    @DELETE
    @Path("/runtimes/{agentRuntimeId}{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAgentRuntime(
            @Context HttpHeaders headers, @PathParam("agentRuntimeId") String agentRuntimeId) {
        return handle(() -> {
            AgentRuntime runtime = service.deleteAgentRuntime(
                    regionResolver.resolveRegion(headers), agentRuntimeId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", runtime.getStatus());
            response.put("agentRuntimeId", runtime.getAgentRuntimeId());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/gateways{slash: [/]?}")
    public Response createGateway(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(toGatewayDetail(
                service.createGateway(regionResolver.resolveRegion(headers), parse(body)))).build());
    }

    @GET
    @Path("/gateways{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response listGateways(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return handle(() -> {
            BedrockAgentCoreService.Page<Gateway> page = service.listGateways(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("items");
            for (Gateway gateway : page.items()) {
                items.add(toGatewaySummary(gateway));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/gateways/{gatewayIdentifier}{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response getGateway(
            @Context HttpHeaders headers, @PathParam("gatewayIdentifier") String gatewayIdentifier) {
        return handle(() -> Response.ok(toGatewayDetail(
                service.getGateway(regionResolver.resolveRegion(headers), gatewayIdentifier))).build());
    }

    @PUT
    @Path("/gateways/{gatewayIdentifier}{slash: [/]?}")
    public Response updateGateway(
            @Context HttpHeaders headers,
            @PathParam("gatewayIdentifier") String gatewayIdentifier,
            String body) {
        return handle(() -> Response.ok(toGatewayDetail(
                service.updateGateway(regionResolver.resolveRegion(headers), gatewayIdentifier, parse(body))))
                .build());
    }

    @DELETE
    @Path("/gateways/{gatewayIdentifier}{slash: [/]?}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGateway(
            @Context HttpHeaders headers, @PathParam("gatewayIdentifier") String gatewayIdentifier) {
        return handle(() -> {
            Gateway gateway = service.deleteGateway(regionResolver.resolveRegion(headers), gatewayIdentifier);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("gatewayId", gateway.getGatewayId());
            response.put("status", gateway.getStatus());
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/code-interpreters/{codeInterpreterIdentifier}/sessions/start")
    public Response startCodeInterpreterSession(
            @Context HttpHeaders headers,
            @PathParam("codeInterpreterIdentifier") String codeInterpreterIdentifier,
            String body) {
        return handle(() -> {
            AgentCoreSession session = service.startCodeInterpreterSession(
                    regionResolver.resolveRegion(headers), codeInterpreterIdentifier, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterIdentifier", codeInterpreterIdentifier);
            response.put("sessionId", session.getSessionId());
            response.put("createdAt", session.getCreatedAt());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/code-interpreters/{codeInterpreterIdentifier}/sessions/get")
    @Consumes(MediaType.WILDCARD)
    public Response getCodeInterpreterSession(
            @Context HttpHeaders headers,
            @PathParam("codeInterpreterIdentifier") String codeInterpreterIdentifier,
            @QueryParam("sessionId") String sessionId) {
        return handle(() -> {
            AgentCoreSession session = service.getCodeInterpreterSession(
                    regionResolver.resolveRegion(headers), codeInterpreterIdentifier, sessionId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterIdentifier", codeInterpreterIdentifier);
            response.put("sessionId", session.getSessionId());
            if (session.getName() != null) {
                response.put("name", session.getName());
            }
            response.put("createdAt", session.getCreatedAt());
            if (session.getSessionTimeoutSeconds() != null) {
                response.put("sessionTimeoutSeconds", session.getSessionTimeoutSeconds());
            }
            response.put("status", session.getStatus());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/code-interpreters/{codeInterpreterIdentifier}/sessions/list")
    public Response listCodeInterpreterSessions(
            @Context HttpHeaders headers,
            @PathParam("codeInterpreterIdentifier") String codeInterpreterIdentifier,
            String body) {
        return handle(() -> {
            List<AgentCoreSession> sessions = service.listCodeInterpreterSessions(
                    regionResolver.resolveRegion(headers), codeInterpreterIdentifier, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode items = response.putArray("items");
            for (AgentCoreSession session : sessions) {
                ObjectNode summary = items.addObject();
                summary.put("codeInterpreterIdentifier", codeInterpreterIdentifier);
                summary.put("sessionId", session.getSessionId());
                if (session.getName() != null) {
                    summary.put("name", session.getName());
                }
                summary.put("status", session.getStatus());
                summary.put("createdAt", session.getCreatedAt());
                if (session.getLastUpdatedAt() != null) {
                    summary.put("lastUpdatedAt", session.getLastUpdatedAt());
                }
            }
            return Response.ok(response).build();
        });
    }

    @PUT
    @Path("/code-interpreters/{codeInterpreterIdentifier}/sessions/stop")
    public Response stopCodeInterpreterSession(
            @Context HttpHeaders headers,
            @PathParam("codeInterpreterIdentifier") String codeInterpreterIdentifier,
            @QueryParam("sessionId") String sessionId) {
        return handle(() -> {
            AgentCoreSession session = service.stopCodeInterpreterSession(
                    regionResolver.resolveRegion(headers), codeInterpreterIdentifier, sessionId);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("codeInterpreterIdentifier", codeInterpreterIdentifier);
            response.put("sessionId", session.getSessionId());
            response.put("lastUpdatedAt", session.getLastUpdatedAt());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/code-interpreters/{codeInterpreterIdentifier}/tools/invoke")
    public Response invokeCodeInterpreter(
            @Context HttpHeaders headers,
            @PathParam("codeInterpreterIdentifier") String codeInterpreterIdentifier,
            @HeaderParam("x-amzn-code-interpreter-session-id") String sessionId,
            String body) {
        return handle(() -> {
            AgentCoreSession session = service.requireReadyCodeSession(
                    regionResolver.resolveRegion(headers), codeInterpreterIdentifier, sessionId);
            JsonNode request = parse(body);
            String stdout = service.executeCode(request);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", stdout.trim());
            ObjectNode structured = result.putObject("structuredContent");
            structured.put("stdout", stdout);
            structured.put("stderr", "");
            structured.put("exitCode", 0);
            structured.put("taskStatus", "completed");
            result.put("isError", false);
            byte[] stream = encodeEvent("result", result);
            return Response.ok(stream)
                    .type(EVENT_STREAM)
                    .header("x-amzn-code-interpreter-session-id", session.getSessionId())
                    .build();
        });
    }

    private ObjectNode toMemory(MemoryResource memory) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", memory.getArn());
        node.put("id", memory.getId());
        node.put("name", memory.getName());
        if (memory.getDescription() != null) {
            node.put("description", memory.getDescription());
        }
        if (memory.getEncryptionKeyArn() != null) {
            node.put("encryptionKeyArn", memory.getEncryptionKeyArn());
        }
        if (memory.getMemoryExecutionRoleArn() != null) {
            node.put("memoryExecutionRoleArn", memory.getMemoryExecutionRoleArn());
        }
        node.put("eventExpiryDuration", memory.getEventExpiryDuration() == null ? 90 : memory.getEventExpiryDuration());
        node.put("status", memory.getStatus());
        if (memory.getFailureReason() != null) {
            node.put("failureReason", memory.getFailureReason());
        }
        node.put("createdAt", memory.getCreatedAt());
        node.put("updatedAt", memory.getUpdatedAt());
        if (memory.getStrategies() != null) {
            node.set("strategies", memory.getStrategies());
        }
        return node;
    }

    private ObjectNode toEvent(MemoryEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("memoryId", event.getMemoryId());
        node.put("actorId", event.getActorId());
        node.put("sessionId", event.getSessionId());
        node.put("eventId", event.getEventId());
        node.put("eventTimestamp", event.getEventTimestamp());
        if (event.getPayload() != null) {
            node.set("payload", event.getPayload());
        }
        if (event.getBranch() != null) {
            node.set("branch", event.getBranch());
        }
        if (event.getMetadata() != null) {
            node.set("metadata", event.getMetadata());
        }
        return node;
    }

    private ObjectNode toRecord(MemoryRecordItem record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("memoryRecordId", record.getMemoryRecordId());
        if (record.getContent() != null) {
            node.set("content", record.getContent());
        } else {
            node.putObject("content").put("text", "");
        }
        if (record.getMemoryStrategyId() != null) {
            node.put("memoryStrategyId", record.getMemoryStrategyId());
        }
        ArrayNode namespaces = node.putArray("namespaces");
        for (String namespace : record.getNamespaces()) {
            namespaces.add(namespace);
        }
        node.put("createdAt", record.getCreatedAt());
        if (record.getMetadata() != null) {
            node.set("metadata", record.getMetadata());
        }
        return node;
    }

    private ObjectNode toRecordSummary(MemoryRecordItem record) {
        return toRecord(record);
    }

    private ObjectNode toBatchResult(BedrockAgentCoreService.BatchResult result) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode successful = response.putArray("successfulRecords");
        for (MemoryRecordItem record : result.successful()) {
            ObjectNode item = successful.addObject();
            item.put("memoryRecordId", record.getMemoryRecordId());
            item.put("status", "SUCCEEDED");
            if (record.getRequestIdentifier() != null) {
                item.put("requestIdentifier", record.getRequestIdentifier());
            }
        }
        ArrayNode failed = response.putArray("failedRecords");
        for (MemoryRecordItem record : result.failed()) {
            ObjectNode item = failed.addObject();
            item.put("memoryRecordId", record.getMemoryRecordId());
            item.put("status", "FAILED");
        }
        return response;
    }

    private ObjectNode toBrowserDetail(Browser browser) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("browserId", browser.getBrowserId());
        response.put("browserArn", browser.getBrowserArn());
        response.put("name", browser.getName());
        if (browser.getDescription() != null) {
            response.put("description", browser.getDescription());
        }
        if (browser.getExecutionRoleArn() != null) {
            response.put("executionRoleArn", browser.getExecutionRoleArn());
        }
        JsonNode network = browser.getNetworkConfiguration();
        if (network != null) {
            response.set("networkConfiguration", network);
        } else {
            ObjectNode defaults = response.putObject("networkConfiguration");
            defaults.put("networkMode", "PUBLIC");
        }
        if (browser.getRecording() != null) {
            response.set("recording", browser.getRecording());
        }
        if (browser.getBrowserSigning() != null) {
            response.set("browserSigning", browser.getBrowserSigning());
        }
        if (browser.getEnterprisePolicies() != null) {
            response.set("enterprisePolicies", browser.getEnterprisePolicies());
        }
        if (browser.getCertificates() != null) {
            response.set("certificates", browser.getCertificates());
        }
        response.put("status", browser.getStatus());
        if (browser.getFailureReason() != null) {
            response.put("failureReason", browser.getFailureReason());
        }
        response.put("createdAt", browser.getCreatedAt());
        response.put("lastUpdatedAt", browser.getLastUpdatedAt());
        return response;
    }

    private ObjectNode toBrowserSummary(Browser browser) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("browserId", browser.getBrowserId());
        summary.put("browserArn", browser.getBrowserArn());
        if (browser.getName() != null) {
            summary.put("name", browser.getName());
        }
        if (browser.getDescription() != null) {
            summary.put("description", browser.getDescription());
        }
        summary.put("status", browser.getStatus());
        summary.put("createdAt", browser.getCreatedAt());
        if (browser.getLastUpdatedAt() != null) {
            summary.put("lastUpdatedAt", browser.getLastUpdatedAt());
        }
        return summary;
    }

    private ObjectNode toSessionSummary(String browserIdentifier, AgentCoreSession session) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("browserIdentifier", browserIdentifier);
        summary.put("sessionId", session.getSessionId());
        if (session.getName() != null) {
            summary.put("name", session.getName());
        }
        summary.put("status", session.getStatus());
        summary.put("createdAt", session.getCreatedAt());
        if (session.getLastUpdatedAt() != null) {
            summary.put("lastUpdatedAt", session.getLastUpdatedAt());
        }
        return summary;
    }

    private ObjectNode toRuntimeDetail(AgentRuntime runtime) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("agentRuntimeArn", runtime.getAgentRuntimeArn());
        response.put("agentRuntimeName", runtime.getAgentRuntimeName());
        response.put("agentRuntimeId", runtime.getAgentRuntimeId());
        response.put("agentRuntimeVersion", runtime.getAgentRuntimeVersion());
        response.put("createdAt", runtime.getCreatedAt());
        response.put("lastUpdatedAt", runtime.getLastUpdatedAt());
        response.put("roleArn", runtime.getRoleArn());
        if (runtime.getNetworkConfiguration() != null) {
            response.set("networkConfiguration", runtime.getNetworkConfiguration());
        } else {
            response.putObject("networkConfiguration").put("networkMode", "PUBLIC");
        }
        response.put("status", runtime.getStatus());
        if (runtime.getLifecycleConfiguration() != null) {
            response.set("lifecycleConfiguration", runtime.getLifecycleConfiguration());
        } else {
            response.putObject("lifecycleConfiguration");
        }
        if (runtime.getFailureReason() != null) {
            response.put("failureReason", runtime.getFailureReason());
        }
        if (runtime.getDescription() != null) {
            response.put("description", runtime.getDescription());
        }
        if (runtime.getWorkloadIdentityDetails() != null) {
            response.set("workloadIdentityDetails", runtime.getWorkloadIdentityDetails());
        }
        if (runtime.getAgentRuntimeArtifact() != null) {
            response.set("agentRuntimeArtifact", runtime.getAgentRuntimeArtifact());
        }
        if (runtime.getProtocolConfiguration() != null) {
            response.set("protocolConfiguration", runtime.getProtocolConfiguration());
        }
        if (runtime.getEnvironmentVariables() != null) {
            response.set("environmentVariables", runtime.getEnvironmentVariables());
        }
        if (runtime.getAuthorizerConfiguration() != null) {
            response.set("authorizerConfiguration", runtime.getAuthorizerConfiguration());
        }
        if (runtime.getRequestHeaderConfiguration() != null) {
            response.set("requestHeaderConfiguration", runtime.getRequestHeaderConfiguration());
        }
        if (runtime.getMetadataConfiguration() != null) {
            response.set("metadataConfiguration", runtime.getMetadataConfiguration());
        }
        if (runtime.getFilesystemConfigurations() != null) {
            response.set("filesystemConfigurations", runtime.getFilesystemConfigurations());
        }
        return response;
    }

    private ObjectNode toRuntimeSummary(AgentRuntime runtime) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("agentRuntimeArn", runtime.getAgentRuntimeArn());
        summary.put("agentRuntimeId", runtime.getAgentRuntimeId());
        summary.put("agentRuntimeVersion", runtime.getAgentRuntimeVersion());
        summary.put("agentRuntimeName", runtime.getAgentRuntimeName());
        if (runtime.getDescription() != null) {
            summary.put("description", runtime.getDescription());
        }
        summary.put("lastUpdatedAt", runtime.getLastUpdatedAt());
        summary.put("status", runtime.getStatus());
        return summary;
    }

    private ObjectNode toGatewayDetail(Gateway gateway) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("gatewayArn", gateway.getGatewayArn());
        response.put("gatewayId", gateway.getGatewayId());
        if (gateway.getGatewayUrl() != null) {
            response.put("gatewayUrl", gateway.getGatewayUrl());
        }
        response.put("createdAt", gateway.getCreatedAt());
        response.put("updatedAt", gateway.getUpdatedAt());
        response.put("status", gateway.getStatus());
        if (gateway.getStatusReasons() != null) {
            ArrayNode reasons = response.putArray("statusReasons");
            for (String reason : gateway.getStatusReasons()) {
                reasons.add(reason);
            }
        }
        response.put("name", gateway.getName());
        if (gateway.getDescription() != null) {
            response.put("description", gateway.getDescription());
        }
        if (gateway.getRoleArn() != null) {
            response.put("roleArn", gateway.getRoleArn());
        }
        if (gateway.getProtocolType() != null) {
            response.put("protocolType", gateway.getProtocolType());
        }
        if (gateway.getProtocolConfiguration() != null) {
            response.set("protocolConfiguration", gateway.getProtocolConfiguration());
        }
        response.put("authorizerType", gateway.getAuthorizerType());
        if (gateway.getAuthorizerConfiguration() != null) {
            response.set("authorizerConfiguration", gateway.getAuthorizerConfiguration());
        }
        if (gateway.getKmsKeyArn() != null) {
            response.put("kmsKeyArn", gateway.getKmsKeyArn());
        }
        if (gateway.getCustomTransformConfiguration() != null) {
            response.set("customTransformConfiguration", gateway.getCustomTransformConfiguration());
        }
        if (gateway.getInterceptorConfigurations() != null) {
            response.set("interceptorConfigurations", gateway.getInterceptorConfigurations());
        }
        if (gateway.getPolicyEngineConfiguration() != null) {
            response.set("policyEngineConfiguration", gateway.getPolicyEngineConfiguration());
        }
        if (gateway.getWorkloadIdentityDetails() != null) {
            response.set("workloadIdentityDetails", gateway.getWorkloadIdentityDetails());
        }
        if (gateway.getExceptionLevel() != null) {
            response.put("exceptionLevel", gateway.getExceptionLevel());
        }
        return response;
    }

    private ObjectNode toGatewaySummary(Gateway gateway) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("gatewayId", gateway.getGatewayId());
        summary.put("name", gateway.getName());
        summary.put("status", gateway.getStatus());
        if (gateway.getDescription() != null) {
            summary.put("description", gateway.getDescription());
        }
        summary.put("createdAt", gateway.getCreatedAt());
        summary.put("updatedAt", gateway.getUpdatedAt());
        summary.put("authorizerType", gateway.getAuthorizerType());
        if (gateway.getProtocolType() != null) {
            summary.put("protocolType", gateway.getProtocolType());
        }
        return summary;
    }

    private byte[] encodeEvent(String eventType, JsonNode payload) {
        try {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put(":message-type", "event");
            headers.put(":event-type", eventType);
            headers.put(":content-type", "application/json");
            return AwsEventStreamEncoder.encodeMessage(headers, objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Failed to encode event stream: " + e.getMessage(), 500);
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

    private static Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException exception) {
            return Response.status(exception.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", exception.jsonType())
                    .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                    .build();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
