package io.github.hectorvent.floci.services.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Personalize Runtime restJson1 ({@code personalize-runtime}).
 * Signed as {@code personalize}. Paths are rewritten by {@link PersonalizeRoutingFilter}.
 */
@Path("/personalize-runtime")
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
public class PersonalizeRuntimeController {

    private final PersonalizeService service;
    private final ObjectMapper objectMapper;

    @Inject
    public PersonalizeRuntimeController(PersonalizeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/recommendations")
    public Response getRecommendations(String body) {
        return handle(body, service::getRecommendations);
    }

    @POST
    @Path("/personalize-ranking")
    public Response getPersonalizedRanking(String body) {
        return handle(body, service::getPersonalizedRanking);
    }

    @POST
    @Path("/action-recommendations")
    public Response getActionRecommendations(String body) {
        return handle(body, service::getActionRecommendations);
    }

    private Response handle(String body, Handler handler) {
        try {
            return Response.ok(handler.handle(parse(body))).build();
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
                throw new AwsException("InvalidInputException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidInputException", "Request body is not valid JSON.", 400);
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
        Object handle(JsonNode request);
    }
}
