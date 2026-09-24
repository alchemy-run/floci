package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.ApplicationSummary;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.DeleteApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.GetApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsRequest;
import io.github.hectorvent.floci.services.emrserverless.model.ListApplicationsResponse;
import io.github.hectorvent.floci.services.emrserverless.model.StartApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.StopApplicationResponse;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/_emrserverless/applications")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmrServerlessController {

    private final EmrServerlessService service;

    @Inject
    public EmrServerlessController(EmrServerlessService service) {
        this.service = service;
    }

    @POST
    public CreateApplicationResponse createApplication(CreateApplicationRequest request) {
        Application app = service.createApplication(request);
        CreateApplicationResponse response = new CreateApplicationResponse();
        response.setApplicationId(app.getApplicationId());
        response.setArn(app.getArn());
        response.setName(app.getName());
        return response;
    }

    @GET
    public ListApplicationsResponse listApplications(
            @QueryParam("states") List<String> states,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        
        ListApplicationsRequest req = new ListApplicationsRequest();
        req.setStates(states);
        req.setMaxResults(Pagination.parseMaxResults(maxResults, "ValidationException"));
        req.setNextToken(nextToken);
        
        PaginatedResult<ApplicationSummary> page = service.listApplications(req);
        
        ListApplicationsResponse response = new ListApplicationsResponse();
        response.setApplications(page.items());
        response.setNextToken(page.nextToken());
        return response;
    }

    @GET
    @Path("/{applicationId}")
    public GetApplicationResponse getApplication(@PathParam("applicationId") String applicationId) {
        Application app = service.getApplication(applicationId);
        GetApplicationResponse response = new GetApplicationResponse();
        response.setApplication(app);
        return response;
    }

    @PATCH
    @Path("/{applicationId}")
    public UpdateApplicationResponse updateApplication(@PathParam("applicationId") String applicationId,
                                                     UpdateApplicationRequest request) {
        Application app = service.updateApplication(applicationId, request);
        UpdateApplicationResponse response = new UpdateApplicationResponse();
        response.setApplication(app);
        return response;
    }

    @DELETE
    @Path("/{applicationId}")
    public DeleteApplicationResponse deleteApplication(@PathParam("applicationId") String applicationId) {
        service.deleteApplication(applicationId);
        DeleteApplicationResponse response = new DeleteApplicationResponse();
        response.setApplicationId(applicationId);
        return response;
    }

    @GET
    @Path("/{applicationId}/jobruns")
    public Map<String, Object> listJobRuns(@PathParam("applicationId") String applicationId,
                                          @QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken,
                                          @QueryParam("states") List<String> states,
                                          @QueryParam("createdAtAfter") String after,
                                          @QueryParam("createdAtBefore") String before,
                                          @QueryParam("mode") String mode) {
        PaginatedResult<ObjectNode> page = service.listJobRuns(applicationId,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, states,
                timestamp(after), timestamp(before), mode);
        return page.nextToken() == null ? Map.of("jobRuns", page.items())
                : Map.of("jobRuns", page.items(), "nextToken", page.nextToken());
    }

    @POST
    @Path("/{applicationId}/jobruns")
    public ObjectNode startJobRun(@PathParam("applicationId") String applicationId,
                                  @HeaderParam("Authorization") String authorization, JsonNode request) {
        return service.startJobRun(applicationId, request, authorization);
    }

    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}")
    public Map<String, Object> getJobRun(@PathParam("applicationId") String applicationId,
                                        @PathParam("jobRunId") String jobRunId,
                                        @QueryParam("attempt") String attempt) {
        return Map.of("jobRun", service.getJobRun(applicationId, jobRunId, attempt));
    }

    @DELETE
    @Path("/{applicationId}/jobruns/{jobRunId}")
    public ObjectNode cancelJobRun(@PathParam("applicationId") String applicationId,
                                   @PathParam("jobRunId") String jobRunId) {
        return service.cancelJobRun(applicationId, jobRunId);
    }

    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}/attempts")
    public Map<String, Object> listJobRunAttempts(@PathParam("applicationId") String applicationId,
                                                @PathParam("jobRunId") String jobRunId,
                                                @QueryParam("maxResults") String maxResults,
                                                @QueryParam("nextToken") String nextToken) {
        PaginatedResult<ObjectNode> page = service.listJobRunAttempts(applicationId, jobRunId,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        return page.nextToken() == null ? Map.of("jobRunAttempts", page.items())
                : Map.of("jobRunAttempts", page.items(), "nextToken", page.nextToken());
    }

    @GET
    @Path("/{applicationId}/jobruns/{jobRunId}/dashboard")
    public void getDashboardForJobRun(@PathParam("applicationId") String applicationId,
                                      @PathParam("jobRunId") String jobRunId) {
        service.getDashboardForJobRun(applicationId, jobRunId);
    }

    private static Double timestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            double result = Double.parseDouble(value);
            if (Double.isFinite(result)) {
                return result;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new AwsException("ValidationException", "Invalid timestamp filter", 400);
    }

    @GET
    @Path("/{applicationId}/sessions")
    public Map<String, Object> listSessions(@PathParam("applicationId") String applicationId,
                                           @QueryParam("maxResults") String maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return Map.of("sessions", service.listSessions(applicationId,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken));
    }

    @POST
    @Path("/{applicationId}/sessions")
    public void startSession(@PathParam("applicationId") String applicationId, JsonNode request) {
        service.startSession(applicationId, request);
    }

    @GET
    @Path("/{applicationId}/dashboard")
    public void getResourceDashboard(@PathParam("applicationId") String applicationId,
                                     @QueryParam("resourceId") String resourceId,
                                     @QueryParam("resourceType") String resourceType,
                                     @HeaderParam("Authorization") String authorization) {
        service.getResourceDashboard(applicationId, resourceId, resourceType, authorization);
    }

    @POST
    @Path("/{applicationId}/start")
    public StartApplicationResponse startApplication(@PathParam("applicationId") String applicationId) {
        service.startApplication(applicationId);
        StartApplicationResponse response = new StartApplicationResponse();
        return response;
    }

    @POST
    @Path("/{applicationId}/stop")
    public StopApplicationResponse stopApplication(@PathParam("applicationId") String applicationId) {
        service.stopApplication(applicationId);
        StopApplicationResponse response = new StopApplicationResponse();
        return response;
    }
}
