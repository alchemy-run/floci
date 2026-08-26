package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appintegrations.model.Application;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
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
import java.util.Map;

/**
 * Amazon AppIntegrations restJson1. Public AWS application paths are {@code /applications};
 * {@link AppIntegrationsRoutingFilter} prefixes them so they do not collide
 * with AppConfig's {@code /applications} routes. Data integrations use
 * {@code /dataIntegrations} which does not collide.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppIntegrationsController {

    private final AppIntegrationsService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AppIntegrationsController(
            AppIntegrationsService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
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
                throw new AwsException("InvalidRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/app-integrations/applications")
    public Response createApplication(@Context HttpHeaders headers, String body) {
        try {
            Application application = service.createApplication(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("Arn", application.getArn());
            response.put("Id", application.getId());
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/app-integrations/applications")
    @Consumes(MediaType.WILDCARD)
    public Response listApplications(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        try {
            AppIntegrationsService.Page<Application> page = service.listApplications(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode applications = response.putArray("Applications");
            for (Application application : page.items()) {
                applications.add(toSummary(application));
            }
            if (page.nextToken() != null) {
                response.put("NextToken", page.nextToken());
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/app-integrations/applications/{id}/associations")
    @Consumes(MediaType.WILDCARD)
    public Response listApplicationAssociations(
            @Context HttpHeaders headers, @PathParam("id") String id) {
        service.getApplication(regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ApplicationAssociations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/app-integrations/applications/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getApplication(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        try {
            Application application = service.getApplication(regionResolver.resolveRegion(headers), arn);
            return Response.ok(toDetail(application)).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @PATCH
    @Path("/app-integrations/applications/{arn: .+}")
    public Response updateApplication(
            @Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        try {
            service.updateApplication(regionResolver.resolveRegion(headers), arn, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/app-integrations/applications/{arn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteApplication(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        try {
            service.deleteApplication(regionResolver.resolveRegion(headers), arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @POST
    @Path("/dataIntegrations")
    public Response createDataIntegration(@Context HttpHeaders headers, String body) {
        DataIntegration integration = service.createDataIntegration(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = toDataIntegrationNode(integration);
        if (integration.getClientToken() != null) {
            response.put("ClientToken", integration.getClientToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/dataIntegrations")
    @Consumes(MediaType.WILDCARD)
    public Response listDataIntegrations(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppIntegrationsService.Page<DataIntegration> page = service.listDataIntegrations(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode integrations = response.putArray("DataIntegrations");
        for (DataIntegration integration : page.items()) {
            ObjectNode summary = integrations.addObject();
            summary.put("Arn", integration.getArn());
            summary.put("Name", integration.getName());
            if (integration.getSourceURI() != null) {
                summary.put("SourceURI", integration.getSourceURI());
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/dataIntegrations/{id}/associations")
    @Consumes(MediaType.WILDCARD)
    public Response listDataIntegrationAssociations(
            @Context HttpHeaders headers, @PathParam("id") String id) {
        service.getDataIntegration(regionResolver.resolveRegion(headers), id);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DataIntegrationAssociations");
        return Response.ok(response).build();
    }

    @POST
    @Path("/dataIntegrations/{id}/associations")
    public Response createDataIntegrationAssociation(
            @Context HttpHeaders headers, @PathParam("id") String id, String body) {
        service.denyCreateDataIntegrationAssociation(regionResolver.resolveRegion(headers), id);
        return Response.ok().build();
    }

    @PATCH
    @Path("/dataIntegrations/{id}/associations/{associationId}")
    public Response updateDataIntegrationAssociation(
            @Context HttpHeaders headers,
            @PathParam("id") String id,
            @PathParam("associationId") String associationId) {
        service.getDataIntegration(regionResolver.resolveRegion(headers), id);
        throw new AwsException(
                "ResourceNotFoundException",
                "Data integration association " + associationId + " does not exist.",
                404);
    }

    @GET
    @Path("/dataIntegrations/{identifier: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getDataIntegration(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        DataIntegration integration = service.getDataIntegration(
                regionResolver.resolveRegion(headers), identifier);
        return Response.ok(toDataIntegrationNode(integration)).build();
    }

    @PATCH
    @Path("/dataIntegrations/{identifier: .+}")
    public Response updateDataIntegration(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        service.updateDataIntegration(regionResolver.resolveRegion(headers), identifier, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/dataIntegrations/{identifier: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDataIntegration(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        service.deleteDataIntegration(regionResolver.resolveRegion(headers), identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/eventIntegrations")
    public Response createEventIntegration(@Context HttpHeaders headers, String body) {
        EventIntegration integration = service.createEventIntegration(
                regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EventIntegrationArn", integration.getEventIntegrationArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/eventIntegrations")
    @Consumes(MediaType.WILDCARD)
    public Response listEventIntegrations(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppIntegrationsService.Page<EventIntegration> page = service.listEventIntegrations(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode integrations = response.putArray("EventIntegrations");
        for (EventIntegration integration : page.items()) {
            integrations.add(toEventIntegration(integration));
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/eventIntegrations/{name}/associations")
    @Consumes(MediaType.WILDCARD)
    public Response listEventIntegrationAssociations(
            @Context HttpHeaders headers, @PathParam("name") String name) {
        service.getEventIntegration(regionResolver.resolveRegion(headers), name);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("EventIntegrationAssociations");
        return Response.ok(response).build();
    }

    @GET
    @Path("/eventIntegrations/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response getEventIntegration(@Context HttpHeaders headers, @PathParam("name") String name) {
        EventIntegration integration = service.getEventIntegration(
                regionResolver.resolveRegion(headers), name);
        return Response.ok(toEventIntegration(integration)).build();
    }

    @PATCH
    @Path("/eventIntegrations/{name}")
    public Response updateEventIntegration(
            @Context HttpHeaders headers, @PathParam("name") String name, String body) {
        service.updateEventIntegration(regionResolver.resolveRegion(headers), name, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/eventIntegrations/{name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEventIntegration(@Context HttpHeaders headers, @PathParam("name") String name) {
        service.deleteEventIntegration(regionResolver.resolveRegion(headers), name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode toSummary(Application application) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("Arn", application.getArn());
        summary.put("Id", application.getId());
        summary.put("Name", application.getName());
        summary.put("Namespace", application.getNamespace());
        if (application.getCreatedTime() != null) {
            summary.put("CreatedTime", application.getCreatedTime());
        }
        if (application.getLastModifiedTime() != null) {
            summary.put("LastModifiedTime", application.getLastModifiedTime());
        }
        return summary;
    }

    private ObjectNode toDetail(Application application) {
        ObjectNode response = toSummary(application);
        if (application.getDescription() != null) {
            response.put("Description", application.getDescription());
        }
        ObjectNode sourceConfig = response.putObject("ApplicationSourceConfig");
        ObjectNode urlConfig = sourceConfig.putObject("ExternalUrlConfig");
        urlConfig.put("AccessUrl", application.getAccessUrl());
        putStringArray(urlConfig, "ApprovedOrigins", application.getApprovedOrigins());
        putStringArray(response, "Permissions", application.getPermissions());
        ObjectNode tags = response.putObject("Tags");
        if (application.getTags() != null) {
            for (Map.Entry<String, String> entry : application.getTags().entrySet()) {
                tags.put(entry.getKey(), entry.getValue());
            }
        }
        return response;
    }

    private ObjectNode toDataIntegrationNode(DataIntegration integration) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", integration.getArn());
        response.put("Id", integration.getId());
        response.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            response.put("Description", integration.getDescription());
        }
        response.put("KmsKey", integration.getKmsKey());
        if (integration.getSourceURI() != null) {
            response.put("SourceURI", integration.getSourceURI());
        }
        if (integration.getScheduleConfiguration() != null) {
            response.set("ScheduleConfiguration", integration.getScheduleConfiguration());
        }
        if (integration.getFileConfiguration() != null) {
            response.set("FileConfiguration", integration.getFileConfiguration());
        }
        if (integration.getObjectConfiguration() != null) {
            response.set("ObjectConfiguration", integration.getObjectConfiguration());
        }
        ObjectNode tags = response.putObject("Tags");
        if (integration.getTags() != null) {
            integration.getTags().forEach(tags::put);
        }
        return response;
    }

    private ObjectNode toEventIntegration(EventIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        node.put("EventIntegrationArn", integration.getEventIntegrationArn());
        node.put("EventBridgeBus", integration.getEventBridgeBus());
        if (integration.getEventFilter() != null) {
            node.set("EventFilter", integration.getEventFilter());
        }
        ObjectNode tags = node.putObject("Tags");
        if (integration.getTags() != null) {
            integration.getTags().forEach(tags::put);
        }
        return node;
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    private static void putStringArray(ObjectNode parent, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    private ObjectNode toDataIntegration(DataIntegration integration) {
        ObjectNode node = toDataIntegrationSummary(integration);
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        if (integration.getKmsKey() != null) {
            node.put("KmsKey", integration.getKmsKey());
        }
        if (integration.getClientToken() != null) {
            node.put("ClientToken", integration.getClientToken());
        }
        if (integration.getScheduleConfiguration() != null) {
            node.set("ScheduleConfiguration", integration.getScheduleConfiguration());
        }
        if (integration.getFileConfiguration() != null) {
            node.set("FileConfiguration", integration.getFileConfiguration());
        }
        if (integration.getObjectConfiguration() != null) {
            node.set("ObjectConfiguration", integration.getObjectConfiguration());
        }
        ObjectNode tags = node.putObject("Tags");
        if (integration.getTags() != null) {
            for (Map.Entry<String, String> entry : integration.getTags().entrySet()) {
                tags.put(entry.getKey(), entry.getValue());
            }
        }
        return node;
    }

    private ObjectNode toDataIntegrationSummary(DataIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", integration.getArn());
        node.put("Id", integration.getId());
        node.put("Name", integration.getName());
        if (integration.getSourceURI() != null) {
            node.put("SourceURI", integration.getSourceURI());
        }
        return node;
    }
}
