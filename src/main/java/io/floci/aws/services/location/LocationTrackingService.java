package io.floci.aws.services.location;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.location.LocationService.Kind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Geofences and device tracking.
 * <p>
 * A geofence is a polygon, multipolygon or circle. Evaluating a device position against a
 * collection compares it with the device's last known state there and publishes an
 * {@code ENTER} or {@code EXIT} "Location Geofence Event" to EventBridge when it crossed a
 * geofence. A tracker evaluates every update against its linked collections, then stores it
 * subject to its position filtering ({@code TimeBased}: one per 30 seconds; {@code DistanceBased}:
 * only after 30 m of movement; {@code AccuracyBased}: only beyond the combined accuracy), keeps
 * 30 days of history, and with {@code EventBridgeEnabled} publishes a
 * "Location Device Position Event" for each stored update.
 */
@ApplicationScoped
public class LocationTrackingService implements Resettable {
    static final Duration HISTORY = Duration.ofDays(30);
    private static final Duration TIME_FILTER = Duration.ofSeconds(30);
    private static final double DISTANCE_FILTER_METERS = 30;

    private final StorageBackend<String, ObjectNode> geofences;
    private final StorageBackend<String, ObjectNode> devices;
    private final StorageBackend<String, ObjectNode> fenceStates;
    private final LocationService resources;
    private final Instance<EventBridgeService> eventBridge;
    private final ObjectMapper mapper;

    @Inject
    public LocationTrackingService(StorageFactory storageFactory, LocationService resources,
                                   Instance<EventBridgeService> eventBridge, ObjectMapper mapper) {
        this.geofences = storageFactory.create("location", "location-geofences.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.devices = storageFactory.create("location", "location-device-positions.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.fenceStates = storageFactory.create("location", "location-geofence-states.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.resources = resources;
        this.eventBridge = eventBridge;
        this.mapper = mapper;
    }

    // ── Geofences ──────────────────────────────────────────────────────────

    public synchronized ObjectNode putGeofence(String collection, String geofenceId, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        validId(geofenceId, "GeofenceId");
        String now = LocationService.now();
        ObjectNode existing = geofences.get(fenceKey(region, collection, geofenceId)).orElse(null);
        ObjectNode fence = mapper.createObjectNode();
        fence.put("GeofenceId", geofenceId);
        fence.set("Geometry", geometry(request.path("Geometry")));
        fence.put("Status", "ACTIVE");
        fence.put("CreateTime", existing == null ? now : existing.path("CreateTime").asText());
        fence.put("UpdateTime", now);
        if (request.path("GeofenceProperties").isObject()) {
            if (request.get("GeofenceProperties").size() > 3) {
                throw LocationService.validation("A geofence can have at most 3 properties.");
            }
            fence.set("GeofenceProperties", request.get("GeofenceProperties").deepCopy());
        }
        geofences.put(fenceKey(region, collection, geofenceId), fence);
        ObjectNode response = mapper.createObjectNode();
        response.put("GeofenceId", geofenceId);
        response.put("CreateTime", fence.path("CreateTime").asText());
        response.put("UpdateTime", now);
        return response;
    }

    public ObjectNode getGeofence(String collection, String geofenceId, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        return geofences.get(fenceKey(region, collection, geofenceId)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "The geofence " + geofenceId
                        + " was not found in collection " + collection + ".", 404));
    }

    public ObjectNode listGeofences(String collection, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw LocationService.validation("MaxResults must be between 1 and 100.");
        }
        List<ObjectNode> all = fencesOf(region, collection);
        int start = LocationService.offset(request.path("NextToken").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        all.subList(Math.min(start, end), end).forEach(fence -> entries.add(fence.deepCopy()));
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    public synchronized ObjectNode batchPutGeofence(String collection, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        JsonNode entries = request.path("Entries");
        requireBatch(entries, "Entries");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode successes = response.putArray("Successes");
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode entry : entries) {
            String id = entry.path("GeofenceId").asText(null);
            try {
                ObjectNode put = putGeofence(collection, id, entry, region);
                successes.add(put);
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("GeofenceId", id);
                error.set("Error", itemError(e));
            }
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteGeofence(String collection, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        JsonNode ids = request.path("GeofenceIds");
        requireBatch(ids, "GeofenceIds");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode id : ids) {
            String fenceKey = fenceKey(region, collection, id.asText());
            if (geofences.get(fenceKey).isPresent()) {
                geofences.delete(fenceKey);
            } else {
                ObjectNode error = errors.addObject();
                error.put("GeofenceId", id.asText());
                error.putObject("Error").put("Code", "ResourceNotFoundError")
                        .put("Message", "The geofence " + id.asText() + " was not found.");
            }
        }
        return response;
    }

    /** The number of geofences in a collection, for {@code DescribeGeofenceCollection}. */
    int geofenceCount(String collection, String region) {
        return fencesOf(region, collection).size();
    }

    void deleteCollectionData(String collection, String region) {
        String prefix = region + "::" + collection + "::";
        geofences.scan(k -> k.startsWith(prefix)).forEach(fence ->
                geofences.delete(prefix + fence.path("GeofenceId").asText()));
    }

    private List<ObjectNode> fencesOf(String region, String collection) {
        String prefix = region + "::" + collection + "::";
        return geofences.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(fence -> fence.path("GeofenceId").asText())).toList();
    }

    /** A checked copy of a geofence geometry: one of Polygon, MultiPolygon, Circle or Geobuf. */
    private ObjectNode geometry(JsonNode geometry) {
        int kinds = 0;
        for (String kind : List.of("Polygon", "MultiPolygon", "Circle", "Geobuf")) {
            if (geometry.has(kind)) {
                kinds++;
            }
        }
        if (kinds != 1) {
            throw LocationService.validation("Geometry needs exactly one of Polygon, MultiPolygon, Circle or Geobuf.");
        }
        if (geometry.has("Polygon")) {
            checkPolygon(geometry.get("Polygon"));
        } else if (geometry.has("MultiPolygon")) {
            for (JsonNode polygon : geometry.get("MultiPolygon")) {
                checkPolygon(polygon);
            }
        } else if (geometry.has("Circle")) {
            JsonNode circle = geometry.get("Circle");
            position(circle.path("Center"), "Circle.Center");
            if (circle.path("Radius").asDouble(0) <= 0) {
                throw LocationService.validation("Circle.Radius must be greater than zero meters.");
            }
        }
        return (ObjectNode) geometry.deepCopy();
    }

    private static void checkPolygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw LocationService.validation("A polygon needs at least one linear ring.");
        }
        for (JsonNode ring : rings) {
            if (!ring.isArray() || ring.size() < 4) {
                throw LocationService.validation("A linear ring needs at least four positions.");
            }
            if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                throw LocationService.validation("A linear ring must start and end at the same position.");
            }
            for (JsonNode point : ring) {
                position(point, "Polygon");
            }
        }
    }

    /** Whether the device at {@code point} is inside the geofence, and how far it is from its edge. */
    private record Containment(boolean inside, double edgeDistance) {}

    private Containment contain(ObjectNode fence, double[] point) {
        JsonNode geometry = fence.path("Geometry");
        if (geometry.has("Circle")) {
            double[] center = position(geometry.path("Circle").path("Center"), "Circle.Center");
            double radius = geometry.path("Circle").path("Radius").asDouble();
            double distance = LocationGeometry.distance(point, center);
            return new Containment(distance <= radius, Math.abs(radius - distance));
        }
        List<JsonNode> polygons = new ArrayList<>();
        if (geometry.has("Polygon")) {
            polygons.add(geometry.get("Polygon"));
        } else if (geometry.has("MultiPolygon")) {
            geometry.get("MultiPolygon").forEach(polygons::add);
        } else {
            return new Containment(false, Double.MAX_VALUE);
        }
        boolean inside = false;
        double edge = Double.MAX_VALUE;
        for (JsonNode polygon : polygons) {
            List<List<double[]>> rings = rings(polygon);
            inside |= LocationGeometry.inPolygon(point, rings);
            for (List<double[]> ring : rings) {
                edge = Math.min(edge, LocationGeometry.distanceToRing(point, ring));
            }
        }
        return new Containment(inside, edge);
    }

    private static List<List<double[]>> rings(JsonNode polygon) {
        List<List<double[]>> rings = new ArrayList<>();
        for (JsonNode ring : polygon) {
            List<double[]> points = new ArrayList<>();
            ring.forEach(p -> points.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()}));
            rings.add(points);
        }
        return rings;
    }

    // ── Evaluation and events ──────────────────────────────────────────────

    public synchronized ObjectNode batchEvaluateGeofences(String collection, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        JsonNode updates = request.path("DevicePositionUpdates");
        requireBatch(updates, "DevicePositionUpdates");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode update : updates) {
            try {
                evaluate(collection, update, region);
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("DeviceId", update.path("DeviceId").asText());
                error.put("SampleTime", update.path("SampleTime").asText());
                error.set("Error", itemError(e));
            }
        }
        return response;
    }

    /** Compares the device's position with its last state in the collection and publishes the crossings. */
    private void evaluate(String collection, JsonNode update, String region) {
        String deviceId = update.path("DeviceId").asText(null);
        validId(deviceId, "DeviceId");
        double[] point = position(update.path("Position"), "Position");
        String stateKey = region + "::" + collection + "::" + deviceId;
        ObjectNode state = fenceStates.get(stateKey).orElseGet(mapper::createObjectNode);
        ArrayNode inside = state.withArray("Inside");
        List<String> previously = new ArrayList<>();
        inside.forEach(id -> previously.add(id.asText()));
        List<String> now = new ArrayList<>();
        String collectionArn = resources.arn(Kind.GEOFENCE_COLLECTION, region, collection);
        for (ObjectNode fence : fencesOf(region, collection)) {
            String id = fence.path("GeofenceId").asText();
            if (contain(fence, point).inside()) {
                now.add(id);
                if (!previously.contains(id)) {
                    geofenceEvent("ENTER", collectionArn, fence, update, region);
                }
            } else if (previously.contains(id)) {
                geofenceEvent("EXIT", collectionArn, fence, update, region);
            }
        }
        inside.removeAll();
        now.forEach(inside::add);
        fenceStates.put(stateKey, state);
    }

    private void geofenceEvent(String type, String collectionArn, ObjectNode fence, JsonNode update, String region) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("EventType", type);
        detail.put("GeofenceId", fence.path("GeofenceId").asText());
        detail.put("DeviceId", update.path("DeviceId").asText());
        detail.put("SampleTime", update.path("SampleTime").asText());
        detail.set("Position", update.path("Position").deepCopy());
        if (update.has("Accuracy")) {
            detail.set("Accuracy", update.get("Accuracy").deepCopy());
        }
        if (fence.has("GeofenceProperties")) {
            detail.set("GeofenceProperties", fence.get("GeofenceProperties").deepCopy());
        }
        if (update.has("PositionProperties")) {
            detail.set("PositionProperties", update.get("PositionProperties").deepCopy());
        }
        publish("Location Geofence Event", detail, collectionArn + "/geofence/" + fence.path("GeofenceId").asText(),
                region);
    }

    private void publish(String detailType, ObjectNode detail, String resource, String region) {
        if (eventBridge != null && eventBridge.isResolvable()) {
            eventBridge.get().publishServiceEvent("aws.geo", detailType, detail.toString(), resource, region);
        }
    }

    public ObjectNode forecast(String collection, JsonNode request, String region) {
        resources.require(Kind.GEOFENCE_COLLECTION, collection, region);
        JsonNode state = request.path("DeviceState");
        double[] point = position(state.path("Position"), "DeviceState.Position");
        String speedUnit = request.path("SpeedUnit").asText("KilometersPerHour");
        String distanceUnit = request.path("DistanceUnit").asText("Kilometers");
        double speed = state.path("Speed").asDouble(0);
        double metersPerSecond = speed * ("MilesPerHour".equals(speedUnit) ? 1609.344 : 1000) / 3600;
        double horizonMinutes = request.path("TimeHorizonMinutes").asDouble(Double.MAX_VALUE);
        double reach = metersPerSecond * horizonMinutes * 60;
        int max = request.path("MaxResults").asInt(20);
        if (max < 1 || max > 20) {
            throw LocationService.validation("MaxResults must be between 1 and 20.");
        }
        List<ObjectNode> events = new ArrayList<>();
        for (ObjectNode fence : fencesOf(region, collection)) {
            Containment containment = contain(fence, point);
            String type;
            if (containment.inside()) {
                type = containment.edgeDistance() <= reach ? "EXIT" : "IDLE";
            } else if (containment.edgeDistance() <= reach) {
                type = "ENTER";
            } else {
                continue;
            }
            ObjectNode event = mapper.createObjectNode();
            event.put("EventId", UUID.randomUUID().toString());
            event.put("GeofenceId", fence.path("GeofenceId").asText());
            event.put("IsDeviceInGeofence", containment.inside());
            event.put("NearestDistance", toUnit(containment.edgeDistance(), distanceUnit));
            event.put("EventType", type);
            if (!"IDLE".equals(type) && metersPerSecond > 0) {
                event.put("ForecastedBreachTime", Instant.now()
                        .plusMillis((long) (containment.edgeDistance() / metersPerSecond * 1000)).toString());
            }
            if (fence.has("GeofenceProperties")) {
                event.set("GeofenceProperties", fence.get("GeofenceProperties").deepCopy());
            }
            events.add(event);
        }
        events.sort(Comparator.comparingDouble(e -> e.path("NearestDistance").asDouble()));
        int start = LocationService.offset(request.path("NextToken").asText(null));
        int end = Math.min(events.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("ForecastedEvents");
        events.subList(Math.min(start, end), end).forEach(list::add);
        if (end < events.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        response.put("DistanceUnit", distanceUnit);
        response.put("SpeedUnit", speedUnit);
        return response;
    }

    // ── Tracking ───────────────────────────────────────────────────────────

    public synchronized ObjectNode batchUpdate(String trackerName, JsonNode request, String region) {
        ObjectNode tracker = resources.require(Kind.TRACKER, trackerName, region);
        JsonNode updates = request.path("Updates");
        requireBatch(updates, "Updates");
        List<String> collections = resources.consumerCollections(tracker, region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode update : updates) {
            try {
                String deviceId = update.path("DeviceId").asText(null);
                validId(deviceId, "DeviceId");
                position(update.path("Position"), "Position");
                Instant sample = sampleTime(update);
                if (update.path("PositionProperties").size() > 3) {
                    throw LocationService.validation("A position can have at most 3 properties.");
                }
                for (String collection : collections) {
                    evaluate(collection, update, region);
                }
                record(tracker, trackerName, update, sample, region);
            } catch (AwsException e) {
                ObjectNode error = errors.addObject();
                error.put("DeviceId", update.path("DeviceId").asText());
                error.put("SampleTime", update.path("SampleTime").asText());
                error.set("Error", itemError(e));
            }
        }
        return response;
    }

    /** Stores the update unless the tracker's position filtering drops it. */
    private void record(ObjectNode tracker, String trackerName, JsonNode update, Instant sample, String region) {
        String deviceKey = deviceKey(region, trackerName, update.path("DeviceId").asText());
        ObjectNode device = devices.get(deviceKey).orElseGet(mapper::createObjectNode);
        ArrayNode history = device.withArray("History");
        ObjectNode latest = history.isEmpty() ? null : (ObjectNode) history.get(history.size() - 1);
        if (latest != null && filtered(tracker.path("PositionFiltering").asText("TimeBased"), latest, update, sample)) {
            return;
        }
        ObjectNode position = mapper.createObjectNode();
        position.put("DeviceId", update.path("DeviceId").asText());
        position.put("SampleTime", sample.toString());
        position.put("ReceivedTime", LocationService.now());
        position.set("Position", update.path("Position").deepCopy());
        if (update.has("Accuracy")) {
            position.set("Accuracy", update.get("Accuracy").deepCopy());
        }
        if (update.path("PositionProperties").isObject()) {
            position.set("PositionProperties", update.get("PositionProperties").deepCopy());
        }
        history.add(position);
        Instant cutoff = Instant.now().minus(HISTORY);
        while (!history.isEmpty() && Instant.parse(history.get(0).path("SampleTime").asText()).isBefore(cutoff)) {
            history.remove(0);
        }
        devices.put(deviceKey, device);
        if (tracker.path("EventBridgeEnabled").asBoolean(false)) {
            ObjectNode detail = position.deepCopy();
            detail.put("EventType", "UPDATE");
            detail.put("TrackerName", trackerName);
            publish("Location Device Position Event", detail,
                    tracker.path("TrackerArn").asText() + "/device/" + update.path("DeviceId").asText(), region);
        }
    }

    private static boolean filtered(String filtering, ObjectNode latest, JsonNode update, Instant sample) {
        double[] last = {latest.path("Position").get(0).asDouble(), latest.path("Position").get(1).asDouble()};
        double[] next = {update.path("Position").get(0).asDouble(), update.path("Position").get(1).asDouble()};
        double moved = LocationGeometry.distance(last, next);
        return switch (filtering) {
            case "DistanceBased" -> moved < DISTANCE_FILTER_METERS;
            case "AccuracyBased" -> moved < latest.path("Accuracy").path("Horizontal").asDouble(0)
                    + update.path("Accuracy").path("Horizontal").asDouble(0);
            default -> Duration.between(Instant.parse(latest.path("SampleTime").asText()), sample).abs()
                    .compareTo(TIME_FILTER) < 0;
        };
    }

    public ObjectNode latest(String trackerName, String deviceId, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        return latestOf(trackerName, deviceId, region).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "No position is stored for device " + deviceId + ".", 404));
    }

    private Optional<ObjectNode> latestOf(String trackerName, String deviceId, String region) {
        return devices.get(deviceKey(region, trackerName, deviceId))
                .map(device -> device.withArray("History"))
                .filter(history -> !history.isEmpty())
                .map(history -> (ObjectNode) history.get(history.size() - 1).deepCopy());
    }

    public ObjectNode batchGet(String trackerName, JsonNode request, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        JsonNode ids = request.path("DeviceIds");
        requireBatch(ids, "DeviceIds");
        ObjectNode response = mapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        ArrayNode positions = response.putArray("DevicePositions");
        for (JsonNode id : ids) {
            Optional<ObjectNode> latest = latestOf(trackerName, id.asText(), region);
            if (latest.isPresent()) {
                positions.add(latest.get());
            } else {
                ObjectNode error = errors.addObject();
                error.put("DeviceId", id.asText());
                error.putObject("Error").put("Code", "ResourceNotFoundError")
                        .put("Message", "No position is stored for device " + id.asText() + ".");
            }
        }
        return response;
    }

    public ObjectNode history(String trackerName, String deviceId, JsonNode request, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        Instant from = request.hasNonNull("StartTimeInclusive")
                ? Instant.parse(request.get("StartTimeInclusive").asText()) : Instant.MIN;
        Instant to = request.hasNonNull("EndTimeExclusive")
                ? Instant.parse(request.get("EndTimeExclusive").asText()) : Instant.MAX;
        if (!from.isBefore(to)) {
            throw LocationService.validation("StartTimeInclusive must be before EndTimeExclusive.");
        }
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw LocationService.validation("MaxResults must be between 1 and 100.");
        }
        List<JsonNode> matching = new ArrayList<>();
        devices.get(deviceKey(region, trackerName, deviceId)).ifPresent(device -> device.withArray("History")
                .forEach(position -> {
                    Instant sample = Instant.parse(position.path("SampleTime").asText());
                    if (!sample.isBefore(from) && sample.isBefore(to)) {
                        matching.add(position);
                    }
                }));
        int start = LocationService.offset(request.path("NextToken").asText(null));
        int end = Math.min(matching.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode list = response.putArray("DevicePositions");
        matching.subList(Math.min(start, end), end).forEach(p -> list.add(p.deepCopy()));
        if (end < matching.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    public ObjectNode listPositions(String trackerName, JsonNode request, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw LocationService.validation("MaxResults must be between 1 and 100.");
        }
        JsonNode filter = request.path("FilterGeometry").path("Polygon");
        String prefix = region + "::" + trackerName + "::";
        List<ObjectNode> latest = devices.scan(k -> k.startsWith(prefix)).stream()
                .map(device -> device.withArray("History"))
                .filter(history -> !history.isEmpty())
                .map(history -> (ObjectNode) history.get(history.size() - 1))
                .filter(p -> filter.isMissingNode() || LocationGeometry.inPolygon(
                        new double[]{p.path("Position").get(0).asDouble(), p.path("Position").get(1).asDouble()},
                        rings(filter)))
                .sorted(Comparator.comparing(p -> p.path("DeviceId").asText()))
                .toList();
        int start = LocationService.offset(request.path("NextToken").asText(null));
        int end = Math.min(latest.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (ObjectNode p : latest.subList(Math.min(start, end), end)) {
            ObjectNode entry = entries.addObject();
            for (String field : List.of("DeviceId", "SampleTime", "Position", "Accuracy", "PositionProperties")) {
                if (p.has(field)) {
                    entry.set(field, p.get(field).deepCopy());
                }
            }
        }
        if (end < latest.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    public synchronized ObjectNode deleteHistory(String trackerName, JsonNode request, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        JsonNode ids = request.path("DeviceIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > 100) {
            throw LocationService.validation("DeviceIds must hold 1 to 100 device ids.");
        }
        ids.forEach(id -> devices.delete(deviceKey(region, trackerName, id.asText())));
        ObjectNode response = mapper.createObjectNode();
        response.putArray("Errors");
        return response;
    }

    void deleteTrackerData(String trackerName, String region) {
        String prefix = region + "::" + trackerName + "::";
        devices.scan(k -> k.startsWith(prefix)).forEach(device -> {
            JsonNode history = device.path("History");
            if (!history.isEmpty()) {
                devices.delete(prefix + history.get(0).path("DeviceId").asText());
            }
        });
    }

    /**
     * {@code VerifyDevicePosition}: Floci has no network-location data, so the inferred position is
     * the reported one, with no deviation and no proxy detected.
     */
    public ObjectNode verify(String trackerName, JsonNode request, String region) {
        resources.require(Kind.TRACKER, trackerName, region);
        JsonNode state = request.path("DeviceState");
        validId(state.path("DeviceId").asText(null), "DeviceState.DeviceId");
        position(state.path("Position"), "DeviceState.Position");
        Instant sample = sampleTime(state);
        String ip = state.path("Ipv4Address").asText(null);
        if (ip != null && !ip.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
            throw LocationService.validation("Ipv4Address must be a dotted IPv4 address.");
        }
        ObjectNode response = mapper.createObjectNode();
        ObjectNode inferred = response.putObject("InferredState");
        inferred.set("Position", state.get("Position").deepCopy());
        if (state.has("Accuracy")) {
            inferred.set("Accuracy", state.get("Accuracy").deepCopy());
        }
        inferred.put("DeviationDistance", 0.0);
        inferred.put("ProxyDetected", false);
        response.put("DeviceId", state.path("DeviceId").asText());
        response.put("SampleTime", sample.toString());
        response.put("ReceivedTime", LocationService.now());
        response.put("DistanceUnit", request.path("DistanceUnit").asText("Kilometers"));
        return response;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode itemError(AwsException e) {
        String code = switch (e.getErrorCode()) {
            case "ResourceNotFoundException" -> "ResourceNotFoundError";
            case "ConflictException" -> "ConflictError";
            case "AccessDeniedException" -> "AccessDeniedError";
            default -> "ValidationError";
        };
        return mapper.createObjectNode().put("Code", code).put("Message", e.getMessage());
    }

    private static Instant sampleTime(JsonNode update) {
        String text = update.path("SampleTime").asText(null);
        if (text == null) {
            throw LocationService.validation("SampleTime is required.");
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            throw LocationService.validation("SampleTime must be an ISO 8601 timestamp.");
        }
    }

    static double[] position(JsonNode node, String member) {
        if (!node.isArray() || node.size() != 2 || !node.get(0).isNumber() || !node.get(1).isNumber()) {
            throw LocationService.validation(member + " must be a [longitude, latitude] pair.");
        }
        double[] position = {node.get(0).asDouble(), node.get(1).asDouble()};
        if (!LocationGeometry.validPosition(position)) {
            throw LocationService.validation(member + " is outside the valid longitude and latitude ranges.");
        }
        return position;
    }

    private static void validId(String id, String member) {
        if (id == null || !id.matches("[-._\\p{L}\\p{N}]+") || id.length() > 100) {
            throw LocationService.validation(member + " must be 1-100 letters, digits, hyphens, periods or underscores.");
        }
    }

    private static void requireBatch(JsonNode list, String member) {
        if (!list.isArray() || list.isEmpty() || list.size() > 10) {
            throw LocationService.validation(member + " must hold 1 to 10 entries.");
        }
    }

    static double toUnit(double meters, String unit) {
        return "Miles".equals(unit) ? meters / 1609.344 : meters / 1000;
    }

    private static String fenceKey(String region, String collection, String id) {
        return region + "::" + collection + "::" + id;
    }

    private static String deviceKey(String region, String tracker, String device) {
        return region + "::" + tracker + "::" + device;
    }

    @Override
    public void clear() {
        geofences.clear();
        devices.clear();
        fenceStates.clear();
    }
}
