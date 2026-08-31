package io.github.hectorvent.floci.services.medicalimaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.medicalimaging.model.Datastore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS HealthImaging restJson1. Public AWS paths are {@code /datastore} and
 * {@code /datastore/{datastoreId}}; {@link MedicalImagingRoutingFilter}
 * prefixes them so they do not collide with S3 path-style routes. Tag APIs
 * share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code medical-imaging}.
 */
@Path(MedicalImagingRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MedicalImagingController {

    private final MedicalImagingService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public MedicalImagingController(
            MedicalImagingService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/datastore")
    public Response createDatastore(@Context HttpHeaders headers, String body) {
        Datastore datastore = service.createDatastore(region(headers), parse(body));
        return Response.ok(service.toCreateResponse(datastore)).build();
    }

    @GET
    @Path("/datastore")
    @Consumes(MediaType.WILDCARD)
    public Response listDatastores(@QueryParam("datastoreStatus") String datastoreStatus) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("datastoreSummaries");
        for (Datastore datastore : service.listDatastores(datastoreStatus)) {
            summaries.add(service.toSummary(datastore));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/datastore/{datastoreId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDatastore(@PathParam("datastoreId") String datastoreId) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("datastoreProperties", service.toProperties(service.getDatastore(datastoreId)));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/datastore/{datastoreId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDatastore(@PathParam("datastoreId") String datastoreId) {
        return Response.ok(service.toDeleteResponse(service.deleteDatastore(datastoreId))).build();
    }

    @POST
    @Path("/startDICOMImportJob/datastore/{datastoreId}")
    public Response startDicomImportJob(@PathParam("datastoreId") String datastoreId, String body) {
        return Response.ok(service.startDicomImportJob(datastoreId, parse(body))).build();
    }

    @GET
    @Path("/getDICOMImportJob/datastore/{datastoreId}/job/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDicomImportJob(
            @PathParam("datastoreId") String datastoreId, @PathParam("jobId") String jobId) {
        return Response.ok(service.getDicomImportJob(datastoreId, jobId)).build();
    }

    @GET
    @Path("/listDICOMImportJobs/datastore/{datastoreId}")
    @Consumes(MediaType.WILDCARD)
    public Response listDicomImportJobs(
            @PathParam("datastoreId") String datastoreId, @QueryParam("jobStatus") String jobStatus) {
        return Response.ok(service.listDicomImportJobs(datastoreId, jobStatus)).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/searchImageSets")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response searchImageSets(@PathParam("datastoreId") String datastoreId, String body) {
        parse(body);
        return Response.ok(service.searchImageSets(datastoreId)).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/getImageSet")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response getImageSet(
            @PathParam("datastoreId") String datastoreId,
            @PathParam("imageSetId") String imageSetId,
            @QueryParam("version") String version) {
        return Response.ok(service.getImageSet(datastoreId, imageSetId, version)).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/getImageSetMetadata")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response getImageSetMetadata(
            @PathParam("datastoreId") String datastoreId,
            @PathParam("imageSetId") String imageSetId,
            @QueryParam("version") String version) {
        byte[] metadata = service.getImageSetMetadata(datastoreId, imageSetId, version);
        return Response.ok(metadata).type("application/json").build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/getImageFrame")
    public Response getImageFrame(
            @PathParam("datastoreId") String datastoreId,
            @PathParam("imageSetId") String imageSetId,
            String body) {
        byte[] frame = service.getImageFrame(datastoreId, imageSetId, parse(body));
        return Response.ok(frame).type(MediaType.APPLICATION_OCTET_STREAM).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/listImageSetVersions")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response listImageSetVersions(
            @PathParam("datastoreId") String datastoreId, @PathParam("imageSetId") String imageSetId) {
        return Response.ok(service.listImageSetVersions(datastoreId, imageSetId)).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/updateImageSetMetadata")
    public Response updateImageSetMetadata(
            @PathParam("datastoreId") String datastoreId,
            @PathParam("imageSetId") String imageSetId,
            @QueryParam("latestVersion") String latestVersion,
            String body) {
        return Response.ok(service.updateImageSetMetadata(datastoreId, imageSetId, latestVersion, parse(body)))
                .build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{sourceImageSetId}/copyImageSet")
    public Response copyImageSet(
            @PathParam("datastoreId") String datastoreId,
            @PathParam("sourceImageSetId") String sourceImageSetId,
            String body) {
        return Response.ok(service.copyImageSet(datastoreId, sourceImageSetId, parse(body))).build();
    }

    @POST
    @Path("/datastore/{datastoreId}/imageSet/{imageSetId}/deleteImageSet")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response deleteImageSet(
            @PathParam("datastoreId") String datastoreId, @PathParam("imageSetId") String imageSetId) {
        return Response.ok(service.deleteImageSet(datastoreId, imageSetId)).build();
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
