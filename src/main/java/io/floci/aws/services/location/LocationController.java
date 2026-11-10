package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.location.LocationService.Kind;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.util.Set;

/** Amazon Location Service's REST API (signing name {@code geo}). */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class LocationController {
    private static final Set<String> SPRITE_FILES = Set.of("sprites.png", "sprites@2x.png", "sprites.json",
            "sprites@2x.json");

    private final LocationService resources;
    private final LocationTrackingService tracking;
    private final LocationPlaces places;
    private final LocationRouting routing;
    private final LocationJobs jobs;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public LocationController(LocationService resources, LocationTrackingService tracking, LocationPlaces places,
                              LocationRouting routing, LocationJobs jobs, RegionResolver regions, ObjectMapper mapper) {
        this.resources = resources;
        this.tracking = tracking;
        this.places = places;
        this.routing = routing;
        this.jobs = jobs;
        this.regions = regions;
        this.mapper = mapper;
    }

    // ── Maps ───────────────────────────────────────────────────────────────

    @POST
    @Path("/maps/v0/maps")
    public Response createMap(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.MAP, check("CreateMap", body), region(headers)));
    }

    @GET
    @Path("/maps/v0/maps/{MapName}")
    public Response describeMap(@Context HttpHeaders headers, @PathParam("MapName") String name) {
        return ok(resources.describe(Kind.MAP, name, region(headers)));
    }

    @PATCH
    @Path("/maps/v0/maps/{MapName}")
    public Response updateMap(@Context HttpHeaders headers, @PathParam("MapName") String name, String body) {
        return ok(resources.update(Kind.MAP, name, check("UpdateMap", body, "MapName", name), region(headers)));
    }

    @DELETE
    @Path("/maps/v0/maps/{MapName}")
    public Response deleteMap(@Context HttpHeaders headers, @PathParam("MapName") String name) {
        resources.delete(Kind.MAP, name, region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/maps/v0/list-maps")
    public Response listMaps(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.MAP, check("ListMaps", body), region(headers)));
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/style-descriptor")
    public Response getMapStyleDescriptor(@Context HttpHeaders headers, @Context UriInfo uri,
                                          @PathParam("MapName") String name) {
        ObjectNode map = resources.require(Kind.MAP, name, region(headers));
        String style = map.path("Configuration").path("Style").asText();
        String base = base(uri) + "/maps/v0/maps/" + name;
        byte[] descriptor = LocationMapAssets.styleDescriptor(style, base + "/tiles/{z}/{x}/{y}",
                style.startsWith("Raster"), base + "/glyphs/{fontstack}/{range}", base + "/sprites/sprites", mapper);
        return blob(descriptor, "application/json");
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/glyphs/{FontStack}/{FontUnicodeRange}")
    public Response getMapGlyphs(@Context HttpHeaders headers, @PathParam("MapName") String name,
                                 @PathParam("FontStack") String fontStack,
                                 @PathParam("FontUnicodeRange") String range) {
        resources.require(Kind.MAP, name, region(headers));
        if (!range.matches("\\d+-\\d+(\\.pbf)?")) {
            throw LocationService.validation("FontUnicodeRange must be a range such as 0-255.");
        }
        return blob(LocationMapAssets.glyphs(fontStack, range.replace(".pbf", "")), "application/octet-stream");
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/sprites/{FileName}")
    public Response getMapSprites(@Context HttpHeaders headers, @PathParam("MapName") String name,
                                  @PathParam("FileName") String file) {
        resources.require(Kind.MAP, name, region(headers));
        if (!SPRITE_FILES.contains(file)) {
            throw LocationService.validation("FileName must be one of " + SPRITE_FILES + ".");
        }
        return file.endsWith(".json") ? blob(LocationMapAssets.spriteIndex(), "application/json")
                : blob(LocationMapAssets.png(file.contains("@2x") ? 2 : 1, file.contains("@2x") ? 2 : 1, 0),
                "image/png");
    }

    @GET
    @Path("/maps/v0/maps/{MapName}/tiles/{Z}/{X}/{Y}")
    public Response getMapTile(@Context HttpHeaders headers, @PathParam("MapName") String name,
                               @PathParam("Z") String z, @PathParam("X") String x, @PathParam("Y") String y) {
        ObjectNode map = resources.require(Kind.MAP, name, region(headers));
        checkTile(z, x, y);
        boolean raster = map.path("Configuration").path("Style").asText().startsWith("Raster");
        return raster ? blob(LocationMapAssets.png(256, 256, 0x6b8e4eff), "image/png")
                : blob(LocationMapAssets.vectorTile(), "application/vnd.mapbox-vector-tile");
    }

    static void checkTile(String z, String x, String y) {
        int zoom;
        long col;
        long row;
        try {
            zoom = Integer.parseInt(z);
            col = Long.parseLong(x);
            row = Long.parseLong(y);
        } catch (NumberFormatException e) {
            throw LocationService.validation("Z, X and Y must be integers.");
        }
        long size = 1L << Math.min(zoom, 30);
        if (zoom < 0 || zoom > 22 || col < 0 || row < 0 || col >= size || row >= size) {
            throw LocationService.validation("The tile " + z + "/" + x + "/" + y + " is outside the map.");
        }
    }

    // ── Place indexes ──────────────────────────────────────────────────────

    @POST
    @Path("/places/v0/indexes")
    public Response createPlaceIndex(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.PLACE_INDEX, check("CreatePlaceIndex", body), region(headers)));
    }

    @GET
    @Path("/places/v0/indexes/{IndexName}")
    public Response describePlaceIndex(@Context HttpHeaders headers, @PathParam("IndexName") String name) {
        return ok(resources.describe(Kind.PLACE_INDEX, name, region(headers)));
    }

    @PATCH
    @Path("/places/v0/indexes/{IndexName}")
    public Response updatePlaceIndex(@Context HttpHeaders headers, @PathParam("IndexName") String name, String body) {
        return ok(resources.update(Kind.PLACE_INDEX, name, check("UpdatePlaceIndex", body, "IndexName", name), region(headers)));
    }

    @DELETE
    @Path("/places/v0/indexes/{IndexName}")
    public Response deletePlaceIndex(@Context HttpHeaders headers, @PathParam("IndexName") String name) {
        resources.delete(Kind.PLACE_INDEX, name, region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/places/v0/list-indexes")
    public Response listPlaceIndexes(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.PLACE_INDEX, check("ListPlaceIndexes", body), region(headers)));
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/text")
    public Response searchPlaceIndexForText(@Context HttpHeaders headers, @PathParam("IndexName") String name,
                                            String body) {
        return ok(places.searchText(resources.require(Kind.PLACE_INDEX, name, region(headers)),
                check("SearchPlaceIndexForText", body, "IndexName", name)));
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/position")
    public Response searchPlaceIndexForPosition(@Context HttpHeaders headers, @PathParam("IndexName") String name,
                                                String body) {
        return ok(places.searchPosition(resources.require(Kind.PLACE_INDEX, name, region(headers)),
                check("SearchPlaceIndexForPosition", body, "IndexName", name)));
    }

    @POST
    @Path("/places/v0/indexes/{IndexName}/search/suggestions")
    public Response searchPlaceIndexForSuggestions(@Context HttpHeaders headers, @PathParam("IndexName") String name,
                                                   String body) {
        return ok(places.suggestions(resources.require(Kind.PLACE_INDEX, name, region(headers)),
                check("SearchPlaceIndexForSuggestions", body, "IndexName", name)));
    }

    @GET
    @Path("/places/v0/indexes/{IndexName}/places/{PlaceId}")
    public Response getPlace(@Context HttpHeaders headers, @PathParam("IndexName") String name,
                             @PathParam("PlaceId") String placeId) {
        return ok(places.getPlace(resources.require(Kind.PLACE_INDEX, name, region(headers)), placeId));
    }

    // ── Route calculators ──────────────────────────────────────────────────

    @POST
    @Path("/routes/v0/calculators")
    public Response createRouteCalculator(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.ROUTE_CALCULATOR, check("CreateRouteCalculator", body), region(headers)));
    }

    @GET
    @Path("/routes/v0/calculators/{CalculatorName}")
    public Response describeRouteCalculator(@Context HttpHeaders headers, @PathParam("CalculatorName") String name) {
        return ok(resources.describe(Kind.ROUTE_CALCULATOR, name, region(headers)));
    }

    @PATCH
    @Path("/routes/v0/calculators/{CalculatorName}")
    public Response updateRouteCalculator(@Context HttpHeaders headers, @PathParam("CalculatorName") String name,
                                          String body) {
        return ok(resources.update(Kind.ROUTE_CALCULATOR, name, check("UpdateRouteCalculator", body, "CalculatorName", name),
                region(headers)));
    }

    @DELETE
    @Path("/routes/v0/calculators/{CalculatorName}")
    public Response deleteRouteCalculator(@Context HttpHeaders headers, @PathParam("CalculatorName") String name) {
        resources.delete(Kind.ROUTE_CALCULATOR, name, region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/routes/v0/list-calculators")
    public Response listRouteCalculators(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.ROUTE_CALCULATOR, check("ListRouteCalculators", body), region(headers)));
    }

    @POST
    @Path("/routes/v0/calculators/{CalculatorName}/calculate/route")
    public Response calculateRoute(@Context HttpHeaders headers, @PathParam("CalculatorName") String name,
                                   String body) {
        ObjectNode calculator = resources.require(Kind.ROUTE_CALCULATOR, name, region(headers));
        return ok(routing.calculateRoute(check("CalculateRoute", body, "CalculatorName", name), calculator.path("DataSource").asText()));
    }

    @POST
    @Path("/routes/v0/calculators/{CalculatorName}/calculate/route-matrix")
    public Response calculateRouteMatrix(@Context HttpHeaders headers, @PathParam("CalculatorName") String name,
                                         String body) {
        ObjectNode calculator = resources.require(Kind.ROUTE_CALCULATOR, name, region(headers));
        return ok(routing.calculateMatrix(check("CalculateRouteMatrix", body, "CalculatorName", name), calculator.path("DataSource").asText()));
    }

    // ── Geofence collections and geofences ─────────────────────────────────

    @POST
    @Path("/geofencing/v0/collections")
    public Response createGeofenceCollection(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.GEOFENCE_COLLECTION, check("CreateGeofenceCollection", body),
                region(headers)));
    }

    @GET
    @Path("/geofencing/v0/collections/{CollectionName}")
    public Response describeGeofenceCollection(@Context HttpHeaders headers,
                                               @PathParam("CollectionName") String name) {
        String region = region(headers);
        ObjectNode collection = resources.describe(Kind.GEOFENCE_COLLECTION, name, region);
        collection.put("GeofenceCount", tracking.geofenceCount(name, region));
        return ok(collection);
    }

    @PATCH
    @Path("/geofencing/v0/collections/{CollectionName}")
    public Response updateGeofenceCollection(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                             String body) {
        return ok(resources.update(Kind.GEOFENCE_COLLECTION, name, check("UpdateGeofenceCollection", body, "CollectionName", name),
                region(headers)));
    }

    @DELETE
    @Path("/geofencing/v0/collections/{CollectionName}")
    public Response deleteGeofenceCollection(@Context HttpHeaders headers, @PathParam("CollectionName") String name) {
        String region = region(headers);
        resources.delete(Kind.GEOFENCE_COLLECTION, name, region);
        tracking.deleteCollectionData(name, region);
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/geofencing/v0/list-collections")
    public Response listGeofenceCollections(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.GEOFENCE_COLLECTION, check("ListGeofenceCollections", body), region(headers)));
    }

    @PUT
    @Path("/geofencing/v0/collections/{CollectionName}/geofences/{GeofenceId}")
    public Response putGeofence(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                @PathParam("GeofenceId") String id, String body) {
        return ok(tracking.putGeofence(name, id, check("PutGeofence", body, "CollectionName", name, "GeofenceId", id), region(headers)));
    }

    @GET
    @Path("/geofencing/v0/collections/{CollectionName}/geofences/{GeofenceId}")
    public Response getGeofence(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                @PathParam("GeofenceId") String id) {
        return ok(tracking.getGeofence(name, id, region(headers)));
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/list-geofences")
    public Response listGeofences(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                  String body) {
        return ok(tracking.listGeofences(name, check("ListGeofences", body, "CollectionName", name), region(headers)));
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/put-geofences")
    public Response batchPutGeofence(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                     String body) {
        return ok(tracking.batchPutGeofence(name, check("BatchPutGeofence", body, "CollectionName", name), region(headers)));
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/delete-geofences")
    public Response batchDeleteGeofence(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                        String body) {
        return ok(tracking.batchDeleteGeofence(name, check("BatchDeleteGeofence", body, "CollectionName", name), region(headers)));
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/positions")
    public Response batchEvaluateGeofences(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                           String body) {
        return ok(tracking.batchEvaluateGeofences(name, check("BatchEvaluateGeofences", body, "CollectionName", name), region(headers)));
    }

    @POST
    @Path("/geofencing/v0/collections/{CollectionName}/forecast-geofence-events")
    public Response forecastGeofenceEvents(@Context HttpHeaders headers, @PathParam("CollectionName") String name,
                                           String body) {
        return ok(tracking.forecast(name, check("ForecastGeofenceEvents", body, "CollectionName", name), region(headers)));
    }

    // ── Trackers ───────────────────────────────────────────────────────────

    @POST
    @Path("/tracking/v0/trackers")
    public Response createTracker(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.TRACKER, check("CreateTracker", body), region(headers)));
    }

    @GET
    @Path("/tracking/v0/trackers/{TrackerName}")
    public Response describeTracker(@Context HttpHeaders headers, @PathParam("TrackerName") String name) {
        return ok(resources.describe(Kind.TRACKER, name, region(headers)));
    }

    @PATCH
    @Path("/tracking/v0/trackers/{TrackerName}")
    public Response updateTracker(@Context HttpHeaders headers, @PathParam("TrackerName") String name, String body) {
        return ok(resources.update(Kind.TRACKER, name, check("UpdateTracker", body, "TrackerName", name), region(headers)));
    }

    @DELETE
    @Path("/tracking/v0/trackers/{TrackerName}")
    public Response deleteTracker(@Context HttpHeaders headers, @PathParam("TrackerName") String name) {
        String region = region(headers);
        resources.delete(Kind.TRACKER, name, region);
        tracking.deleteTrackerData(name, region);
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/tracking/v0/list-trackers")
    public Response listTrackers(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.TRACKER, check("ListTrackers", body), region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/consumers")
    public Response associateTrackerConsumer(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                             String body) {
        JsonNode request = check("AssociateTrackerConsumer", body, "TrackerName", name);
        resources.associateConsumer(name, request.path("ConsumerArn").asText(null), region(headers));
        return ok(mapper.createObjectNode());
    }

    @DELETE
    @Path("/tracking/v0/trackers/{TrackerName}/consumers/{ConsumerArn: .+}")
    public Response disassociateTrackerConsumer(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                                @PathParam("ConsumerArn") String consumerArn) {
        resources.disassociateConsumer(name, consumerArn, region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/list-consumers")
    public Response listTrackerConsumers(@Context HttpHeaders headers, @PathParam("TrackerName") String name) {
        return ok(resources.listConsumers(name, region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/positions")
    public Response batchUpdateDevicePosition(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                              String body) {
        return ok(tracking.batchUpdate(name, check("BatchUpdateDevicePosition", body, "TrackerName", name), region(headers)));
    }

    @GET
    @Path("/tracking/v0/trackers/{TrackerName}/devices/{DeviceId}/positions/latest")
    public Response getDevicePosition(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                      @PathParam("DeviceId") String deviceId) {
        return ok(tracking.latest(name, deviceId, region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/devices/{DeviceId}/list-positions")
    public Response getDevicePositionHistory(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                             @PathParam("DeviceId") String deviceId, String body) {
        return ok(tracking.history(name, deviceId, check("GetDevicePositionHistory", body, "TrackerName", name, "DeviceId", deviceId), region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/get-positions")
    public Response batchGetDevicePosition(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                           String body) {
        return ok(tracking.batchGet(name, check("BatchGetDevicePosition", body, "TrackerName", name), region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/list-positions")
    public Response listDevicePositions(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                        String body) {
        return ok(tracking.listPositions(name, check("ListDevicePositions", body, "TrackerName", name), region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/delete-positions")
    public Response batchDeleteDevicePositionHistory(@Context HttpHeaders headers,
                                                     @PathParam("TrackerName") String name, String body) {
        return ok(tracking.deleteHistory(name, check("BatchDeleteDevicePositionHistory", body, "TrackerName", name), region(headers)));
    }

    @POST
    @Path("/tracking/v0/trackers/{TrackerName}/positions/verify")
    public Response verifyDevicePosition(@Context HttpHeaders headers, @PathParam("TrackerName") String name,
                                         String body) {
        return ok(tracking.verify(name, check("VerifyDevicePosition", body, "TrackerName", name), region(headers)));
    }

    // ── API keys ───────────────────────────────────────────────────────────

    @POST
    @Path("/metadata/v0/keys")
    public Response createKey(@Context HttpHeaders headers, String body) {
        return ok(resources.create(Kind.KEY, check("CreateKey", body), region(headers)));
    }

    @GET
    @Path("/metadata/v0/keys/{KeyName}")
    public Response describeKey(@Context HttpHeaders headers, @PathParam("KeyName") String name) {
        return ok(resources.describe(Kind.KEY, name, region(headers)));
    }

    @PATCH
    @Path("/metadata/v0/keys/{KeyName}")
    public Response updateKey(@Context HttpHeaders headers, @PathParam("KeyName") String name, String body) {
        return ok(resources.update(Kind.KEY, name, check("UpdateKey", body, "KeyName", name), region(headers)));
    }

    @DELETE
    @Path("/metadata/v0/keys/{KeyName}")
    public Response deleteKey(@Context HttpHeaders headers, @PathParam("KeyName") String name) {
        resources.delete(Kind.KEY, name, region(headers));
        return ok(mapper.createObjectNode());
    }

    @POST
    @Path("/metadata/v0/list-keys")
    public Response listKeys(@Context HttpHeaders headers, String body) {
        return ok(resources.list(Kind.KEY, check("ListKeys", body), region(headers)));
    }

    // ── Jobs ───────────────────────────────────────────────────────────────

    @POST
    @Path("/metadata/v0/jobs")
    public Response startJob(@Context HttpHeaders headers, String body) {
        return ok(jobs.start(check("StartJob", body), region(headers)));
    }

    @GET
    @Path("/metadata/v0/jobs/{JobId}")
    public Response getJob(@Context HttpHeaders headers, @PathParam("JobId") String jobId) {
        return ok(jobs.get(jobId, region(headers)));
    }

    @POST
    @Path("/metadata/v0/jobs/list-jobs")
    public Response listJobs(@Context HttpHeaders headers, String body) {
        return ok(jobs.list(check("ListJobs", body), region(headers)));
    }

    @POST
    @Path("/metadata/v0/jobs/cancel-job")
    public Response cancelJob(@Context HttpHeaders headers, String body) {
        return ok(jobs.cancel(check("CancelJob", body).path("JobId").asText(null), region(headers)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String region(HttpHeaders headers) {
        return regions.resolveRegion(headers);
    }

    private JsonNode check(String operation, String body, String... pathMembers) {
        JsonNode request = parse(body);
        LocationRequestShapes.check("location", operation, request, pathMembers);
        return request;
    }

    JsonNode parse(String body) {
        try {
            JsonNode node = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw LocationService.validation("The request body must be a JSON object.");
            }
            return node;
        } catch (IOException e) {
            throw LocationService.validation("The request body must be a JSON object.");
        }
    }

    static Response ok(Object body) {
        return Response.ok(body).build();
    }

    static Response blob(byte[] bytes, String contentType) {
        return Response.ok(bytes, contentType).header("Cache-Control", "max-age=86400").build();
    }

    static String base(UriInfo uri) {
        String base = uri.getBaseUri().toString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
