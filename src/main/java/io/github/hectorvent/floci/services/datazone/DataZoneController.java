package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.datazone.model.Domain;
import io.github.hectorvent.floci.services.datazone.model.Environment;
import io.github.hectorvent.floci.services.datazone.model.Project;
import io.github.hectorvent.floci.services.datazone.model.UserProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
 * Amazon DataZone restJson1. Public AWS paths are {@code /v2/domains} and
 * peers; {@link DataZoneRoutingFilter} prefixes them so they do not collide
 * with S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are
 * dispatched by {@code SharedTagsController}. Requests are signed as
 * {@code datazone}.
 */
@Path(DataZoneRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneController {

    private final DataZoneService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneController(
            DataZoneService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v2/domains")
    public Response createDomain(@Context HttpHeaders headers, String body) {
        Domain domain = service.createDomain(region(headers), parse(body));
        return Response.ok(service.toDomain(domain)).build();
    }

    @GET
    @Path("/v2/domains")
    @Consumes(MediaType.WILDCARD)
    public Response listDomains(@Context HttpHeaders headers, @QueryParam("status") String status) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (Domain domain : service.listDomains(region(headers), status)) {
            items.add(service.toDomainSummary(domain));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v2/domains/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getDomain(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        return Response.ok(service.toDomain(service.getDomain(region(headers), identifier))).build();
    }

    @PUT
    @Path("/v2/domains/{identifier}")
    public Response updateDomain(
            @Context HttpHeaders headers, @PathParam("identifier") String identifier, String body) {
        Domain domain = service.updateDomain(region(headers), identifier, parse(body));
        return Response.ok(service.toUpdateDomain(domain)).build();
    }

    @DELETE
    @Path("/v2/domains/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDomain(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        Domain domain = service.deleteDomain(region(headers), identifier);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", domain.getStatus() == null ? "DELETED" : domain.getStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/projects")
    public Response createProject(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        Project project = service.createProject(region(headers), domainIdentifier, parse(body));
        return Response.ok(service.toProject(project)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/projects")
    @Consumes(MediaType.WILDCARD)
    public Response listProjects(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @QueryParam("name") String name) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (Project project : service.listProjects(region(headers), domainIdentifier, name)) {
            items.add(service.toProjectSummary(project));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/projects/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getProject(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier) {
        return Response.ok(service.toProject(
                service.getProject(region(headers), domainIdentifier, identifier))).build();
    }

    @PATCH
    @Path("/v2/domains/{domainIdentifier}/projects/{identifier}")
    public Response updateProject(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier,
            String body) {
        Project project = service.updateProject(region(headers), domainIdentifier, identifier, parse(body));
        return Response.ok(service.toProject(project)).build();
    }

    @DELETE
    @Path("/v2/domains/{domainIdentifier}/projects/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteProject(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier) {
        service.deleteProject(region(headers), domainIdentifier, identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/projects/{projectIdentifier}/createMembership")
    public Response createProjectMembership(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("projectIdentifier") String projectIdentifier,
            String body) {
        service.createProjectMembership(region(headers), domainIdentifier, projectIdentifier, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/environments")
    public Response createEnvironment(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        Environment environment = service.createEnvironment(region(headers), domainIdentifier, parse(body));
        return Response.ok(service.toEnvironment(environment)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/environments")
    @Consumes(MediaType.WILDCARD)
    public Response listEnvironments(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @QueryParam("projectIdentifier") String projectIdentifier,
            @QueryParam("name") String name,
            @QueryParam("status") String status) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (Environment environment : service.listEnvironments(
                region(headers), domainIdentifier, projectIdentifier, name, status)) {
            items.add(service.toEnvironmentSummary(environment));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/environments/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getEnvironment(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier) {
        return Response.ok(service.toEnvironment(
                service.getEnvironment(region(headers), domainIdentifier, identifier))).build();
    }

    @PATCH
    @Path("/v2/domains/{domainIdentifier}/environments/{identifier}")
    public Response updateEnvironment(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier,
            String body) {
        Environment environment = service.updateEnvironment(
                region(headers), domainIdentifier, identifier, parse(body));
        return Response.ok(service.toEnvironment(environment)).build();
    }

    @DELETE
    @Path("/v2/domains/{domainIdentifier}/environments/{identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEnvironment(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("identifier") String identifier) {
        service.deleteEnvironment(region(headers), domainIdentifier, identifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/environment-blueprints")
    @Consumes(MediaType.WILDCARD)
    public Response listEnvironmentBlueprints(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @QueryParam("name") String name,
            @QueryParam("managed") Boolean managed) {
        return Response.ok(service.listEnvironmentBlueprints(
                region(headers), domainIdentifier, name, managed)).build();
    }

    @PUT
    @Path("/v2/domains/{domainIdentifier}/environment-blueprint-configurations/{environmentBlueprintIdentifier}")
    public Response putEnvironmentBlueprintConfiguration(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("environmentBlueprintIdentifier") String environmentBlueprintIdentifier,
            String body) {
        return Response.ok(service.toBlueprintConfiguration(
                service.putEnvironmentBlueprintConfiguration(
                        region(headers), domainIdentifier, environmentBlueprintIdentifier, parse(body))))
                .build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/environment-blueprint-configurations/{environmentBlueprintIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getEnvironmentBlueprintConfiguration(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("environmentBlueprintIdentifier") String environmentBlueprintIdentifier) {
        return Response.ok(service.toBlueprintConfiguration(
                service.getEnvironmentBlueprintConfiguration(
                        region(headers), domainIdentifier, environmentBlueprintIdentifier)))
                .build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/environment-blueprint-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response listEnvironmentBlueprintConfigurations(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (var config : service.listEnvironmentBlueprintConfigurations(
                region(headers), domainIdentifier)) {
            items.add(service.toBlueprintConfiguration(config));
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/v2/domains/{domainIdentifier}/environment-blueprint-configurations/{environmentBlueprintIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteEnvironmentBlueprintConfiguration(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("environmentBlueprintIdentifier") String environmentBlueprintIdentifier) {
        service.deleteEnvironmentBlueprintConfiguration(
                region(headers), domainIdentifier, environmentBlueprintIdentifier);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/user-profiles")
    public Response createUserProfile(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        UserProfile profile = service.createUserProfile(region(headers), domainIdentifier, parse(body));
        return Response.ok(service.toUserProfile(profile)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/user-profiles/{userIdentifier:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getUserProfile(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            @PathParam("userIdentifier") String userIdentifier,
            @QueryParam("type") String type) {
        return Response.ok(service.toUserProfile(
                service.getUserProfile(region(headers), domainIdentifier, userIdentifier, type))).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/search")
    public Response search(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        return Response.ok(service.search(region(headers), domainIdentifier, parse(body))).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/listings/search")
    public Response searchListings(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        return Response.ok(service.searchListings(region(headers), domainIdentifier)).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/types-search")
    public Response searchTypes(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        return Response.ok(service.searchTypes(region(headers), domainIdentifier)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/subscriptions")
    @Consumes(MediaType.WILDCARD)
    public Response listSubscriptions(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier) {
        return Response.ok(service.listSubscriptions(region(headers), domainIdentifier)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/subscription-requests")
    @Consumes(MediaType.WILDCARD)
    public Response listSubscriptionRequests(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier) {
        return Response.ok(service.listSubscriptionRequests(region(headers), domainIdentifier)).build();
    }

    @GET
    @Path("/v2/domains/{domainIdentifier}/notifications")
    @Consumes(MediaType.WILDCARD)
    public Response listNotifications(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier) {
        return Response.ok(service.listNotifications(region(headers), domainIdentifier)).build();
    }

    @POST
    @Path("/v2/domains/{domainIdentifier}/get-portal-login-url")
    public Response getIamPortalLoginUrl(
            @Context HttpHeaders headers,
            @PathParam("domainIdentifier") String domainIdentifier,
            String body) {
        return Response.ok(service.getIamPortalLoginUrl(region(headers), domainIdentifier)).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
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
