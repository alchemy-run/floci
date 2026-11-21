package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

/** Amazon Location's Routes API (signing name {@code geo-routes}) over {@link LocationRouting}. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class GeoRoutesController {
    private final LocationRouting routing;
    private final ObjectMapper mapper;

    @Inject
    public GeoRoutesController(LocationRouting routing, ObjectMapper mapper) {
        this.routing = routing;
        this.mapper = mapper;
    }

    @POST
    @Path("/v2/routes")
    public Response calculateRoutes(String body) {
        return priced(routing.calculateRoutes(check("CalculateRoutes", body)));
    }

    @POST
    @Path("/v2/route-matrix")
    public Response calculateRouteMatrix(String body) {
        return priced(routing.calculateRouteMatrixV2(check("CalculateRouteMatrix", body)));
    }

    @POST
    @Path("/v2/isolines")
    public Response calculateIsolines(String body) {
        return priced(routing.calculateIsolines(check("CalculateIsolines", body)));
    }

    @POST
    @Path("/v2/optimize-waypoints")
    public Response optimizeWaypoints(String body) {
        return priced(routing.optimizeWaypoints(check("OptimizeWaypoints", body)));
    }

    @POST
    @Path("/v2/snap-to-roads")
    public Response snapToRoads(String body) {
        return priced(routing.snapToRoads(check("SnapToRoads", body)));
    }

    static Response priced(Object body) {
        return Response.ok(body).header("x-amz-geo-pricing-bucket", "Bucket1").build();
    }

    private JsonNode check(String operation, String body) {
        JsonNode request;
        try {
            request = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw LocationService.validation("The request body must be a JSON object.");
        }
        if (request == null || !request.isObject()) {
            throw LocationService.validation("The request body must be a JSON object.");
        }
        LocationRequestShapes.check("geo-routes", operation, request);
        return request;
    }
}
