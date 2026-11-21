package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/** The AWS Glue DataBrew REST API, routed here by {@link DataBrewRouteFilter}. */
@Path(DataBrewRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DataBrewController {
    private final DataBrewService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public DataBrewController(DataBrewService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---- datasets

    @POST
    @Path("/datasets")
    public Response createDataset(@Context HttpHeaders headers, String body) {
        return ok(service.createDataset(parse(body), region(headers)));
    }

    @GET
    @Path("/datasets")
    public Response listDatasets(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listDatasets(query(uri), region(headers)));
    }

    @GET
    @Path("/datasets/{name}")
    public Response describeDataset(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.describeDataset(name, region(headers)));
    }

    @PUT
    @Path("/datasets/{name}")
    public Response updateDataset(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateDataset(name, parse(body), region(headers)));
    }

    @DELETE
    @Path("/datasets/{name}")
    public Response deleteDataset(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.deleteDataset(name, region(headers)));
    }

    // ---- recipes

    @POST
    @Path("/recipes")
    public Response createRecipe(@Context HttpHeaders headers, String body) {
        return ok(service.createRecipe(parse(body), region(headers)));
    }

    @GET
    @Path("/recipes")
    public Response listRecipes(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listRecipes(query(uri), region(headers)));
    }

    @GET
    @Path("/recipeVersions")
    public Response listRecipeVersions(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listRecipeVersions(query(uri), region(headers)));
    }

    @GET
    @Path("/recipes/{name}")
    public Response describeRecipe(@Context HttpHeaders headers, @PathParam("name") String name,
                                   @QueryParam("recipeVersion") String version) {
        return ok(service.describeRecipe(name, version, region(headers)));
    }

    @PUT
    @Path("/recipes/{name}")
    public Response updateRecipe(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateRecipe(name, parse(body), region(headers)));
    }

    @POST
    @Path("/recipes/{name}/publishRecipe")
    public Response publishRecipe(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.publishRecipe(name, parse(body), region(headers)));
    }

    @DELETE
    @Path("/recipes/{name}/recipeVersion/{version}")
    public Response deleteRecipeVersion(@Context HttpHeaders headers, @PathParam("name") String name,
                                        @PathParam("version") String version) {
        return ok(service.deleteRecipeVersion(name, version, region(headers)));
    }

    @POST
    @Path("/recipes/{name}/batchDeleteRecipeVersion")
    public Response batchDeleteRecipeVersion(@Context HttpHeaders headers, @PathParam("name") String name,
                                             String body) {
        return ok(service.batchDeleteRecipeVersion(name, parse(body), region(headers)));
    }

    // ---- projects

    @POST
    @Path("/projects")
    public Response createProject(@Context HttpHeaders headers, String body) {
        return ok(service.createProject(parse(body), region(headers)));
    }

    @GET
    @Path("/projects")
    public Response listProjects(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listProjects(query(uri), region(headers)));
    }

    @GET
    @Path("/projects/{name}")
    public Response describeProject(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.describeProject(name, region(headers)));
    }

    @PUT
    @Path("/projects/{name}")
    public Response updateProject(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateProject(name, parse(body), region(headers)));
    }

    @DELETE
    @Path("/projects/{name}")
    public Response deleteProject(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.deleteProject(name, region(headers)));
    }

    @PUT
    @Path("/projects/{name}/startProjectSession")
    public Response startProjectSession(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.startProjectSession(name, parse(body), region(headers)));
    }

    @PUT
    @Path("/projects/{name}/sendProjectSessionAction")
    public Response sendProjectSessionAction(@Context HttpHeaders headers, @PathParam("name") String name,
                                             String body) {
        return ok(service.sendProjectSessionAction(name, parse(body), region(headers)));
    }

    // ---- jobs

    @POST
    @Path("/profileJobs")
    public Response createProfileJob(@Context HttpHeaders headers, String body) {
        return ok(service.createProfileJob(parse(body), region(headers)));
    }

    @PUT
    @Path("/profileJobs/{name}")
    public Response updateProfileJob(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateProfileJob(name, parse(body), region(headers)));
    }

    @POST
    @Path("/recipeJobs")
    public Response createRecipeJob(@Context HttpHeaders headers, String body) {
        return ok(service.createRecipeJob(parse(body), region(headers)));
    }

    @PUT
    @Path("/recipeJobs/{name}")
    public Response updateRecipeJob(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateRecipeJob(name, parse(body), region(headers)));
    }

    @GET
    @Path("/jobs")
    public Response listJobs(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listJobs(query(uri), region(headers)));
    }

    @GET
    @Path("/jobs/{name}")
    public Response describeJob(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.describeJob(name, region(headers)));
    }

    @DELETE
    @Path("/jobs/{name}")
    public Response deleteJob(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.deleteJob(name, region(headers)));
    }

    @POST
    @Path("/jobs/{name}/startJobRun")
    public Response startJobRun(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.startJobRun(name, region(headers)));
    }

    @GET
    @Path("/jobs/{name}/jobRuns")
    public Response listJobRuns(@Context HttpHeaders headers, @PathParam("name") String name, @Context UriInfo uri) {
        return ok(service.listJobRuns(name, query(uri), region(headers)));
    }

    @GET
    @Path("/jobs/{name}/jobRun/{runId}")
    public Response describeJobRun(@Context HttpHeaders headers, @PathParam("name") String name,
                                   @PathParam("runId") String runId) {
        return ok(service.describeJobRun(name, runId, region(headers)));
    }

    @POST
    @Path("/jobs/{name}/jobRun/{runId}/stopJobRun")
    public Response stopJobRun(@Context HttpHeaders headers, @PathParam("name") String name,
                               @PathParam("runId") String runId) {
        return ok(service.stopJobRun(name, runId, region(headers)));
    }

    // ---- rulesets

    @POST
    @Path("/rulesets")
    public Response createRuleset(@Context HttpHeaders headers, String body) {
        return ok(service.createRuleset(parse(body), region(headers)));
    }

    @GET
    @Path("/rulesets")
    public Response listRulesets(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listRulesets(query(uri), region(headers)));
    }

    @GET
    @Path("/rulesets/{name}")
    public Response describeRuleset(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.describeRuleset(name, region(headers)));
    }

    @PUT
    @Path("/rulesets/{name}")
    public Response updateRuleset(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateRuleset(name, parse(body), region(headers)));
    }

    @DELETE
    @Path("/rulesets/{name}")
    public Response deleteRuleset(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.deleteRuleset(name, region(headers)));
    }

    // ---- schedules

    @POST
    @Path("/schedules")
    public Response createSchedule(@Context HttpHeaders headers, String body) {
        return ok(service.createSchedule(parse(body), region(headers)));
    }

    @GET
    @Path("/schedules")
    public Response listSchedules(@Context HttpHeaders headers, @Context UriInfo uri) {
        return ok(service.listSchedules(query(uri), region(headers)));
    }

    @GET
    @Path("/schedules/{name}")
    public Response describeSchedule(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.describeSchedule(name, region(headers)));
    }

    @PUT
    @Path("/schedules/{name}")
    public Response updateSchedule(@Context HttpHeaders headers, @PathParam("name") String name, String body) {
        return ok(service.updateSchedule(name, parse(body), region(headers)));
    }

    @DELETE
    @Path("/schedules/{name}")
    public Response deleteSchedule(@Context HttpHeaders headers, @PathParam("name") String name) {
        return ok(service.deleteSchedule(name, region(headers)));
    }

    // ---- tags

    @GET
    @Path("/tags/{arn:.+}")
    public Response listTagsForResource(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return ok(service.listTags(arn, region(headers)));
    }

    @POST
    @Path("/tags/{arn:.+}")
    public Response tagResource(@PathParam("arn") String arn, String body) {
        service.tagResource(arn, parse(body));
        return ok(mapper.createObjectNode());
    }

    @DELETE
    @Path("/tags/{arn:.+}")
    public Response untagResource(@PathParam("arn") String arn, @QueryParam("tagKeys") List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw DataBrewService.invalid("tagKeys is required.");
        }
        service.untagResource(arn, tagKeys);
        return ok(mapper.createObjectNode());
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
}
