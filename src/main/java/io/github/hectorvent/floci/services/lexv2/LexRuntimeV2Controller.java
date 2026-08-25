package io.github.hectorvent.floci.services.lexv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;

/**
 * Amazon Lex Runtime V2 restJson1 ({@code runtime-v2-lex}). Signed as {@code lex}.
 */
@Path("/lex-v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LexRuntimeV2Controller {

    private final LexV2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public LexRuntimeV2Controller(LexV2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}/text")
    public Response recognizeText(@Context HttpHeaders headers,
                                  @PathParam("botId") String botId,
                                  @PathParam("botAliasId") String botAliasId,
                                  @PathParam("localeId") String localeId,
                                  @PathParam("sessionId") String sessionId,
                                  String body) {
        return handle(body, request -> Response.ok(
                service.recognizeText(regionResolver.resolveRegion(headers),
                        botId, botAliasId, localeId, sessionId, request)).build());
    }

    @POST
    @Path("/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}/utterance")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response recognizeUtterance(@Context HttpHeaders headers,
                                       @PathParam("botId") String botId,
                                       @PathParam("botAliasId") String botAliasId,
                                       @PathParam("localeId") String localeId,
                                       @PathParam("sessionId") String sessionId,
                                       @HeaderParam("Response-Content-Type") String responseContentType,
                                       byte[] body) {
        try {
            String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            ObjectNode recognized = service.recognize(regionResolver.resolveRegion(headers),
                    botId, botAliasId, localeId, sessionId, text, null);
            String contentType = responseContentType == null || responseContentType.isBlank()
                    ? "text/plain; charset=utf-8"
                    : responseContentType;
            return Response.ok()
                    .type(contentType)
                    .header("Content-Type", contentType)
                    .header("x-amz-lex-session-id", sessionId)
                    .header("x-amz-lex-input-mode", "Text")
                    .header("x-amz-lex-input-transcript", text)
                    .header("x-amz-lex-session-state", json(recognized.get("sessionState")))
                    .header("x-amz-lex-messages", json(recognized.get("messages")))
                    .header("x-amz-lex-interpretations", json(recognized.get("interpretations")))
                    .entity(new byte[0])
                    .build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}")
    public Response putSession(@Context HttpHeaders headers,
                               @PathParam("botId") String botId,
                               @PathParam("botAliasId") String botAliasId,
                               @PathParam("localeId") String localeId,
                               @PathParam("sessionId") String sessionId,
                               @HeaderParam("ResponseContentType") String responseContentType,
                               String body) {
        try {
            JsonNode request = parse(body);
            ObjectNode put = service.putSession(regionResolver.resolveRegion(headers),
                    botId, botAliasId, localeId, sessionId, request);
            String contentType = responseContentType == null || responseContentType.isBlank()
                    ? "text/plain; charset=utf-8"
                    : responseContentType;
            return Response.ok()
                    .type(contentType)
                    .header("Content-Type", contentType)
                    .header("x-amz-lex-session-id", put.path("sessionId").asText(sessionId))
                    .entity(new byte[0])
                    .build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSession(@Context HttpHeaders headers,
                               @PathParam("botId") String botId,
                               @PathParam("botAliasId") String botAliasId,
                               @PathParam("localeId") String localeId,
                               @PathParam("sessionId") String sessionId) {
        return handle(() -> Response.ok(
                service.getSession(regionResolver.resolveRegion(headers),
                        botId, botAliasId, localeId, sessionId)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botAliases/{botAliasId}/botLocales/{localeId}/sessions/{sessionId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSession(@Context HttpHeaders headers,
                                  @PathParam("botId") String botId,
                                  @PathParam("botAliasId") String botAliasId,
                                  @PathParam("localeId") String localeId,
                                  @PathParam("sessionId") String sessionId) {
        return handle(() -> Response.ok(
                service.deleteSession(regionResolver.resolveRegion(headers),
                        botId, botAliasId, localeId, sessionId)).build());
    }

    private String json(JsonNode node) {
        try {
            return node == null || node.isNull() ? "[]" : objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response handle(Action action) {
        try {
            return action.run();
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

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }

    @FunctionalInterface
    private interface Action {
        Response run();
    }
}
