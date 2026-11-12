package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The Lex V2 runtime REST API, routed here by {@link LexRouteFilter}: RecognizeText, RecognizeUtterance and the session
 * operations. RecognizeUtterance and PutSession return the bot's speech (or nothing, for a text response) as the body,
 * and the rest of the response in gzip-compressed, base64-encoded {@code x-amz-lex-*} headers, as AWS does.
 */
@Path(LexRouteFilter.RUNTIME_PREFIX + "/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class LexRuntimeController {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/lexv2-runtime-request-shapes.json", "Lex V2 runtime request shapes", LexRouteFilter.MESSAGES);

    private final LexRuntime runtime;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public LexRuntimeController(LexRuntime runtime, RegionResolver regions, ObjectMapper mapper) {
        this.runtime = runtime;
        this.regions = regions;
        this.mapper = mapper;
    }

    private interface Call {
        Response run(ObjectNode in);
    }

    private Response handle(String operation, String body, String botId, String botAliasId, String localeId,
                            String sessionId, Call call) {
        try {
            ObjectNode in = parse(body);
            in.put("botId", botId);
            in.put("botAliasId", botAliasId);
            in.put("localeId", localeId);
            in.put("sessionId", sessionId);
            return call.run(in);
        } catch (AwsException e) {
            return LexRouteFilter.error(mapper, e);
        }
    }

    private ObjectNode checked(String operation, ObjectNode in) {
        ObjectNode copy = in.deepCopy();
        SHAPES.check(operation, copy);
        return in;
    }

    @POST
    @Path("/text")
    public Response recognizeText(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                                  @PathParam("localeId") String localeId, @PathParam("sessionId") String sessionId,
                                  String body) {
        return handle("RecognizeText", body, botId, botAliasId, localeId, sessionId, in -> json(
                runtime.recognizeText(checked("RecognizeText", in), regions.getRegion())));
    }

    @POST
    @Path("/utterance")
    public Response recognizeUtterance(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                                       @PathParam("localeId") String localeId,
                                       @PathParam("sessionId") String sessionId,
                                       @HeaderParam("x-amz-lex-session-state") String sessionState,
                                       @HeaderParam("x-amz-lex-request-attributes") String requestAttributes,
                                       @HeaderParam("Content-Type") String contentType,
                                       @HeaderParam("Response-Content-Type") String responseContentType,
                                       byte[] body) {
        return handle("RecognizeUtterance", null, botId, botAliasId, localeId, sessionId, in -> {
            put(in, "sessionState", sessionState);
            put(in, "requestAttributes", requestAttributes);
            put(in, "requestContentType", contentType);
            put(in, "responseContentType", responseContentType);
            LexRuntime.Spoken spoken = runtime.recognizeUtterance(checked("RecognizeUtterance", in), body,
                    regions.getRegion());
            Response.ResponseBuilder response = Response.ok(spoken.audio()).type(spoken.contentType());
            ObjectNode result = spoken.result();
            response.header("x-amz-lex-input-mode", spoken.inputMode());
            header(response, "x-amz-lex-messages", result.get("messages"));
            header(response, "x-amz-lex-interpretations", result.get("interpretations"));
            header(response, "x-amz-lex-session-state", result.get("sessionState"));
            header(response, "x-amz-lex-request-attributes", result.get("requestAttributes"));
            header(response, "x-amz-lex-recognized-bot-member", result.get("recognizedBotMember"));
            response.header("x-amz-lex-session-id", result.path("sessionId").asText());
            if (spoken.transcript() != null) {
                response.header("x-amz-lex-input-transcript", LexRuntime.encodeHeader(spoken.transcript()));
            }
            return response.build();
        });
    }

    /**
     * StartConversation is served as it streams, by {@link LexConversation}, ahead of this controller; a request that
     * reaches here wasn't signed so it could be.
     */
    @POST
    @Path("/conversation")
    public Response startConversation(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                                      @PathParam("localeId") String localeId,
                                      @PathParam("sessionId") String sessionId) {
        return LexRouteFilter.error(mapper, LexStore.validation("StartConversation is an event stream: send its "
                + "events in a request signed for lex, over HTTP/2 or chunked HTTP/1.1."));
    }

    @GET
    public Response getSession(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                               @PathParam("localeId") String localeId, @PathParam("sessionId") String sessionId) {
        return handle("GetSession", null, botId, botAliasId, localeId, sessionId, in -> json(
                runtime.getSession(checked("GetSession", in), regions.getRegion())));
    }

    @POST
    public Response putSession(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                               @PathParam("localeId") String localeId, @PathParam("sessionId") String sessionId,
                               @HeaderParam("ResponseContentType") String responseContentType, String body) {
        return handle("PutSession", body, botId, botAliasId, localeId, sessionId, in -> {
            put(in, "responseContentType", responseContentType);
            LexRuntime.Spoken spoken = runtime.putSession(checked("PutSession", in), regions.getRegion());
            ObjectNode result = spoken.result();
            Response.ResponseBuilder response = Response.ok(spoken.audio()).type(spoken.contentType());
            header(response, "x-amz-lex-messages", result.get("messages"));
            header(response, "x-amz-lex-session-state", result.get("sessionState"));
            header(response, "x-amz-lex-request-attributes", result.get("requestAttributes"));
            response.header("x-amz-lex-session-id", result.path("sessionId").asText());
            return response.build();
        });
    }

    @DELETE
    public Response deleteSession(@PathParam("botId") String botId, @PathParam("botAliasId") String botAliasId,
                                  @PathParam("localeId") String localeId, @PathParam("sessionId") String sessionId) {
        return handle("DeleteSession", null, botId, botAliasId, localeId, sessionId, in -> json(
                runtime.deleteSession(checked("DeleteSession", in), regions.getRegion())));
    }

    private static void put(ObjectNode in, String name, String value) {
        if (value != null) {
            in.put(name, value);
        }
    }

    private static void header(Response.ResponseBuilder response, String name, JsonNode value) {
        if (value != null && !value.isNull()) {
            response.header(name, LexRuntime.encodeHeader(value.toString()));
        }
    }

    private static Response json(ObjectNode out) {
        return Response.ok(out.toString(), MediaType.APPLICATION_JSON).build();
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw LexStore.validation("The request body must be a JSON object.");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException e) {
            throw LexStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
