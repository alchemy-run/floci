package io.floci.aws.services.greengrassv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.function.Supplier;

/** The AWS IoT Greengrass V2 REST API, routed here by {@link GreengrassV2RouteFilter}. */
@Path(GreengrassV2RouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class GreengrassV2Controller {

    private final GreengrassV2Service service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public GreengrassV2Controller(GreengrassV2Service service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- components

    @POST
    @Path("/greengrass/v2/createComponentVersion")
    public Response createComponentVersion(String body) {
        return reply(201, () -> service.createComponentVersion(parse(body), region()));
    }

    @GET
    @Path("/greengrass/v2/components")
    public Response listComponents(@QueryParam("scope") String scope, @QueryParam("maxResults") String maxResults,
                                   @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listComponents(scope, maxResults, nextToken, region()));
    }

    @GET
    @Path("/greengrass/v2/components/{arn}")
    public Response getComponent(@PathParam("arn") String arn,
                                 @QueryParam("recipeOutputFormat") String recipeOutputFormat) {
        return reply(200, () -> service.getComponent(arn, recipeOutputFormat, region()));
    }

    @GET
    @Path("/greengrass/v2/components/{arn}/metadata")
    public Response describeComponent(@PathParam("arn") String arn) {
        return reply(200, () -> service.describeComponent(arn, region()));
    }

    @GET
    @Path("/greengrass/v2/components/{arn}/versions")
    public Response listComponentVersions(@PathParam("arn") String arn, @QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listComponentVersions(arn, maxResults, nextToken, region()));
    }

    @GET
    @Path("/greengrass/v2/components/{arn}/artifacts/{artifactName: .+}")
    public Response getComponentVersionArtifact(@PathParam("arn") String arn,
                                                @PathParam("artifactName") String artifactName) {
        return reply(200, () -> service.getComponentVersionArtifact(arn, artifactName, region()));
    }

    @DELETE
    @Path("/greengrass/v2/components/{arn}")
    public Response deleteComponent(@PathParam("arn") String arn) {
        return empty(204, () -> service.deleteComponent(arn, region()));
    }

    @POST
    @Path("/greengrass/v2/resolveComponentCandidates")
    public Response resolveComponentCandidates(@HeaderParam("Authorization") String authorization,
                                               @Context UriInfo uriInfo, String body) {
        boolean signed = (authorization != null && authorization.startsWith("AWS4-HMAC-SHA256"))
                || uriInfo.getQueryParameters().containsKey("X-Amz-Signature");
        return reply(200, () -> service.resolveComponentCandidates(parse(body), signed, region()));
    }

    // ---------------------------------------------------------------- deployments

    @POST
    @Path("/greengrass/v2/deployments")
    public Response createDeployment(String body) {
        return reply(201, () -> service.createDeployment(parse(body), region()));
    }

    @GET
    @Path("/greengrass/v2/deployments")
    public Response listDeployments(@QueryParam("targetArn") String targetArn,
                                    @QueryParam("historyFilter") String historyFilter,
                                    @QueryParam("parentTargetArn") String parentTargetArn,
                                    @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listDeployments(targetArn, historyFilter, parentTargetArn, maxResults,
                nextToken, region()));
    }

    @GET
    @Path("/greengrass/v2/deployments/{deploymentId}")
    public Response getDeployment(@PathParam("deploymentId") String deploymentId) {
        return reply(200, () -> service.getDeployment(deploymentId, region()));
    }

    @POST
    @Path("/greengrass/v2/deployments/{deploymentId}/cancel")
    public Response cancelDeployment(@PathParam("deploymentId") String deploymentId) {
        return reply(200, () -> service.cancelDeployment(deploymentId, region()));
    }

    @DELETE
    @Path("/greengrass/v2/deployments/{deploymentId}")
    public Response deleteDeployment(@PathParam("deploymentId") String deploymentId) {
        return empty(204, () -> service.deleteDeployment(deploymentId, region()));
    }

    // ---------------------------------------------------------------- core devices

    @GET
    @Path("/greengrass/v2/coreDevices")
    public Response listCoreDevices(@QueryParam("thingGroupArn") String thingGroupArn,
                                    @QueryParam("status") String status, @QueryParam("runtime") String runtime,
                                    @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listCoreDevices(thingGroupArn, status, runtime, maxResults, nextToken,
                region()));
    }

    @GET
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}")
    public Response getCoreDevice(@PathParam("coreDeviceThingName") String thing) {
        return reply(200, () -> service.getCoreDevice(thing, region()));
    }

    @DELETE
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}")
    public Response deleteCoreDevice(@PathParam("coreDeviceThingName") String thing) {
        return empty(204, () -> service.deleteCoreDevice(thing, region()));
    }

    @GET
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}/effectiveDeployments")
    public Response listEffectiveDeployments(@PathParam("coreDeviceThingName") String thing,
                                             @QueryParam("maxResults") String maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listEffectiveDeployments(thing, maxResults, nextToken, region()));
    }

    @GET
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}/installedComponents")
    public Response listInstalledComponents(@PathParam("coreDeviceThingName") String thing,
                                            @QueryParam("topologyFilter") String topologyFilter,
                                            @QueryParam("maxResults") String maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listInstalledComponents(thing, topologyFilter, maxResults, nextToken,
                region()));
    }

    @POST
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}/associateClientDevices")
    public Response batchAssociateClientDeviceWithCoreDevice(@PathParam("coreDeviceThingName") String thing,
                                                             String body) {
        return reply(200, () -> service.associateClientDevices(thing, parse(body), region()));
    }

    @POST
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}/disassociateClientDevices")
    public Response batchDisassociateClientDeviceFromCoreDevice(@PathParam("coreDeviceThingName") String thing,
                                                                String body) {
        return reply(200, () -> service.disassociateClientDevices(thing, parse(body), region()));
    }

    @GET
    @Path("/greengrass/v2/coreDevices/{coreDeviceThingName}/associatedClientDevices")
    public Response listClientDevicesAssociatedWithCoreDevice(@PathParam("coreDeviceThingName") String thing,
                                                              @QueryParam("maxResults") String maxResults,
                                                              @QueryParam("nextToken") String nextToken) {
        return reply(200, () -> service.listClientDevices(thing, maxResults, nextToken, region()));
    }

    // ---------------------------------------------------------------- connectivity and the service role

    @GET
    @Path("/greengrass/things/{thingName}/connectivityInfo")
    public Response getConnectivityInfo(@PathParam("thingName") String thing) {
        return reply(200, () -> service.getConnectivityInfo(thing, region()));
    }

    @PUT
    @Path("/greengrass/things/{thingName}/connectivityInfo")
    public Response updateConnectivityInfo(@PathParam("thingName") String thing, String body) {
        return reply(200, () -> service.updateConnectivityInfo(thing, parse(body), region()));
    }

    @PUT
    @Path("/greengrass/servicerole")
    public Response associateServiceRoleToAccount(String body) {
        return reply(200, () -> service.associateServiceRole(parse(body), region()));
    }

    @GET
    @Path("/greengrass/servicerole")
    public Response getServiceRoleForAccount() {
        return reply(200, () -> service.getServiceRole(region()));
    }

    @DELETE
    @Path("/greengrass/servicerole")
    public Response disassociateServiceRoleFromAccount() {
        return reply(200, () -> service.disassociateServiceRole(region()));
    }

    // ---------------------------------------------------------------- tags

    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(@PathParam("resourceArn") String resourceArn) {
        return reply(200, () -> service.listTags(resourceArn, region()));
    }

    @POST
    @Path("/tags/{resourceArn: .+}")
    public Response tagResource(@PathParam("resourceArn") String resourceArn, String body) {
        return empty(200, () -> service.tagResource(resourceArn, parse(body), region()));
    }

    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return empty(200, () -> service.untagResource(resourceArn, tagKeys, region()));
    }

    // ---------------------------------------------------------------- shared

    private String region() {
        return regions.getRegion();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw GreengrassV2Service.validation("The request body must be a JSON object.");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw GreengrassV2Service.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private Response empty(int status, Runnable call) {
        return reply(status, () -> {
            call.run();
            return null;
        });
    }

    private Response reply(int status, Supplier<ObjectNode> call) {
        try {
            ObjectNode body = call.get();
            Response.ResponseBuilder builder = Response.status(status);
            if (body != null) {
                builder.type(MediaType.APPLICATION_JSON).entity(body.toString());
            } else if (status == 200) {
                builder.type(MediaType.APPLICATION_JSON).entity("{}");
            }
            return builder.build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("message", e.getMessage());
            if (e.getExtendedData() != null) {
                e.getExtendedData().forEach((field, value) -> error.set(field, mapper.valueToTree(value)));
            }
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }
}
