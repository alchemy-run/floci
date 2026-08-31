package io.github.hectorvent.floci.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

/**
 * AWS IoT Wireless restJson1. Public AWS paths such as {@code /destinations}
 * and {@code /wireless-devices} are rewritten onto
 * {@link IotWirelessRoutingFilter#INTERNAL_PREFIX} so they do not collide
 * with IoT Managed Integrations or S3's catch-all.
 */
@Path(IotWirelessRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotWirelessController {

    private final IotWirelessService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IotWirelessController(
            IotWirelessService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/destinations")
    public Response createDestination(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createDestination(region(headers), parse(body))).build());
    }

    @GET
    @Path("/destinations/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response getDestination(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> Response.ok(service.getDestination(region(headers), name)).build());
    }

    @PATCH
    @Path("/destinations/{Name}")
    public Response updateDestination(
            @Context HttpHeaders headers, @PathParam("Name") String name, String body) {
        return run(() -> Response.ok(service.updateDestination(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/destinations/{Name}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDestination(@Context HttpHeaders headers, @PathParam("Name") String name) {
        return run(() -> Response.ok(service.deleteDestination(region(headers), name)).build());
    }

    @GET
    @Path("/destinations")
    @Consumes(MediaType.WILDCARD)
    public Response listDestinations(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.listDestinations(region(headers))).build());
    }

    @POST
    @Path("/device-profiles")
    public Response createDeviceProfile(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createDeviceProfile(region(headers), parse(body))).build());
    }

    @GET
    @Path("/device-profiles/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response getDeviceProfile(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.getDeviceProfile(region(headers), id)).build());
    }

    @DELETE
    @Path("/device-profiles/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDeviceProfile(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.deleteDeviceProfile(region(headers), id)).build());
    }

    @GET
    @Path("/device-profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listDeviceProfiles(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.listDeviceProfiles(region(headers))).build());
    }

    @POST
    @Path("/service-profiles")
    public Response createServiceProfile(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createServiceProfile(region(headers), parse(body))).build());
    }

    @GET
    @Path("/service-profiles/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response getServiceProfile(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.getServiceProfile(region(headers), id)).build());
    }

    @DELETE
    @Path("/service-profiles/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteServiceProfile(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.deleteServiceProfile(region(headers), id)).build());
    }

    @GET
    @Path("/service-profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listServiceProfiles(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.listServiceProfiles(region(headers))).build());
    }

    @POST
    @Path("/wireless-devices")
    public Response createWirelessDevice(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createWirelessDevice(region(headers), parse(body))).build());
    }

    @GET
    @Path("/wireless-devices/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getWirelessDevice(
            @Context HttpHeaders headers,
            @PathParam("Identifier") String identifier,
            @QueryParam("identifierType") String identifierType) {
        return run(() ->
                Response.ok(service.getWirelessDevice(region(headers), identifier, identifierType)).build());
    }

    @PATCH
    @Path("/wireless-devices/{Id}")
    public Response updateWirelessDevice(
            @Context HttpHeaders headers, @PathParam("Id") String id, String body) {
        return run(() -> Response.ok(service.updateWirelessDevice(region(headers), id, parse(body))).build());
    }

    @DELETE
    @Path("/wireless-devices/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteWirelessDevice(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.deleteWirelessDevice(region(headers), id)).build());
    }

    @GET
    @Path("/wireless-devices")
    @Consumes(MediaType.WILDCARD)
    public Response listWirelessDevices(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.listWirelessDevices(region(headers))).build());
    }

    @POST
    @Path("/wireless-devices/{Id}/data")
    public Response sendDataToWirelessDevice(
            @Context HttpHeaders headers, @PathParam("Id") String id, String body) {
        return run(() ->
                Response.ok(service.sendDataToWirelessDevice(region(headers), id, parse(body))).build());
    }

    @GET
    @Path("/wireless-devices/{Id}/data")
    @Consumes(MediaType.WILDCARD)
    public Response listQueuedMessages(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.listQueuedMessages(region(headers), id)).build());
    }

    @DELETE
    @Path("/wireless-devices/{Id}/data")
    @Consumes(MediaType.WILDCARD)
    public Response deleteQueuedMessages(
            @Context HttpHeaders headers,
            @PathParam("Id") String id,
            @QueryParam("messageId") String messageId) {
        return run(() -> Response.ok(service.deleteQueuedMessages(region(headers), id, messageId)).build());
    }

    @GET
    @Path("/wireless-devices/{WirelessDeviceId}/statistics")
    @Consumes(MediaType.WILDCARD)
    public Response getWirelessDeviceStatistics(
            @Context HttpHeaders headers, @PathParam("WirelessDeviceId") String id) {
        return run(() -> Response.ok(service.getWirelessDeviceStatistics(region(headers), id)).build());
    }

    @POST
    @Path("/wireless-devices/{Id}/test")
    @Consumes(MediaType.WILDCARD)
    public Response testWirelessDevice(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.testWirelessDevice(region(headers), id)).build());
    }

    @POST
    @Path("/wireless-gateways")
    public Response createWirelessGateway(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createWirelessGateway(region(headers), parse(body))).build());
    }

    @GET
    @Path("/wireless-gateways/{Identifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getWirelessGateway(
            @Context HttpHeaders headers,
            @PathParam("Identifier") String identifier,
            @QueryParam("identifierType") String identifierType) {
        return run(() ->
                Response.ok(service.getWirelessGateway(region(headers), identifier, identifierType)).build());
    }

    @PATCH
    @Path("/wireless-gateways/{Id}")
    public Response updateWirelessGateway(
            @Context HttpHeaders headers, @PathParam("Id") String id, String body) {
        return run(() -> Response.ok(service.updateWirelessGateway(region(headers), id, parse(body))).build());
    }

    @DELETE
    @Path("/wireless-gateways/{Id}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteWirelessGateway(@Context HttpHeaders headers, @PathParam("Id") String id) {
        return run(() -> Response.ok(service.deleteWirelessGateway(region(headers), id)).build());
    }

    @GET
    @Path("/wireless-gateways")
    @Consumes(MediaType.WILDCARD)
    public Response listWirelessGateways(@Context HttpHeaders headers) {
        return run(() -> Response.ok(service.listWirelessGateways(region(headers))).build());
    }

    @GET
    @Path("/wireless-gateways/{WirelessGatewayId}/statistics")
    @Consumes(MediaType.WILDCARD)
    public Response getWirelessGatewayStatistics(
            @Context HttpHeaders headers, @PathParam("WirelessGatewayId") String id) {
        return run(() -> Response.ok(service.getWirelessGatewayStatistics(region(headers), id)).build());
    }

    @GET
    @Path("/service-endpoint")
    @Consumes(MediaType.WILDCARD)
    public Response getServiceEndpoint(
            @Context HttpHeaders headers, @QueryParam("serviceType") String serviceType) {
        return run(() -> Response.ok(service.getServiceEndpoint(region(headers), serviceType)).build());
    }

    @PATCH
    @Path("/resource-positions/{ResourceIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response updateResourcePosition(
            @Context HttpHeaders headers,
            @PathParam("ResourceIdentifier") String resourceIdentifier,
            @QueryParam("resourceType") String resourceType,
            String body) {
        return run(() -> {
            service.updateResourcePosition(region(headers), resourceIdentifier, resourceType, body);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @GET
    @Path("/resource-positions/{ResourceIdentifier}")
    @Consumes(MediaType.WILDCARD)
    public Response getResourcePosition(
            @Context HttpHeaders headers,
            @PathParam("ResourceIdentifier") String resourceIdentifier,
            @QueryParam("resourceType") String resourceType) {
        return run(() -> Response.ok(service.getResourcePosition(region(headers), resourceIdentifier, resourceType))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    @POST
    @Path("/position-estimate")
    public Response getPositionEstimate() {
        return run(() -> Response.ok(service.getPositionEstimate()).type(MediaType.APPLICATION_JSON).build());
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

    private Response run(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        if (exception.getExtendedData() != null) {
            exception.getExtendedData()
                    .forEach((key, value) -> node.set(key, objectMapper.valueToTree(value)));
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
