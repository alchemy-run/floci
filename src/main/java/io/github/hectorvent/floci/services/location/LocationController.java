package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

import java.util.function.Supplier;

/**
 * Amazon Location Service v1 (Smithy restJson1, signing name {@code geo}).
 *
 * <p>Literal {@code /maps/v0}, {@code /places/v0}, {@code /routes/v0},
 * {@code /geofencing/v0}, {@code /tracking/v0} and {@code /metadata/v0}
 * paths take JAX-RS precedence over S3's {@code /{bucket}} catch-all. Tag
 * APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocationController {

    private final LocationService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public LocationController(
            LocationService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    // ── Maps ────────────────────────────────────────────────────────────────

    @POST
    @Path("/maps/v0/maps")
    public Response createMap(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createMap(region(headers), parse(body))).build());
    }

    @GET
    @Path("/maps/v0/maps/{MapName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeMap(@Context HttpHeaders headers, @PathParam("MapName") String name) {
        return run(() -> Response.ok(service.describeMap(region(headers), name)).build());
    }

    @PATCH
    @Path("/maps/v0/maps/{MapName}")
    public Response updateMap(@Context HttpHeaders headers, @PathParam("MapName") String name, String body) {
        return run(() -> Response.ok(service.updateMap(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/maps/v0/maps/{MapName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteMap(@Context HttpHeaders headers, @PathParam("MapName") String name) {
        return run(() -> {
            service.deleteMap(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/maps/v0/list-maps")
    @Consumes(MediaType.WILDCARD)
    public Response listMaps(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listMaps(region(headers))).build());
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/style-descriptor")
    @Consumes(MediaType.WILDCARD)
    public Response getMapStyleDescriptor(
            @Context HttpHeaders headers, @PathParam("MapName") String name) {
        return run(() -> binary(service.getMapStyleDescriptor(region(headers), name)));
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/glyphs/{FontStack}/{FontUnicodeRange}")
    @Consumes(MediaType.WILDCARD)
    public Response getMapGlyphs(
            @Context HttpHeaders headers, @PathParam("MapName") String name) {
        return run(() -> binary(service.getMapGlyphs(region(headers), name)));
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/sprites/{FileName}")
    @Consumes(MediaType.WILDCARD)
    public Response getMapSprites(
            @Context HttpHeaders headers,
            @PathParam("MapName") String name,
            @PathParam("FileName") String fileName) {
        return run(() -> binary(service.getMapSprites(region(headers), name, fileName)));
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/tiles/{Z}/{X}/{Y}")
    @Consumes(MediaType.WILDCARD)
    public Response getMapTile(@Context HttpHeaders headers, @PathParam("MapName") String name) {
        return run(() -> binary(service.getMapTile(region(headers), name)));
    }

    // ── Place indexes ───────────────────────────────────────────────────────

    @POST
    @Path("/places/v0/indexes")
    public Response createPlaceIndex(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createPlaceIndex(region(headers), parse(body))).build());
    }

    @GET
    @Path("/places/v0/indexes/{IndexName}")
    @Consumes(MediaType.WILDCARD)
    public Response describePlaceIndex(
            @Context HttpHeaders headers, @PathParam("IndexName") String name) {
        return run(() -> Response.ok(service.describePlaceIndex(region(headers), name)).build());
    }

    @PATCH
    @Path("/places/v0/indexes/{IndexName}")
    public Response updatePlaceIndex(
            @Context HttpHeaders headers, @PathParam("IndexName") String name, String body) {
        return run(() -> Response.ok(service.updatePlaceIndex(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/places/v0/indexes/{IndexName}")
    @Consumes(MediaType.WILDCARD)
    public Response deletePlaceIndex(
            @Context HttpHeaders headers, @PathParam("IndexName") String name) {
        return run(() -> {
            service.deletePlaceIndex(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/places/v0/list-indexes")
    @Consumes(MediaType.WILDCARD)
    public Response listPlaceIndexes(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listPlaceIndexes(region(headers))).build());
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/text")
    public Response searchPlaceIndexForText(
            @Context HttpHeaders headers, @PathParam("IndexName") String name, String body) {
        return run(() -> Response.ok(
                service.searchPlaceIndexForText(region(headers), name, parse(body))).build());
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/position")
    public Response searchPlaceIndexForPosition(
            @Context HttpHeaders headers, @PathParam("IndexName") String name, String body) {
        return run(() -> Response.ok(
                service.searchPlaceIndexForPosition(region(headers), name, parse(body))).build());
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/suggestions")
    public Response searchPlaceIndexForSuggestions(
            @Context HttpHeaders headers, @PathParam("IndexName") String name, String body) {
        return run(() -> Response.ok(
                service.searchPlaceIndexForSuggestions(region(headers), name, parse(body))).build());
    }

    @GET
    @Path("/places/v0/indexes/{IndexName}/places/{PlaceId}")
    @Consumes(MediaType.WILDCARD)
    public Response getPlace(
            @Context HttpHeaders headers,
            @PathParam("IndexName") String name,
            @PathParam("PlaceId") String placeId) {
        return run(() -> Response.ok(service.getPlace(region(headers), name, placeId)).build());
    }

    // ── Route calculators ───────────────────────────────────────────────────

    @POST
    @Path("/routes/v0/calculators")
    public Response createRouteCalculator(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createRouteCalculator(region(headers), parse(body))).build());
    }

    @GET
    @Path("/routes/v0/calculators/{CalculatorName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRouteCalculator(
            @Context HttpHeaders headers, @PathParam("CalculatorName") String name) {
        return run(() -> Response.ok(service.describeRouteCalculator(region(headers), name)).build());
    }

    @PATCH
    @Path("/routes/v0/calculators/{CalculatorName}")
    public Response updateRouteCalculator(
            @Context HttpHeaders headers, @PathParam("CalculatorName") String name, String body) {
        return run(() -> Response.ok(
                service.updateRouteCalculator(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/routes/v0/calculators/{CalculatorName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRouteCalculator(
            @Context HttpHeaders headers, @PathParam("CalculatorName") String name) {
        return run(() -> {
            service.deleteRouteCalculator(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/routes/v0/list-calculators")
    @Consumes(MediaType.WILDCARD)
    public Response listRouteCalculators(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listRouteCalculators(region(headers))).build());
    }

    @POST
    @Path("/routes/v0/calculators/{CalculatorName}/calculate/route")
    public Response calculateRoute(
            @Context HttpHeaders headers, @PathParam("CalculatorName") String name, String body) {
        return run(() -> Response.ok(service.calculateRoute(region(headers), name, parse(body))).build());
    }

    @POST
    @Path("/routes/v0/calculators/{CalculatorName}/calculate/route-matrix")
    public Response calculateRouteMatrix(
            @Context HttpHeaders headers, @PathParam("CalculatorName") String name, String body) {
        return run(() -> Response.ok(
                service.calculateRouteMatrix(region(headers), name, parse(body))).build());
    }

    // ── Geofence collections ────────────────────────────────────────────────

    @POST
    @Path("/geofencing/v0/collections")
    public Response createGeofenceCollection(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(
                service.createGeofenceCollection(region(headers), parse(body))).build());
    }

    @GET
    @Path("/geofencing/v0/collections/{CollectionName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeGeofenceCollection(
            @Context HttpHeaders headers, @PathParam("CollectionName") String name) {
        return run(() -> Response.ok(service.describeGeofenceCollection(region(headers), name)).build());
    }

    @PATCH
    @Path("/geofencing/v0/collections/{CollectionName}")
    public Response updateGeofenceCollection(
            @Context HttpHeaders headers, @PathParam("CollectionName") String name, String body) {
        return run(() -> Response.ok(
                service.updateGeofenceCollection(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/geofencing/v0/collections/{CollectionName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGeofenceCollection(
            @Context HttpHeaders headers, @PathParam("CollectionName") String name) {
        return run(() -> {
            service.deleteGeofenceCollection(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/geofencing/v0/list-collections")
    @Consumes(MediaType.WILDCARD)
    public Response listGeofenceCollections(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listGeofenceCollections(region(headers))).build());
    }

    @PUT
    @Path("/geofencing/v0/collections/{CollectionName}/geofences/{GeofenceId}")
    public Response putGeofence(
            @Context HttpHeaders headers,
            @PathParam("CollectionName") String collectionName,
            @PathParam("GeofenceId") String geofenceId,
            String body) {
        return run(() -> Response.ok(
                service.putGeofence(region(headers), collectionName, geofenceId, parse(body))).build());
    }

    @GET
    @Path("/geofencing/v0/collections/{CollectionName}/geofences/{GeofenceId}")
    @Consumes(MediaType.WILDCARD)
    public Response getGeofence(
            @Context HttpHeaders headers,
            @PathParam("CollectionName") String collectionName,
            @PathParam("GeofenceId") String geofenceId) {
        return run(() -> Response.ok(
                service.getGeofence(region(headers), collectionName, geofenceId)).build());
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/list-geofences")
    @Consumes(MediaType.WILDCARD)
    public Response listGeofences(
            @Context HttpHeaders headers, @PathParam("CollectionName") String collectionName, String body) {
        return run(() -> Response.ok(service.listGeofences(region(headers), collectionName)).build());
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/put-geofences")
    public Response batchPutGeofence(
            @Context HttpHeaders headers, @PathParam("CollectionName") String collectionName, String body) {
        return run(() -> Response.ok(
                service.batchPutGeofence(region(headers), collectionName, parse(body))).build());
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/delete-geofences")
    public Response batchDeleteGeofence(
            @Context HttpHeaders headers, @PathParam("CollectionName") String collectionName, String body) {
        return run(() -> Response.ok(
                service.batchDeleteGeofence(region(headers), collectionName, parse(body))).build());
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/positions")
    public Response batchEvaluateGeofences(
            @Context HttpHeaders headers, @PathParam("CollectionName") String collectionName, String body) {
        return run(() -> Response.ok(
                service.batchEvaluateGeofences(region(headers), collectionName, parse(body))).build());
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/forecast-geofence-events")
    public Response forecastGeofenceEvents(
            @Context HttpHeaders headers, @PathParam("CollectionName") String collectionName, String body) {
        return run(() -> Response.ok(
                service.forecastGeofenceEvents(region(headers), collectionName, parse(body))).build());
    }

    // ── Trackers ────────────────────────────────────────────────────────────

    @POST
    @Path("/tracking/v0/trackers")
    public Response createTracker(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createTracker(region(headers), parse(body))).build());
    }

    @GET
    @Path("/tracking/v0/trackers/{TrackerName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeTracker(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name) {
        return run(() -> Response.ok(service.describeTracker(region(headers), name)).build());
    }

    @PATCH
    @Path("/tracking/v0/trackers/{TrackerName}")
    public Response updateTracker(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(service.updateTracker(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/tracking/v0/trackers/{TrackerName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTracker(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name) {
        return run(() -> {
            service.deleteTracker(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/tracking/v0/list-trackers")
    @Consumes(MediaType.WILDCARD)
    public Response listTrackers(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listTrackers(region(headers))).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/consumers")
    public Response associateTrackerConsumer(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(
                service.associateTrackerConsumer(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/tracking/v0/trackers/{TrackerName}/consumers/{ConsumerArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateTrackerConsumer(
            @Context HttpHeaders headers,
            @PathParam("TrackerName") String name,
            @PathParam("ConsumerArn") String consumerArn) {
        return run(() -> Response.ok(
                service.disassociateTrackerConsumer(region(headers), name, consumerArn)).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/list-consumers")
    @Consumes(MediaType.WILDCARD)
    public Response listTrackerConsumers(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(service.listTrackerConsumers(region(headers), name)).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/positions")
    public Response batchUpdateDevicePosition(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(
                service.batchUpdateDevicePosition(region(headers), name, parse(body))).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/get-positions")
    public Response batchGetDevicePosition(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(
                service.batchGetDevicePosition(region(headers), name, parse(body))).build());
    }

    @GET
    @Path("/tracking/v0/trackers/{TrackerName}/devices/{DeviceId}/positions/latest")
    @Consumes(MediaType.WILDCARD)
    public Response getDevicePosition(
            @Context HttpHeaders headers,
            @PathParam("TrackerName") String name,
            @PathParam("DeviceId") String deviceId) {
        return run(() -> Response.ok(
                service.getDevicePosition(region(headers), name, deviceId)).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/devices/{DeviceId}/list-positions")
    public Response getDevicePositionHistory(
            @Context HttpHeaders headers,
            @PathParam("TrackerName") String name,
            @PathParam("DeviceId") String deviceId,
            String body) {
        return run(() -> Response.ok(
                service.getDevicePositionHistory(region(headers), name, deviceId, parse(body))).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/list-positions")
    @Consumes(MediaType.WILDCARD)
    public Response listDevicePositions(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(service.listDevicePositions(region(headers), name)).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/delete-positions")
    public Response batchDeleteDevicePositionHistory(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(
                service.batchDeleteDevicePositionHistory(region(headers), name, parse(body))).build());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/positions/verify")
    public Response verifyDevicePosition(
            @Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return run(() -> Response.ok(
                service.verifyDevicePosition(region(headers), name, parse(body))).build());
    }

    // ── API keys ────────────────────────────────────────────────────────────

    @POST
    @Path("/metadata/v0/keys")
    public Response createKey(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.createKey(region(headers), parse(body))).build());
    }

    @GET
    @Path("/metadata/v0/keys/{KeyName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeKey(@Context HttpHeaders headers, @PathParam("KeyName") String name) {
        return run(() -> Response.ok(service.describeKey(region(headers), name)).build());
    }

    @PATCH
    @Path("/metadata/v0/keys/{KeyName}")
    public Response updateKey(
            @Context HttpHeaders headers, @PathParam("KeyName") String name, String body) {
        return run(() -> Response.ok(service.updateKey(region(headers), name, parse(body))).build());
    }

    @DELETE
    @Path("/metadata/v0/keys/{KeyName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteKey(
            @Context HttpHeaders headers,
            @PathParam("KeyName") String name,
            @QueryParam("forceDelete") Boolean forceDelete) {
        return run(() -> {
            service.deleteKey(region(headers), name);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/metadata/v0/list-keys")
    @Consumes(MediaType.WILDCARD)
    public Response listKeys(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listKeys(region(headers))).build());
    }

    @POST
    @Path("/metadata/v0/jobs")
    public Response startJob(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.startJob(region(headers), parse(body))).build());
    }

    @POST
    @Path("/metadata/v0/jobs/list-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listJobs(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.listJobs(region(headers))).build());
    }

    @GET
    @Path("/metadata/v0/jobs/{JobId}")
    @Consumes(MediaType.WILDCARD)
    public Response getJob(@Context HttpHeaders headers, @PathParam("JobId") String jobId) {
        return run(() -> Response.ok(service.getJob(region(headers), jobId)).build());
    }

    @POST
    @Path("/metadata/v0/jobs/cancel-job")
    public Response cancelJob(@Context HttpHeaders headers, String body) {
        return run(() -> Response.ok(service.cancelJob(region(headers), parse(body))).build());
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    private static Response binary(LocationService.BinaryAsset asset) {
        return Response.ok(asset.body())
                .type(asset.contentType())
                .header("Cache-Control", asset.cacheControl())
                .build();
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
