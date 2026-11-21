package io.floci.aws.services.backupsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
 * The AWS Backup Search REST API: search jobs under {@code /search-jobs} and export jobs under
 * {@code /export-search-jobs}. Tags go through the shared {@code /tags/{ResourceArn}} routes, which hand search and
 * export job ARNs ({@code arn:aws:backup:...:search-job/...}) to this service through AWS Backup's tag handler.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BackupSearchController {
    private final BackupSearchService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public BackupSearchController(BackupSearchService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @PUT
    @Path("/search-jobs")
    public Response startSearchJob(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startSearchJob(parse(body), region(headers))).build();
    }

    @GET
    @Path("/search-jobs")
    public Response listSearchJobs(@Context HttpHeaders headers, @QueryParam("Status") String status,
                                   @QueryParam("MaxResults") Integer maxResults,
                                   @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.listSearchJobs(status, maxResults, nextToken, region(headers))).build();
    }

    @GET
    @Path("/search-jobs/{id}")
    public Response getSearchJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(service.getSearchJob(id, region(headers))).build();
    }

    @GET
    @Path("/search-jobs/{id}/search-results")
    public Response listSearchJobResults(@Context HttpHeaders headers, @PathParam("id") String id,
                                         @QueryParam("maxResults") Integer maxResults,
                                         @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listSearchJobResults(id, maxResults, nextToken, region(headers))).build();
    }

    @GET
    @Path("/search-jobs/{id}/backups")
    public Response listSearchJobBackups(@Context HttpHeaders headers, @PathParam("id") String id,
                                         @QueryParam("maxResults") Integer maxResults,
                                         @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listSearchJobBackups(id, maxResults, nextToken, region(headers))).build();
    }

    @PUT
    @Path("/search-jobs/{id}/actions/cancel")
    public Response stopSearchJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.stopSearchJob(id, region(headers));
        return Response.ok(mapper.createObjectNode()).build();
    }

    @PUT
    @Path("/export-search-jobs")
    public Response startSearchResultExportJob(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startExportJob(parse(body), region(headers))).build();
    }

    @GET
    @Path("/export-search-jobs")
    public Response listSearchResultExportJobs(@Context HttpHeaders headers, @QueryParam("Status") String status,
                                               @QueryParam("SearchJobIdentifier") String searchJobId,
                                               @QueryParam("MaxResults") Integer maxResults,
                                               @QueryParam("NextToken") String nextToken) {
        return Response.ok(service.listExportJobs(status, searchJobId, maxResults, nextToken, region(headers)))
                .build();
    }

    @GET
    @Path("/export-search-jobs/{id}")
    public Response getSearchResultExportJob(@Context HttpHeaders headers, @PathParam("id") String id) {
        return Response.ok(service.getExportJob(id, region(headers))).build();
    }

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            if (!parsed.isObject()) {
                throw new AwsException("ValidationException", "The request body must be a JSON object.", 400);
            }
            return parsed;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
