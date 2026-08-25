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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Lex Model Building V2 restJson1 ({@code models-v2-lex}). Signed as {@code lex}.
 */
@Path("/lex-v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LexModelsV2Controller {

    private final LexV2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public LexModelsV2Controller(LexV2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/bots")
    public Response createBot(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.createBot(regionResolver.resolveRegion(headers), request)).build());
    }

    @POST
    @Path("/bots")
    @Consumes(MediaType.WILDCARD)
    public Response listBots(@Context HttpHeaders headers, String body) {
        return handle(body, request -> Response.ok(
                service.listBots(regionResolver.resolveRegion(headers), request)).build());
    }

    @GET
    @Path("/bots/{botId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBot(@Context HttpHeaders headers, @PathParam("botId") String botId) {
        return handle(() -> Response.ok(
                service.describeBot(regionResolver.resolveRegion(headers), botId)).build());
    }

    @PUT
    @Path("/bots/{botId}")
    public Response updateBot(@Context HttpHeaders headers, @PathParam("botId") String botId, String body) {
        return handle(body, request -> Response.ok(
                service.updateBot(regionResolver.resolveRegion(headers), botId, request)).build());
    }

    @DELETE
    @Path("/bots/{botId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBot(@Context HttpHeaders headers, @PathParam("botId") String botId) {
        return handle(() -> Response.ok(
                service.deleteBot(regionResolver.resolveRegion(headers), botId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales")
    public Response createBotLocale(@Context HttpHeaders headers,
                                    @PathParam("botId") String botId,
                                    @PathParam("botVersion") String botVersion,
                                    String body) {
        return handle(body, request -> Response.ok(
                service.createBotLocale(regionResolver.resolveRegion(headers), botId, botVersion, request)).build());
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBotLocale(@Context HttpHeaders headers,
                                      @PathParam("botId") String botId,
                                      @PathParam("botVersion") String botVersion,
                                      @PathParam("localeId") String localeId) {
        return handle(() -> Response.ok(
                service.describeBotLocale(regionResolver.resolveRegion(headers), botId, botVersion, localeId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    public Response updateBotLocale(@Context HttpHeaders headers,
                                    @PathParam("botId") String botId,
                                    @PathParam("botVersion") String botVersion,
                                    @PathParam("localeId") String localeId,
                                    String body) {
        return handle(body, request -> Response.ok(
                service.updateBotLocale(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, request)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBotLocale(@Context HttpHeaders headers,
                                    @PathParam("botId") String botId,
                                    @PathParam("botVersion") String botVersion,
                                    @PathParam("localeId") String localeId) {
        return handle(() -> Response.ok(
                service.deleteBotLocale(regionResolver.resolveRegion(headers), botId, botVersion, localeId)).build());
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}")
    @Consumes(MediaType.WILDCARD)
    public Response buildBotLocale(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botVersion") String botVersion,
                                   @PathParam("localeId") String localeId) {
        return handle(() -> Response.ok(
                service.buildBotLocale(regionResolver.resolveRegion(headers), botId, botVersion, localeId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents")
    public Response createIntent(@Context HttpHeaders headers,
                                 @PathParam("botId") String botId,
                                 @PathParam("botVersion") String botVersion,
                                 @PathParam("localeId") String localeId,
                                 String body) {
        return handle(body, request -> Response.ok(
                service.createIntent(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, request)).build());
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents")
    @Consumes(MediaType.WILDCARD)
    public Response listIntents(@Context HttpHeaders headers,
                                @PathParam("botId") String botId,
                                @PathParam("botVersion") String botVersion,
                                @PathParam("localeId") String localeId,
                                String body) {
        return handle(body, request -> Response.ok(
                service.listIntents(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, request)).build());
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeIntent(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botVersion") String botVersion,
                                   @PathParam("localeId") String localeId,
                                   @PathParam("intentId") String intentId) {
        return handle(() -> Response.ok(
                service.describeIntent(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, intentId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    public Response updateIntent(@Context HttpHeaders headers,
                                 @PathParam("botId") String botId,
                                 @PathParam("botVersion") String botVersion,
                                 @PathParam("localeId") String localeId,
                                 @PathParam("intentId") String intentId,
                                 String body) {
        return handle(body, request -> Response.ok(
                service.updateIntent(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, intentId, request)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/intents/{intentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteIntent(@Context HttpHeaders headers,
                                 @PathParam("botId") String botId,
                                 @PathParam("botVersion") String botVersion,
                                 @PathParam("localeId") String localeId,
                                 @PathParam("intentId") String intentId) {
        return handle(() -> {
            service.deleteIntent(regionResolver.resolveRegion(headers),
                    botId, botVersion, localeId, intentId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes")
    public Response createSlotType(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botVersion") String botVersion,
                                   @PathParam("localeId") String localeId,
                                   String body) {
        return handle(body, request -> Response.ok(
                service.createSlotType(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, request)).build());
    }

    @POST
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes")
    @Consumes(MediaType.WILDCARD)
    public Response listSlotTypes(@Context HttpHeaders headers,
                                  @PathParam("botId") String botId,
                                  @PathParam("botVersion") String botVersion,
                                  @PathParam("localeId") String localeId,
                                  String body) {
        return handle(body, request -> Response.ok(
                service.listSlotTypes(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, request)).build());
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeSlotType(@Context HttpHeaders headers,
                                     @PathParam("botId") String botId,
                                     @PathParam("botVersion") String botVersion,
                                     @PathParam("localeId") String localeId,
                                     @PathParam("slotTypeId") String slotTypeId) {
        return handle(() -> Response.ok(
                service.describeSlotType(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, slotTypeId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    public Response updateSlotType(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botVersion") String botVersion,
                                   @PathParam("localeId") String localeId,
                                   @PathParam("slotTypeId") String slotTypeId,
                                   String body) {
        return handle(body, request -> Response.ok(
                service.updateSlotType(regionResolver.resolveRegion(headers),
                        botId, botVersion, localeId, slotTypeId, request)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}/botlocales/{localeId}/slottypes/{slotTypeId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSlotType(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botVersion") String botVersion,
                                   @PathParam("localeId") String localeId,
                                   @PathParam("slotTypeId") String slotTypeId) {
        return handle(() -> {
            service.deleteSlotType(regionResolver.resolveRegion(headers),
                    botId, botVersion, localeId, slotTypeId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @PUT
    @Path("/bots/{botId}/botversions")
    public Response createBotVersion(@Context HttpHeaders headers,
                                     @PathParam("botId") String botId,
                                     String body) {
        return handle(body, request -> Response.ok(
                service.createBotVersion(regionResolver.resolveRegion(headers), botId, request)).build());
    }

    @GET
    @Path("/bots/{botId}/botversions/{botVersion}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBotVersion(@Context HttpHeaders headers,
                                       @PathParam("botId") String botId,
                                       @PathParam("botVersion") String botVersion) {
        return handle(() -> Response.ok(
                service.describeBotVersion(regionResolver.resolveRegion(headers), botId, botVersion)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botversions/{botVersion}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBotVersion(@Context HttpHeaders headers,
                                     @PathParam("botId") String botId,
                                     @PathParam("botVersion") String botVersion) {
        return handle(() -> Response.ok(
                service.deleteBotVersion(regionResolver.resolveRegion(headers), botId, botVersion)).build());
    }

    @PUT
    @Path("/bots/{botId}/botaliases")
    public Response createBotAlias(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   String body) {
        return handle(body, request -> Response.ok(
                service.createBotAlias(regionResolver.resolveRegion(headers), botId, request)).build());
    }

    @POST
    @Path("/bots/{botId}/botaliases")
    @Consumes(MediaType.WILDCARD)
    public Response listBotAliases(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   String body) {
        return handle(body, request -> Response.ok(
                service.listBotAliases(regionResolver.resolveRegion(headers), botId, request)).build());
    }

    @GET
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBotAlias(@Context HttpHeaders headers,
                                     @PathParam("botId") String botId,
                                     @PathParam("botAliasId") String botAliasId) {
        return handle(() -> Response.ok(
                service.describeBotAlias(regionResolver.resolveRegion(headers), botId, botAliasId)).build());
    }

    @PUT
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    public Response updateBotAlias(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botAliasId") String botAliasId,
                                   String body) {
        return handle(body, request -> Response.ok(
                service.updateBotAlias(regionResolver.resolveRegion(headers), botId, botAliasId, request)).build());
    }

    @DELETE
    @Path("/bots/{botId}/botaliases/{botAliasId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBotAlias(@Context HttpHeaders headers,
                                   @PathParam("botId") String botId,
                                   @PathParam("botAliasId") String botAliasId) {
        return handle(() -> Response.ok(
                service.deleteBotAlias(regionResolver.resolveRegion(headers), botId, botAliasId)).build());
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
