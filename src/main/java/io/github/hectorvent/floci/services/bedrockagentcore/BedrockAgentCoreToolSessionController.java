package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsEventStreamWriter;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Function;

/**
 * AgentCore tool sessions, the data plane of code interpreters and browsers. Paths and status
 * codes follow the service model; the session id travels as a query parameter, except on the
 * invoke operations where it is the {@code x-amzn-code-interpreter-session-id} or
 * {@code x-amzn-browser-session-id} header.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BedrockAgentCoreToolSessionController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreToolSessionController.class);
    private static final String CODE_SESSION_HEADER = "x-amzn-code-interpreter-session-id";
    private static final String BROWSER_SESSION_HEADER = "x-amzn-browser-session-id";
    private static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private final BedrockAgentCoreCodeInterpreterService codeInterpreters;
    private final BedrockAgentCoreBrowserService browsers;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreToolSessionController(BedrockAgentCoreCodeInterpreterService codeInterpreters,
                                                 BedrockAgentCoreBrowserService browsers,
                                                 RegionResolver regionResolver,
                                                 ObjectMapper objectMapper) {
        this.codeInterpreters = codeInterpreters;
        this.browsers = browsers;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── code interpreter sessions ───────────────────────────────

    @PUT
    @Blocking
    @Path("/code-interpreters/{codeInterpreterId}/sessions/start")
    public Response startCodeInterpreterSession(@Context HttpHeaders headers,
                                                @PathParam("codeInterpreterId") String identifier,
                                                String body) {
        return respond("starting code interpreter session", () -> {
            ToolSession session = codeInterpreters.start(identifier, json(body), region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("codeInterpreterIdentifier", session.getIdentifier());
            out.put("sessionId", session.getSessionId());
            out.put("createdAt", Instant.ofEpochMilli(session.getCreatedAtMillis()).toString());
            return Response.ok(out).build();
        });
    }

    @GET
    @Path("/code-interpreters/{codeInterpreterId}/sessions/get")
    public Response getCodeInterpreterSession(@Context HttpHeaders headers,
                                              @PathParam("codeInterpreterId") String identifier,
                                              @QueryParam("sessionId") String sessionId) {
        return respond("getting code interpreter session", () -> Response.ok(
                codeInterpreters.describe(codeInterpreters.get(identifier, sessionId, region(headers)))).build());
    }

    @POST
    @Path("/code-interpreters/{codeInterpreterId}/sessions/list")
    public Response listCodeInterpreterSessions(@Context HttpHeaders headers,
                                                @PathParam("codeInterpreterId") String identifier,
                                                String body) {
        return respond("listing code interpreter sessions", () -> Response.ok(page(
                codeInterpreters.list(identifier, json(body), region(headers)), codeInterpreters::summary)).build());
    }

    @PUT
    @Blocking
    @Path("/code-interpreters/{codeInterpreterId}/sessions/stop")
    public Response stopCodeInterpreterSession(@Context HttpHeaders headers,
                                               @PathParam("codeInterpreterId") String identifier,
                                               @QueryParam("sessionId") String sessionId) {
        return respond("stopping code interpreter session", () -> {
            ToolSession session = codeInterpreters.stop(identifier, sessionId, region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("codeInterpreterIdentifier", session.getIdentifier());
            out.put("sessionId", session.getSessionId());
            out.put("lastUpdatedAt", Instant.ofEpochMilli(session.getLastUpdatedAtMillis()).toString());
            return Response.ok(out).build();
        });
    }

    /**
     * Runs the tool before the response is committed, so request errors are ordinary JSON errors,
     * then streams the outcome as one {@code result} event.
     */
    @POST
    @Blocking
    @Produces(MediaType.WILDCARD)
    @Path("/code-interpreters/{codeInterpreterId}/tools/invoke")
    public Response invokeCodeInterpreter(@Context HttpHeaders headers,
                                          @PathParam("codeInterpreterId") String identifier,
                                          String body) {
        String sessionId = headers.getHeaderString(CODE_SESSION_HEADER);
        return respond("invoking code interpreter", () -> {
            ObjectNode result = codeInterpreters.invoke(identifier, sessionId, json(body), region(headers));
            return Response.ok(streaming(output ->
                            AwsEventStreamWriter.writeEvent(objectMapper, output, "result", result)))
                    .type(EVENT_STREAM)
                    .header(CODE_SESSION_HEADER, sessionId)
                    .build();
        });
    }

    // ── browser sessions ────────────────────────────────────────

    @PUT
    @Blocking
    @Path("/browsers/{browserId}/sessions/start")
    public Response startBrowserSession(@Context HttpHeaders headers,
                                        @PathParam("browserId") String identifier,
                                        String body) {
        return respond("starting browser session", () -> {
            ToolSession session = browsers.start(identifier, json(body), region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("browserIdentifier", session.getIdentifier());
            out.put("sessionId", session.getSessionId());
            out.put("createdAt", Instant.ofEpochMilli(session.getCreatedAtMillis()).toString());
            out.set("streams", browsers.streams(session));
            return Response.ok(out).build();
        });
    }

    @GET
    @Path("/browsers/{browserId}/sessions/get")
    public Response getBrowserSession(@Context HttpHeaders headers,
                                      @PathParam("browserId") String identifier,
                                      @QueryParam("sessionId") String sessionId) {
        return respond("getting browser session", () -> Response.ok(
                browsers.describe(browsers.get(identifier, sessionId, region(headers)))).build());
    }

    @POST
    @Path("/browsers/{browserId}/sessions/list")
    public Response listBrowserSessions(@Context HttpHeaders headers,
                                        @PathParam("browserId") String identifier,
                                        String body) {
        return respond("listing browser sessions", () -> Response.ok(page(
                browsers.list(identifier, json(body), region(headers)), browsers::summary)).build());
    }

    @PUT
    @Blocking
    @Path("/browsers/{browserId}/sessions/stop")
    public Response stopBrowserSession(@Context HttpHeaders headers,
                                       @PathParam("browserId") String identifier,
                                       @QueryParam("sessionId") String sessionId) {
        return respond("stopping browser session", () -> {
            ToolSession session = browsers.stop(identifier, sessionId, region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("browserIdentifier", session.getIdentifier());
            out.put("sessionId", session.getSessionId());
            out.put("lastUpdatedAt", Instant.ofEpochMilli(session.getLastUpdatedAtMillis()).toString());
            return Response.ok(out).build();
        });
    }

    @POST
    @Blocking
    @Path("/browsers/{browserId}/sessions/invoke")
    public Response invokeBrowser(@Context HttpHeaders headers,
                                  @PathParam("browserId") String identifier,
                                  String body) {
        String sessionId = headers.getHeaderString(BROWSER_SESSION_HEADER);
        return respond("invoking browser", () -> {
            ObjectNode out = objectMapper.createObjectNode();
            out.set("result", browsers.invoke(identifier, sessionId, json(body), region(headers)));
            return Response.ok(out).header(BROWSER_SESSION_HEADER, sessionId).build();
        });
    }

    @PUT
    @Path("/browsers/{browserId}/sessions/streams/update")
    public Response updateBrowserStream(@Context HttpHeaders headers,
                                        @PathParam("browserId") String identifier,
                                        @QueryParam("sessionId") String sessionId,
                                        String body) {
        return respond("updating browser stream", () -> {
            ToolSession session = browsers.updateStream(identifier, sessionId, json(body), region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("browserIdentifier", session.getIdentifier());
            out.put("sessionId", session.getSessionId());
            out.set("streams", browsers.streams(session));
            out.put("updatedAt", Instant.ofEpochMilli(session.getLastUpdatedAtMillis()).toString());
            return Response.ok(out).build();
        });
    }

    @PUT
    @Blocking
    @Path("/browser-profiles/{profileId}/save")
    public Response saveBrowserSessionProfile(@Context HttpHeaders headers,
                                              @PathParam("profileId") String profileIdentifier,
                                              String body) {
        return respond("saving browser session profile", () -> {
            JsonNode request = json(body);
            Instant savedAt = browsers.saveProfile(profileIdentifier, request, region(headers));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("profileIdentifier", profileIdentifier);
            out.put("browserIdentifier", request.path("browserIdentifier").asText());
            out.put("sessionId", request.path("sessionId").asText());
            out.put("lastUpdatedAt", savedAt.toString());
            return Response.ok(out).build();
        });
    }

    // ── helpers ──────────────────────────────────────────────────

    private ObjectNode page(PaginatedResult<ToolSession> result, Function<ToolSession, ObjectNode> summary) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("items");
        result.items().forEach(session -> items.add(summary.apply(session)));
        if (result.nextToken() != null) {
            out.put("nextToken", result.nextToken());
        }
        return out;
    }

    /**
     * The explicit return type keeps {@code StreamingOutput} as the entity type; inlining the
     * constructor erases it to {@code Object}. Same helper as the harness controller.
     */
    private static GenericEntity<StreamingOutput> streaming(StreamingOutput stream) {
        return new GenericEntity<>(stream, StreamingOutput.class);
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode json(String body) {
        try {
            JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            if (!request.isObject()) {
                throw new AwsException("ValidationException", "request body must be a JSON object", 400);
            }
            return request;
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON", 400);
        }
    }

    private interface Action {
        Response run();
    }

    private Response respond(String action, Action body) {
        try {
            return body.run();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", e.jsonType())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.errorv(e, "Error {0}", action);
            return Response.status(500)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "InternalServerException")
                    .entity(new AwsErrorResponse("InternalServerException", String.valueOf(e.getMessage())))
                    .build();
        }
    }
}
