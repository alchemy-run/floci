package io.floci.aws.services.appregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
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

/** The AWS Service Catalog AppRegistry REST API, routed here by {@link AppRegistryRouteFilter}. */
@Path(AppRegistryRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AppRegistryController {
    private static final String APP = "/applications/{application}";
    private static final String RESOURCE = APP + "/resources/{resourceType}/{resource}";

    private final AppRegistryService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public AppRegistryController(AppRegistryService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/applications")
    public Response createApplication(@Context HttpHeaders headers, String body) {
        return Response.status(201).entity(service.createApplication(parse(body), region(headers))).build();
    }

    @GET
    @Path("/applications")
    public Response listApplications(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return ok(service.listApplications(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(APP)
    public Response getApplication(@Context HttpHeaders headers, @PathParam("application") String application) {
        return ok(service.getApplication(application, region(headers)));
    }

    @PATCH
    @Path(APP)
    public Response updateApplication(@Context HttpHeaders headers, @PathParam("application") String application,
                                      String body) {
        return ok(service.updateApplication(application, parse(body), region(headers)));
    }

    @DELETE
    @Path(APP)
    public Response deleteApplication(@Context HttpHeaders headers, @PathParam("application") String application) {
        return ok(service.deleteApplication(application, region(headers)));
    }

    @GET
    @Path(APP + "/attribute-groups")
    public Response listAssociatedAttributeGroups(@Context HttpHeaders headers,
                                                  @PathParam("application") String application,
                                                  @QueryParam("maxResults") Integer maxResults,
                                                  @QueryParam("nextToken") String nextToken) {
        return ok(service.listAssociatedAttributeGroups(application, maxResults, nextToken, region(headers)));
    }

    @GET
    @Path(APP + "/attribute-group-details")
    public Response listAttributeGroupsForApplication(@Context HttpHeaders headers,
                                                      @PathParam("application") String application,
                                                      @QueryParam("maxResults") Integer maxResults,
                                                      @QueryParam("nextToken") String nextToken) {
        return ok(service.listAttributeGroupsForApplication(application, maxResults, nextToken, region(headers)));
    }

    @PUT
    @Path(APP + "/attribute-groups/{attributeGroup}")
    public Response associateAttributeGroup(@Context HttpHeaders headers,
                                            @PathParam("application") String application,
                                            @PathParam("attributeGroup") String attributeGroup) {
        return ok(service.associateAttributeGroup(application, attributeGroup, region(headers)));
    }

    @DELETE
    @Path(APP + "/attribute-groups/{attributeGroup}")
    public Response disassociateAttributeGroup(@Context HttpHeaders headers,
                                               @PathParam("application") String application,
                                               @PathParam("attributeGroup") String attributeGroup) {
        return ok(service.disassociateAttributeGroup(application, attributeGroup, region(headers)));
    }

    @GET
    @Path(APP + "/resources")
    public Response listAssociatedResources(@Context HttpHeaders headers,
                                            @PathParam("application") String application,
                                            @QueryParam("maxResults") Integer maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        return ok(service.listAssociatedResources(application, maxResults, nextToken, region(headers)));
    }

    @PUT
    @Path(RESOURCE)
    public Response associateResource(@Context HttpHeaders headers, @PathParam("application") String application,
                                      @PathParam("resourceType") String resourceType,
                                      @PathParam("resource") String resource, String body) {
        return ok(service.associateResource(application, resourceType, resource, parse(body), region(headers)));
    }

    @DELETE
    @Path(RESOURCE)
    public Response disassociateResource(@Context HttpHeaders headers, @PathParam("application") String application,
                                         @PathParam("resourceType") String resourceType,
                                         @PathParam("resource") String resource) {
        return ok(service.disassociateResource(application, resourceType, resource, region(headers)));
    }

    @GET
    @Path(RESOURCE)
    public Response getAssociatedResource(@Context HttpHeaders headers, @PathParam("application") String application,
                                          @PathParam("resourceType") String resourceType,
                                          @PathParam("resource") String resource) {
        return ok(service.getAssociatedResource(application, resourceType, resource, region(headers)));
    }

    @POST
    @Path("/attribute-groups")
    public Response createAttributeGroup(@Context HttpHeaders headers, String body) {
        return Response.status(201).entity(service.createAttributeGroup(parse(body), region(headers))).build();
    }

    @GET
    @Path("/attribute-groups")
    public Response listAttributeGroups(@Context HttpHeaders headers, @QueryParam("maxResults") Integer maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        return ok(service.listAttributeGroups(maxResults, nextToken, region(headers)));
    }

    @GET
    @Path("/attribute-groups/{attributeGroup}")
    public Response getAttributeGroup(@Context HttpHeaders headers,
                                      @PathParam("attributeGroup") String attributeGroup) {
        return ok(service.getAttributeGroup(attributeGroup, region(headers)));
    }

    @PATCH
    @Path("/attribute-groups/{attributeGroup}")
    public Response updateAttributeGroup(@Context HttpHeaders headers,
                                         @PathParam("attributeGroup") String attributeGroup, String body) {
        return ok(service.updateAttributeGroup(attributeGroup, parse(body), region(headers)));
    }

    @DELETE
    @Path("/attribute-groups/{attributeGroup}")
    public Response deleteAttributeGroup(@Context HttpHeaders headers,
                                         @PathParam("attributeGroup") String attributeGroup) {
        return ok(service.deleteAttributeGroup(attributeGroup, region(headers)));
    }

    @GET
    @Path("/configuration")
    public Response getConfiguration(@Context HttpHeaders headers) {
        return ok(service.getConfiguration(region(headers)));
    }

    @PUT
    @Path("/configuration")
    public Response putConfiguration(@Context HttpHeaders headers, String body) {
        service.putConfiguration(parse(body), region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/sync/{resourceType}/{resource}")
    public Response syncResource(@Context HttpHeaders headers, @PathParam("resourceType") String resourceType,
                                 @PathParam("resource") String resource) {
        return ok(service.syncResource(resourceType, resource, region(headers)));
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
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
