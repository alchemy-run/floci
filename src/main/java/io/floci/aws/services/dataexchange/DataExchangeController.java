package io.floci.aws.services.dataexchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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
import jakarta.ws.rs.core.UriInfo;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The AWS Data Exchange REST API, routed here by {@link DataExchangeRouteFilter}. */
@Path(DataExchangeRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DataExchangeController {
    private static final String HEADER_PREFIX = "x-amzn-dataexchange-header-";

    private final DataExchangeService service;
    private final DataExchangeJobs jobs;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public DataExchangeController(DataExchangeService service, DataExchangeJobs jobs, RegionResolver regions,
                                  ObjectMapper mapper) {
        this.service = service;
        this.jobs = jobs;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---- data sets

    @POST
    @Path("/v1/data-sets")
    public Response createDataSet(@Context HttpHeaders headers, String body) {
        return status(201, service.createDataSet(parse(body), region(headers)));
    }

    @GET
    @Path("/v1/data-sets")
    public Response listDataSets(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listDataSets(query(uri), region(headers)));
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}")
    public Response getDataSet(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId) {
        return ok(service.getDataSet(dataSetId, region(headers)));
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}")
    public Response updateDataSet(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                  String body) {
        return ok(service.updateDataSet(dataSetId, parse(body), region(headers)));
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}")
    public Response deleteDataSet(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId) {
        service.deleteDataSet(dataSetId, region(headers));
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/data-sets/{dataSetId}/notification")
    public Response sendDataSetNotification(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                            String body) {
        service.sendDataSetNotification(dataSetId, parse(body), region(headers));
        return status(202, mapper.createObjectNode());
    }

    // ---- revisions

    @POST
    @Path("/v1/data-sets/{dataSetId}/revisions")
    public Response createRevision(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                   String body) {
        return status(201, service.createRevision(dataSetId, parse(body), region(headers)));
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions")
    public Response listDataSetRevisions(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                         @Context UriInfo uri) {
        return ok(service.listDataSetRevisions(dataSetId, query(uri), region(headers)));
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    public Response getRevision(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                @PathParam("revisionId") String revisionId) {
        return ok(service.getRevision(dataSetId, revisionId, region(headers)));
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    public Response updateRevision(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                   @PathParam("revisionId") String revisionId, String body) {
        return ok(service.updateRevision(dataSetId, revisionId, parse(body), region(headers)));
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}")
    public Response deleteRevision(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                   @PathParam("revisionId") String revisionId) {
        service.deleteRevision(dataSetId, revisionId, region(headers));
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/revoke")
    public Response revokeRevision(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                   @PathParam("revisionId") String revisionId, String body) {
        return ok(service.revokeRevision(dataSetId, revisionId, parse(body), region(headers)));
    }

    // ---- assets

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets")
    public Response listRevisionAssets(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                       @PathParam("revisionId") String revisionId, @Context UriInfo uri) {
        return ok(service.listRevisionAssets(dataSetId, revisionId, query(uri), region(headers)));
    }

    @GET
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    public Response getAsset(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                             @PathParam("revisionId") String revisionId, @PathParam("assetId") String assetId) {
        return ok(service.getAsset(dataSetId, revisionId, assetId, region(headers)));
    }

    @PATCH
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    public Response updateAsset(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                @PathParam("revisionId") String revisionId, @PathParam("assetId") String assetId,
                                String body) {
        return ok(service.updateAsset(dataSetId, revisionId, assetId, parse(body), region(headers)));
    }

    @DELETE
    @Path("/v1/data-sets/{dataSetId}/revisions/{revisionId}/assets/{assetId}")
    public Response deleteAsset(@Context HttpHeaders headers, @PathParam("dataSetId") String dataSetId,
                                @PathParam("revisionId") String revisionId, @PathParam("assetId") String assetId) {
        service.deleteAsset(dataSetId, revisionId, assetId, region(headers));
        return Response.noContent().build();
    }

    // ---- jobs

    @POST
    @Path("/v1/jobs")
    public Response createJob(@Context HttpHeaders headers, String body) {
        return status(201, jobs.createJob(parse(body), region(headers)));
    }

    @GET
    @Path("/v1/jobs")
    public Response listJobs(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(jobs.listJobs(query(uri), region(headers)));
    }

    @GET
    @Path("/v1/jobs/{jobId}")
    public Response getJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        return ok(jobs.getJob(jobId, region(headers)));
    }

    @PATCH
    @Path("/v1/jobs/{jobId}")
    public Response startJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        jobs.startJob(jobId, region(headers));
        return status(202, mapper.createObjectNode());
    }

    @DELETE
    @Path("/v1/jobs/{jobId}")
    public Response cancelJob(@Context HttpHeaders headers, @PathParam("jobId") String jobId) {
        jobs.cancelJob(jobId, region(headers));
        return Response.noContent().build();
    }

    // ---- event actions

    @POST
    @Path("/v1/event-actions")
    public Response createEventAction(@Context HttpHeaders headers, String body) {
        return status(201, service.createEventAction(parse(body), region(headers)));
    }

    @GET
    @Path("/v1/event-actions")
    public Response listEventActions(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listEventActions(query(uri), region(headers)));
    }

    @GET
    @Path("/v1/event-actions/{id}")
    public Response getEventAction(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getEventAction(id, region(headers)));
    }

    @PATCH
    @Path("/v1/event-actions/{id}")
    public Response updateEventAction(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        return ok(service.updateEventAction(id, parse(body), region(headers)));
    }

    @DELETE
    @Path("/v1/event-actions/{id}")
    public Response deleteEventAction(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteEventAction(id, region(headers));
        return Response.noContent().build();
    }

    // ---- data grants

    @POST
    @Path("/v1/data-grants")
    public Response createDataGrant(@Context HttpHeaders headers, String body) {
        return status(201, service.createDataGrant(parse(body), region(headers)));
    }

    @GET
    @Path("/v1/data-grants")
    public Response listDataGrants(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listDataGrants(query(uri), region(headers)));
    }

    @GET
    @Path("/v1/data-grants/{id}")
    public Response getDataGrant(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getDataGrant(id, region(headers)));
    }

    @DELETE
    @Path("/v1/data-grants/{id}")
    public Response deleteDataGrant(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.deleteDataGrant(id, region(headers));
        return Response.noContent().build();
    }

    @POST
    @Path("/v1/data-grants/{arn:.+}/accept")
    public Response acceptDataGrant(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return ok(service.acceptDataGrant(arn, region(headers)));
    }

    @GET
    @Path("/v1/received-data-grants")
    public Response listReceivedDataGrants(@Context HttpHeaders headers, @Context UriInfo uri,
                                           @QueryParam("acceptanceState") List<String> states) {
        return ok(service.listReceivedDataGrants(query(uri), states == null ? List.of() : states, region(headers)));
    }

    @GET
    @Path("/v1/received-data-grants/{arn:.+}")
    public Response getReceivedDataGrant(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return ok(service.getReceivedDataGrant(arn, region(headers)));
    }

    // ---- API assets

    @POST
    @Path("/v1")
    public Response sendApiAsset(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        String dataSetId = required(headers, "x-amzn-dataexchange-data-set-id");
        String revisionId = required(headers, "x-amzn-dataexchange-revision-id");
        String assetId = required(headers, "x-amzn-dataexchange-asset-id");
        Map<String, String> query = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((name, values) -> query.put(name, values.get(0)));
        Map<String, String> forwarded = new LinkedHashMap<>();
        headers.getRequestHeaders().forEach((name, values) -> {
            if (name.toLowerCase().startsWith(HEADER_PREFIX) && !values.isEmpty()) {
                forwarded.put(name.substring(HEADER_PREFIX.length()), values.get(0));
            }
        });
        HttpResponse<byte[]> response = jobs.sendApiAsset(dataSetId, revisionId, assetId,
                headers.getHeaderString("x-amzn-dataexchange-http-method"),
                headers.getHeaderString("x-amzn-dataexchange-path"), query, forwarded, body, region(headers));
        Response.ResponseBuilder builder = Response.status(response.statusCode()).entity(response.body());
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty() && !List.of("content-length", "transfer-encoding", "connection")
                    .contains(name.toLowerCase())) {
                builder.header(name, values.get(0));
            }
        });
        return builder.build();
    }

    // ---- tags

    @GET
    @Path("/tags/{arn:.+}")
    public Response listTagsForResource(@PathParam("arn") String arn) {
        return ok(service.listTags(arn));
    }

    @POST
    @Path("/tags/{arn:.+}")
    public Response tagResource(@PathParam("arn") String arn, String body) {
        service.tagResource(arn, parse(body));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/tags/{arn:.+}")
    public Response untagResource(@PathParam("arn") String arn, @QueryParam("tagKeys") List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw DataExchangeService.invalid("tagKeys is required.");
        }
        service.untagResource(arn, tagKeys);
        return Response.noContent().build();
    }

    private static String required(HttpHeaders headers, String name) {
        String value = headers.getHeaderString(name);
        if (value == null || value.isBlank()) {
            throw DataExchangeService.invalid(name + " is required.");
        }
        return value;
    }

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private ObjectNode query(UriInfo uri) {
        ObjectNode query = mapper.createObjectNode();
        uri.getQueryParameters().forEach((key, values) -> {
            if (!values.isEmpty()) {
                query.put(key, values.get(0));
            }
        });
        return query;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }

    private static Response ok(Object entity) {
        return Response.ok(entity).build();
    }

    private static Response status(int status, Object entity) {
        return Response.status(status).entity(entity).build();
    }
}
