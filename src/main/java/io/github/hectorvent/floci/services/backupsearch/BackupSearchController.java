package io.github.hectorvent.floci.services.backupsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.backupsearch.model.ExportJob;
import io.github.hectorvent.floci.services.backupsearch.model.SearchJob;
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
 * AWS Backup Search restJson1. Literal {@code /search-jobs} and
 * {@code /export-search-jobs} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code backup-search}.
 */
@Path(BackupSearchRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupSearchController {

    private final BackupSearchService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BackupSearchController(BackupSearchService service, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/search-jobs")
    public Response startSearchJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        SearchJob job = service.startSearchJob(region, parse(body));
        return Response.ok(service.toStartSearchJob(job)).build();
    }

    @GET
    @Path("/search-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listSearchJobs(@Context HttpHeaders headers,
                                   @QueryParam("Status") String status,
                                   @QueryParam("NextToken") String nextToken,
                                   @QueryParam("MaxResults") Integer maxResults) {
        String region = regionResolver.resolveRegion(headers);
        BackupSearchService.Page<SearchJob> page =
                service.listSearchJobs(region, status, nextToken, maxResults);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode jobs = out.putArray("SearchJobs");
        for (SearchJob job : page.items()) {
            jobs.add(service.toSearchJobSummary(job));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/search-jobs/{searchJobIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getSearchJob(@Context HttpHeaders headers,
                                 @PathParam("searchJobIdentifier") String searchJobIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toGetSearchJob(service.getSearchJob(region, searchJobIdentifier))).build();
    }

    @GET
    @Path("/search-jobs/{searchJobIdentifier}/search-results")
    @Consumes(MediaType.WILDCARD)
    public Response listSearchJobResults(@Context HttpHeaders headers,
                                         @PathParam("searchJobIdentifier") String searchJobIdentifier,
                                         @QueryParam("nextToken") String nextToken,
                                         @QueryParam("maxResults") Integer maxResults,
                                         @QueryParam("NextToken") String nextTokenAlt,
                                         @QueryParam("MaxResults") Integer maxResultsAlt) {
        String region = regionResolver.resolveRegion(headers);
        service.requireSearchJob(region, searchJobIdentifier);
        int limit = firstInt(maxResults, maxResultsAlt, 25);
        if (limit < 1 || limit > 1000) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 1000.", 400);
        }
        // Token is accepted for wire compatibility; there are never results to page.
        firstNonBlank(nextToken, nextTokenAlt);
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("Results");
        return Response.ok(out).build();
    }

    @GET
    @Path("/search-jobs/{searchJobIdentifier}/backups")
    @Consumes(MediaType.WILDCARD)
    public Response listSearchJobBackups(@Context HttpHeaders headers,
                                         @PathParam("searchJobIdentifier") String searchJobIdentifier,
                                         @QueryParam("nextToken") String nextToken,
                                         @QueryParam("maxResults") Integer maxResults,
                                         @QueryParam("NextToken") String nextTokenAlt,
                                         @QueryParam("MaxResults") Integer maxResultsAlt) {
        String region = regionResolver.resolveRegion(headers);
        service.requireSearchJob(region, searchJobIdentifier);
        int limit = firstInt(maxResults, maxResultsAlt, 25);
        if (limit < 1 || limit > 1000) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 1000.", 400);
        }
        firstNonBlank(nextToken, nextTokenAlt);
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("Results");
        return Response.ok(out).build();
    }

    @PUT
    @Path("/search-jobs/{searchJobIdentifier}/actions/cancel")
    @Consumes(MediaType.WILDCARD)
    public Response stopSearchJob(@Context HttpHeaders headers,
                                  @PathParam("searchJobIdentifier") String searchJobIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        service.stopSearchJob(region, searchJobIdentifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/export-search-jobs")
    public Response startExportJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ExportJob job = service.startExportJob(region, parse(body));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportJobArn", job.getExportJobArn());
        out.put("ExportJobIdentifier", job.getExportJobIdentifier());
        return Response.ok(out).build();
    }

    @GET
    @Path("/export-search-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listExportJobs(@Context HttpHeaders headers,
                                   @QueryParam("Status") String status,
                                   @QueryParam("SearchJobIdentifier") String searchJobIdentifier,
                                   @QueryParam("NextToken") String nextToken,
                                   @QueryParam("MaxResults") Integer maxResults) {
        String region = regionResolver.resolveRegion(headers);
        BackupSearchService.Page<ExportJob> page =
                service.listExportJobs(region, status, searchJobIdentifier, nextToken, maxResults);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode jobs = out.putArray("ExportJobs");
        for (ExportJob job : page.items()) {
            jobs.add(service.toExportJobSummary(job));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/export-search-jobs/{exportJobIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getExportJob(@Context HttpHeaders headers,
                                 @PathParam("exportJobIdentifier") String exportJobIdentifier) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toGetExportJob(service.getExportJob(region, exportJobIdentifier))).build();
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static int firstInt(Integer a, Integer b, int fallback) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return fallback;
    }
}
