package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

import java.util.List;

/**
 * Amazon EMR Serverless restJson1. Public AWS paths are {@code /applications};
 * {@link EmrServerlessRoutingFilter} prefixes them so they do not collide with
 * AppConfig's {@code /applications} routes or S3's catch-all. Requests are
 * signed as {@code emr-serverless}.
 */
@Path(EmrServerlessRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmrServerlessController {

    private final EmrServerlessService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public EmrServerlessController(
            EmrServerlessService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/applications")
    public Response createApplication(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        return Response.ok(service.toCreateResponse(service.createApplication(region, parse(body)))).build();
    }

    @GET
    @Path("/applications")
    @Consumes(MediaType.WILDCARD)
    public Response listApplications(
            @Context HttpHeaders headers,
            @QueryParam("states") List<String> states,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        String region = region(headers);
        return Response.ok(service.toListApplications(service.listApplications(region, states))).build();
    }

    @GET
    @Path("/applications/{applicationId}")
    @Consumes(MediaType.WILDCARD)
    public Response getApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        String region = region(headers);
        return Response.ok(service.toApplicationEnvelope(service.getApplication(region, applicationId))).build();
    }

    @PATCH
    @Path("/applications/{applicationId}")
    public Response updateApplication(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            String body) {
        String region = region(headers);
        return Response.ok(service.toApplicationEnvelope(
                service.updateApplication(region, applicationId, parse(body)))).build();
    }

    @DELETE
    @Path("/applications/{applicationId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        String region = region(headers);
        service.deleteApplication(region, applicationId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/applications/{applicationId}/start")
    @Consumes(MediaType.WILDCARD)
    public Response startApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        String region = region(headers);
        service.startApplication(region, applicationId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/applications/{applicationId}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        String region = region(headers);
        service.stopApplication(region, applicationId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/applications/{applicationId}/dashboard")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceDashboard(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @QueryParam("resourceId") String resourceId,
            @QueryParam("resourceType") String resourceType) {
        String region = region(headers);
        service.getResourceDashboard(region, applicationId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/applications/{applicationId}/jobruns")
    public Response startJobRun(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            String body) {
        return Response.ok(service.toStartJobRun(
                service.startJobRun(region(headers), applicationId, parse(body)))).build();
    }

    @GET
    @Path("/applications/{applicationId}/jobruns")
    @Consumes(MediaType.WILDCARD)
    public Response listJobRuns(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @QueryParam("states") List<String> states) {
        return Response.ok(service.toListJobRuns(
                service.listJobRuns(region(headers), applicationId, states))).build();
    }

    @GET
    @Path("/applications/{applicationId}/jobruns/{jobRunId}")
    @Consumes(MediaType.WILDCARD)
    public Response getJobRun(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("jobRunId") String jobRunId) {
        return Response.ok(service.toJobRunEnvelope(
                service.getJobRun(region(headers), applicationId, jobRunId))).build();
    }

    @DELETE
    @Path("/applications/{applicationId}/jobruns/{jobRunId}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelJobRun(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("jobRunId") String jobRunId) {
        return Response.ok(service.toCancelJobRun(
                service.cancelJobRun(region(headers), applicationId, jobRunId))).build();
    }

    @GET
    @Path("/applications/{applicationId}/jobruns/{jobRunId}/dashboard")
    @Consumes(MediaType.WILDCARD)
    public Response getDashboardForJobRun(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("jobRunId") String jobRunId) {
        return Response.ok(service.getDashboardForJobRun(
                region(headers), applicationId, jobRunId)).build();
    }

    @GET
    @Path("/applications/{applicationId}/jobruns/{jobRunId}/attempts")
    @Consumes(MediaType.WILDCARD)
    public Response listJobRunAttempts(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("jobRunId") String jobRunId) {
        return Response.ok(service.listJobRunAttempts(
                region(headers), applicationId, jobRunId)).build();
    }

    @POST
    @Path("/applications/{applicationId}/sessions")
    public Response startSession(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            String body) {
        return Response.ok(service.toStartSession(
                service.startSession(region(headers), applicationId, parse(body)))).build();
    }

    @GET
    @Path("/applications/{applicationId}/sessions")
    @Consumes(MediaType.WILDCARD)
    public Response listSessions(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @QueryParam("states") List<String> states) {
        return Response.ok(service.toListSessions(
                service.listSessions(region(headers), applicationId, states))).build();
    }

    @GET
    @Path("/applications/{applicationId}/sessions/{sessionId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSession(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("sessionId") String sessionId) {
        return Response.ok(service.toSessionEnvelope(
                service.getSession(region(headers), applicationId, sessionId))).build();
    }

    @DELETE
    @Path("/applications/{applicationId}/sessions/{sessionId}")
    @Consumes(MediaType.WILDCARD)
    public Response terminateSession(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("sessionId") String sessionId) {
        return Response.ok(service.toTerminateSession(
                service.terminateSession(region(headers), applicationId, sessionId))).build();
    }

    @GET
    @Path("/applications/{applicationId}/sessions/{sessionId}/endpoint")
    @Consumes(MediaType.WILDCARD)
    public Response getSessionEndpoint(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("sessionId") String sessionId) {
        return Response.ok(service.getSessionEndpoint(
                region(headers), applicationId, sessionId)).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Invalid JSON", 400);
        }
    }
}
