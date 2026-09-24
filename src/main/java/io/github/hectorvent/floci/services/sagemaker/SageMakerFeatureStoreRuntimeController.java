package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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

import java.util.List;

/**
 * SageMaker Feature Store Runtime (REST-JSON, signed as {@code sagemaker}); AWS serves it at
 * {@code featurestore-runtime.sagemaker.<region>.amazonaws.com}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SageMakerFeatureStoreRuntimeController {
    private final SageMakerFeatureStoreService service;
    private final RegionResolver regionResolver;
    private final ObjectReader strictReader;

    @Inject
    public SageMakerFeatureStoreRuntimeController(SageMakerFeatureStoreService service, RegionResolver regionResolver,
                                                  ObjectMapper mapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.strictReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PUT
    @Path("/FeatureGroup/{FeatureGroupName: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response putRecord(@PathParam("FeatureGroupName") String featureGroupName,
                              @Context HttpHeaders headers, String body) {
        return ok(service.putRecord(featureGroupName, parse(body), region(headers)));
    }

    @GET
    @Path("/FeatureGroup/{FeatureGroupName: .+}")
    public Response getRecord(@PathParam("FeatureGroupName") String featureGroupName,
                              @QueryParam("RecordIdentifierValueAsString") String recordIdentifier,
                              @QueryParam("FeatureName") List<String> featureNames,
                              @QueryParam("ExpirationTimeResponse") String expirationTimeResponse,
                              @Context HttpHeaders headers) {
        return ok(service.getRecord(featureGroupName, recordIdentifier, featureNames, expirationTimeResponse,
                region(headers)));
    }

    @DELETE
    @Path("/FeatureGroup/{FeatureGroupName: .+}")
    public Response deleteRecord(@PathParam("FeatureGroupName") String featureGroupName,
                                 @QueryParam("RecordIdentifierValueAsString") String recordIdentifier,
                                 @QueryParam("EventTime") String eventTime,
                                 @QueryParam("TargetStores") List<String> targetStores,
                                 @QueryParam("DeletionMode") String deletionMode,
                                 @Context HttpHeaders headers) {
        return ok(service.deleteRecord(featureGroupName, recordIdentifier, eventTime, targetStores, deletionMode,
                region(headers)));
    }

    @POST
    @Path("/FeatureGroup/{FeatureGroupName: .+}/ListRecords")
    @Consumes(MediaType.WILDCARD)
    public Response listRecords(@PathParam("FeatureGroupName") String featureGroupName,
                                @Context HttpHeaders headers, String body) {
        return ok(service.listRecords(featureGroupName, parse(body), region(headers)));
    }

    @POST
    @Path("/BatchGetRecord")
    @Consumes(MediaType.WILDCARD)
    public Response batchGetRecord(@Context HttpHeaders headers, String body) {
        return ok(service.batchGetRecord(parse(body), region(headers)));
    }

    @POST
    @Path("/BatchWriteRecord")
    @Consumes(MediaType.WILDCARD)
    public Response batchWriteRecord(@Context HttpHeaders headers, String body) {
        return ok(service.batchWriteRecord(parse(body), region(headers)));
    }

    private JsonNode parse(String body) {
        try {
            JsonNode value = strictReader.readTree(body == null || body.isBlank() ? "{}" : body);
            if (value == null || !value.isObject()) {
                throw SageMakerFeatureStoreService.runtimeValidation("Request body must be a JSON object.");
            }
            return value;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw SageMakerFeatureStoreService.runtimeValidation("Request body is not valid JSON.");
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static Response ok(JsonNode node) {
        return Response.ok(node).type(MediaType.APPLICATION_JSON).build();
    }
}
