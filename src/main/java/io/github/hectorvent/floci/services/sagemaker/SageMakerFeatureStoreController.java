package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Amazon SageMaker Feature Store Runtime restJson1
 * ({@code sagemaker-featurestore-runtime}). Signed as {@code sagemaker}.
 * Paths are rewritten by {@link SageMakerFeatureStoreRoutingFilter}.
 */
@Path(SageMakerFeatureStoreRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
public class SageMakerFeatureStoreController {

    private final SageMakerService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SageMakerFeatureStoreController(SageMakerService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/FeatureGroup/{featureGroupName}")
    public Response putRecord(@PathParam("featureGroupName") String featureGroupName, String body) {
        return handle(() -> service.putRecord(featureGroupName, parse(body)));
    }

    @GET
    @Path("/FeatureGroup/{featureGroupName}")
    public Response getRecord(
            @PathParam("featureGroupName") String featureGroupName,
            @QueryParam("RecordIdentifierValueAsString") String identifier,
            @QueryParam("FeatureName") List<String> featureNames) {
        return handle(() -> service.getRecord(featureGroupName, identifier, featureNames));
    }

    @DELETE
    @Path("/FeatureGroup/{featureGroupName}")
    public Response deleteRecord(
            @PathParam("featureGroupName") String featureGroupName,
            @QueryParam("RecordIdentifierValueAsString") String identifier,
            @QueryParam("DeletionMode") String deletionMode) {
        return handle(() -> service.deleteRecord(featureGroupName, identifier, deletionMode));
    }

    @POST
    @Path("/FeatureGroup/{featureGroupName}/ListRecords")
    public Response listRecords(@PathParam("featureGroupName") String featureGroupName, String body) {
        return handle(() -> service.listRecords(featureGroupName, parse(body)));
    }

    @POST
    @Path("/BatchGetRecord")
    public Response batchGetRecord(String body) {
        return handle(() -> service.batchGetRecord(parse(body)));
    }

    @POST
    @Path("/BatchWriteRecord")
    public Response batchWriteRecord(String body) {
        return handle(() -> service.batchWriteRecord(parse(body)));
    }

    private Response handle(Handler handler) {
        try {
            return Response.ok(handler.handle()).build();
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
            if (request == null || request.isNull() || request.isMissingNode()) {
                return objectMapper.createObjectNode();
            }
            return request;
        } catch (Exception e) {
            throw new AwsException("ValidationError", "Request body is not valid JSON.", 400);
        }
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("Message", exception.getMessage());
        node.put("message", exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Object handle();
    }
}
