package io.floci.aws.services.iotmanagedintegrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Managed integrations for AWS IoT Device Management REST API, routed here by {@link IotMiRouteFilter}. Each
 * operation's path, query and body members are gathered into one request, checked against the API's shapes, and
 * handed to the service; errors carry {@code Message} and {@code x-amzn-ErrorType}.
 */
@Path(IotMiRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class IotMiController {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/iotmanagedintegrations-request-shapes.json", "Managed integrations request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return IotMiStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return IotMiStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return IotMiStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return IotMiStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return IotMiStore.validation(path + " is required.");
                }
            });

    private final IotMiService service;
    private final IotMiConnectors connectors;
    private final IotMiNotifications notifications;
    private final IotMiOta ota;
    private final IotMiSchemas schemas;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public IotMiController(IotMiService service, IotMiConnectors connectors, IotMiNotifications notifications,
                           IotMiOta ota, IotMiSchemas schemas, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.connectors = connectors;
        this.notifications = notifications;
        this.ota = ota;
        this.schemas = schemas;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/account-associations")
    public Response createAccountAssociation(String body) {
        return call("CreateAccountAssociation", body, in -> {
        }, in -> connectors.createAccountAssociation(in, region()));
    }

    @POST
    @Path("/cloud-connectors")
    public Response createCloudConnector(String body) {
        return call("CreateCloudConnector", body, in -> {
        }, in -> connectors.createCloudConnector(in, region()));
    }

    @POST
    @Path("/connector-destinations")
    public Response createConnectorDestination(String body) {
        return call("CreateConnectorDestination", body, in -> {
        }, in -> connectors.createConnectorDestination(in, region()));
    }

    @POST
    @Path("/credential-lockers")
    public Response createCredentialLocker(String body) {
        return call("CreateCredentialLocker", body, in -> {
        }, in -> service.createCredentialLocker(in, region()));
    }

    @POST
    @Path("/destinations")
    public Response createDestination(String body) {
        return call("CreateDestination", body, in -> {
        }, in -> notifications.createDestination(in, region()));
    }

    @POST
    @Path("/event-log-configurations")
    public Response createEventLogConfiguration(String body) {
        return call("CreateEventLogConfiguration", body, in -> {
        }, in -> service.createEventLogConfiguration(in, region()));
    }

    @POST
    @Path("/managed-things")
    public Response createManagedThing(String body) {
        return call("CreateManagedThing", body, in -> {
        }, in -> service.createManagedThing(in, region()));
    }

    @POST
    @Path("/notification-configurations")
    public Response createNotificationConfiguration(String body) {
        return call("CreateNotificationConfiguration", body, in -> {
        }, in -> notifications.createNotificationConfiguration(in, region()));
    }

    @POST
    @Path("/ota-tasks")
    public Response createOtaTask(String body) {
        return call("CreateOtaTask", body, in -> {
        }, in -> ota.createOtaTask(in, region()));
    }

    @POST
    @Path("/ota-task-configurations")
    public Response createOtaTaskConfiguration(String body) {
        return call("CreateOtaTaskConfiguration", body, in -> {
        }, in -> ota.createOtaTaskConfiguration(in, region()));
    }

    @POST
    @Path("/provisioning-profiles")
    public Response createProvisioningProfile(String body) {
        return call("CreateProvisioningProfile", body, in -> {
        }, in -> service.createProvisioningProfile(in, region()));
    }

    @DELETE
    @Path("/account-associations/{AccountAssociationId}")
    public Response deleteAccountAssociation(@PathParam("AccountAssociationId") String accountAssociationId) {
        return call("DeleteAccountAssociation", null, in -> {
            in.put("AccountAssociationId", accountAssociationId);
        }, in -> connectors.deleteAccountAssociation(text(in, "AccountAssociationId"), region()));
    }

    @DELETE
    @Path("/cloud-connectors/{Identifier}")
    public Response deleteCloudConnector(@PathParam("Identifier") String identifier) {
        return call("DeleteCloudConnector", null, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.deleteCloudConnector(text(in, "Identifier"), region()));
    }

    @DELETE
    @Path("/connector-destinations/{Identifier}")
    public Response deleteConnectorDestination(@PathParam("Identifier") String identifier) {
        return call("DeleteConnectorDestination", null, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.deleteConnectorDestination(text(in, "Identifier"), region()));
    }

    @DELETE
    @Path("/credential-lockers/{Identifier}")
    public Response deleteCredentialLocker(@PathParam("Identifier") String identifier) {
        return call("DeleteCredentialLocker", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.deleteCredentialLocker(text(in, "Identifier"), region()));
    }

    @DELETE
    @Path("/destinations/{Name}")
    public Response deleteDestination(@PathParam("Name") String name) {
        return call("DeleteDestination", null, in -> {
            in.put("Name", name);
        }, in -> notifications.deleteDestination(text(in, "Name"), region()));
    }

    @DELETE
    @Path("/event-log-configurations/{Id}")
    public Response deleteEventLogConfiguration(@PathParam("Id") String id) {
        return call("DeleteEventLogConfiguration", null, in -> {
            in.put("Id", id);
        }, in -> service.deleteEventLogConfiguration(text(in, "Id"), region()));
    }

    @DELETE
    @Path("/managed-things/{Identifier}")
    public Response deleteManagedThing(@PathParam("Identifier") String identifier, @QueryParam("Force") String force) {
        return call("DeleteManagedThing", null, in -> {
            in.put("Identifier", identifier);
            bool(in, "Force", force);
        }, in -> service.deleteManagedThing(text(in, "Identifier"), in.path("Force").asBoolean(false), region()));
    }

    @DELETE
    @Path("/notification-configurations/{EventType}")
    public Response deleteNotificationConfiguration(@PathParam("EventType") String eventType) {
        return call("DeleteNotificationConfiguration", null, in -> {
            in.put("EventType", eventType);
        }, in -> notifications.deleteNotificationConfiguration(text(in, "EventType"), region()));
    }

    @DELETE
    @Path("/ota-tasks/{Identifier}")
    public Response deleteOtaTask(@PathParam("Identifier") String identifier) {
        return call("DeleteOtaTask", null, in -> {
            in.put("Identifier", identifier);
        }, in -> ota.deleteOtaTask(text(in, "Identifier"), region()));
    }

    @DELETE
    @Path("/ota-task-configurations/{Identifier}")
    public Response deleteOtaTaskConfiguration(@PathParam("Identifier") String identifier) {
        return call("DeleteOtaTaskConfiguration", null, in -> {
            in.put("Identifier", identifier);
        }, in -> ota.deleteOtaTaskConfiguration(text(in, "Identifier"), region()));
    }

    @DELETE
    @Path("/provisioning-profiles/{Identifier}")
    public Response deleteProvisioningProfile(@PathParam("Identifier") String identifier) {
        return call("DeleteProvisioningProfile", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.deleteProvisioningProfile(text(in, "Identifier"), region()));
    }

    @PUT
    @Path("/managed-thing-associations/deregister")
    public Response deregisterAccountAssociation(String body) {
        return call("DeregisterAccountAssociation", body, in -> {
        }, in -> connectors.deregisterAccountAssociation(in, region()));
    }

    @GET
    @Path("/account-associations/{AccountAssociationId}")
    public Response getAccountAssociation(@PathParam("AccountAssociationId") String accountAssociationId) {
        return call("GetAccountAssociation", null, in -> {
            in.put("AccountAssociationId", accountAssociationId);
        }, in -> connectors.getAccountAssociation(text(in, "AccountAssociationId"), region()));
    }

    @GET
    @Path("/cloud-connectors/{Identifier}")
    public Response getCloudConnector(@PathParam("Identifier") String identifier) {
        return call("GetCloudConnector", null, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.getCloudConnector(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/connector-destinations/{Identifier}")
    public Response getConnectorDestination(@PathParam("Identifier") String identifier) {
        return call("GetConnectorDestination", null, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.getConnectorDestination(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/credential-lockers/{Identifier}")
    public Response getCredentialLocker(@PathParam("Identifier") String identifier) {
        return call("GetCredentialLocker", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getCredentialLocker(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/custom-endpoint")
    public Response getCustomEndpoint() {
        return call("GetCustomEndpoint", null, in -> {
        }, in -> service.getCustomEndpoint(region()));
    }

    @GET
    @Path("/configuration/account/encryption")
    public Response getDefaultEncryptionConfiguration() {
        return call("GetDefaultEncryptionConfiguration", null, in -> {
        }, in -> service.getDefaultEncryptionConfiguration(region()));
    }

    @GET
    @Path("/destinations/{Name}")
    public Response getDestination(@PathParam("Name") String name) {
        return call("GetDestination", null, in -> {
            in.put("Name", name);
        }, in -> notifications.getDestination(text(in, "Name"), region()));
    }

    @GET
    @Path("/device-discoveries/{Identifier}")
    public Response getDeviceDiscovery(@PathParam("Identifier") String identifier) {
        return call("GetDeviceDiscovery", null, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.getDeviceDiscovery(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/event-log-configurations/{Id}")
    public Response getEventLogConfiguration(@PathParam("Id") String id) {
        return call("GetEventLogConfiguration", null, in -> {
            in.put("Id", id);
        }, in -> service.getEventLogConfiguration(text(in, "Id"), region()));
    }

    @GET
    @Path("/hub-configuration")
    public Response getHubConfiguration() {
        return call("GetHubConfiguration", null, in -> {
        }, in -> service.getHubConfiguration(region()));
    }

    @GET
    @Path("/managed-things/{Identifier}")
    public Response getManagedThing(@PathParam("Identifier") String identifier) {
        return call("GetManagedThing", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getManagedThing(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/managed-things-capabilities/{Identifier}")
    public Response getManagedThingCapabilities(@PathParam("Identifier") String identifier) {
        return call("GetManagedThingCapabilities", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getManagedThingCapabilities(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/managed-things-certificate/{Identifier}")
    public Response getManagedThingCertificate(@PathParam("Identifier") String identifier) {
        return call("GetManagedThingCertificate", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getManagedThingCertificate(text(in, "Identifier"), region()));
    }

    @POST
    @Path("/managed-things-connectivity-data/{Identifier}")
    public Response getManagedThingConnectivityData(@PathParam("Identifier") String identifier) {
        return call("GetManagedThingConnectivityData", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getManagedThingConnectivityData(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/managed-things-metadata/{Identifier}")
    public Response getManagedThingMetaData(@PathParam("Identifier") String identifier) {
        return call("GetManagedThingMetaData", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getManagedThingMetaData(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/managed-thing-states/{ManagedThingId}")
    public Response getManagedThingState(@PathParam("ManagedThingId") String managedThingId) {
        return call("GetManagedThingState", null, in -> {
            in.put("ManagedThingId", managedThingId);
        }, in -> service.getManagedThingState(text(in, "ManagedThingId"), region()));
    }

    @GET
    @Path("/notification-configurations/{EventType}")
    public Response getNotificationConfiguration(@PathParam("EventType") String eventType) {
        return call("GetNotificationConfiguration", null, in -> {
            in.put("EventType", eventType);
        }, in -> notifications.getNotificationConfiguration(text(in, "EventType"), region()));
    }

    @GET
    @Path("/ota-tasks/{Identifier}")
    public Response getOtaTask(@PathParam("Identifier") String identifier) {
        return call("GetOtaTask", null, in -> {
            in.put("Identifier", identifier);
        }, in -> ota.getOtaTask(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/ota-task-configurations/{Identifier}")
    public Response getOtaTaskConfiguration(@PathParam("Identifier") String identifier) {
        return call("GetOtaTaskConfiguration", null, in -> {
            in.put("Identifier", identifier);
        }, in -> ota.getOtaTaskConfiguration(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/provisioning-profiles/{Identifier}")
    public Response getProvisioningProfile(@PathParam("Identifier") String identifier) {
        return call("GetProvisioningProfile", null, in -> {
            in.put("Identifier", identifier);
        }, in -> service.getProvisioningProfile(text(in, "Identifier"), region()));
    }

    @GET
    @Path("/runtime-log-configurations/{ManagedThingId}")
    public Response getRuntimeLogConfiguration(@PathParam("ManagedThingId") String managedThingId) {
        return call("GetRuntimeLogConfiguration", null, in -> {
            in.put("ManagedThingId", managedThingId);
        }, in -> service.getRuntimeLogConfiguration(text(in, "ManagedThingId"), region()));
    }

    @GET
    @Path("/schema-versions/{Type}/{SchemaVersionedId}")
    public Response getSchemaVersion(@PathParam("Type") String type,
                                     @PathParam("SchemaVersionedId") String schemaVersionedId,
                                     @QueryParam("Format") String format) {
        return call("GetSchemaVersion", null, in -> {
            in.put("Type", type);
            in.put("SchemaVersionedId", schemaVersionedId);
            in.put("Format", format);
        }, in -> schemas.getSchemaVersion(text(in, "Type"), text(in, "SchemaVersionedId"), text(in, "Format")));
    }

    @GET
    @Path("/account-associations")
    public Response listAccountAssociations(@QueryParam("ConnectorDestinationId") String connectorDestinationId,
                                            @QueryParam("MaxResults") String maxResults,
                                            @QueryParam("NextToken") String nextToken) {
        return call("ListAccountAssociations", null, in -> {
            in.put("ConnectorDestinationId", connectorDestinationId);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> connectors.listAccountAssociations(
                text(in, "ConnectorDestinationId"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/cloud-connectors")
    public Response listCloudConnectors(@QueryParam("Type") String type, @QueryParam("LambdaArn") String lambdaArn,
                                        @QueryParam("MaxResults") String maxResults,
                                        @QueryParam("NextToken") String nextToken) {
        return call("ListCloudConnectors", null, in -> {
            in.put("Type", type);
            in.put("LambdaArn", lambdaArn);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> connectors.listCloudConnectors(
                text(in, "Type"), text(in, "LambdaArn"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/connector-destinations")
    public Response listConnectorDestinations(@QueryParam("CloudConnectorId") String cloudConnectorId,
                                              @QueryParam("NextToken") String nextToken,
                                              @QueryParam("MaxResults") String maxResults) {
        return call("ListConnectorDestinations", null, in -> {
            in.put("CloudConnectorId", cloudConnectorId);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> connectors.listConnectorDestinations(
                text(in, "CloudConnectorId"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/credential-lockers")
    public Response listCredentialLockers(@QueryParam("NextToken") String nextToken,
                                          @QueryParam("MaxResults") String maxResults) {
        return call("ListCredentialLockers", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listCredentialLockers(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/destinations")
    public Response listDestinations(@QueryParam("NextToken") String nextToken,
                                     @QueryParam("MaxResults") String maxResults) {
        return call("ListDestinations", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> notifications.listDestinations(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/device-discoveries")
    public Response listDeviceDiscoveries(@QueryParam("NextToken") String nextToken,
                                          @QueryParam("MaxResults") String maxResults,
                                          @QueryParam("TypeFilter") String typeFilter,
                                          @QueryParam("StatusFilter") String statusFilter) {
        return call("ListDeviceDiscoveries", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
            in.put("TypeFilter", typeFilter);
            in.put("StatusFilter", statusFilter);
        }, in -> connectors.listDeviceDiscoveries(
                text(in, "TypeFilter"), text(in, "StatusFilter"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/device-discoveries/{Identifier}/devices")
    public Response listDiscoveredDevices(@PathParam("Identifier") String identifier,
                                          @QueryParam("NextToken") String nextToken,
                                          @QueryParam("MaxResults") String maxResults) {
        return call("ListDiscoveredDevices", null, in -> {
            in.put("Identifier", identifier);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> connectors.listDiscoveredDevices(text(in, "Identifier"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/event-log-configurations")
    public Response listEventLogConfigurations(@QueryParam("NextToken") String nextToken,
                                               @QueryParam("MaxResults") String maxResults) {
        return call("ListEventLogConfigurations", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listEventLogConfigurations(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/managed-thing-associations")
    public Response listManagedThingAccountAssociations(@QueryParam("ManagedThingId") String managedThingId,
                                                        @QueryParam("AccountAssociationId") String accountAssociationId,
                                                        @QueryParam("MaxResults") String maxResults,
                                                        @QueryParam("NextToken") String nextToken) {
        return call("ListManagedThingAccountAssociations", null, in -> {
            in.put("ManagedThingId", managedThingId);
            in.put("AccountAssociationId", accountAssociationId);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> connectors.listManagedThingAccountAssociations(
                text(in, "ManagedThingId"), text(in, "AccountAssociationId"), text(in, "NextToken"), max(in),
                region()));
    }

    @GET
    @Path("/managed-thing-schemas/{Identifier}")
    public Response listManagedThingSchemas(@PathParam("Identifier") String identifier,
                                            @QueryParam("EndpointIdFilter") String endpointIdFilter,
                                            @QueryParam("CapabilityIdFilter") String capabilityIdFilter,
                                            @QueryParam("NextToken") String nextToken,
                                            @QueryParam("MaxResults") String maxResults) {
        return call("ListManagedThingSchemas", null, in -> {
            in.put("Identifier", identifier);
            in.put("EndpointIdFilter", endpointIdFilter);
            in.put("CapabilityIdFilter", capabilityIdFilter);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listManagedThingSchemas(
                text(in, "Identifier"), text(in, "EndpointIdFilter"), text(in, "CapabilityIdFilter"), text(in,
                "NextToken"), max(in), region()));
    }

    @GET
    @Path("/managed-things")
    public Response listManagedThings(@QueryParam("OwnerFilter") String ownerFilter,
                                      @QueryParam("CredentialLockerFilter") String credentialLockerFilter,
                                      @QueryParam("RoleFilter") String roleFilter,
                                      @QueryParam("ParentControllerIdentifierFilter") String parentControllerIdentifierFilter,
                                      @QueryParam("ConnectorPolicyIdFilter") String connectorPolicyIdFilter,
                                      @QueryParam("ConnectorDestinationIdFilter") String connectorDestinationIdFilter,
                                      @QueryParam("ConnectorDeviceIdFilter") String connectorDeviceIdFilter,
                                      @QueryParam("SerialNumberFilter") String serialNumberFilter,
                                      @QueryParam("ProvisioningStatusFilter") String provisioningStatusFilter,
                                      @QueryParam("NextToken") String nextToken,
                                      @QueryParam("MaxResults") String maxResults) {
        return call("ListManagedThings", null, in -> {
            in.put("OwnerFilter", ownerFilter);
            in.put("CredentialLockerFilter", credentialLockerFilter);
            in.put("RoleFilter", roleFilter);
            in.put("ParentControllerIdentifierFilter", parentControllerIdentifierFilter);
            in.put("ConnectorPolicyIdFilter", connectorPolicyIdFilter);
            in.put("ConnectorDestinationIdFilter", connectorDestinationIdFilter);
            in.put("ConnectorDeviceIdFilter", connectorDeviceIdFilter);
            in.put("SerialNumberFilter", serialNumberFilter);
            in.put("ProvisioningStatusFilter", provisioningStatusFilter);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listManagedThings(filters(in), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/notification-configurations")
    public Response listNotificationConfigurations(@QueryParam("MaxResults") String maxResults,
                                                   @QueryParam("NextToken") String nextToken) {
        return call("ListNotificationConfigurations", null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> notifications.listNotificationConfigurations(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/ota-task-configurations")
    public Response listOtaTaskConfigurations(@QueryParam("NextToken") String nextToken,
                                              @QueryParam("MaxResults") String maxResults) {
        return call("ListOtaTaskConfigurations", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> ota.listOtaTaskConfigurations(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/ota-tasks/{Identifier}/devices")
    public Response listOtaTaskExecutions(@PathParam("Identifier") String identifier,
                                          @QueryParam("NextToken") String nextToken,
                                          @QueryParam("MaxResults") String maxResults) {
        return call("ListOtaTaskExecutions", null, in -> {
            in.put("Identifier", identifier);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> ota.listOtaTaskExecutions(text(in, "Identifier"), text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/ota-tasks")
    public Response listOtaTasks(@QueryParam("NextToken") String nextToken, @QueryParam("MaxResults") String maxResults) {
        return call("ListOtaTasks", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> ota.listOtaTasks(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/provisioning-profiles")
    public Response listProvisioningProfiles(@QueryParam("NextToken") String nextToken,
                                             @QueryParam("MaxResults") String maxResults) {
        return call("ListProvisioningProfiles", null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listProvisioningProfiles(text(in, "NextToken"), max(in), region()));
    }

    @GET
    @Path("/schema-versions/{Type}")
    public Response listSchemaVersions(@PathParam("Type") String type, @QueryParam("MaxResults") String maxResults,
                                       @QueryParam("NextToken") String nextToken,
                                       @QueryParam("SchemaIdFilter") String schemaId,
                                       @QueryParam("NamespaceFilter") String namespace,
                                       @QueryParam("VisibilityFilter") String visibility,
                                       @QueryParam("SemanticVersionFilter") String semanticVersion) {
        return call("ListSchemaVersions", null, in -> {
            in.put("Type", type);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
            in.put("SchemaId", schemaId);
            in.put("Namespace", namespace);
            in.put("Visibility", visibility);
            in.put("SemanticVersion", semanticVersion);
        }, in -> schemas.listSchemaVersions(
                text(in, "Type"), text(in, "SchemaId"), text(in, "Namespace"), text(in, "Visibility"), text(in,
                "SemanticVersion"), text(in, "NextToken"), max(in)));
    }

    @GET
    @Path("/tags/{ResourceArn:.+}")
    public Response listTagsForResource(@PathParam("ResourceArn") String resourceArn) {
        return call("ListTagsForResource", null, in -> {
            in.put("ResourceArn", resourceArn);
        }, in -> service.listTagsForResource(text(in, "ResourceArn")));
    }

    @POST
    @Path("/configuration/account/encryption")
    public Response putDefaultEncryptionConfiguration(String body) {
        return call("PutDefaultEncryptionConfiguration", body, in -> {
        }, in -> service.putDefaultEncryptionConfiguration(in, region()));
    }

    @PUT
    @Path("/hub-configuration")
    public Response putHubConfiguration(String body) {
        return call("PutHubConfiguration", body, in -> {
        }, in -> service.putHubConfiguration(in, region()));
    }

    @PUT
    @Path("/runtime-log-configurations/{ManagedThingId}")
    public Response putRuntimeLogConfiguration(@PathParam("ManagedThingId") String managedThingId, String body) {
        return call("PutRuntimeLogConfiguration", body, in -> {
            in.put("ManagedThingId", managedThingId);
        }, in -> service.putRuntimeLogConfiguration(text(in, "ManagedThingId"), in, region()));
    }

    @PUT
    @Path("/managed-thing-associations/register")
    public Response registerAccountAssociation(String body) {
        return call("RegisterAccountAssociation", body, in -> {
        }, in -> connectors.registerAccountAssociation(in, region()));
    }

    @POST
    @Path("/custom-endpoint")
    public Response registerCustomEndpoint() {
        return call("RegisterCustomEndpoint", null, in -> {
        }, in -> service.registerCustomEndpoint(region()));
    }

    @DELETE
    @Path("/runtime-log-configurations/{ManagedThingId}")
    public Response resetRuntimeLogConfiguration(@PathParam("ManagedThingId") String managedThingId) {
        return call("ResetRuntimeLogConfiguration", null, in -> {
            in.put("ManagedThingId", managedThingId);
        }, in -> service.resetRuntimeLogConfiguration(text(in, "ManagedThingId"), region()));
    }

    @POST
    @Path("/connector-event/{ConnectorId}")
    public Response sendConnectorEvent(@PathParam("ConnectorId") String connectorId, String body) {
        return call("SendConnectorEvent", body, in -> {
            in.put("ConnectorId", connectorId);
        }, in -> connectors.sendConnectorEvent(text(in, "ConnectorId"), in, region()));
    }

    @POST
    @Path("/managed-things-command/{ManagedThingId}")
    public Response sendManagedThingCommand(@PathParam("ManagedThingId") String managedThingId, String body) {
        return call("SendManagedThingCommand", body, in -> {
            in.put("ManagedThingId", managedThingId);
        }, in -> connectors.sendManagedThingCommand(text(in, "ManagedThingId"), in, region()));
    }

    @POST
    @Path("/account-associations/{AccountAssociationId}/refresh")
    public Response startAccountAssociationRefresh(@PathParam("AccountAssociationId") String accountAssociationId) {
        return call("StartAccountAssociationRefresh", null, in -> {
            in.put("AccountAssociationId", accountAssociationId);
        }, in -> connectors.startAccountAssociationRefresh(text(in, "AccountAssociationId"), region()));
    }

    @POST
    @Path("/device-discoveries")
    public Response startDeviceDiscovery(String body) {
        return call("StartDeviceDiscovery", body, in -> {
        }, in -> connectors.startDeviceDiscovery(in, region()));
    }

    @POST
    @Path("/tags/{ResourceArn:.+}")
    public Response tagResource(@PathParam("ResourceArn") String resourceArn, String body) {
        return call("TagResource", body, in -> {
            in.put("ResourceArn", resourceArn);
        }, in -> service.tagResource(text(in, "ResourceArn"), in.path("Tags")));
    }

    @DELETE
    @Path("/tags/{ResourceArn:.+}")
    public Response untagResource(@PathParam("ResourceArn") String resourceArn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        return call("UntagResource", null, in -> {
            in.put("ResourceArn", resourceArn);
            list(in, "TagKeys", tagKeys);
        }, in -> service.untagResource(text(in, "ResourceArn"), strings(in.path("TagKeys"))));
    }

    @PUT
    @Path("/account-associations/{AccountAssociationId}")
    public Response updateAccountAssociation(@PathParam("AccountAssociationId") String accountAssociationId,
                                             String body) {
        return call("UpdateAccountAssociation", body, in -> {
            in.put("AccountAssociationId", accountAssociationId);
        }, in -> connectors.updateAccountAssociation(text(in, "AccountAssociationId"), in, region()));
    }

    @PUT
    @Path("/cloud-connectors/{Identifier}")
    public Response updateCloudConnector(@PathParam("Identifier") String identifier, String body) {
        return call("UpdateCloudConnector", body, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.updateCloudConnector(text(in, "Identifier"), in, region()));
    }

    @PUT
    @Path("/connector-destinations/{Identifier}")
    public Response updateConnectorDestination(@PathParam("Identifier") String identifier, String body) {
        return call("UpdateConnectorDestination", body, in -> {
            in.put("Identifier", identifier);
        }, in -> connectors.updateConnectorDestination(text(in, "Identifier"), in, region()));
    }

    @PUT
    @Path("/destinations/{Name}")
    public Response updateDestination(@PathParam("Name") String name, String body) {
        return call("UpdateDestination", body, in -> {
            in.put("Name", name);
        }, in -> notifications.updateDestination(text(in, "Name"), in, region()));
    }

    @PATCH
    @Path("/event-log-configurations/{Id}")
    public Response updateEventLogConfiguration(@PathParam("Id") String id, String body) {
        return call("UpdateEventLogConfiguration", body, in -> {
            in.put("Id", id);
        }, in -> service.updateEventLogConfiguration(text(in, "Id"), in, region()));
    }

    @PUT
    @Path("/managed-things/{Identifier}")
    public Response updateManagedThing(@PathParam("Identifier") String identifier, String body) {
        return call("UpdateManagedThing", body, in -> {
            in.put("Identifier", identifier);
        }, in -> service.updateManagedThing(text(in, "Identifier"), in, region()));
    }

    @PUT
    @Path("/notification-configurations/{EventType}")
    public Response updateNotificationConfiguration(@PathParam("EventType") String eventType, String body) {
        return call("UpdateNotificationConfiguration", body, in -> {
            in.put("EventType", eventType);
        }, in -> notifications.updateNotificationConfiguration(text(in, "EventType"), in, region()));
    }

    @PUT
    @Path("/ota-tasks/{Identifier}")
    public Response updateOtaTask(@PathParam("Identifier") String identifier, String body) {
        return call("UpdateOtaTask", body, in -> {
            in.put("Identifier", identifier);
        }, in -> ota.updateOtaTask(text(in, "Identifier"), in, region()));
    }

    // ---------------------------------------------------------------- plumbing

    private String region() {
        return regions.getRegion();
    }

    private Response call(String operation, String body, Consumer<ObjectNode> members,
                          Function<ObjectNode, ObjectNode> handler) {
        try {
            ObjectNode in = parse(body);
            members.accept(in);
            List<String> absent = new ArrayList<>();
            in.fields().forEachRemaining(field -> {
                if (field.getValue().isNull()) {
                    absent.add(field.getKey());
                }
            });
            in.remove(absent);
            SHAPES.check(operation, in);
            return Response.ok(handler.apply(in).toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (AwsException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("Message", e.getMessage());
            if (e.getExtendedData() != null) {
                e.getExtendedData().forEach((key, value) -> error.set(key, mapper.valueToTree(value)));
            }
            return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                    .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
        }
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw IotMiStore.validation("The request body must be a JSON object.");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException e) {
            throw IotMiStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static String text(JsonNode in, String name) {
        return in.hasNonNull(name) ? in.get(name).asText() : null;
    }

    private static Integer max(JsonNode in) {
        return in.hasNonNull("MaxResults") && in.get("MaxResults").isInt() ? in.get("MaxResults").asInt() : null;
    }

    private static void integer(ObjectNode in, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            in.put(name, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            in.put(name, value);
        }
    }

    private static void bool(ObjectNode in, String name, String value) {
        if (value != null) {
            in.put(name, Boolean.parseBoolean(value));
        }
    }

    private static void list(ObjectNode in, String name, List<String> values) {
        if (values != null && !values.isEmpty()) {
            values.forEach(in.putArray(name)::add);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Map<String, String> filters(JsonNode in) {
        Map<String, String> filters = new LinkedHashMap<>();
        in.fields().forEachRemaining(field -> {
            if (field.getKey().endsWith("Filter")) {
                filters.put(field.getKey(), field.getValue().asText());
            }
        });
        return filters;
    }
}
