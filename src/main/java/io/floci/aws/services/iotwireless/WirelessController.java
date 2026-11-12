package io.floci.aws.services.iotwireless;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The IoT Wireless REST API, routed here by {@link WirelessRouteFilter}. Each operation's path, query string and
 * body members are gathered into one request, checked against the API's shapes, and handed to the class that
 * implements it; errors carry {@code Message} and {@code x-amzn-ErrorType}. GeoJSON position payloads travel as the
 * raw request and response body.
 */
@Path(WirelessRouteFilter.PREFIX)
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class WirelessController {

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/iotwireless-request-shapes.json", "IoT Wireless request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return WirelessStore.validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type)
                            ? "size" : "value";
                    return WirelessStore.validation(path + " has " + what + " " + size + ", outside "
                            + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return WirelessStore.validation(path + " must match " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return WirelessStore.validation(path + " must be one of " + String.join(", ", values) + ".");
                }

                @Override
                public AwsException required(String path) {
                    return WirelessStore.validation(path + " is required.");
                }
            });

    private final WirelessFleet fleet;
    private final WirelessNetwork network;
    private final WirelessService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public WirelessController(WirelessFleet fleet, WirelessNetwork network, WirelessService service,
                              RegionResolver regions, ObjectMapper mapper) {
        this.fleet = fleet;
        this.network = network;
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @POST
    @Path("/partner-accounts")
    public Response associateAwsAccountWithPartnerAccount(String body) {
        return call("AssociateAwsAccountWithPartnerAccount", 200, body, in -> {
        }, in -> fleet.associateAwsAccountWithPartnerAccount(in, regions.getRegion()));
    }

    @PUT
    @Path("/fuota-tasks/{Id}/multicast-group")
    public Response associateMulticastGroupWithFuotaTask(@PathParam("Id") String id, String body) {
        return call("AssociateMulticastGroupWithFuotaTask", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.associateMulticastGroupWithFuotaTask(in, regions.getRegion()));
    }

    @PUT
    @Path("/fuota-tasks/{Id}/wireless-device")
    public Response associateWirelessDeviceWithFuotaTask(@PathParam("Id") String id, String body) {
        return call("AssociateWirelessDeviceWithFuotaTask", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.associateWirelessDeviceWithFuotaTask(in, regions.getRegion()));
    }

    @PUT
    @Path("/multicast-groups/{Id}/wireless-device")
    public Response associateWirelessDeviceWithMulticastGroup(@PathParam("Id") String id, String body) {
        return call("AssociateWirelessDeviceWithMulticastGroup", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.associateWirelessDeviceWithMulticastGroup(in, regions.getRegion()));
    }

    @PUT
    @Path("/wireless-devices/{Id}/thing")
    public Response associateWirelessDeviceWithThing(@PathParam("Id") String id, String body) {
        return call("AssociateWirelessDeviceWithThing", 204, body, in -> {
            in.put("Id", id);
        }, in -> service.associateWirelessDeviceWithThing(in, regions.getRegion()));
    }

    @PUT
    @Path("/wireless-gateways/{Id}/certificate")
    public Response associateWirelessGatewayWithCertificate(@PathParam("Id") String id, String body) {
        return call("AssociateWirelessGatewayWithCertificate", 200, body, in -> {
            in.put("Id", id);
        }, in -> service.associateWirelessGatewayWithCertificate(in, regions.getRegion()));
    }

    @PUT
    @Path("/wireless-gateways/{Id}/thing")
    public Response associateWirelessGatewayWithThing(@PathParam("Id") String id, String body) {
        return call("AssociateWirelessGatewayWithThing", 204, body, in -> {
            in.put("Id", id);
        }, in -> service.associateWirelessGatewayWithThing(in, regions.getRegion()));
    }

    @DELETE
    @Path("/multicast-groups/{Id}/session")
    public Response cancelMulticastGroupSession(@PathParam("Id") String id) {
        return call("CancelMulticastGroupSession", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.cancelMulticastGroupSession(in, regions.getRegion()));
    }

    @POST
    @Path("/destinations")
    public Response createDestination(String body) {
        return call("CreateDestination", 201, body, in -> {
        }, in -> service.createDestination(in, regions.getRegion()));
    }

    @POST
    @Path("/device-profiles")
    public Response createDeviceProfile(String body) {
        return call("CreateDeviceProfile", 201, body, in -> {
        }, in -> service.createDeviceProfile(in, regions.getRegion()));
    }

    @POST
    @Path("/fuota-tasks")
    public Response createFuotaTask(String body) {
        return call("CreateFuotaTask", 201, body, in -> {
        }, in -> fleet.createFuotaTask(in, regions.getRegion()));
    }

    @POST
    @Path("/multicast-groups")
    public Response createMulticastGroup(String body) {
        return call("CreateMulticastGroup", 201, body, in -> {
        }, in -> fleet.createMulticastGroup(in, regions.getRegion()));
    }

    @POST
    @Path("/network-analyzer-configurations")
    public Response createNetworkAnalyzerConfiguration(String body) {
        return call("CreateNetworkAnalyzerConfiguration", 201, body, in -> {
        }, in -> fleet.createNetworkAnalyzerConfiguration(in, regions.getRegion()));
    }

    @POST
    @Path("/service-profiles")
    public Response createServiceProfile(String body) {
        return call("CreateServiceProfile", 201, body, in -> {
        }, in -> service.createServiceProfile(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-devices")
    public Response createWirelessDevice(String body) {
        return call("CreateWirelessDevice", 201, body, in -> {
        }, in -> service.createWirelessDevice(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-gateways")
    public Response createWirelessGateway(String body) {
        return call("CreateWirelessGateway", 201, body, in -> {
        }, in -> service.createWirelessGateway(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-gateways/{Id}/tasks")
    public Response createWirelessGatewayTask(@PathParam("Id") String id, String body) {
        return call("CreateWirelessGatewayTask", 201, body, in -> {
            in.put("Id", id);
        }, in -> fleet.createWirelessGatewayTask(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-gateway-task-definitions")
    public Response createWirelessGatewayTaskDefinition(String body) {
        return call("CreateWirelessGatewayTaskDefinition", 201, body, in -> {
        }, in -> fleet.createWirelessGatewayTaskDefinition(in, regions.getRegion()));
    }

    @DELETE
    @Path("/destinations/{Name}")
    public Response deleteDestination(@PathParam("Name") String name) {
        return call("DeleteDestination", 204, null, in -> {
            in.put("Name", name);
        }, in -> service.deleteDestination(in, regions.getRegion()));
    }

    @DELETE
    @Path("/device-profiles/{Id}")
    public Response deleteDeviceProfile(@PathParam("Id") String id) {
        return call("DeleteDeviceProfile", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.deleteDeviceProfile(in, regions.getRegion()));
    }

    @DELETE
    @Path("/fuota-tasks/{Id}")
    public Response deleteFuotaTask(@PathParam("Id") String id) {
        return call("DeleteFuotaTask", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.deleteFuotaTask(in, regions.getRegion()));
    }

    @DELETE
    @Path("/multicast-groups/{Id}")
    public Response deleteMulticastGroup(@PathParam("Id") String id) {
        return call("DeleteMulticastGroup", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.deleteMulticastGroup(in, regions.getRegion()));
    }

    @DELETE
    @Path("/network-analyzer-configurations/{ConfigurationName}")
    public Response deleteNetworkAnalyzerConfiguration(@PathParam("ConfigurationName") String configurationName) {
        return call("DeleteNetworkAnalyzerConfiguration", 204, null, in -> {
            in.put("ConfigurationName", configurationName);
        }, in -> fleet.deleteNetworkAnalyzerConfiguration(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-devices/{Id}/data")
    public Response deleteQueuedMessages(@PathParam("Id") String id, @QueryParam("messageId") String messageId, @QueryParam("WirelessDeviceType") String wirelessDeviceType) {
        return call("DeleteQueuedMessages", 204, null, in -> {
            in.put("Id", id);
            in.put("MessageId", messageId);
            in.put("WirelessDeviceType", wirelessDeviceType);
        }, in -> network.deleteQueuedMessages(in, regions.getRegion()));
    }

    @DELETE
    @Path("/service-profiles/{Id}")
    public Response deleteServiceProfile(@PathParam("Id") String id) {
        return call("DeleteServiceProfile", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.deleteServiceProfile(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-devices/{Id}")
    public Response deleteWirelessDevice(@PathParam("Id") String id) {
        return call("DeleteWirelessDevice", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.deleteWirelessDevice(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless_device_import_task/{Id}")
    public Response deleteWirelessDeviceImportTask(@PathParam("Id") String id) {
        return call("DeleteWirelessDeviceImportTask", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.deleteWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-gateways/{Id}")
    public Response deleteWirelessGateway(@PathParam("Id") String id) {
        return call("DeleteWirelessGateway", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.deleteWirelessGateway(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-gateways/{Id}/tasks")
    public Response deleteWirelessGatewayTask(@PathParam("Id") String id) {
        return call("DeleteWirelessGatewayTask", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.deleteWirelessGatewayTask(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-gateway-task-definitions/{Id}")
    public Response deleteWirelessGatewayTaskDefinition(@PathParam("Id") String id) {
        return call("DeleteWirelessGatewayTaskDefinition", 204, null, in -> {
            in.put("Id", id);
        }, in -> fleet.deleteWirelessGatewayTaskDefinition(in, regions.getRegion()));
    }

    @PATCH
    @Path("/wireless-devices/{Identifier}/deregister")
    public Response deregisterWirelessDevice(@PathParam("Identifier") String identifier, @QueryParam("WirelessDeviceType") String wirelessDeviceType) {
        return call("DeregisterWirelessDevice", 200, null, in -> {
            in.put("Identifier", identifier);
            in.put("WirelessDeviceType", wirelessDeviceType);
        }, in -> service.deregisterWirelessDevice(in, regions.getRegion()));
    }

    @DELETE
    @Path("/partner-accounts/{PartnerAccountId}")
    public Response disassociateAwsAccountFromPartnerAccount(@PathParam("PartnerAccountId") String partnerAccountId, @QueryParam("partnerType") String partnerType) {
        return call("DisassociateAwsAccountFromPartnerAccount", 204, null, in -> {
            in.put("PartnerAccountId", partnerAccountId);
            in.put("PartnerType", partnerType);
        }, in -> fleet.disassociateAwsAccountFromPartnerAccount(in, regions.getRegion()));
    }

    @DELETE
    @Path("/fuota-tasks/{Id}/multicast-groups/{MulticastGroupId}")
    public Response disassociateMulticastGroupFromFuotaTask(@PathParam("Id") String id, @PathParam("MulticastGroupId") String multicastGroupId) {
        return call("DisassociateMulticastGroupFromFuotaTask", 204, null, in -> {
            in.put("Id", id);
            in.put("MulticastGroupId", multicastGroupId);
        }, in -> fleet.disassociateMulticastGroupFromFuotaTask(in, regions.getRegion()));
    }

    @DELETE
    @Path("/fuota-tasks/{Id}/wireless-devices/{WirelessDeviceId}")
    public Response disassociateWirelessDeviceFromFuotaTask(@PathParam("Id") String id, @PathParam("WirelessDeviceId") String wirelessDeviceId) {
        return call("DisassociateWirelessDeviceFromFuotaTask", 204, null, in -> {
            in.put("Id", id);
            in.put("WirelessDeviceId", wirelessDeviceId);
        }, in -> fleet.disassociateWirelessDeviceFromFuotaTask(in, regions.getRegion()));
    }

    @DELETE
    @Path("/multicast-groups/{Id}/wireless-devices/{WirelessDeviceId}")
    public Response disassociateWirelessDeviceFromMulticastGroup(@PathParam("Id") String id, @PathParam("WirelessDeviceId") String wirelessDeviceId) {
        return call("DisassociateWirelessDeviceFromMulticastGroup", 204, null, in -> {
            in.put("Id", id);
            in.put("WirelessDeviceId", wirelessDeviceId);
        }, in -> fleet.disassociateWirelessDeviceFromMulticastGroup(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-devices/{Id}/thing")
    public Response disassociateWirelessDeviceFromThing(@PathParam("Id") String id) {
        return call("DisassociateWirelessDeviceFromThing", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.disassociateWirelessDeviceFromThing(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-gateways/{Id}/certificate")
    public Response disassociateWirelessGatewayFromCertificate(@PathParam("Id") String id) {
        return call("DisassociateWirelessGatewayFromCertificate", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.disassociateWirelessGatewayFromCertificate(in, regions.getRegion()));
    }

    @DELETE
    @Path("/wireless-gateways/{Id}/thing")
    public Response disassociateWirelessGatewayFromThing(@PathParam("Id") String id) {
        return call("DisassociateWirelessGatewayFromThing", 204, null, in -> {
            in.put("Id", id);
        }, in -> service.disassociateWirelessGatewayFromThing(in, regions.getRegion()));
    }

    @GET
    @Path("/destinations/{Name}")
    public Response getDestination(@PathParam("Name") String name) {
        return call("GetDestination", 200, null, in -> {
            in.put("Name", name);
        }, in -> service.getDestination(in, regions.getRegion()));
    }

    @GET
    @Path("/device-profiles/{Id}")
    public Response getDeviceProfile(@PathParam("Id") String id) {
        return call("GetDeviceProfile", 200, null, in -> {
            in.put("Id", id);
        }, in -> service.getDeviceProfile(in, regions.getRegion()));
    }

    @GET
    @Path("/event-configurations-resource-types")
    public Response getEventConfigurationByResourceTypes() {
        return call("GetEventConfigurationByResourceTypes", 200, null, in -> {
        }, in -> network.getEventConfigurationByResourceTypes(in, regions.getRegion()));
    }

    @GET
    @Path("/fuota-tasks/{Id}")
    public Response getFuotaTask(@PathParam("Id") String id) {
        return call("GetFuotaTask", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getFuotaTask(in, regions.getRegion()));
    }

    @GET
    @Path("/log-levels")
    public Response getLogLevelsByResourceTypes() {
        return call("GetLogLevelsByResourceTypes", 200, null, in -> {
        }, in -> network.getLogLevelsByResourceTypes(in, regions.getRegion()));
    }

    @GET
    @Path("/metric-configuration")
    public Response getMetricConfiguration() {
        return call("GetMetricConfiguration", 200, null, in -> {
        }, in -> network.getMetricConfiguration(in, regions.getRegion()));
    }

    @POST
    @Path("/metrics")
    public Response getMetrics(String body) {
        return call("GetMetrics", 200, body, in -> {
        }, in -> network.getMetrics(in, regions.getRegion()));
    }

    @GET
    @Path("/multicast-groups/{Id}")
    public Response getMulticastGroup(@PathParam("Id") String id) {
        return call("GetMulticastGroup", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getMulticastGroup(in, regions.getRegion()));
    }

    @GET
    @Path("/multicast-groups/{Id}/session")
    public Response getMulticastGroupSession(@PathParam("Id") String id) {
        return call("GetMulticastGroupSession", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getMulticastGroupSession(in, regions.getRegion()));
    }

    @GET
    @Path("/network-analyzer-configurations/{ConfigurationName}")
    public Response getNetworkAnalyzerConfiguration(@PathParam("ConfigurationName") String configurationName) {
        return call("GetNetworkAnalyzerConfiguration", 200, null, in -> {
            in.put("ConfigurationName", configurationName);
        }, in -> fleet.getNetworkAnalyzerConfiguration(in, regions.getRegion()));
    }

    @GET
    @Path("/partner-accounts/{PartnerAccountId}")
    public Response getPartnerAccount(@PathParam("PartnerAccountId") String partnerAccountId, @QueryParam("partnerType") String partnerType) {
        return call("GetPartnerAccount", 200, null, in -> {
            in.put("PartnerAccountId", partnerAccountId);
            in.put("PartnerType", partnerType);
        }, in -> fleet.getPartnerAccount(in, regions.getRegion()));
    }

    @GET
    @Path("/positions/{ResourceIdentifier}")
    public Response getPosition(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType) {
        return call("GetPosition", 200, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.getPosition(in, regions.getRegion()));
    }

    @GET
    @Path("/position-configurations/{ResourceIdentifier}")
    public Response getPositionConfiguration(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType) {
        return call("GetPositionConfiguration", 200, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.getPositionConfiguration(in, regions.getRegion()));
    }

    @POST
    @Path("/position-estimate")
    public Response getPositionEstimate(String body) {
        return blob("GetPositionEstimate", 200, body, in -> {
        }, in -> network.getPositionEstimate(in, regions.getRegion()));
    }

    @GET
    @Path("/event-configurations/{Identifier}")
    public Response getResourceEventConfiguration(@PathParam("Identifier") String identifier, @QueryParam("identifierType") String identifierType, @QueryParam("partnerType") String partnerType) {
        return call("GetResourceEventConfiguration", 200, null, in -> {
            in.put("Identifier", identifier);
            in.put("IdentifierType", identifierType);
            in.put("PartnerType", partnerType);
        }, in -> network.getResourceEventConfiguration(in, regions.getRegion()));
    }

    @GET
    @Path("/log-levels/{ResourceIdentifier}")
    public Response getResourceLogLevel(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType) {
        return call("GetResourceLogLevel", 200, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.getResourceLogLevel(in, regions.getRegion()));
    }

    @GET
    @Path("/resource-positions/{ResourceIdentifier}")
    public Response getResourcePosition(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType) {
        return blob("GetResourcePosition", 200, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.getResourcePosition(in, regions.getRegion()));
    }

    @GET
    @Path("/service-endpoint")
    public Response getServiceEndpoint(@QueryParam("serviceType") String serviceType) {
        return call("GetServiceEndpoint", 200, null, in -> {
            in.put("ServiceType", serviceType);
        }, in -> network.getServiceEndpoint(in, regions.getRegion()));
    }

    @GET
    @Path("/service-profiles/{Id}")
    public Response getServiceProfile(@PathParam("Id") String id) {
        return call("GetServiceProfile", 200, null, in -> {
            in.put("Id", id);
        }, in -> service.getServiceProfile(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-devices/{Identifier}")
    public Response getWirelessDevice(@PathParam("Identifier") String identifier, @QueryParam("identifierType") String identifierType) {
        return call("GetWirelessDevice", 200, null, in -> {
            in.put("Identifier", identifier);
            in.put("IdentifierType", identifierType);
        }, in -> service.getWirelessDevice(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless_device_import_task/{Id}")
    public Response getWirelessDeviceImportTask(@PathParam("Id") String id) {
        return call("GetWirelessDeviceImportTask", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-devices/{Id}/statistics")
    public Response getWirelessDeviceStatistics(@PathParam("Id") String wirelessDeviceId) {
        return call("GetWirelessDeviceStatistics", 200, null, in -> {
            in.put("WirelessDeviceId", wirelessDeviceId);
        }, in -> network.getWirelessDeviceStatistics(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways/{Identifier}")
    public Response getWirelessGateway(@PathParam("Identifier") String identifier, @QueryParam("identifierType") String identifierType) {
        return call("GetWirelessGateway", 200, null, in -> {
            in.put("Identifier", identifier);
            in.put("IdentifierType", identifierType);
        }, in -> service.getWirelessGateway(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways/{Id}/certificate")
    public Response getWirelessGatewayCertificate(@PathParam("Id") String id) {
        return call("GetWirelessGatewayCertificate", 200, null, in -> {
            in.put("Id", id);
        }, in -> service.getWirelessGatewayCertificate(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways/{Id}/firmware-information")
    public Response getWirelessGatewayFirmwareInformation(@PathParam("Id") String id) {
        return call("GetWirelessGatewayFirmwareInformation", 200, null, in -> {
            in.put("Id", id);
        }, in -> service.getWirelessGatewayFirmwareInformation(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways/{Id}/statistics")
    public Response getWirelessGatewayStatistics(@PathParam("Id") String wirelessGatewayId) {
        return call("GetWirelessGatewayStatistics", 200, null, in -> {
            in.put("WirelessGatewayId", wirelessGatewayId);
        }, in -> network.getWirelessGatewayStatistics(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways/{Id}/tasks")
    public Response getWirelessGatewayTask(@PathParam("Id") String id) {
        return call("GetWirelessGatewayTask", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getWirelessGatewayTask(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateway-task-definitions/{Id}")
    public Response getWirelessGatewayTaskDefinition(@PathParam("Id") String id) {
        return call("GetWirelessGatewayTaskDefinition", 200, null, in -> {
            in.put("Id", id);
        }, in -> fleet.getWirelessGatewayTaskDefinition(in, regions.getRegion()));
    }

    @GET
    @Path("/destinations")
    public Response listDestinations(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListDestinations", 200, null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> service.listDestinations(in, regions.getRegion()));
    }

    @GET
    @Path("/device-profiles")
    public Response listDeviceProfiles(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("deviceProfileType") String deviceProfileType) {
        return call("ListDeviceProfiles", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
            in.put("DeviceProfileType", deviceProfileType);
        }, in -> service.listDeviceProfiles(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless_device_import_task")
    public Response listDevicesForWirelessDeviceImportTask(@QueryParam("id") String id, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken, @QueryParam("status") String status) {
        return call("ListDevicesForWirelessDeviceImportTask", 200, null, in -> {
            in.put("Id", id);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
            in.put("Status", status);
        }, in -> fleet.listDevicesForWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @GET
    @Path("/event-configurations")
    public Response listEventConfigurations(@QueryParam("resourceType") String resourceType, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListEventConfigurations", 200, null, in -> {
            in.put("ResourceType", resourceType);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> network.listEventConfigurations(in, regions.getRegion()));
    }

    @GET
    @Path("/fuota-tasks")
    public Response listFuotaTasks(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListFuotaTasks", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> fleet.listFuotaTasks(in, regions.getRegion()));
    }

    @GET
    @Path("/multicast-groups")
    public Response listMulticastGroups(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListMulticastGroups", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> fleet.listMulticastGroups(in, regions.getRegion()));
    }

    @GET
    @Path("/fuota-tasks/{Id}/multicast-groups")
    public Response listMulticastGroupsByFuotaTask(@PathParam("Id") String id, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListMulticastGroupsByFuotaTask", 200, null, in -> {
            in.put("Id", id);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> fleet.listMulticastGroupsByFuotaTask(in, regions.getRegion()));
    }

    @GET
    @Path("/network-analyzer-configurations")
    public Response listNetworkAnalyzerConfigurations(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListNetworkAnalyzerConfigurations", 200, null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> fleet.listNetworkAnalyzerConfigurations(in, regions.getRegion()));
    }

    @GET
    @Path("/partner-accounts")
    public Response listPartnerAccounts(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListPartnerAccounts", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> fleet.listPartnerAccounts(in, regions.getRegion()));
    }

    @GET
    @Path("/position-configurations")
    public Response listPositionConfigurations(@QueryParam("resourceType") String resourceType, @QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListPositionConfigurations", 200, null, in -> {
            in.put("ResourceType", resourceType);
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> network.listPositionConfigurations(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-devices/{Id}/data")
    public Response listQueuedMessages(@PathParam("Id") String id, @QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults, @QueryParam("WirelessDeviceType") String wirelessDeviceType) {
        return call("ListQueuedMessages", 200, null, in -> {
            in.put("Id", id);
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
            in.put("WirelessDeviceType", wirelessDeviceType);
        }, in -> network.listQueuedMessages(in, regions.getRegion()));
    }

    @GET
    @Path("/service-profiles")
    public Response listServiceProfiles(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListServiceProfiles", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listServiceProfiles(in, regions.getRegion()));
    }

    @GET
    @Path("/tags")
    public Response listTagsForResource(@QueryParam("resourceArn") String resourceArn) {
        return call("ListTagsForResource", 200, null, in -> {
            in.put("ResourceArn", resourceArn);
        }, in -> service.listTagsForResource(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless_device_import_tasks")
    public Response listWirelessDeviceImportTasks(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken) {
        return call("ListWirelessDeviceImportTasks", 200, null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
        }, in -> fleet.listWirelessDeviceImportTasks(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-devices")
    public Response listWirelessDevices(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken, @QueryParam("destinationName") String destinationName, @QueryParam("deviceProfileId") String deviceProfileId, @QueryParam("serviceProfileId") String serviceProfileId, @QueryParam("wirelessDeviceType") String wirelessDeviceType, @QueryParam("fuotaTaskId") String fuotaTaskId, @QueryParam("multicastGroupId") String multicastGroupId) {
        return call("ListWirelessDevices", 200, null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
            in.put("DestinationName", destinationName);
            in.put("DeviceProfileId", deviceProfileId);
            in.put("ServiceProfileId", serviceProfileId);
            in.put("WirelessDeviceType", wirelessDeviceType);
            in.put("FuotaTaskId", fuotaTaskId);
            in.put("MulticastGroupId", multicastGroupId);
        }, in -> service.listWirelessDevices(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateway-task-definitions")
    public Response listWirelessGatewayTaskDefinitions(@QueryParam("maxResults") String maxResults, @QueryParam("nextToken") String nextToken, @QueryParam("taskDefinitionType") String taskDefinitionType) {
        return call("ListWirelessGatewayTaskDefinitions", 200, null, in -> {
            integer(in, "MaxResults", maxResults);
            in.put("NextToken", nextToken);
            in.put("TaskDefinitionType", taskDefinitionType);
        }, in -> fleet.listWirelessGatewayTaskDefinitions(in, regions.getRegion()));
    }

    @GET
    @Path("/wireless-gateways")
    public Response listWirelessGateways(@QueryParam("nextToken") String nextToken, @QueryParam("maxResults") String maxResults) {
        return call("ListWirelessGateways", 200, null, in -> {
            in.put("NextToken", nextToken);
            integer(in, "MaxResults", maxResults);
        }, in -> service.listWirelessGateways(in, regions.getRegion()));
    }

    @PUT
    @Path("/position-configurations/{ResourceIdentifier}")
    public Response putPositionConfiguration(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType, String body) {
        return call("PutPositionConfiguration", 200, body, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.putPositionConfiguration(in, regions.getRegion()));
    }

    @PUT
    @Path("/log-levels/{ResourceIdentifier}")
    public Response putResourceLogLevel(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType, String body) {
        return call("PutResourceLogLevel", 200, body, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.putResourceLogLevel(in, regions.getRegion()));
    }

    @DELETE
    @Path("/log-levels")
    public Response resetAllResourceLogLevels() {
        return call("ResetAllResourceLogLevels", 204, null, in -> {
        }, in -> network.resetAllResourceLogLevels(in, regions.getRegion()));
    }

    @DELETE
    @Path("/log-levels/{ResourceIdentifier}")
    public Response resetResourceLogLevel(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType) {
        return call("ResetResourceLogLevel", 204, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.resetResourceLogLevel(in, regions.getRegion()));
    }

    @POST
    @Path("/multicast-groups/{Id}/data")
    public Response sendDataToMulticastGroup(@PathParam("Id") String id, String body) {
        return call("SendDataToMulticastGroup", 201, body, in -> {
            in.put("Id", id);
        }, in -> fleet.sendDataToMulticastGroup(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-devices/{Id}/data")
    public Response sendDataToWirelessDevice(@PathParam("Id") String id, String body) {
        return call("SendDataToWirelessDevice", 202, body, in -> {
            in.put("Id", id);
        }, in -> network.sendDataToWirelessDevice(in, regions.getRegion()));
    }

    @PATCH
    @Path("/multicast-groups/{Id}/bulk")
    public Response startBulkAssociateWirelessDeviceWithMulticastGroup(@PathParam("Id") String id, String body) {
        return call("StartBulkAssociateWirelessDeviceWithMulticastGroup", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.startBulkAssociateWirelessDeviceWithMulticastGroup(in, regions.getRegion()));
    }

    @POST
    @Path("/multicast-groups/{Id}/bulk")
    public Response startBulkDisassociateWirelessDeviceFromMulticastGroup(@PathParam("Id") String id, String body) {
        return call("StartBulkDisassociateWirelessDeviceFromMulticastGroup", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.startBulkDisassociateWirelessDeviceFromMulticastGroup(in, regions.getRegion()));
    }

    @PUT
    @Path("/fuota-tasks/{Id}")
    public Response startFuotaTask(@PathParam("Id") String id, String body) {
        return call("StartFuotaTask", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.startFuotaTask(in, regions.getRegion()));
    }

    @PUT
    @Path("/multicast-groups/{Id}/session")
    public Response startMulticastGroupSession(@PathParam("Id") String id, String body) {
        return call("StartMulticastGroupSession", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.startMulticastGroupSession(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless_single_device_import_task")
    public Response startSingleWirelessDeviceImportTask(String body) {
        return call("StartSingleWirelessDeviceImportTask", 201, body, in -> {
        }, in -> fleet.startSingleWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless_device_import_task")
    public Response startWirelessDeviceImportTask(String body) {
        return call("StartWirelessDeviceImportTask", 201, body, in -> {
        }, in -> fleet.startWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @POST
    @Path("/tags")
    public Response tagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        return call("TagResource", 204, body, in -> {
            in.put("ResourceArn", resourceArn);
        }, in -> service.tagResource(in, regions.getRegion()));
    }

    @POST
    @Path("/wireless-devices/{Id}/test")
    public Response testWirelessDevice(@PathParam("Id") String id) {
        return call("TestWirelessDevice", 200, null, in -> {
            in.put("Id", id);
        }, in -> network.testWirelessDevice(in, regions.getRegion()));
    }

    @DELETE
    @Path("/tags")
    public Response untagResource(@QueryParam("resourceArn") String resourceArn, @QueryParam("tagKeys") List<String> tagKeys) {
        return call("UntagResource", 204, null, in -> {
            in.put("ResourceArn", resourceArn);
            list(in, "TagKeys", tagKeys);
        }, in -> service.untagResource(in, regions.getRegion()));
    }

    @PATCH
    @Path("/destinations/{Name}")
    public Response updateDestination(@PathParam("Name") String name, String body) {
        return call("UpdateDestination", 204, body, in -> {
            in.put("Name", name);
        }, in -> service.updateDestination(in, regions.getRegion()));
    }

    @PATCH
    @Path("/event-configurations-resource-types")
    public Response updateEventConfigurationByResourceTypes(String body) {
        return call("UpdateEventConfigurationByResourceTypes", 204, body, in -> {
        }, in -> network.updateEventConfigurationByResourceTypes(in, regions.getRegion()));
    }

    @PATCH
    @Path("/fuota-tasks/{Id}")
    public Response updateFuotaTask(@PathParam("Id") String id, String body) {
        return call("UpdateFuotaTask", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.updateFuotaTask(in, regions.getRegion()));
    }

    @POST
    @Path("/log-levels")
    public Response updateLogLevelsByResourceTypes(String body) {
        return call("UpdateLogLevelsByResourceTypes", 200, body, in -> {
        }, in -> network.updateLogLevelsByResourceTypes(in, regions.getRegion()));
    }

    @PUT
    @Path("/metric-configuration")
    public Response updateMetricConfiguration(String body) {
        return call("UpdateMetricConfiguration", 204, body, in -> {
        }, in -> network.updateMetricConfiguration(in, regions.getRegion()));
    }

    @PATCH
    @Path("/multicast-groups/{Id}")
    public Response updateMulticastGroup(@PathParam("Id") String id, String body) {
        return call("UpdateMulticastGroup", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.updateMulticastGroup(in, regions.getRegion()));
    }

    @PATCH
    @Path("/network-analyzer-configurations/{ConfigurationName}")
    public Response updateNetworkAnalyzerConfiguration(@PathParam("ConfigurationName") String configurationName, String body) {
        return call("UpdateNetworkAnalyzerConfiguration", 204, body, in -> {
            in.put("ConfigurationName", configurationName);
        }, in -> fleet.updateNetworkAnalyzerConfiguration(in, regions.getRegion()));
    }

    @PATCH
    @Path("/partner-accounts/{PartnerAccountId}")
    public Response updatePartnerAccount(@PathParam("PartnerAccountId") String partnerAccountId, @QueryParam("partnerType") String partnerType, String body) {
        return call("UpdatePartnerAccount", 204, body, in -> {
            in.put("PartnerAccountId", partnerAccountId);
            in.put("PartnerType", partnerType);
        }, in -> fleet.updatePartnerAccount(in, regions.getRegion()));
    }

    @PATCH
    @Path("/positions/{ResourceIdentifier}")
    public Response updatePosition(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType, String body) {
        return call("UpdatePosition", 204, body, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
        }, in -> network.updatePosition(in, regions.getRegion()));
    }

    @PATCH
    @Path("/event-configurations/{Identifier}")
    public Response updateResourceEventConfiguration(@PathParam("Identifier") String identifier, @QueryParam("identifierType") String identifierType, @QueryParam("partnerType") String partnerType, String body) {
        return call("UpdateResourceEventConfiguration", 204, body, in -> {
            in.put("Identifier", identifier);
            in.put("IdentifierType", identifierType);
            in.put("PartnerType", partnerType);
        }, in -> network.updateResourceEventConfiguration(in, regions.getRegion()));
    }

    @PATCH
    @Path("/resource-positions/{ResourceIdentifier}")
    public Response updateResourcePosition(@PathParam("ResourceIdentifier") String resourceIdentifier, @QueryParam("resourceType") String resourceType, byte[] body) {
        return call("UpdateResourcePosition", 204, null, in -> {
            in.put("ResourceIdentifier", resourceIdentifier);
            in.put("ResourceType", resourceType);
            in.put("GeoJsonPayload", body == null || body.length == 0 ? null : new String(body, StandardCharsets.UTF_8));
        }, in -> network.updateResourcePosition(in, regions.getRegion()));
    }

    @PATCH
    @Path("/wireless-devices/{Id}")
    public Response updateWirelessDevice(@PathParam("Id") String id, String body) {
        return call("UpdateWirelessDevice", 204, body, in -> {
            in.put("Id", id);
        }, in -> service.updateWirelessDevice(in, regions.getRegion()));
    }

    @PATCH
    @Path("/wireless_device_import_task/{Id}")
    public Response updateWirelessDeviceImportTask(@PathParam("Id") String id, String body) {
        return call("UpdateWirelessDeviceImportTask", 204, body, in -> {
            in.put("Id", id);
        }, in -> fleet.updateWirelessDeviceImportTask(in, regions.getRegion()));
    }

    @PATCH
    @Path("/wireless-gateways/{Id}")
    public Response updateWirelessGateway(@PathParam("Id") String id, String body) {
        return call("UpdateWirelessGateway", 204, body, in -> {
            in.put("Id", id);
        }, in -> service.updateWirelessGateway(in, regions.getRegion()));
    }
    // ---------------------------------------------------------------- plumbing

    private ObjectNode request(String operation, String body, Consumer<ObjectNode> members) {
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
        return in;
    }

    private Response call(String operation, int status, String body, Consumer<ObjectNode> members,
                          Function<ObjectNode, ObjectNode> handler) {
        try {
            ObjectNode out = handler.apply(request(operation, body, members));
            return Response.status(status).type(MediaType.APPLICATION_JSON).entity(out.toString()).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response blob(String operation, int status, String body, Consumer<ObjectNode> members,
                          Function<ObjectNode, byte[]> handler) {
        try {
            byte[] out = handler.apply(request(operation, body, members));
            return Response.status(status).type(MediaType.APPLICATION_OCTET_STREAM).entity(out).build();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException e) {
        ObjectNode error = mapper.createObjectNode();
        error.put("Message", e.getMessage());
        if (e.getExtendedData() != null) {
            e.getExtendedData().forEach((key, value) -> error.set(key, mapper.valueToTree(value)));
        }
        return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw WirelessStore.validation("The request body must be a JSON object.");
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException e) {
            throw WirelessStore.validation("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void integer(ObjectNode in, String name, String value) {
        if (value == null) {
            return;
        }
        try {
            in.put(name, Long.parseLong(value));
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
}
