package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.fis.model.Experiment;
import io.github.hectorvent.floci.services.fis.model.ExperimentTemplate;
import io.github.hectorvent.floci.services.fis.model.SafetyLever;
import io.github.hectorvent.floci.services.fis.model.TargetAccountConfiguration;
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
 * AWS Fault Injection Service restJson1. Public AWS paths are
 * {@code /experimentTemplates}, {@code /experiments}, {@code /actions},
 * {@code /targetResourceTypes} and {@code /safetyLevers};
 * {@link FisRoutingFilter} prefixes them so they do not collide with S3
 * path-style routes. Tag APIs share {@code /tags/{arn}}.
 */
@Path(FisRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FisController {

    private final FisService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public FisController(FisService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/experimentTemplates")
    public Response createExperimentTemplate(@Context HttpHeaders headers, String body) {
        ExperimentTemplate template = service.createExperimentTemplate(
                regionResolver.resolveRegion(headers), parse(body));
        return wrap("experimentTemplate", service.toPublicTemplate(template));
    }

    @GET
    @Path("/experimentTemplates")
    @Consumes(MediaType.WILDCARD)
    public Response listExperimentTemplates(@Context HttpHeaders headers) {
        List<ExperimentTemplate> templates =
                service.listExperimentTemplates(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("experimentTemplates", service.toPublicTemplateSummaries(templates));
        return Response.ok(response).build();
    }

    @GET
    @Path("/experimentTemplates/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getExperimentTemplate(@Context HttpHeaders headers, @PathParam("id") String id) {
        ExperimentTemplate template = service.getExperimentTemplate(
                regionResolver.resolveRegion(headers), id);
        return wrap("experimentTemplate", service.toPublicTemplate(template));
    }

    @PATCH
    @Path("/experimentTemplates/{id}")
    public Response updateExperimentTemplate(
            @Context HttpHeaders headers, @PathParam("id") String id, String body) {
        ExperimentTemplate template = service.updateExperimentTemplate(
                regionResolver.resolveRegion(headers), id, parse(body));
        return wrap("experimentTemplate", service.toPublicTemplate(template));
    }

    @DELETE
    @Path("/experimentTemplates/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteExperimentTemplate(@Context HttpHeaders headers, @PathParam("id") String id) {
        ExperimentTemplate template = service.deleteExperimentTemplate(
                regionResolver.resolveRegion(headers), id);
        return wrap("experimentTemplate", service.toPublicTemplate(template));
    }

    @POST
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response createTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId,
            String body) {
        TargetAccountConfiguration config = service.createTargetAccountConfiguration(
                regionResolver.resolveRegion(headers), experimentTemplateId, accountId, parse(body));
        return wrap("targetAccountConfiguration", service.toPublicTargetAccount(config));
    }

    @GET
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    @Consumes(MediaType.WILDCARD)
    public Response getTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId) {
        TargetAccountConfiguration config = service.getTargetAccountConfiguration(
                regionResolver.resolveRegion(headers), experimentTemplateId, accountId);
        return wrap("targetAccountConfiguration", service.toPublicTargetAccount(config));
    }

    @PATCH
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response updateTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId,
            String body) {
        TargetAccountConfiguration config = service.updateTargetAccountConfiguration(
                regionResolver.resolveRegion(headers), experimentTemplateId, accountId, parse(body));
        return wrap("targetAccountConfiguration", service.toPublicTargetAccount(config));
    }

    @DELETE
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId) {
        TargetAccountConfiguration config = service.deleteTargetAccountConfiguration(
                regionResolver.resolveRegion(headers), experimentTemplateId, accountId);
        return wrap("targetAccountConfiguration", service.toPublicTargetAccount(config));
    }

    @GET
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations")
    @Consumes(MediaType.WILDCARD)
    public Response listTargetAccountConfigurations(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId) {
        List<TargetAccountConfiguration> configs = service.listTargetAccountConfigurations(
                regionResolver.resolveRegion(headers), experimentTemplateId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("targetAccountConfigurations", service.toPublicTargetAccounts(configs));
        return Response.ok(response).build();
    }

    @POST
    @Path("/experiments")
    public Response startExperiment(@Context HttpHeaders headers, String body) {
        Experiment experiment = service.startExperiment(regionResolver.resolveRegion(headers), parse(body));
        return wrap("experiment", service.toPublicExperiment(experiment));
    }

    @GET
    @Path("/experiments")
    @Consumes(MediaType.WILDCARD)
    public Response listExperiments(
            @Context HttpHeaders headers,
            @QueryParam("experimentTemplateId") String experimentTemplateId) {
        List<Experiment> experiments = service.listExperiments(
                regionResolver.resolveRegion(headers), experimentTemplateId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("experiments", service.toPublicExperimentSummaries(experiments));
        return Response.ok(response).build();
    }

    @GET
    @Path("/experiments/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getExperiment(@Context HttpHeaders headers, @PathParam("id") String id) {
        Experiment experiment = service.getExperiment(regionResolver.resolveRegion(headers), id);
        return wrap("experiment", service.toPublicExperiment(experiment));
    }

    @DELETE
    @Path("/experiments/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response stopExperiment(@Context HttpHeaders headers, @PathParam("id") String id) {
        Experiment experiment = service.stopExperiment(regionResolver.resolveRegion(headers), id);
        return wrap("experiment", service.toPublicExperiment(experiment));
    }

    @GET
    @Path("/experiments/{id}/resolvedTargets")
    @Consumes(MediaType.WILDCARD)
    public Response listExperimentResolvedTargets(@Context HttpHeaders headers, @PathParam("id") String id) {
        service.getExperiment(regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("resolvedTargets");
        return Response.ok(response).build();
    }

    @GET
    @Path("/actions")
    @Consumes(MediaType.WILDCARD)
    public Response listActions() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("actions", service.listActions());
        return Response.ok(response).build();
    }

    @GET
    @Path("/actions/{id:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getAction(@PathParam("id") String id) {
        return wrap("action", service.getAction(id));
    }

    @GET
    @Path("/targetResourceTypes")
    @Consumes(MediaType.WILDCARD)
    public Response listTargetResourceTypes() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("targetResourceTypes", service.listTargetResourceTypes());
        return Response.ok(response).build();
    }

    @GET
    @Path("/targetResourceTypes/{resourceType:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getTargetResourceType(@PathParam("resourceType") String resourceType) {
        return wrap("targetResourceType", service.getTargetResourceType(resourceType));
    }

    @GET
    @Path("/safetyLevers/{id}")
    @Consumes(MediaType.WILDCARD)
    public Response getSafetyLever(@Context HttpHeaders headers, @PathParam("id") String id) {
        SafetyLever lever = service.getSafetyLever(regionResolver.resolveRegion(headers), id);
        return wrap("safetyLever", service.toPublicSafetyLever(lever));
    }

    private Response wrap(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return Response.ok(response).build();
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
