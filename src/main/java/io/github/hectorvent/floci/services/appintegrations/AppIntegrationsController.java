package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Amazon AppIntegrations REST-JSON controller (API version 2020-07-29).
 *
 * <p>Tag operations live on the shared {@code /tags/{resourceArn}} path and are served by
 * {@code SharedTagsController} through {@link AppIntegrationsService}'s {@code TagHandler}.
 * Only the operations declared below are served; anything else falls through to the
 * emulator's not-found handling rather than a stub success.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppIntegrationsController {

    private final AppIntegrationsService appIntegrationsService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AppIntegrationsController(AppIntegrationsService appIntegrationsService,
                                     RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.appIntegrationsService = appIntegrationsService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH)
    public Response createApplication(@Context HttpHeaders headers, String body) {
        return Response.ok(appIntegrationsService.createApplication(readTree(body),
                regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH + "/{identifier:.+}")
    public Response getApplication(@PathParam("identifier") String identifier, @Context HttpHeaders headers) {
        return Response.ok(appIntegrationsService.getApplication(identifier,
                regionResolver.resolveRegion(headers))).build();
    }

    @PATCH
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH + "/{identifier:.+}")
    public Response updateApplication(@PathParam("identifier") String identifier,
                                      @Context HttpHeaders headers, String body) {
        appIntegrationsService.updateApplication(identifier, readTree(body), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH + "/{identifier:.+}")
    public Response deleteApplication(@PathParam("identifier") String identifier, @Context HttpHeaders headers) {
        appIntegrationsService.deleteApplication(identifier, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH)
    public Response listApplications(@Context HttpHeaders headers,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken,
                                     @QueryParam("applicationType") String applicationType) {
        return page("Applications", appIntegrationsService.listApplications(
                        regionResolver.resolveRegion(headers), applicationType),
                app -> app.path("Id").asText(), this::applicationSummary, maxResults, nextToken);
    }

    @GET
    @Path(AppIntegrationsRouteFilter.APPLICATIONS_PATH + "/{identifier:.+}/associations")
    public Response listApplicationAssociations(@PathParam("identifier") String identifier,
                                               @Context HttpHeaders headers,
                                               @QueryParam("maxResults") String maxResults,
                                               @QueryParam("nextToken") String nextToken) {
        return page("ApplicationAssociations", appIntegrationsService.listApplicationAssociations(
                        identifier, regionResolver.resolveRegion(headers)),
                association -> association.path("ApplicationAssociationArn").asText(),
                Function.identity(), maxResults, nextToken);
    }

    @POST
    @Path("/eventIntegrations")
    public Response createEventIntegration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ObjectNode response = appIntegrationsService.createIdempotently("CreateEventIntegration", request, region, () -> {
            EventIntegration integration = appIntegrationsService.createEventIntegration(
                    textOrNull(request, "Name"), textOrNull(request, "Description"), request.get("EventFilter"),
                    textOrNull(request, "EventBridgeBus"), parseTags(request.get("Tags")), region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("EventIntegrationArn", integration.getEventIntegrationArn());
            return result;
        });
        return Response.ok(response).build();
    }

    @GET
    @Path("/eventIntegrations/{name}")
    public Response getEventIntegration(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        EventIntegration integration = appIntegrationsService.getEventIntegration(name, region);
        return Response.ok(eventIntegrationNode(integration)).build();
    }

    @PATCH
    @Path("/eventIntegrations/{name}")
    public Response updateEventIntegration(@PathParam("name") String name,
                                           @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        appIntegrationsService.updateEventIntegration(name, optionalText(request, "Description"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/eventIntegrations/{name}")
    public Response deleteEventIntegration(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        appIntegrationsService.deleteEventIntegration(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/eventIntegrations")
    public Response listEventIntegrations(@Context HttpHeaders headers,
                                         @QueryParam("maxResults") String maxResults,
                                         @QueryParam("nextToken") String nextToken) {
        return page("EventIntegrations", appIntegrationsService.listEventIntegrations(regionResolver.resolveRegion(headers)),
                EventIntegration::getName, this::eventIntegrationNode, maxResults, nextToken);
    }

    /**
     * Associations are created by the consuming service (Amazon Connect and friends)
     * when it binds a client to the integration. Floci has no path that creates one, so
     * an existing integration always reports an empty list; a missing one still raises
     * ResourceNotFoundException.
     */
    @GET
    @Path("/eventIntegrations/{name}/associations")
    public Response listEventIntegrationAssociations(@PathParam("name") String name,
                                                     @Context HttpHeaders headers,
                                                     @QueryParam("maxResults") String maxResults,
                                                     @QueryParam("nextToken") String nextToken) {
        appIntegrationsService.getEventIntegration(name, regionResolver.resolveRegion(headers));
        return page("EventIntegrationAssociations", List.<ObjectNode>of(),
                association -> association.path("EventIntegrationAssociationArn").asText(),
                Function.identity(), maxResults, nextToken);
    }

    @POST
    @Path("/dataIntegrations")
    public Response createDataIntegration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ObjectNode response = appIntegrationsService.createIdempotently("CreateDataIntegration", request, region, () -> {
            DataIntegration integration = appIntegrationsService.createDataIntegration(
                    textOrNull(request, "Name"), textOrNull(request, "Description"), textOrNull(request, "KmsKey"),
                    textOrNull(request, "SourceURI"), request.get("ScheduleConfig"), request.get("FileConfiguration"),
                    request.get("ObjectConfiguration"), parseTags(request.get("Tags")), region);
            ObjectNode result = dataIntegrationNode(integration);
            if (request.hasNonNull("ClientToken")) {
                result.put("ClientToken", textOrNull(request, "ClientToken"));
            }
            return result;
        });
        return Response.ok(response).build();
    }

    @GET
    @Path("/dataIntegrations/{identifier}")
    public Response getDataIntegration(@PathParam("identifier") String identifier,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        DataIntegration integration = appIntegrationsService.getDataIntegration(identifier, region);
        return Response.ok(dataIntegrationNode(integration)).build();
    }

    @PATCH
    @Path("/dataIntegrations/{identifier}")
    public Response updateDataIntegration(@PathParam("identifier") String identifier,
                                          @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        appIntegrationsService.updateDataIntegration(identifier, optionalText(request, "Name"),
                optionalText(request, "Description"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/dataIntegrations/{identifier}")
    public Response deleteDataIntegration(@PathParam("identifier") String identifier,
                                          @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        appIntegrationsService.deleteDataIntegration(identifier, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/dataIntegrations")
    public Response listDataIntegrations(@Context HttpHeaders headers,
                                        @QueryParam("maxResults") String maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        return page("DataIntegrations", appIntegrationsService.listDataIntegrations(regionResolver.resolveRegion(headers)),
                DataIntegration::getId, integration -> {
                    ObjectNode summary = objectMapper.createObjectNode();
                    summary.put("Arn", integration.getArn());
                    summary.put("Name", integration.getName());
                    if (integration.getSourceUri() != null) {
                        summary.put("SourceURI", integration.getSourceUri());
                    }
                    return summary;
                }, maxResults, nextToken);
    }

    @GET
    @Path("/dataIntegrations/{identifier}/associations")
    public Response listDataIntegrationAssociations(@PathParam("identifier") String identifier,
                                                   @Context HttpHeaders headers,
                                                   @QueryParam("maxResults") String maxResults,
                                                   @QueryParam("nextToken") String nextToken) {
        return page("DataIntegrationAssociations", appIntegrationsService.listDataIntegrationAssociations(
                        identifier, regionResolver.resolveRegion(headers)),
                association -> association.path("DataIntegrationAssociationArn").asText(),
                Function.identity(), maxResults, nextToken);
    }

    @POST
    @Path("/dataIntegrations/{identifier}/associations")
    public Response createDataIntegrationAssociation(@PathParam("identifier") String identifier,
                                                     @Context HttpHeaders headers, String body) {
        readTree(body);
        throw appIntegrationsService.dataIntegrationAssociationWriteDenied(identifier,
                "CreateDataIntegrationAssociation", regionResolver.resolveRegion(headers));
    }

    @PATCH
    @Path("/dataIntegrations/{identifier}/associations/{associationId}")
    public Response updateDataIntegrationAssociation(@PathParam("identifier") String identifier,
                                                     @Context HttpHeaders headers, String body) {
        readTree(body);
        throw appIntegrationsService.dataIntegrationAssociationWriteDenied(identifier,
                "UpdateDataIntegrationAssociation", regionResolver.resolveRegion(headers));
    }

    private ObjectNode applicationSummary(ObjectNode application) {
        ObjectNode summary = objectMapper.createObjectNode();
        for (String field : List.of("Arn", "Id", "Name", "Namespace", "CreatedTime", "LastModifiedTime",
                "IsService", "ApplicationType")) {
            setIfPresent(summary, field, application.get(field));
        }
        return summary;
    }

    private <T> Response page(String field, List<T> values, Function<T, String> cursor,
                              Function<T, ObjectNode> serialize, String maxResults, String nextToken) {
        PaginatedResult<T> page = Pagination.paginate(values, cursor,
                Pagination.parseMaxResults(maxResults, "InvalidRequestException"), nextToken,
                50, "InvalidRequestException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray(field);
        page.items().forEach(item -> entries.add(serialize.apply(item)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode eventIntegrationNode(EventIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        node.put("EventIntegrationArn", integration.getEventIntegrationArn());
        node.put("EventBridgeBus", integration.getEventBridgeBus());
        node.putObject("EventFilter").put("Source", integration.getEventFilterSource());
        node.set("Tags", objectMapper.valueToTree(
                integration.getTags() != null ? integration.getTags() : Map.of()));
        return node;
    }

    private ObjectNode dataIntegrationNode(DataIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", integration.getArn());
        node.put("Id", integration.getId());
        node.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        node.put("KmsKey", integration.getKmsKey());
        if (integration.getSourceUri() != null) {
            node.put("SourceURI", integration.getSourceUri());
        }
        setIfPresent(node, "ScheduleConfiguration", integration.getScheduleConfiguration());
        setIfPresent(node, "FileConfiguration", integration.getFileConfiguration());
        setIfPresent(node, "ObjectConfiguration", integration.getObjectConfiguration());
        node.set("Tags", objectMapper.valueToTree(
                integration.getTags() != null ? integration.getTags() : Map.of()));
        return node;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private JsonNode readTree(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (request == null || !request.isObject()) {
                throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
            }
            return request;
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return optionalText(node, field).orElse(null);
    }

    private Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new AwsException("InvalidRequestException", field + " must be a string", 400);
        }
        return Optional.of(value.asText());
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isObject()) {
                throw new AwsException("InvalidRequestException", "Tags must be a map", 400);
            }
            tagsNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw new AwsException("InvalidRequestException", "Tag values must be strings", 400);
                }
                tags.put(entry.getKey(), entry.getValue().asText());
            });
        }
        return tags;
    }
}
