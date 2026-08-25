package io.github.hectorvent.floci.services.appregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appregistry.model.Application;
import io.github.hectorvent.floci.services.appregistry.model.Application.AssociatedResource;
import io.github.hectorvent.floci.services.appregistry.model.AttributeGroup;
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

import java.util.Map;

/**
 * Service Catalog AppRegistry restJson1.
 *
 * <p>{@link AppRegistryRoutingFilter} prefixes public {@code /applications} and
 * {@code /attribute-groups} paths so they do not collide with AppConfig.
 * Tag APIs share {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 */
@Path(AppRegistryRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppRegistryController {

    private final AppRegistryService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public AppRegistryController(
            AppRegistryService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/sync/{resourceType}/{resource: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response syncResource(
            @Context HttpHeaders headers,
            @PathParam("resourceType") String resourceType,
            @PathParam("resource") String resource) {
        AppRegistryService.SyncResourceResult result = service.syncResource(
                regionResolver.resolveRegion(headers), resourceType, resource);
        ObjectNode response = objectMapper.createObjectNode();
        if (result.applicationArn() != null) {
            response.put("applicationArn", result.applicationArn());
        }
        if (result.resourceArn() != null) {
            response.put("resourceArn", result.resourceArn());
        }
        response.put("actionTaken", result.actionTaken());
        return Response.ok(response).build();
    }

    @POST
    @Path("/applications")
    public Response createApplication(@Context HttpHeaders headers, String body) {
        Application application = service.createApplication(regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("application", toApplicationSummary(application));
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications")
    @Consumes(MediaType.WILDCARD)
    public Response listApplications(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppRegistryService.Page<Application> page = service.listApplications(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode applications = response.putArray("applications");
        for (Application application : page.items()) {
            applications.add(toApplicationSummary(application));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications/{application}/attribute-groups")
    @Consumes(MediaType.WILDCARD)
    public Response listAssociatedAttributeGroups(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppRegistryService.Page<String> page = service.listAssociatedAttributeGroups(
                regionResolver.resolveRegion(headers), application, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("attributeGroups");
        page.items().forEach(groups::add);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications/{application}/attribute-group-details")
    @Consumes(MediaType.WILDCARD)
    public Response listAttributeGroupsForApplication(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppRegistryService.Page<AttributeGroup> page = service.listAttributeGroupsForApplication(
                regionResolver.resolveRegion(headers), application, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("attributeGroupsDetails");
        for (AttributeGroup group : page.items()) {
            ObjectNode detail = groups.addObject();
            detail.put("id", group.getId());
            detail.put("arn", group.getArn());
            detail.put("name", group.getName());
            if (group.getCreatedBy() != null) {
                detail.put("createdBy", group.getCreatedBy());
            }
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/applications/{application}/attribute-groups/{attributeGroup}")
    public Response associateAttributeGroup(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @PathParam("attributeGroup") String attributeGroup) {
        AppRegistryService.AssociateAttributeGroupResult result = service.associateAttributeGroup(
                regionResolver.resolveRegion(headers), application, attributeGroup);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationArn", result.applicationArn());
        response.put("attributeGroupArn", result.attributeGroupArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/applications/{application}/attribute-groups/{attributeGroup}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateAttributeGroup(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @PathParam("attributeGroup") String attributeGroup) {
        AppRegistryService.AssociateAttributeGroupResult result = service.disassociateAttributeGroup(
                regionResolver.resolveRegion(headers), application, attributeGroup);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationArn", result.applicationArn());
        response.put("attributeGroupArn", result.attributeGroupArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications/{application}/resources")
    @Consumes(MediaType.WILDCARD)
    public Response listAssociatedResources(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        AppRegistryService.Page<AssociatedResource> page = service.listAssociatedResources(
                regionResolver.resolveRegion(headers), application, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resources = response.putArray("resources");
        for (AssociatedResource resource : page.items()) {
            resources.add(toResourceInfo(resource));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications/{application}/resources/{resourceType}/{resource}")
    @Consumes(MediaType.WILDCARD)
    public Response getAssociatedResource(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @PathParam("resourceType") String resourceType,
            @PathParam("resource") String resource) {
        AssociatedResource associated = service.getAssociatedResource(
                regionResolver.resolveRegion(headers), application, resourceType, resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resource", toResource(associated));
        ArrayNode options = response.putArray("options");
        associated.getOptions().forEach(options::add);
        return Response.ok(response).build();
    }

    @PUT
    @Path("/applications/{application}/resources/{resourceType}/{resource}")
    public Response associateResource(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @PathParam("resourceType") String resourceType,
            @PathParam("resource") String resource,
            String body) {
        AppRegistryService.AssociateResourceResult result = service.associateResource(
                regionResolver.resolveRegion(headers), application, resourceType, resource, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationArn", result.applicationArn());
        response.put("resourceArn", result.resourceArn());
        ArrayNode options = response.putArray("options");
        result.options().forEach(options::add);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/applications/{application}/resources/{resourceType}/{resource}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateResource(
            @Context HttpHeaders headers,
            @PathParam("application") String application,
            @PathParam("resourceType") String resourceType,
            @PathParam("resource") String resource) {
        AppRegistryService.AssociateResourceResult result = service.disassociateResource(
                regionResolver.resolveRegion(headers), application, resourceType, resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationArn", result.applicationArn());
        response.put("resourceArn", result.resourceArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/applications/{application}")
    @Consumes(MediaType.WILDCARD)
    public Response getApplication(
            @Context HttpHeaders headers, @PathParam("application") String application) {
        return Response.ok(toApplicationDetail(
                service.getApplication(regionResolver.resolveRegion(headers), application))).build();
    }

    @PATCH
    @Path("/applications/{application}")
    public Response updateApplication(
            @Context HttpHeaders headers, @PathParam("application") String application, String body) {
        Application updated = service.updateApplication(
                regionResolver.resolveRegion(headers), application, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("application", toApplicationSummary(updated));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/applications/{application}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteApplication(
            @Context HttpHeaders headers, @PathParam("application") String application) {
        Application deleted = service.deleteApplication(regionResolver.resolveRegion(headers), application);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("application", toApplicationSummary(deleted));
        return Response.ok(response).build();
    }

    @POST
    @Path("/attribute-groups")
    public Response createAttributeGroup(@Context HttpHeaders headers, String body) {
        try {
            AttributeGroup group = service.createAttributeGroup(regionResolver.resolveRegion(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("attributeGroup", toAttributeGroupSummary(group));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/attribute-groups")
    @Consumes(MediaType.WILDCARD)
    public Response listAttributeGroups(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        try {
            AppRegistryService.Page<AttributeGroup> page = service.listAttributeGroups(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode groups = response.putArray("attributeGroups");
            for (AttributeGroup group : page.items()) {
                groups.add(toAttributeGroupSummary(group));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @GET
    @Path("/attribute-groups/{attributeGroup: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response getAttributeGroup(
            @Context HttpHeaders headers, @PathParam("attributeGroup") String attributeGroup) {
        try {
            return Response.ok(toAttributeGroupDetail(
                    service.getAttributeGroup(regionResolver.resolveRegion(headers), attributeGroup))).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @PATCH
    @Path("/attribute-groups/{attributeGroup: .+}")
    public Response updateAttributeGroup(
            @Context HttpHeaders headers, @PathParam("attributeGroup") String attributeGroup, String body) {
        try {
            AttributeGroup updated = service.updateAttributeGroup(
                    regionResolver.resolveRegion(headers), attributeGroup, parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.set("attributeGroup", toAttributeGroupSummary(updated));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/attribute-groups/{attributeGroup: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAttributeGroup(
            @Context HttpHeaders headers, @PathParam("attributeGroup") String attributeGroup) {
        try {
            AttributeGroup deleted = service.deleteAttributeGroup(
                    regionResolver.resolveRegion(headers), attributeGroup);
            ObjectNode response = objectMapper.createObjectNode();
            response.set("attributeGroup", toAttributeGroupSummary(deleted));
            return Response.ok(response).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    private ObjectNode toApplicationSummary(Application application) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", application.getId());
        node.put("arn", application.getArn());
        node.put("name", application.getName());
        if (application.getDescription() != null) {
            node.put("description", application.getDescription());
        }
        node.put("creationTime", application.getCreationTime());
        node.put("lastUpdateTime", application.getLastUpdateTime());
        putTags(node, application.getTags());
        ObjectNode applicationTag = node.putObject("applicationTag");
        applicationTag.put("awsApplication", application.getArn());
        return node;
    }

    private ObjectNode toApplicationDetail(Application application) {
        ObjectNode node = toApplicationSummary(application);
        node.put("associatedResourceCount", application.getAssociatedResources().size());
        return node;
    }

    private ObjectNode toAttributeGroupSummary(AttributeGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", group.getId());
        node.put("arn", group.getArn());
        node.put("name", group.getName());
        if (group.getDescription() != null) {
            node.put("description", group.getDescription());
        }
        node.put("creationTime", group.getCreationTime());
        node.put("lastUpdateTime", group.getLastUpdateTime());
        if (group.getCreatedBy() != null) {
            node.put("createdBy", group.getCreatedBy());
        }
        putTags(node, group.getTags());
        return node;
    }

    private ObjectNode toAttributeGroupDetail(AttributeGroup group) {
        ObjectNode node = toAttributeGroupSummary(group);
        node.put("attributes", group.getAttributes());
        return node;
    }

    private ObjectNode toResource(AssociatedResource resource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", resource.getName());
        node.put("arn", resource.getArn());
        if (resource.getAssociationTime() != null) {
            node.put("associationTime", resource.getAssociationTime());
        }
        return node;
    }

    private ObjectNode toResourceInfo(AssociatedResource resource) {
        ObjectNode node = toResource(resource);
        node.put("resourceType", resource.getResourceType());
        ArrayNode options = node.putArray("options");
        resource.getOptions().forEach(options::add);
        return node;
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        ObjectNode node = parent.putObject("tags");
        if (tags != null) {
            tags.forEach(node::put);
        }
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
