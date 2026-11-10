package io.floci.aws.services.amplify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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

/** The AWS Amplify Hosting REST API: {@code /apps}, {@code /webhooks} and {@code /artifacts}. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AmplifyController {
    private static final String APP = "/apps/{appId}";
    private static final String BRANCH = APP + "/branches/{branchName}";
    private static final String JOB = BRANCH + "/jobs/{jobId}";

    private final AmplifyService service;
    private final AmplifyHosting hosting;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public AmplifyController(AmplifyService service, AmplifyHosting hosting, RegionResolver regions,
                             ObjectMapper mapper) {
        this.service = service;
        this.hosting = hosting;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ── Apps ───────────────────────────────────────────────────────────────

    @POST
    @Path("/apps")
    public Response createApp(@Context HttpHeaders headers, String body) {
        return ok(service.createApp(parse(body), region(headers)));
    }

    @GET
    @Path("/apps")
    public Response listApps(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                             @QueryParam("nextToken") String nextToken) {
        return ok(service.listApps(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(APP)
    public Response getApp(@Context HttpHeaders headers, @PathParam("appId") String appId) {
        return ok(service.getApp(appId, region(headers)));
    }

    @POST
    @Path(APP)
    public Response updateApp(@Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        return ok(service.updateApp(appId, parse(body), region(headers)));
    }

    @DELETE
    @Path(APP)
    public Response deleteApp(@Context HttpHeaders headers, @PathParam("appId") String appId) {
        return ok(service.deleteApp(appId, region(headers), hosting));
    }

    @POST
    @Path(APP + "/accesslogs")
    public Response generateAccessLogs(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                       String body) {
        return ok(hosting.generateAccessLogs(appId, parse(body), region(headers)));
    }

    // ── Branches ───────────────────────────────────────────────────────────

    @POST
    @Path(APP + "/branches")
    public Response createBranch(@Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        return ok(service.createBranch(appId, parse(body), region(headers)));
    }

    @GET
    @Path(APP + "/branches")
    public Response listBranches(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                 @QueryParam("maxResults") Integer maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        return ok(service.listBranches(appId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(BRANCH)
    public Response getBranch(@Context HttpHeaders headers, @PathParam("appId") String appId,
                              @PathParam("branchName") String branchName) {
        return ok(service.getBranch(appId, branchName, region(headers)));
    }

    @POST
    @Path(BRANCH)
    public Response updateBranch(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                 @PathParam("branchName") String branchName, String body) {
        return ok(service.updateBranch(appId, branchName, parse(body), region(headers)));
    }

    @DELETE
    @Path(BRANCH)
    public Response deleteBranch(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                 @PathParam("branchName") String branchName) {
        return ok(service.deleteBranch(appId, branchName, region(headers), hosting));
    }

    // ── Deployments and jobs ───────────────────────────────────────────────

    @POST
    @Path(BRANCH + "/deployments")
    public Response createDeployment(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                     @PathParam("branchName") String branchName, String body) {
        return ok(hosting.createDeployment(appId, branchName, parse(body), region(headers)));
    }

    @POST
    @Path(BRANCH + "/deployments/start")
    public Response startDeployment(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                    @PathParam("branchName") String branchName, String body) {
        return ok(hosting.startDeployment(appId, branchName, parse(body), region(headers)));
    }

    @POST
    @Path(BRANCH + "/jobs")
    public Response startJob(@Context HttpHeaders headers, @PathParam("appId") String appId,
                             @PathParam("branchName") String branchName, String body) {
        return ok(hosting.startJob(appId, branchName, parse(body), region(headers)));
    }

    @GET
    @Path(BRANCH + "/jobs")
    public Response listJobs(@Context HttpHeaders headers, @PathParam("appId") String appId,
                             @PathParam("branchName") String branchName, @QueryParam("maxResults") Integer maxResults,
                             @QueryParam("nextToken") String nextToken) {
        return ok(hosting.listJobs(appId, branchName, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(JOB)
    public Response getJob(@Context HttpHeaders headers, @PathParam("appId") String appId,
                           @PathParam("branchName") String branchName, @PathParam("jobId") String jobId) {
        return ok(hosting.getJob(appId, branchName, jobId, region(headers)));
    }

    @DELETE
    @Path(JOB)
    public Response deleteJob(@Context HttpHeaders headers, @PathParam("appId") String appId,
                              @PathParam("branchName") String branchName, @PathParam("jobId") String jobId) {
        return ok(hosting.deleteJob(appId, branchName, jobId, region(headers)));
    }

    @DELETE
    @Path(JOB + "/stop")
    public Response stopJob(@Context HttpHeaders headers, @PathParam("appId") String appId,
                            @PathParam("branchName") String branchName, @PathParam("jobId") String jobId) {
        return ok(hosting.stopJob(appId, branchName, jobId, region(headers)));
    }

    @GET
    @Path(JOB + "/artifacts")
    public Response listArtifacts(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                  @PathParam("branchName") String branchName, @PathParam("jobId") String jobId) {
        return ok(hosting.listArtifacts(appId, branchName, jobId, region(headers)));
    }

    @GET
    @Path("/artifacts/{artifactId}")
    public Response getArtifactUrl(@PathParam("artifactId") String artifactId) {
        return ok(hosting.getArtifactUrl(artifactId));
    }

    // ── Backend environments ───────────────────────────────────────────────

    @POST
    @Path(APP + "/backendenvironments")
    public Response createBackendEnvironment(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                             String body) {
        return ok(service.createBackend(appId, parse(body), region(headers)));
    }

    @GET
    @Path(APP + "/backendenvironments")
    public Response listBackendEnvironments(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                            @QueryParam("environmentName") String environmentName,
                                            @QueryParam("maxResults") Integer maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        return ok(service.listBackends(appId, environmentName, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(APP + "/backendenvironments/{environmentName}")
    public Response getBackendEnvironment(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                          @PathParam("environmentName") String environmentName) {
        return ok(service.getBackend(appId, environmentName, region(headers)));
    }

    @DELETE
    @Path(APP + "/backendenvironments/{environmentName}")
    public Response deleteBackendEnvironment(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                             @PathParam("environmentName") String environmentName) {
        return ok(service.deleteBackend(appId, environmentName, region(headers)));
    }

    // ── Webhooks ───────────────────────────────────────────────────────────

    @POST
    @Path(APP + "/webhooks")
    public Response createWebhook(@Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        return ok(service.createWebhook(appId, parse(body), region(headers)));
    }

    @GET
    @Path(APP + "/webhooks")
    public Response listWebhooks(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                 @QueryParam("maxResults") Integer maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        return ok(service.listWebhooks(appId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/webhooks/{webhookId}")
    public Response getWebhook(@Context HttpHeaders headers, @PathParam("webhookId") String webhookId) {
        return ok(service.getWebhook(webhookId, region(headers)));
    }

    @POST
    @Path("/webhooks/{webhookId}")
    public Response updateWebhook(@Context HttpHeaders headers, @PathParam("webhookId") String webhookId,
                                  String body) {
        return ok(service.updateWebhook(webhookId, parse(body), region(headers)));
    }

    @DELETE
    @Path("/webhooks/{webhookId}")
    public Response deleteWebhook(@Context HttpHeaders headers, @PathParam("webhookId") String webhookId) {
        return ok(service.deleteWebhook(webhookId, region(headers)));
    }

    // ── Domain associations ────────────────────────────────────────────────

    @POST
    @Path(APP + "/domains")
    public Response createDomainAssociation(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                            String body) {
        return ok(service.createDomain(appId, parse(body), region(headers)));
    }

    @GET
    @Path(APP + "/domains")
    public Response listDomainAssociations(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                           @QueryParam("maxResults") Integer maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return ok(service.listDomains(appId, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(APP + "/domains/{domainName}")
    public Response getDomainAssociation(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                         @PathParam("domainName") String domainName) {
        return ok(service.getDomain(appId, domainName, region(headers)));
    }

    @POST
    @Path(APP + "/domains/{domainName}")
    public Response updateDomainAssociation(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                            @PathParam("domainName") String domainName, String body) {
        return ok(service.updateDomain(appId, domainName, parse(body), region(headers)));
    }

    @DELETE
    @Path(APP + "/domains/{domainName}")
    public Response deleteDomainAssociation(@Context HttpHeaders headers, @PathParam("appId") String appId,
                                            @PathParam("domainName") String domainName) {
        return ok(service.deleteDomain(appId, domainName, region(headers)));
    }

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "The request body is not valid JSON.", 400);
        }
    }
}
