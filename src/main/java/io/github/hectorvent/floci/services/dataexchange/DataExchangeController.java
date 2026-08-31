package io.github.hectorvent.floci.services.dataexchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dataexchange.model.Asset;
import io.github.hectorvent.floci.services.dataexchange.model.DataSet;
import io.github.hectorvent.floci.services.dataexchange.model.EventAction;
import io.github.hectorvent.floci.services.dataexchange.model.Job;
import io.github.hectorvent.floci.services.dataexchange.model.Revision;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
 * AWS Data Exchange restJson1. Public AWS paths are {@code /v1/data-sets},
 * {@code /v1/jobs}, {@code /v1/event-actions} and peers;
 * {@link DataExchangeRoutingFilter} prefixes them so they do not collide with
 * S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are dispatched
 * by {@code SharedTagsController}. Requests are signed as {@code dataexchange}.
 */
@Path(DataExchangeRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataExchangeController {

    private final DataExchangeService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DataExchangeController(
            DataExchangeService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v1/data-sets")
    public Response createDataSet(@Context HttpHeaders headers, String body) {
        DataSet dataSet = service.createDataSet(region(headers), parse(body));
        return Response.ok(service.toDataSet(dataSet)).build();
    }

    @GET
    @Path("/v1/data-sets")
    @Consumes(MediaType.WILDCARD)
    public Response listDataSets(
            @Context HttpHeaders headers, @QueryParam("origin") String origin) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DataSets");
        for (DataSet dataSet : service.listDataSets(region(headers), origin)) {
            list.add(service.toDataSet(dataSet));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDataSet(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId) {
        return Response.ok(service.toDataSet(service.getDataSet(region(headers), dataSetId))).build();
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}")
    public Response updateDataSet(
            @Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId, String body) {
        DataSet dataSet = service.updateDataSet(region(headers), dataSetId, parse(body));
        return Response.ok(service.toDataSet(dataSet)).build();
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataSet(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId) {
        service.deleteDataSet(region(headers), dataSetId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/data-sets/{dataSetId}/revisions")
    public Response createRevision(
            @Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId, String body) {
        Revision revision = service.createRevision(region(headers), dataSetId, parse(body));
        return Response.ok(service.toRevision(revision)).build();
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions")
    @Consumes(MediaType.WILDCARD)
    public Response listRevisions(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Revisions");
        for (Revision revision : service.listRevisions(region(headers), dataSetId)) {
            list.add(service.toRevision(revision));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    @Consumes(MediaType.WILDCARD)
    public Response getRevision(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId) {
        return Response.ok(service.toRevision(service.getRevision(region(headers), dataSetId, revisionId))).build();
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    public Response updateRevision(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId,
            String body) {
        Revision revision = service.updateRevision(region(headers), dataSetId, revisionId, parse(body));
        return Response.ok(service.toRevision(revision)).build();
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRevision(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId) {
        service.deleteRevision(region(headers), dataSetId, revisionId);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets")
    @Consumes(MediaType.WILDCARD)
    public Response listAssets(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Assets");
        for (Asset asset : service.listAssets(region(headers), dataSetId, revisionId)) {
            list.add(service.toAsset(asset));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getAsset(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId,
            @PathParam("assetId") String assetId) {
        return Response.ok(service.toAsset(
                service.getAsset(region(headers), dataSetId, revisionId, assetId))).build();
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    public Response updateAsset(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId,
            @PathParam("assetId") String assetId,
            String body) {
        Asset asset = service.updateAsset(region(headers), dataSetId, revisionId, assetId, parse(body));
        return Response.ok(service.toAsset(asset)).build();
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAsset(
            @Context HttpHeaders headers,
            @PathParam("dataSetId") String dataSetId,
            @PathParam("revisionId") String revisionId,
            @PathParam("assetId") String assetId) {
        service.deleteAsset(region(headers), dataSetId, revisionId, assetId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/data-sets/{dataSetId}/notification")
    public Response sendNotification(
            @Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId, String body) {
        service.sendDataSetNotification(region(headers), dataSetId, parse(body));
        return Response.accepted().build();
    }

    @POST
    @Path("/v1/jobs")
    public Response createJob(@Context HttpHeaders headers, String body) {
        Job job = service.createJob(region(headers), parse(body));
        return Response.ok(service.toJob(job)).build();
    }

    @GET
    @Path("/v1/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @QueryParam("dataSetId") String dataSetId,
            @QueryParam("revisionId") String revisionId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Jobs");
        for (Job job : service.listJobs(region(headers), dataSetId, revisionId)) {
            list.add(service.toJob(job));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        return Response.ok(service.toJob(service.getJob(region(headers), jobId))).build();
    }

    @PATCH
    @Path("/v1/jobs/{jobId}")
    public Response startJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId, String body) {
        service.startJob(region(headers), jobId);
        return Response.accepted().build();
    }

    @DELETE
    @Path("/v1/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        service.cancelJob(region(headers), jobId);
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/event-actions")
    public Response createEventAction(@Context HttpHeaders headers, String body) {
        EventAction eventAction = service.createEventAction(region(headers), parse(body));
        return Response.ok(service.toEventAction(eventAction)).build();
    }

    @GET
    @Path("/v1/event-actions")
    @Consumes(MediaType.WILDCARD)
    public Response listEventActions(
            @Context HttpHeaders headers, @QueryParam("eventSourceId") String eventSourceId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("EventActions");
        for (EventAction eventAction : service.listEventActions(region(headers), eventSourceId)) {
            list.add(service.toEventAction(eventAction));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/event-actions/{eventActionId}")
    @Consumes(MediaType.WILDCARD)
    public Response getEventAction(
            @Context HttpHeaders headers, @PathParam("eventActionId") String eventActionId) {
        return Response.ok(service.toEventAction(service.getEventAction(region(headers), eventActionId))).build();
    }

    @PATCH
    @Path("/v1/event-actions/{eventActionId}")
    public Response updateEventAction(
            @Context HttpHeaders headers, @PathParam("eventActionId") String eventActionId, String body) {
        EventAction eventAction = service.updateEventAction(region(headers), eventActionId, parse(body));
        return Response.ok(service.toEventAction(eventAction)).build();
    }

    @DELETE
    @Path("/v1/event-actions/{eventActionId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEventAction(
            @Context HttpHeaders headers, @PathParam("eventActionId") String eventActionId) {
        service.deleteEventAction(region(headers), eventActionId);
        return Response.noContent().build();
    }

    @GET
    @Path("/v1/data-grants")
    @Consumes(MediaType.WILDCARD)
    public Response listDataGrants() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DataGrantSummaries");
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/received-data-grants")
    @Consumes(MediaType.WILDCARD)
    public Response listReceivedDataGrants() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DataGrantSummaries");
        return Response.ok(response).build();
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
