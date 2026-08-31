package io.github.hectorvent.floci.services.georoutes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Location Routes (geo-routes) restJson1.
 *
 * <p>Literal {@code /v2/routes}, {@code /v2/isolines}, {@code /v2/route-matrix},
 * {@code /v2/optimize-waypoints} and {@code /v2/snap-to-roads} paths take JAX-RS
 * precedence over S3's {@code /{bucket}/{key}} catch-all. Pricing is returned
 * on {@code x-amz-geo-pricing-bucket} as the Smithy model requires.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeoRoutesController {

    private final GeoRoutesService service;
    private final ObjectMapper objectMapper;

    @Inject
    public GeoRoutesController(GeoRoutesService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/v2/routes")
    public Response calculateRoutes(String body) {
        return priced(service.calculateRoutes(parse(body)));
    }

    @POST
    @Path("/v2/isolines")
    public Response calculateIsolines(String body) {
        return priced(service.calculateIsolines(parse(body)));
    }

    @POST
    @Path("/v2/route-matrix")
    public Response calculateRouteMatrix(String body) {
        return priced(service.calculateRouteMatrix(parse(body)));
    }

    @POST
    @Path("/v2/optimize-waypoints")
    public Response optimizeWaypoints(String body) {
        return priced(service.optimizeWaypoints(parse(body)));
    }

    @POST
    @Path("/v2/snap-to-roads")
    public Response snapToRoads(String body) {
        return priced(service.snapToRoads(parse(body)));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
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

    private static Response priced(ObjectNode body) {
        return Response.ok(body)
                .header(GeoRoutesService.PRICING_BUCKET_HEADER, GeoRoutesService.PRICING_BUCKET)
                .build();
    }
}
