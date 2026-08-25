package io.github.hectorvent.floci.services.iotfleetwise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iotfleetwise.model.CampaignStatus;
import io.github.hectorvent.floci.services.iotfleetwise.model.Fleet;
import io.github.hectorvent.floci.services.iotfleetwise.model.Vehicle;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
 * AWS IoT FleetWise restJson1.
 *
 * <p>Literal {@code /vehicles} and {@code /fleets} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} catch-all. Requests are signed as {@code iotfleetwise}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotFleetWiseController {

    private final IotFleetWiseService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public IotFleetWiseController(
            IotFleetWiseService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/vehicles/{vehicleName}")
    public Response createVehicle(
            @Context HttpHeaders headers, @PathParam("vehicleName") String vehicleName, String body) {
        Vehicle vehicle = service.createVehicle(regionResolver.resolveRegion(headers), vehicleName, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicle.getVehicleName());
        response.put("arn", vehicle.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/vehicles/{vehicleName}")
    @Consumes(MediaType.WILDCARD)
    public Response getVehicle(@Context HttpHeaders headers, @PathParam("vehicleName") String vehicleName) {
        Vehicle vehicle = service.getVehicle(regionResolver.resolveRegion(headers), vehicleName);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("vehicleName", vehicle.getVehicleName());
        response.put("arn", vehicle.getArn());
        response.put("modelManifestArn", vehicle.getModelManifestArn());
        response.put("decoderManifestArn", vehicle.getDecoderManifestArn());
        if (vehicle.getAttributes() != null && !vehicle.getAttributes().isEmpty()) {
            ObjectNode attributes = response.putObject("attributes");
            vehicle.getAttributes().forEach(attributes::put);
        }
        response.put("creationTime", vehicle.getCreationTime());
        response.put("lastModificationTime", vehicle.getLastModificationTime());
        return Response.ok(response).build();
    }

    @GET
    @Path("/vehicles/{vehicleName}/status")
    @Consumes(MediaType.WILDCARD)
    public Response getVehicleStatus(
            @Context HttpHeaders headers,
            @PathParam("vehicleName") String vehicleName,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("maxResults") String maxResults) {
        IotFleetWiseService.Page<CampaignStatus> page = service.getVehicleStatus(
                regionResolver.resolveRegion(headers), vehicleName, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode campaigns = response.putArray("campaigns");
        for (CampaignStatus status : page.items()) {
            ObjectNode node = campaigns.addObject();
            node.put("campaignName", status.getCampaignName());
            node.put("vehicleName", status.getVehicleName());
            node.put("status", status.getStatus());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/vehicles/{vehicleName}/associate")
    public Response associateVehicleFleet(
            @Context HttpHeaders headers, @PathParam("vehicleName") String vehicleName, String body) {
        service.associateVehicleFleet(regionResolver.resolveRegion(headers), vehicleName, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/fleets/{fleetId}")
    public Response createFleet(
            @Context HttpHeaders headers, @PathParam("fleetId") String fleetId, String body) {
        Fleet fleet = service.createFleet(regionResolver.resolveRegion(headers), fleetId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleet.getFleetId());
        response.put("arn", fleet.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/fleets/{fleetId}")
    @Consumes(MediaType.WILDCARD)
    public Response getFleet(@Context HttpHeaders headers, @PathParam("fleetId") String fleetId) {
        Fleet fleet = service.getFleet(regionResolver.resolveRegion(headers), fleetId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", fleet.getFleetId());
        response.put("arn", fleet.getArn());
        response.put("signalCatalogArn", fleet.getSignalCatalogArn());
        if (fleet.getDescription() != null) {
            response.put("description", fleet.getDescription());
        }
        response.put("creationTime", fleet.getCreationTime());
        response.put("lastModificationTime", fleet.getLastModificationTime());
        return Response.ok(response).build();
    }

    @GET
    @Path("/fleets/{fleetId}/vehicles")
    @Consumes(MediaType.WILDCARD)
    public Response listVehiclesInFleet(
            @Context HttpHeaders headers,
            @PathParam("fleetId") String fleetId,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("maxResults") String maxResults) {
        IotFleetWiseService.Page<String> page = service.listVehiclesInFleet(
                regionResolver.resolveRegion(headers), fleetId, nextToken, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode vehicles = response.putArray("vehicles");
        for (String name : page.items()) {
            vehicles.add(name);
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
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
