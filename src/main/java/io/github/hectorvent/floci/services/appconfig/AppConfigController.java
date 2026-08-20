package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppConfigController {
    private static final Logger LOG = Logger.getLogger(AppConfigController.class);

    private final AppConfigService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigController(AppConfigService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Application ────────────────────────────

    @POST
    @Path("/applications")
    public Response createApplication(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        Application app = service.createApplication(request);
        return Response.status(201).entity(app).build();
    }

    @GET
    @Path("/applications/{id}")
    public Response getApplication(@PathParam("id") String id) {
        return Response.ok(service.getApplication(id)).build();
    }

    @GET
    @Path("/applications")
    public Response listApplications() {
        List<Application> apps = service.listApplications();
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        apps.forEach(items::addPOJO);
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/applications/{id}")
    public Response updateApplication(@PathParam("id") String id, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateApplication(id, request)).build();
    }

    @DELETE
    @Path("/applications/{id}")
    public Response deleteApplication(@PathParam("id") String id) {
        service.deleteApplication(id);
        return Response.noContent().build();
    }

    // ──────────────────────────── Environment ────────────────────────────

    @POST
    @Path("/applications/{appId}/environments")
    public Response createEnvironment(@PathParam("appId") String appId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        Environment env = service.createEnvironment(appId, request);
        return Response.status(201).entity(env).build();
    }

    @GET
    @Path("/applications/{appId}/environments/{envId}")
    public Response getEnvironment(@PathParam("appId") String appId, @PathParam("envId") String envId) {
        return Response.ok(service.getEnvironment(appId, envId)).build();
    }

    @GET
    @Path("/applications/{appId}/environments")
    public Response listEnvironments(@PathParam("appId") String appId) {
        List<Environment> envs = service.listEnvironments(appId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        envs.forEach(items::addPOJO);
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/applications/{appId}/environments/{envId}")
    public Response updateEnvironment(@PathParam("appId") String appId, @PathParam("envId") String envId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateEnvironment(appId, envId, request)).build();
    }

    @DELETE
    @Path("/applications/{appId}/environments/{envId}")
    public Response deleteEnvironment(@PathParam("appId") String appId, @PathParam("envId") String envId) {
        service.deleteEnvironment(appId, envId);
        return Response.noContent().build();
    }

    // ──────────────────────────── Configuration Profile ────────────────────────────

    @POST
    @Path("/applications/{appId}/configurationprofiles")
    public Response createConfigurationProfile(@PathParam("appId") String appId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        ConfigurationProfile profile = service.createConfigurationProfile(appId, request);
        return Response.status(201).entity(profile).build();
    }

    @GET
    @Path("/applications/{appId}/configurationprofiles/{profileId}")
    public Response getConfigurationProfile(@PathParam("appId") String appId, @PathParam("profileId") String profileId) {
        return Response.ok(service.getConfigurationProfile(appId, profileId)).build();
    }

    @GET
    @Path("/applications/{appId}/configurationprofiles")
    public Response listConfigurationProfiles(@PathParam("appId") String appId) {
        List<ConfigurationProfile> profiles = service.listConfigurationProfiles(appId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        profiles.forEach(items::addPOJO);
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/applications/{appId}/configurationprofiles/{profileId}")
    public Response updateConfigurationProfile(@PathParam("appId") String appId,
                                               @PathParam("profileId") String profileId,
                                               String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateConfigurationProfile(appId, profileId, request)).build();
    }

    @DELETE
    @Path("/applications/{appId}/configurationprofiles/{profileId}")
    public Response deleteConfigurationProfile(@PathParam("appId") String appId, @PathParam("profileId") String profileId) {
        service.deleteConfigurationProfile(appId, profileId);
        return Response.noContent().build();
    }

    @POST
    @Path("/applications/{appId}/configurationprofiles/{profileId}/validators")
    public Response validateConfiguration(@PathParam("appId") String appId,
                                          @PathParam("profileId") String profileId,
                                          @QueryParam("configuration_version") String configurationVersion) {
        service.validateConfiguration(appId, profileId, configurationVersion);
        return Response.ok().build();
    }

    // ──────────────────────────── Hosted Configuration Version ────────────────────────────

    @GET
    @Path("/applications/{appId}/configurationprofiles/{profileId}/hostedconfigurationversions")
    public Response listHostedConfigurationVersions(@PathParam("appId") String appId,
                                                    @PathParam("profileId") String profileId) {
        List<HostedConfigurationVersionSummary> items = service.listHostedConfigurationVersions(appId, profileId);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("Items");
        items.forEach(arr::addPOJO);
        return Response.ok(root).build();
    }

    @POST
    @Path("/applications/{appId}/configurationprofiles/{profileId}/hostedconfigurationversions")
    @Consumes(MediaType.WILDCARD)
    public Response createHostedConfigurationVersion(@PathParam("appId") String appId,
                                                     @PathParam("profileId") String profileId,
                                                     @HeaderParam("Content-Type") String contentType,
                                                     @HeaderParam("Description") String description,
                                                     @HeaderParam("VersionLabel") String versionLabel,
                                                     byte[] content) {
        HostedConfigurationVersion version = service.createHostedConfigurationVersion(
                appId, profileId, content, contentType, description, versionLabel);
        return versionResponse(version, 201);
    }

    @GET
    @Path("/applications/{appId}/configurationprofiles/{profileId}/hostedconfigurationversions/{versionNumber}")
    public Response getHostedConfigurationVersion(@PathParam("appId") String appId,
                                                  @PathParam("profileId") String profileId,
                                                  @PathParam("versionNumber") int versionNumber) {
        HostedConfigurationVersion version = service.getHostedConfigurationVersion(appId, profileId, versionNumber);
        return versionResponse(version, 200);
    }

    @DELETE
    @Path("/applications/{appId}/configurationprofiles/{profileId}/hostedconfigurationversions/{versionNumber}")
    public Response deleteHostedConfigurationVersion(@PathParam("appId") String appId,
                                                     @PathParam("profileId") String profileId,
                                                     @PathParam("versionNumber") int versionNumber) {
        service.deleteHostedConfigurationVersion(appId, profileId, versionNumber);
        return Response.noContent().build();
    }

    private Response versionResponse(HostedConfigurationVersion v, int status) {
        Response.ResponseBuilder rb = Response.status(status).entity(v.getContent());
        rb.header("Application-Id", v.getApplicationId());
        rb.header("Configuration-Profile-Id", v.getConfigurationProfileId());
        rb.header("Version-Number", v.getVersionNumber());
        rb.header("Content-Type", v.getContentType());
        if (v.getDescription() != null) rb.header("Description", v.getDescription());
        if (v.getVersionLabel() != null) rb.header("VersionLabel", v.getVersionLabel());
        return rb.build();
    }

    // ──────────────────────────── Deployment Strategy ────────────────────────────

    @POST
    @Path("/deploymentstrategies")
    public Response createDeploymentStrategy(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        DeploymentStrategy strategy = service.createDeploymentStrategy(request);
        return Response.status(201).entity(strategy).build();
    }

    @GET
    @Path("/deploymentstrategies/{id}")
    public Response getDeploymentStrategy(@PathParam("id") String id) {
        return Response.ok(service.getDeploymentStrategy(id)).build();
    }

    @GET
    @Path("/deploymentstrategies")
    public Response listDeploymentStrategies() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        service.listDeploymentStrategies().forEach(items::addPOJO);
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/deploymentstrategies/{id}")
    public Response updateDeploymentStrategy(@PathParam("id") String id, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateDeploymentStrategy(id, request)).build();
    }

    @DELETE
    @Path("/deploymentstrategies/{id}")
    public Response deleteDeploymentStrategy(@PathParam("id") String id) {
        service.deleteDeploymentStrategy(id);
        return Response.noContent().build();
    }

    // AWS Smithy models this path with the historical "deployement" typo.
    @DELETE
    @Path("/deployementstrategies/{id}")
    public Response deleteDeploymentStrategyTypo(@PathParam("id") String id) {
        service.deleteDeploymentStrategy(id);
        return Response.noContent().build();
    }

    // ──────────────────────────── Deployment ────────────────────────────

    @POST
    @Path("/applications/{appId}/environments/{envId}/deployments")
    public Response startDeployment(@PathParam("appId") String appId, @PathParam("envId") String envId, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        Deployment deployment = service.startDeployment(appId, envId, request);
        return Response.status(201).entity(deployment).build();
    }

    @GET
    @Path("/applications/{appId}/environments/{envId}/deployments/{deploymentNumber}")
    public Response getDeployment(@PathParam("appId") String appId, @PathParam("envId") String envId, @PathParam("deploymentNumber") int deploymentNumber) {
        return Response.ok(service.getDeployment(appId, envId, deploymentNumber)).build();
    }

    @DELETE
    @Path("/applications/{appId}/environments/{envId}/deployments/{deploymentNumber}")
    public Response stopDeployment(@PathParam("appId") String appId, @PathParam("envId") String envId,
                                   @PathParam("deploymentNumber") int deploymentNumber) {
        return Response.ok(service.stopDeployment(appId, envId, deploymentNumber)).build();
    }

    // ──────────────────────────── Extensions ────────────────────────────

    @POST
    @Path("/extensions")
    public Response createExtension(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.status(201).entity(service.createExtension(request)).build();
    }

    @GET
    @Path("/extensions/{id}")
    public Response getExtension(@PathParam("id") String id) {
        return Response.ok(service.getExtension(id)).build();
    }

    @GET
    @Path("/extensions")
    public Response listExtensions(@QueryParam("name") String name) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        service.listExtensions(name).forEach(ext -> {
            ObjectNode item = items.addObject();
            item.put("Id", ext.getId());
            item.put("Name", ext.getName());
            item.put("VersionNumber", ext.getVersionNumber());
            item.put("Arn", ext.getArn());
            if (ext.getDescription() != null) {
                item.put("Description", ext.getDescription());
            }
        });
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/extensions/{id}")
    public Response updateExtension(@PathParam("id") String id, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateExtension(id, request)).build();
    }

    @DELETE
    @Path("/extensions/{id}")
    public Response deleteExtension(@PathParam("id") String id) {
        service.deleteExtension(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/extensionassociations")
    public Response createExtensionAssociation(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.status(201).entity(service.createExtensionAssociation(request)).build();
    }

    @GET
    @Path("/extensionassociations/{id}")
    public Response getExtensionAssociation(@PathParam("id") String id) {
        return Response.ok(service.getExtensionAssociation(id)).build();
    }

    @GET
    @Path("/extensionassociations")
    public Response listExtensionAssociations(@QueryParam("resource_identifier") String resourceIdentifier,
                                              @QueryParam("extension_identifier") String extensionIdentifier) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("Items");
        service.listExtensionAssociations(resourceIdentifier, extensionIdentifier).forEach(a -> {
            ObjectNode item = items.addObject();
            item.put("Id", a.getId());
            item.put("ExtensionArn", a.getExtensionArn());
            item.put("ResourceArn", a.getResourceArn());
        });
        return Response.ok(root).build();
    }

    @PATCH
    @Path("/extensionassociations/{id}")
    public Response updateExtensionAssociation(@PathParam("id") String id, String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        return Response.ok(service.updateExtensionAssociation(id, request)).build();
    }

    @DELETE
    @Path("/extensionassociations/{id}")
    public Response deleteExtensionAssociation(@PathParam("id") String id) {
        service.deleteExtensionAssociation(id);
        return Response.noContent().build();
    }
}
