package io.github.hectorvent.floci.services.amplify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.amplify.model.AmplifyApp;
import io.github.hectorvent.floci.services.amplify.model.AmplifyBranch;
import io.github.hectorvent.floci.services.amplify.model.AmplifyJob;
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

import java.util.Map;

/**
 * Amplify Hosting restJson1 — app, branch, and manual-deploy job lifecycle.
 *
 * <p>Literal {@code /apps} paths take JAX-RS precedence over S3's {@code /{bucket}}
 * catch-all. Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmplifyController {

    private final AmplifyService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AmplifyController(
            AmplifyService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/apps")
    public Response createApp(@Context HttpHeaders headers, String body) {
        AmplifyApp app = service.createApp(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("app", toAppNode(app));
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps")
    @Consumes(MediaType.WILDCARD)
    public Response listApps(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AmplifyService.Page<AmplifyApp> page = service.listApps(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode apps = response.putArray("apps");
        for (AmplifyApp app : page.items()) {
            apps.add(toAppNode(app));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}")
    @Consumes(MediaType.WILDCARD)
    public Response getApp(@Context HttpHeaders headers, @PathParam("appId") String appId) {
        AmplifyApp app = service.getApp(regionResolver.resolveRegion(headers), appId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("app", toAppNode(app));
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}")
    public Response updateApp(
            @Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        AmplifyApp app = service.updateApp(regionResolver.resolveRegion(headers), appId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("app", toAppNode(app));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/apps/{appId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteApp(@Context HttpHeaders headers, @PathParam("appId") String appId) {
        AmplifyApp app = service.deleteApp(regionResolver.resolveRegion(headers), appId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("app", toAppNode(app));
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/branches")
    public Response createBranch(
            @Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        AmplifyBranch branch = service.createBranch(
                regionResolver.resolveRegion(headers), appId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("branch", toBranchNode(branch));
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}/branches")
    @Consumes(MediaType.WILDCARD)
    public Response listBranches(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AmplifyService.Page<AmplifyBranch> page = service.listBranches(
                regionResolver.resolveRegion(headers), appId, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode branches = response.putArray("branches");
        for (AmplifyBranch branch : page.items()) {
            branches.add(toBranchNode(branch));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}/branches/{branchName}")
    @Consumes(MediaType.WILDCARD)
    public Response getBranch(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName) {
        AmplifyBranch branch = service.getBranch(
                regionResolver.resolveRegion(headers), appId, branchName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("branch", toBranchNode(branch));
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/branches/{branchName}")
    public Response updateBranch(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            String body) {
        AmplifyBranch branch = service.updateBranch(
                regionResolver.resolveRegion(headers), appId, branchName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("branch", toBranchNode(branch));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/apps/{appId}/branches/{branchName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBranch(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName) {
        AmplifyBranch branch = service.deleteBranch(
                regionResolver.resolveRegion(headers), appId, branchName);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("branch", toBranchNode(branch));
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/branches/{branchName}/deployments")
    public Response createDeployment(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            String body) {
        AmplifyService.CreateDeploymentOutcome outcome = service.createDeployment(
                regionResolver.resolveRegion(headers), appId, branchName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", outcome.job().getJobId());
        response.put("zipUploadUrl", outcome.zipUploadUrl());
        if (outcome.fileUploadUrls() != null && !outcome.fileUploadUrls().isEmpty()) {
            ObjectNode urls = response.putObject("fileUploadUrls");
            outcome.fileUploadUrls().forEach(urls::put);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/branches/{branchName}/deployments/start")
    public Response startDeployment(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            String body) {
        AmplifyJob job = service.startDeployment(
                regionResolver.resolveRegion(headers), appId, branchName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobSummary", toJobSummaryNode(job));
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/branches/{branchName}/jobs")
    public Response startJob(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            String body) {
        AmplifyJob job = service.startJob(
                regionResolver.resolveRegion(headers), appId, branchName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobSummary", toJobSummaryNode(job));
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}/branches/{branchName}/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AmplifyService.Page<AmplifyJob> page = service.listJobs(
                regionResolver.resolveRegion(headers), appId, branchName, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode jobs = response.putArray("jobSummaries");
        for (AmplifyJob job : page.items()) {
            jobs.add(toJobSummaryNode(job));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}/branches/{branchName}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getJob(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            @PathParam("jobId") String jobId) {
        AmplifyJob job = service.getJob(
                regionResolver.resolveRegion(headers), appId, branchName, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode jobNode = response.putObject("job");
        jobNode.set("summary", toJobSummaryNode(job));
        ArrayNode steps = jobNode.putArray("steps");
        for (AmplifyJob.AmplifyStep step : job.getSteps()) {
            steps.add(toStepNode(step));
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/apps/{appId}/branches/{branchName}/jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteJob(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            @PathParam("jobId") String jobId) {
        AmplifyJob job = service.deleteJob(
                regionResolver.resolveRegion(headers), appId, branchName, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobSummary", toJobSummaryNode(job));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/apps/{appId}/branches/{branchName}/jobs/{jobId}/stop")
    @Consumes(MediaType.WILDCARD)
    public Response stopJob(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            @PathParam("jobId") String jobId) {
        AmplifyJob job = service.stopJob(
                regionResolver.resolveRegion(headers), appId, branchName, jobId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("jobSummary", toJobSummaryNode(job));
        return Response.ok(response).build();
    }

    @GET
    @Path("/apps/{appId}/branches/{branchName}/jobs/{jobId}/artifacts")
    @Consumes(MediaType.WILDCARD)
    public Response listArtifacts(
            @Context HttpHeaders headers,
            @PathParam("appId") String appId,
            @PathParam("branchName") String branchName,
            @PathParam("jobId") String jobId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AmplifyService.Page<AmplifyJob.AmplifyArtifact> page = service.listArtifacts(
                regionResolver.resolveRegion(headers), appId, branchName, jobId, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode artifacts = response.putArray("artifacts");
        for (AmplifyJob.AmplifyArtifact artifact : page.items()) {
            ObjectNode node = artifacts.addObject();
            node.put("artifactFileName", artifact.getArtifactFileName());
            node.put("artifactId", artifact.getArtifactId());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/artifacts/{artifactId}")
    @Consumes(MediaType.WILDCARD)
    public Response getArtifactUrl(
            @Context HttpHeaders headers, @PathParam("artifactId") String artifactId) {
        AmplifyJob.AmplifyArtifact artifact = service.getArtifact(
                regionResolver.resolveRegion(headers), artifactId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("artifactId", artifact.getArtifactId());
        response.put("artifactUrl", artifact.getArtifactUrl());
        return Response.ok(response).build();
    }

    @POST
    @Path("/apps/{appId}/accesslogs")
    public Response generateAccessLogs(
            @Context HttpHeaders headers, @PathParam("appId") String appId, String body) {
        String logUrl = service.generateAccessLogs(
                regionResolver.resolveRegion(headers), appId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("logUrl", logUrl);
        return Response.ok(response).build();
    }

    private ObjectNode toAppNode(AmplifyApp app) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("appId", app.getAppId());
        node.put("appArn", app.getAppArn());
        node.put("name", app.getName());
        node.put("createTime", app.getCreateTime());
        node.put("updateTime", app.getUpdateTime());
        if (app.getDescription() != null) {
            node.put("description", app.getDescription());
        }
        if (app.getPlatform() != null) {
            node.put("platform", app.getPlatform());
        }
        if (app.getDefaultDomain() != null) {
            node.put("defaultDomain", app.getDefaultDomain());
        }
        putBoolean(node, "enableBranchAutoBuild", app.getEnableBranchAutoBuild());
        putBoolean(node, "enableBranchAutoDeletion", app.getEnableBranchAutoDeletion());
        putBoolean(node, "enableBasicAuth", app.getEnableBasicAuth());
        putBoolean(node, "enableAutoBranchCreation", app.getEnableAutoBranchCreation());
        if (app.getBasicAuthCredentials() != null) {
            node.put("basicAuthCredentials", app.getBasicAuthCredentials());
        }
        if (app.getBuildSpec() != null) {
            node.put("buildSpec", app.getBuildSpec());
        }
        if (app.getCustomHeaders() != null) {
            node.put("customHeaders", app.getCustomHeaders());
        }
        if (app.getComputeRoleArn() != null) {
            node.put("computeRoleArn", app.getComputeRoleArn());
        }
        if (app.getIamServiceRoleArn() != null) {
            node.put("iamServiceRoleArn", app.getIamServiceRoleArn());
        }
        if (app.getRepository() != null) {
            node.put("repository", app.getRepository());
        }
        if (app.getCustomRules() != null) {
            node.set("customRules", app.getCustomRules());
        }
        putStringMap(node, "tags", app.getTags());
        putStringMap(node, "environmentVariables", app.getEnvironmentVariables());
        return node;
    }

    private ObjectNode toBranchNode(AmplifyBranch branch) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("branchArn", branch.getBranchArn());
        node.put("branchName", branch.getBranchName());
        node.put("createTime", branch.getCreateTime());
        node.put("updateTime", branch.getUpdateTime());
        if (branch.getDescription() != null) {
            node.put("description", branch.getDescription());
        }
        if (branch.getStage() != null) {
            node.put("stage", branch.getStage());
        }
        if (branch.getDisplayName() != null) {
            node.put("displayName", branch.getDisplayName());
        }
        if (branch.getFramework() != null) {
            node.put("framework", branch.getFramework());
        }
        if (branch.getTtl() != null) {
            node.put("ttl", branch.getTtl());
        }
        if (branch.getBasicAuthCredentials() != null) {
            node.put("basicAuthCredentials", branch.getBasicAuthCredentials());
        }
        if (branch.getBuildSpec() != null) {
            node.put("buildSpec", branch.getBuildSpec());
        }
        if (branch.getPullRequestEnvironmentName() != null) {
            node.put("pullRequestEnvironmentName", branch.getPullRequestEnvironmentName());
        }
        if (branch.getBackendEnvironmentArn() != null) {
            node.put("backendEnvironmentArn", branch.getBackendEnvironmentArn());
        }
        if (branch.getComputeRoleArn() != null) {
            node.put("computeRoleArn", branch.getComputeRoleArn());
        }
        if (branch.getActiveJobId() != null) {
            node.put("activeJobId", branch.getActiveJobId());
        }
        if (branch.getTotalNumberOfJobs() != null) {
            node.put("totalNumberOfJobs", branch.getTotalNumberOfJobs());
        }
        putBoolean(node, "enableNotification", branch.getEnableNotification());
        putBoolean(node, "enableAutoBuild", branch.getEnableAutoBuild());
        putBoolean(node, "enableSkewProtection", branch.getEnableSkewProtection());
        putBoolean(node, "enableBasicAuth", branch.getEnableBasicAuth());
        putBoolean(node, "enablePerformanceMode", branch.getEnablePerformanceMode());
        putBoolean(node, "enablePullRequestPreview", branch.getEnablePullRequestPreview());
        putStringMap(node, "tags", branch.getTags());
        putStringMap(node, "environmentVariables", branch.getEnvironmentVariables());
        return node;
    }

    private ObjectNode toJobSummaryNode(AmplifyJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobArn", job.getJobArn());
        node.put("jobId", job.getJobId());
        if (job.getCommitId() != null) {
            node.put("commitId", job.getCommitId());
        }
        if (job.getCommitMessage() != null) {
            node.put("commitMessage", job.getCommitMessage());
        }
        if (job.getCommitTime() != null) {
            node.put("commitTime", job.getCommitTime());
        }
        node.put("startTime", job.getStartTime());
        node.put("status", job.getStatus());
        if (job.getEndTime() != null) {
            node.put("endTime", job.getEndTime());
        }
        if (job.getJobType() != null) {
            node.put("jobType", job.getJobType());
        }
        if (job.getSourceUrl() != null) {
            node.put("sourceUrl", job.getSourceUrl());
        }
        if (job.getSourceUrlType() != null) {
            node.put("sourceUrlType", job.getSourceUrlType());
        }
        return node;
    }

    private ObjectNode toStepNode(AmplifyJob.AmplifyStep step) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stepName", step.getStepName());
        node.put("startTime", step.getStartTime());
        node.put("status", step.getStatus());
        if (step.getEndTime() != null) {
            node.put("endTime", step.getEndTime());
        }
        if (step.getLogUrl() != null) {
            node.put("logUrl", step.getLogUrl());
        }
        if (step.getStatusReason() != null) {
            node.put("statusReason", step.getStatusReason());
        }
        return node;
    }

    private static void putBoolean(ObjectNode node, String field, Boolean value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putStringMap(ObjectNode node, String field, Map<String, String> values) {
        ObjectNode map = node.putObject(field);
        if (values != null) {
            values.forEach(map::put);
        }
    }
}
