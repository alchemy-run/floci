package io.github.hectorvent.floci.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.databrew.model.Dataset;
import io.github.hectorvent.floci.services.databrew.model.Job;
import io.github.hectorvent.floci.services.databrew.model.JobRun;
import io.github.hectorvent.floci.services.databrew.model.Project;
import io.github.hectorvent.floci.services.databrew.model.Recipe;
import io.github.hectorvent.floci.services.databrew.model.Ruleset;
import io.github.hectorvent.floci.services.databrew.model.Schedule;
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

/**
 * AWS Glue DataBrew restJson1. Public AWS paths are {@code /recipes},
 * {@code /datasets}, {@code /jobs}, {@code /profileJobs}, {@code /recipeJobs}
 * and peers; {@link DataBrewRoutingFilter} prefixes them so they do not collide
 * with S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code databrew}.
 */
@Path(DataBrewRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataBrewController {

    private final DataBrewService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DataBrewController(
            DataBrewService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/recipes")
    public Response createRecipe(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Recipe recipe = service.createRecipe(region, parse(body));
        return Response.ok(service.nameOnly(recipe.getName())).build();
    }

    @GET
    @Path("/recipes/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRecipe(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @QueryParam("recipeVersion") String recipeVersion) {
        String region = regionResolver.resolveRegion(headers);
        Recipe recipe = service.describeRecipe(region, name, recipeVersion);
        return Response.ok(service.toDescribeRecipe(recipe, recipeVersion)).build();
    }

    @PUT
    @Path("/recipes/{name}")
    public Response updateRecipe(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Recipe recipe = service.updateRecipe(region, name, parse(body));
        return Response.ok(service.nameOnly(recipe.getName())).build();
    }

    @POST
    @Path("/recipes/{name}/publishRecipe")
    public Response publishRecipe(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Recipe recipe = service.publishRecipe(region, name, parse(body));
        return Response.ok(service.nameOnly(recipe.getName())).build();
    }

    @GET
    @Path("/recipes")
    @Consumes(MediaType.WILDCARD)
    public Response listRecipes(
            @Context HttpHeaders headers,
            @QueryParam("recipeVersion") String recipeVersion) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode recipes = out.putArray("Recipes");
        for (ObjectNode recipe : service.listRecipes(region, recipeVersion)) {
            recipes.add(recipe);
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/recipeVersions")
    @Consumes(MediaType.WILDCARD)
    public Response listRecipeVersions(
            @Context HttpHeaders headers,
            @QueryParam("name") String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "name is required.", 400);
        }
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode recipes = out.putArray("Recipes");
        for (ObjectNode recipe : service.listRecipeVersions(region, name)) {
            recipes.add(recipe);
        }
        return Response.ok(out).build();
    }

    @POST
    @Path("/recipes/{name}/batchDeleteRecipeVersion")
    public Response batchDeleteRecipeVersion(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        service.batchDeleteRecipeVersion(region, name, parse(body));
        return Response.ok(service.nameOnly(name)).build();
    }

    @DELETE
    @Path("/recipes/{name}/recipeVersion/{recipeVersion}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRecipeVersion(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @PathParam("recipeVersion") String recipeVersion) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRecipeVersion(region, name, recipeVersion);
        return Response.ok(service.nameOnly(name)).build();
    }

    @POST
    @Path("/datasets")
    public Response createDataset(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Dataset dataset = service.createDataset(region, parse(body));
        return Response.ok(service.nameOnly(dataset.getName())).build();
    }

    @GET
    @Path("/datasets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeDataset(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toDataset(service.describeDataset(region, name))).build();
    }

    @PUT
    @Path("/datasets/{name}")
    public Response updateDataset(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Dataset dataset = service.updateDataset(region, name, parse(body));
        return Response.ok(service.nameOnly(dataset.getName())).build();
    }

    @DELETE
    @Path("/datasets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataset(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteDataset(region, name);
        return Response.ok(service.nameOnly(name)).build();
    }

    @GET
    @Path("/datasets")
    @Consumes(MediaType.WILDCARD)
    public Response listDatasets(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Datasets");
        for (Dataset dataset : service.listDatasets(region)) {
            list.add(service.toDataset(dataset));
        }
        return Response.ok(out).build();
    }

    @POST
    @Path("/projects")
    public Response createProject(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Project project = service.createProject(region, parse(body));
        return Response.ok(service.nameOnly(project.getName())).build();
    }

    @GET
    @Path("/projects")
    @Consumes(MediaType.WILDCARD)
    public Response listProjects(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Projects");
        for (Project project : service.listProjects(region, null, null).items()) {
            list.add(service.toProject(project));
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/projects/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeProject(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toProject(service.describeProject(region, name))).build();
    }

    @PUT
    @Path("/projects/{name}")
    public Response updateProject(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Project project = service.updateProject(region, name, parse(body));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", project.getName());
        out.put("LastModifiedDate", project.getLastModifiedDate());
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/projects/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProject(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteProject(region, name);
        return Response.ok(service.nameOnly(name)).build();
    }

    @PUT
    @Path("/projects/{name}/startProjectSession")
    public Response startProjectSession(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Project project = service.startProjectSession(region, name, parse(body));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", project.getName());
        if (project.getClientSessionId() != null) {
            out.put("ClientSessionId", project.getClientSessionId());
        }
        return Response.ok(out).build();
    }

    @PUT
    @Path("/projects/{name}/sendProjectSessionAction")
    public Response sendProjectSessionAction(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        int actionId = service.sendProjectSessionAction(region, name, parse(body));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Name", name);
        out.put("ActionId", actionId);
        return Response.ok(out).build();
    }

    @POST
    @Path("/rulesets")
    public Response createRuleset(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Ruleset ruleset = service.createRuleset(region, parse(body));
        return Response.ok(service.nameOnly(ruleset.getName())).build();
    }

    @GET
    @Path("/rulesets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRuleset(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toDescribe(service.describeRuleset(region, name))).build();
    }

    @PUT
    @Path("/rulesets/{name}")
    public Response updateRuleset(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Ruleset ruleset = service.updateRuleset(region, name, parse(body));
        return Response.ok(service.nameOnly(ruleset.getName())).build();
    }

    @DELETE
    @Path("/rulesets/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRuleset(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        Ruleset ruleset = service.deleteRuleset(region, name);
        return Response.ok(service.nameOnly(ruleset.getName())).build();
    }

    @GET
    @Path("/rulesets")
    @Consumes(MediaType.WILDCARD)
    public Response listRulesets(
            @Context HttpHeaders headers,
            @QueryParam("targetArn") String targetArn,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        DataBrewService.Page<Ruleset> page = service.listRulesets(region, targetArn, maxResults, nextToken);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Rulesets");
        for (Ruleset ruleset : page.items()) {
            list.add(service.toSummary(ruleset));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    @POST
    @Path("/profileJobs")
    public Response createProfileJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Job job = service.createProfileJob(region, parse(body));
        return Response.ok(service.nameOnly(job.getName())).build();
    }

    @POST
    @Path("/recipeJobs")
    public Response createRecipeJob(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Job job = service.createRecipeJob(region, parse(body));
        return Response.ok(service.nameOnly(job.getName())).build();
    }

    @GET
    @Path("/jobs/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeJob(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toJob(service.describeJob(region, name))).build();
    }

    @PUT
    @Path("/profileJobs/{name}")
    public Response updateProfileJob(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Job job = service.updateProfileJob(region, name, parse(body));
        return Response.ok(service.nameOnly(job.getName())).build();
    }

    @PUT
    @Path("/recipeJobs/{name}")
    public Response updateRecipeJob(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Job job = service.updateRecipeJob(region, name, parse(body));
        return Response.ok(service.nameOnly(job.getName())).build();
    }

    @DELETE
    @Path("/jobs/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteJob(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteJob(region, name);
        return Response.ok(service.nameOnly(name)).build();
    }

    @GET
    @Path("/jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(
            @Context HttpHeaders headers,
            @QueryParam("datasetName") String datasetName,
            @QueryParam("projectName") String projectName) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Jobs");
        for (Job job : service.listJobs(region, datasetName, projectName)) {
            list.add(service.toJob(job));
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/jobs/{name}/jobRuns")
    @Consumes(MediaType.WILDCARD)
    public Response listJobRuns(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("JobRuns");
        for (JobRun run : service.listJobRuns(region, name)) {
            list.add(service.toJobRun(run));
        }
        return Response.ok(out).build();
    }

    @GET
    @Path("/jobs/{name}/jobRun/{runId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeJobRun(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @PathParam("runId") String runId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toJobRun(service.describeJobRun(region, name, runId))).build();
    }

    @POST
    @Path("/jobs/{name}/startJobRun")
    public Response startJobRun(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.startJobRunResponse(service.startJobRun(region, name))).build();
    }

    @POST
    @Path("/jobs/{name}/jobRun/{runId}/stopJobRun")
    public Response stopJobRun(
            @Context HttpHeaders headers,
            @PathParam("name") String name,
            @PathParam("runId") String runId) {
        String region = regionResolver.resolveRegion(headers);
        JobRun run = service.stopJobRun(region, name, runId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("RunId", run.getRunId());
        return Response.ok(out).build();
    }

    @POST
    @Path("/schedules")
    public Response createSchedule(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Schedule schedule = service.createSchedule(region, parse(body));
        return Response.ok(service.nameOnly(schedule.getName())).build();
    }

    @GET
    @Path("/schedules/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response describeSchedule(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.toSchedule(service.describeSchedule(region, name))).build();
    }

    @PUT
    @Path("/schedules/{name}")
    public Response updateSchedule(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        String region = regionResolver.resolveRegion(headers);
        Schedule schedule = service.updateSchedule(region, name, parse(body));
        return Response.ok(service.nameOnly(schedule.getName())).build();
    }

    @DELETE
    @Path("/schedules/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSchedule(@Context HttpHeaders headers, @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteSchedule(region, name);
        return Response.ok(service.nameOnly(name)).build();
    }

    @GET
    @Path("/schedules")
    @Consumes(MediaType.WILDCARD)
    public Response listSchedules(
            @Context HttpHeaders headers,
            @QueryParam("jobName") String jobName,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        DataBrewService.Page<Schedule> page = service.listSchedules(region, jobName, maxResults, nextToken);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Schedules");
        for (Schedule schedule : page.items()) {
            list.add(service.toSchedule(schedule));
        }
        if (page.nextToken() != null) {
            out.put("NextToken", page.nextToken());
        }
        return Response.ok(out).build();
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
