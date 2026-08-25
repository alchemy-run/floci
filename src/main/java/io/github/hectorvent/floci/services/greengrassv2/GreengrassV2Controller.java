package io.github.hectorvent.floci.services.greengrassv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.greengrassv2.model.ComponentVersion;
import io.github.hectorvent.floci.services.greengrassv2.model.Deployment;
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
 * IoT Greengrass V2 restJson1.
 *
 * <p>Literal {@code /greengrass/v2/...} and {@code /greengrass/things/...} paths
 * take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag APIs share
 * {@code /tags/{arn}} and are dispatched by {@code SharedTagsController}.
 * Requests are signed as {@code greengrass}.
 */
@Path("/greengrass")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GreengrassV2Controller {

    private final GreengrassV2Service service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public GreengrassV2Controller(
            GreengrassV2Service service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/v2/createComponentVersion")
    public Response createComponentVersion(@Context HttpHeaders headers, String body) {
        ComponentVersion version = service.createComponentVersion(
                regionResolver.resolveRegion(headers), parse(body));
        return Response.status(201).entity(service.toCreateComponentVersion(version)).build();
    }

    @GET
    @Path("/v2/components/{arn}/metadata")
    @Consumes(MediaType.WILDCARD)
    public Response describeComponent(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        ComponentVersion version = service.describeComponent(regionResolver.resolveRegion(headers), arn);
        return Response.ok(service.toDescribeComponent(version)).build();
    }

    @GET
    @Path("/v2/components/{arn}")
    @Consumes(MediaType.WILDCARD)
    public Response getComponent(
            @Context HttpHeaders headers,
            @PathParam("arn") String arn,
            @QueryParam("recipeOutputFormat") String recipeOutputFormat) {
        return Response.ok(service.getComponent(
                regionResolver.resolveRegion(headers), arn, recipeOutputFormat)).build();
    }

    @GET
    @Path("/v2/components/{arn}/versions")
    @Consumes(MediaType.WILDCARD)
    public Response listComponentVersions(
            @Context HttpHeaders headers,
            @PathParam("arn") String arn,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        GreengrassV2Service.Page<ComponentVersion> page = service.listComponentVersions(
                regionResolver.resolveRegion(headers), arn, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("componentVersions");
        for (ComponentVersion version : page.items()) {
            items.add(service.toComponentVersionListItem(version));
        }
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @GET
    @Path("/v2/components/{arn}/artifacts/{artifactName:.+}")
    @Consumes(MediaType.WILDCARD)
    public Response getComponentVersionArtifact(
            @Context HttpHeaders headers,
            @PathParam("arn") String arn,
            @PathParam("artifactName") String artifactName) {
        return Response.ok(service.getComponentVersionArtifact(
                regionResolver.resolveRegion(headers), arn, artifactName)).build();
    }

    @GET
    @Path("/v2/components")
    @Consumes(MediaType.WILDCARD)
    public Response listComponents(
            @Context HttpHeaders headers,
            @QueryParam("scope") String scope,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        GreengrassV2Service.Page<ComponentVersion> page = service.listComponents(
                regionResolver.resolveRegion(headers), scope, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode components = response.putArray("components");
        for (ComponentVersion version : page.items()) {
            components.add(service.toComponentSummary(version));
        }
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/v2/components/{arn}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteComponent(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        service.deleteComponent(regionResolver.resolveRegion(headers), arn);
        return Response.noContent().build();
    }

    @POST
    @Path("/v2/deployments")
    public Response createDeployment(@Context HttpHeaders headers, String body) {
        Deployment deployment = service.createDeployment(
                regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(service.toCreateDeployment(deployment)).build();
    }

    @GET
    @Path("/v2/deployments")
    @Consumes(MediaType.WILDCARD)
    public Response listDeployments(
            @Context HttpHeaders headers,
            @QueryParam("targetArn") String targetArn,
            @QueryParam("historyFilter") String historyFilter,
            @QueryParam("parentTargetArn") String parentTargetArn,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        GreengrassV2Service.Page<Deployment> page = service.listDeployments(
                regionResolver.resolveRegion(headers),
                targetArn,
                historyFilter,
                parentTargetArn,
                nextToken,
                maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode deployments = response.putArray("deployments");
        for (Deployment deployment : page.items()) {
            deployments.add(service.toDeploymentSummary(deployment));
        }
        putNextToken(response, page.nextToken());
        return Response.ok(response).build();
    }

    @GET
    @Path("/v2/deployments/{deploymentId}")
    @Consumes(MediaType.WILDCARD)
    public Response getDeployment(
            @Context HttpHeaders headers, @PathParam("deploymentId") String deploymentId) {
        Deployment deployment = service.getDeployment(
                regionResolver.resolveRegion(headers), deploymentId);
        return Response.ok(service.toGetDeployment(deployment)).build();
    }

    @POST
    @Path("/v2/deployments/{deploymentId}/cancel")
    @Consumes(MediaType.WILDCARD)
    public Response cancelDeployment(
            @Context HttpHeaders headers, @PathParam("deploymentId") String deploymentId) {
        Deployment deployment = service.cancelDeployment(
                regionResolver.resolveRegion(headers), deploymentId);
        return Response.ok(service.toCancelDeployment(deployment)).build();
    }

    @DELETE
    @Path("/v2/deployments/{deploymentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDeployment(
            @Context HttpHeaders headers, @PathParam("deploymentId") String deploymentId) {
        service.deleteDeployment(regionResolver.resolveRegion(headers), deploymentId);
        return Response.noContent().build();
    }

    @GET
    @Path("/v2/coreDevices")
    @Consumes(MediaType.WILDCARD)
    public Response listCoreDevices() {
        return Response.ok(service.listCoreDevices()).build();
    }

    @GET
    @Path("/v2/coreDevices/{coreDeviceThingName}")
    @Consumes(MediaType.WILDCARD)
    public Response getCoreDevice(@PathParam("coreDeviceThingName") String coreDeviceThingName) {
        return Response.ok(service.getCoreDevice(coreDeviceThingName)).build();
    }

    @DELETE
    @Path("/v2/coreDevices/{coreDeviceThingName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteCoreDevice(@PathParam("coreDeviceThingName") String coreDeviceThingName) {
        service.deleteCoreDevice(coreDeviceThingName);
        return Response.noContent().build();
    }

    @GET
    @Path("/v2/coreDevices/{coreDeviceThingName}/installedComponents")
    @Consumes(MediaType.WILDCARD)
    public Response listInstalledComponents(
            @PathParam("coreDeviceThingName") String coreDeviceThingName) {
        return Response.ok(service.listInstalledComponents()).build();
    }

    @GET
    @Path("/v2/coreDevices/{coreDeviceThingName}/effectiveDeployments")
    @Consumes(MediaType.WILDCARD)
    public Response listEffectiveDeployments(
            @PathParam("coreDeviceThingName") String coreDeviceThingName) {
        return Response.ok(service.listEffectiveDeployments()).build();
    }

    @GET
    @Path("/v2/coreDevices/{coreDeviceThingName}/associatedClientDevices")
    @Consumes(MediaType.WILDCARD)
    public Response listClientDevices(
            @Context HttpHeaders headers,
            @PathParam("coreDeviceThingName") String coreDeviceThingName) {
        return Response.ok(service.listClientDevices(
                regionResolver.resolveRegion(headers), coreDeviceThingName)).build();
    }

    @POST
    @Path("/v2/coreDevices/{coreDeviceThingName}/associateClientDevices")
    public Response batchAssociateClientDevices(
            @Context HttpHeaders headers,
            @PathParam("coreDeviceThingName") String coreDeviceThingName,
            String body) {
        return Response.ok(service.batchAssociateClientDevices(
                regionResolver.resolveRegion(headers), coreDeviceThingName, parse(body))).build();
    }

    @POST
    @Path("/v2/coreDevices/{coreDeviceThingName}/disassociateClientDevices")
    public Response batchDisassociateClientDevices(
            @Context HttpHeaders headers,
            @PathParam("coreDeviceThingName") String coreDeviceThingName,
            String body) {
        return Response.ok(service.batchDisassociateClientDevices(
                regionResolver.resolveRegion(headers), coreDeviceThingName, parse(body))).build();
    }

    @POST
    @Path("/v2/resolveComponentCandidates")
    public Response resolveComponentCandidates() {
        service.resolveComponentCandidates();
        return Response.ok().build();
    }

    @GET
    @Path("/things/{thingName}/connectivityInfo")
    @Consumes(MediaType.WILDCARD)
    public Response getConnectivityInfo(
            @Context HttpHeaders headers, @PathParam("thingName") String thingName) {
        return Response.ok(service.getConnectivityInfo(
                regionResolver.resolveRegion(headers), thingName)).build();
    }

    @PUT
    @Path("/things/{thingName}/connectivityInfo")
    public Response updateConnectivityInfo(
            @Context HttpHeaders headers, @PathParam("thingName") String thingName, String body) {
        return Response.ok(service.updateConnectivityInfo(
                regionResolver.resolveRegion(headers), thingName, parse(body))).build();
    }

    private static void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("nextToken", nextToken);
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw GreengrassV2Service.validation("Request body must be a JSON object.");
            }
            return node;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw GreengrassV2Service.validation("Request body must be a JSON object.");
        }
    }
}
