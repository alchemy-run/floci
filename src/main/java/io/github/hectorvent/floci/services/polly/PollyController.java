package io.github.hectorvent.floci.services.polly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Polly restJson1. Public AWS paths are {@code /v1/lexicons},
 * {@code /v1/voices}, {@code /v1/speech} and {@code /v1/synthesisTasks};
 * {@link PollyRoutingFilter} prefixes them so they do not collide with S3
 * path-style routes. Requests are signed as {@code polly}.
 */
@Path(PollyRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PollyController {

    private final PollyService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public PollyController(
            PollyService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @PUT
    @Path("/v1/lexicons/{name}")
    public Response putLexicon(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        service.putLexicon(region(headers), name, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/v1/lexicons/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getLexicon(@Context HttpHeaders headers, @PathParam("name") String name) {
        return Response.ok(service.getLexicon(region(headers), name)).build();
    }

    @GET
    @Path("/v1/lexicons")
    @Consumes(MediaType.WILDCARD)
    public Response listLexicons(
            @Context HttpHeaders headers, @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.listLexicons(region(headers), nextToken)).build();
    }

    @DELETE
    @Path("/v1/lexicons/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLexicon(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deleteLexicon(region(headers), name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/v1/voices")
    @Consumes(MediaType.WILDCARD)
    public Response describeVoices(
            @QueryParam("Engine") String engine,
            @QueryParam("LanguageCode") String languageCode,
            @QueryParam("IncludeAdditionalLanguageCodes") Boolean includeAdditional) {
        return Response.ok(service.describeVoices(engine, languageCode, includeAdditional)).build();
    }

    @POST
    @Path("/v1/speech")
    public Response synthesizeSpeech(String body) {
        PollyService.SynthesisResult result = service.synthesizeSpeech(parse(body));
        return Response.ok(result.audio())
                .type(result.contentType())
                .header("x-amzn-RequestCharacters", String.valueOf(result.requestCharacters()))
                .build();
    }

    @POST
    @Path("/v1/synthesisTasks")
    public Response startSpeechSynthesisTask(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startSpeechSynthesisTask(region(headers), parse(body))).build();
    }

    @GET
    @Path("/v1/synthesisTasks/{taskId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSpeechSynthesisTask(@PathParam("taskId") String taskId) {
        return Response.ok(service.getSpeechSynthesisTask(taskId)).build();
    }

    @GET
    @Path("/v1/synthesisTasks")
    @Consumes(MediaType.WILDCARD)
    public Response listSpeechSynthesisTasks(
            @QueryParam("Status") String status,
            @QueryParam("MaxResults") Integer maxResults,
            @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.listSpeechSynthesisTasks(status, maxResults, nextToken)).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
}
