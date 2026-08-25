package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Amazon Location Service (geo) restJson1 — trackers, geofence collections,
 * place indexes, route calculators, maps, API keys, and the data-plane
 * operations Alchemy bindings exercise.
 *
 * <p>Places and routes are canned (no live geocoding). Map assets are stub
 * PNG / MapLibre / MVT / glyph payloads, same approach as geo-maps.
 */
@ApplicationScoped
public class LocationService implements Resettable, TagHandler {

    static final String SERVICE = "geo";
    static final String CACHE_CONTROL = "max-age=86400";
    static final String CONTENT_TYPE_JSON = "application/json";
    static final String CONTENT_TYPE_PNG = "image/png";
    static final String CONTENT_TYPE_MVT = "application/vnd.mapbox-vector-tile";
    static final String CONTENT_TYPE_PBF = "application/octet-stream";

    private static final double EARTH_RADIUS_METERS = 6_371_000d;
    private static final double METERS_PER_SECOND = 13.4d;
    private static final int DEFAULT_MAX_RESULTS = 5;

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] MVT = new byte[] {0x1a, 0x00};
    private static final byte[] GLYPH_PBF = new byte[] {0x0a, 0x00, 0x12, 0x00};
    private static final byte[] STYLE_JSON =
            "{\"version\":8,\"name\":\"floci\",\"sources\":{},\"layers\":[]}".getBytes();
    private static final byte[] SPRITE_JSON =
            "{\"sprite\":{\"width\":1,\"height\":1,\"x\":0,\"y\":0,\"pixelRatio\":1}}".getBytes();

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final List<CatalogPlace> catalog;

    private final ConcurrentHashMap<String, NamedResource> trackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> collections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> indexes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> calculators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> maps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NamedResource> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Geofence>> geofences =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceSample>>> positions =
            new ConcurrentHashMap<>();

    @Inject
    public LocationService(ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.catalog = List.of(
                new CatalogPlace(
                        "AQAAAFlociSpaceNeedle",
                        "Space Needle",
                        "400 Broad Street, Seattle, WA, 98109, USA",
                        "400",
                        "Broad Street",
                        "Seattle",
                        "WA",
                        "US",
                        "98109",
                        -122.3493,
                        47.6205,
                        List.of("space needle", "seattle", "needle")),
                new CatalogPlace(
                        "AQAAAFlociPikePlaceCoffee",
                        "Starbucks Reserve Roastery",
                        "1912 Pike Place, Seattle, WA, 98101, USA",
                        "1912",
                        "Pike Place",
                        "Seattle",
                        "WA",
                        "US",
                        "98101",
                        -122.3424,
                        47.6094,
                        List.of("coffee", "starbucks", "pike", "cafe", "pike place")),
                new CatalogPlace(
                        "AQAAAFlociWhiteHouse",
                        "White House",
                        "1600 Pennsylvania Avenue NW, Washington, DC, 20500, USA",
                        "1600",
                        "Pennsylvania Avenue NW",
                        "Washington",
                        "DC",
                        "US",
                        "20500",
                        -77.036547,
                        38.897676,
                        List.of("pennsylvania", "white house", "washington", "1600")));
    }

    @Override
    public void clear() {
        trackers.clear();
        collections.clear();
        indexes.clear();
        calculators.clear();
        maps.clear();
        keys.clear();
        jobs.clear();
        geofences.clear();
        positions.clear();
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireByArn(region, arn).tags);
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        NamedResource resource = requireByArn(region, arn);
        if (tags != null) {
            resource.tags.putAll(tags);
        }
        resource.updateTime = now();
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        NamedResource resource = requireByArn(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(resource.tags::remove);
        }
        resource.updateTime = now();
    }

    // ── Trackers ────────────────────────────────────────────────────────────

    public synchronized ObjectNode createTracker(String region, JsonNode request) {
        String name = requireText(request, "TrackerName");
        if (trackers.containsKey(key(region, name))) {
            throw conflict(name);
        }
        NamedResource resource = named(region, name, "tracker", request);
        resource.positionFiltering = textOr(request, "PositionFiltering", "TimeBased");
        resource.eventBridgeEnabled = request.path("EventBridgeEnabled").asBoolean(false);
        resource.kmsKeyEnableGeospatialQueries =
                request.path("KmsKeyEnableGeospatialQueries").asBoolean(false);
        trackers.put(key(region, name), resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TrackerName", resource.name);
        response.put("TrackerArn", resource.arn);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describeTracker(String region, String name) {
        return trackerJson(requireTracker(region, name), true);
    }

    public synchronized ObjectNode updateTracker(String region, String name, JsonNode request) {
        NamedResource resource = requireTracker(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        if (request.has("PositionFiltering")) {
            resource.positionFiltering = textOr(request, "PositionFiltering", resource.positionFiltering);
        }
        if (request.has("EventBridgeEnabled")) {
            resource.eventBridgeEnabled = request.path("EventBridgeEnabled").asBoolean(false);
        }
        if (request.has("KmsKeyEnableGeospatialQueries")) {
            resource.kmsKeyEnableGeospatialQueries =
                    request.path("KmsKeyEnableGeospatialQueries").asBoolean(false);
        }
        resource.updateTime = now();
        return updateAck(resource.name, resource.arn, resource.updateTime, "TrackerName", "TrackerArn");
    }

    public synchronized void deleteTracker(String region, String name) {
        if (trackers.remove(key(region, name)) == null) {
            throw notFound(name);
        }
        positions.remove(key(region, name));
    }

    public ObjectNode listTrackers(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(trackers, region)) {
            entries.add(trackerJson(resource, false));
        }
        return response;
    }

    public synchronized ObjectNode associateTrackerConsumer(String region, String trackerName, JsonNode request) {
        NamedResource tracker = requireTracker(region, trackerName);
        String consumerArn = requireText(request, "ConsumerArn");
        tracker.consumers.add(consumerArn);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode disassociateTrackerConsumer(String region, String trackerName, String consumerArn) {
        NamedResource tracker = requireTracker(region, trackerName);
        tracker.consumers.remove(decode(consumerArn));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTrackerConsumers(String region, String trackerName) {
        NamedResource tracker = requireTracker(region, trackerName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arns = response.putArray("ConsumerArns");
        for (String arn : tracker.consumers) {
            arns.add(arn);
        }
        return response;
    }

    public synchronized ObjectNode batchUpdateDevicePosition(String region, String trackerName, JsonNode request) {
        requireTracker(region, trackerName);
        ArrayNode updates = array(request, "Updates");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        String received = now();
        ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceSample>> devices =
                positions.computeIfAbsent(key(region, trackerName), ignored -> new ConcurrentHashMap<>());
        if (updates != null) {
            for (JsonNode update : updates) {
                String deviceId = textOrNull(update, "DeviceId");
                JsonNode position = update.get("Position");
                if (deviceId == null || !isPosition(position)) {
                    ObjectNode error = errors.addObject();
                    error.put("DeviceId", deviceId == null ? "" : deviceId);
                    error.put("SampleTime", textOr(update, "SampleTime", received));
                    ObjectNode item = error.putObject("Error");
                    item.put("Code", "ValidationException");
                    item.put("Message", "DeviceId and Position are required.");
                    continue;
                }
                DeviceSample sample = new DeviceSample(
                        deviceId,
                        textOr(update, "SampleTime", received),
                        received,
                        position(position),
                        update.get("Accuracy"),
                        update.get("PositionProperties"));
                devices.computeIfAbsent(deviceId, ignored -> new CopyOnWriteArrayList<>()).add(sample);
            }
        }
        return response;
    }

    public ObjectNode getDevicePosition(String region, String trackerName, String deviceId) {
        requireTracker(region, trackerName);
        DeviceSample sample = latest(region, trackerName, deviceId)
                .orElseThrow(() -> notFound(deviceId));
        return devicePositionJson(sample, false);
    }

    public ObjectNode batchGetDevicePosition(String region, String trackerName, JsonNode request) {
        requireTracker(region, trackerName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode found = response.putArray("DevicePositions");
        ArrayNode errors = response.putArray("Errors");
        JsonNode ids = request.get("DeviceIds");
        if (ids != null && ids.isArray()) {
            for (JsonNode idNode : ids) {
                String deviceId = idNode.asText();
                Optional<DeviceSample> sample = latest(region, trackerName, deviceId);
                if (sample.isPresent()) {
                    found.add(devicePositionJson(sample.get(), false));
                } else {
                    ObjectNode error = errors.addObject();
                    error.put("DeviceId", deviceId);
                    ObjectNode item = error.putObject("Error");
                    item.put("Code", "ResourceNotFoundException");
                    item.put("Message", "Resource not found.");
                }
            }
        }
        return response;
    }

    public ObjectNode getDevicePositionHistory(String region, String trackerName, String deviceId, JsonNode request) {
        requireTracker(region, trackerName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("DevicePositions");
        List<DeviceSample> samples = samples(region, trackerName, deviceId);
        for (DeviceSample sample : samples) {
            list.add(devicePositionJson(sample, false));
        }
        return response;
    }

    public ObjectNode listDevicePositions(String region, String trackerName) {
        requireTracker(region, trackerName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceSample>> devices =
                positions.get(key(region, trackerName));
        if (devices != null) {
            for (Map.Entry<String, CopyOnWriteArrayList<DeviceSample>> entry : devices.entrySet()) {
                DeviceSample latest = latestOf(entry.getValue());
                if (latest != null) {
                    entries.add(devicePositionJson(latest, true));
                }
            }
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteDevicePositionHistory(
            String region, String trackerName, JsonNode request) {
        requireTracker(region, trackerName);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Errors");
        JsonNode ids = request.get("DeviceIds");
        ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceSample>> devices =
                positions.get(key(region, trackerName));
        if (devices != null && ids != null && ids.isArray()) {
            for (JsonNode idNode : ids) {
                devices.remove(idNode.asText());
            }
        }
        return response;
    }

    public ObjectNode verifyDevicePosition(String region, String trackerName, JsonNode request) {
        requireTracker(region, trackerName);
        JsonNode state = request.get("DeviceState");
        if (state == null || !state.isObject()) {
            throw validation("DeviceState is required.", "DeviceState");
        }
        String deviceId = requireText(state, "DeviceId");
        JsonNode positionNode = state.get("Position");
        if (!isPosition(positionNode)) {
            throw validation("Position is required.", "DeviceState.Position");
        }
        double[] position = position(positionNode);
        String sampleTime = textOr(state, "SampleTime", now());
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode inferred = response.putObject("InferredState");
        ArrayNode inferredPosition = inferred.putArray("Position");
        inferredPosition.add(position[0]);
        inferredPosition.add(position[1]);
        inferred.put("DeviationDistance", 0);
        inferred.put("ProxyDetected", false);
        if (state.has("Accuracy")) {
            inferred.set("Accuracy", state.get("Accuracy"));
        }
        response.put("DeviceId", deviceId);
        response.put("SampleTime", sampleTime);
        response.put("ReceivedTime", now());
        response.put("DistanceUnit", textOr(request, "DistanceUnit", "Kilometers"));
        return response;
    }

    // ── Geofence collections ────────────────────────────────────────────────

    public synchronized ObjectNode createGeofenceCollection(String region, JsonNode request) {
        String name = requireText(request, "CollectionName");
        if (collections.containsKey(key(region, name))) {
            throw conflict(name);
        }
        NamedResource resource = named(region, name, "geofence-collection", request);
        collections.put(key(region, name), resource);
        geofences.put(key(region, name), new ConcurrentHashMap<>());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CollectionName", resource.name);
        response.put("CollectionArn", resource.arn);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describeGeofenceCollection(String region, String name) {
        NamedResource resource = requireCollection(region, name);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("CollectionName", resource.name);
        json.put("CollectionArn", resource.arn);
        putOptional(json, "Description", resource.description);
        putOptional(json, "KmsKeyId", resource.kmsKeyId);
        json.put("CreateTime", resource.createTime);
        json.put("UpdateTime", resource.updateTime);
        json.set("Tags", tags(resource));
        ConcurrentHashMap<String, Geofence> fences = geofences.get(key(region, name));
        json.put("GeofenceCount", fences == null ? 0 : fences.size());
        return json;
    }

    public synchronized ObjectNode updateGeofenceCollection(String region, String name, JsonNode request) {
        NamedResource resource = requireCollection(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        resource.updateTime = now();
        return updateAck(resource.name, resource.arn, resource.updateTime, "CollectionName", "CollectionArn");
    }

    public synchronized void deleteGeofenceCollection(String region, String name) {
        if (collections.remove(key(region, name)) == null) {
            throw notFound(name);
        }
        geofences.remove(key(region, name));
    }

    public ObjectNode listGeofenceCollections(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(collections, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("CollectionName", resource.name);
            putOptional(entry, "Description", resource.description);
            entry.put("CreateTime", resource.createTime);
            entry.put("UpdateTime", resource.updateTime);
        }
        return response;
    }

    public synchronized ObjectNode putGeofence(
            String region, String collectionName, String geofenceId, JsonNode request) {
        requireCollection(region, collectionName);
        JsonNode geometry = request.get("Geometry");
        if (geometry == null || !geometry.isObject()) {
            throw validation("Geometry is required.", "Geometry");
        }
        ConcurrentHashMap<String, Geofence> fences =
                geofences.computeIfAbsent(key(region, collectionName), ignored -> new ConcurrentHashMap<>());
        String now = now();
        Geofence existing = fences.get(geofenceId);
        Geofence fence = new Geofence(
                geofenceId,
                geometry.deepCopy(),
                "ACTIVE",
                existing == null ? now : existing.createTime,
                now,
                request.get("GeofenceProperties"));
        fences.put(geofenceId, fence);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GeofenceId", fence.geofenceId);
        response.put("CreateTime", fence.createTime);
        response.put("UpdateTime", fence.updateTime);
        return response;
    }

    public ObjectNode getGeofence(String region, String collectionName, String geofenceId) {
        Geofence fence = requireGeofence(region, collectionName, geofenceId);
        return geofenceJson(fence);
    }

    public ObjectNode listGeofences(String region, String collectionName) {
        requireCollection(region, collectionName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        ConcurrentHashMap<String, Geofence> fences = geofences.get(key(region, collectionName));
        if (fences != null) {
            for (Geofence fence : fences.values()) {
                entries.add(geofenceJson(fence));
            }
        }
        return response;
    }

    public synchronized ObjectNode batchPutGeofence(String region, String collectionName, JsonNode request) {
        requireCollection(region, collectionName);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode successes = response.putArray("Successes");
        ArrayNode errors = response.putArray("Errors");
        JsonNode entries = request.get("Entries");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String geofenceId = textOrNull(entry, "GeofenceId");
                JsonNode geometry = entry.get("Geometry");
                if (geofenceId == null || geometry == null || !geometry.isObject()) {
                    ObjectNode error = errors.addObject();
                    error.put("GeofenceId", geofenceId == null ? "" : geofenceId);
                    ObjectNode item = error.putObject("Error");
                    item.put("Code", "ValidationException");
                    item.put("Message", "GeofenceId and Geometry are required.");
                    continue;
                }
                ObjectNode put = putGeofence(region, collectionName, geofenceId, entry);
                successes.add(put);
            }
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteGeofence(String region, String collectionName, JsonNode request) {
        requireCollection(region, collectionName);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Errors");
        JsonNode ids = request.get("GeofenceIds");
        ConcurrentHashMap<String, Geofence> fences = geofences.get(key(region, collectionName));
        if (fences != null && ids != null && ids.isArray()) {
            for (JsonNode idNode : ids) {
                fences.remove(idNode.asText());
            }
        }
        return response;
    }

    public ObjectNode batchEvaluateGeofences(String region, String collectionName, JsonNode request) {
        requireCollection(region, collectionName);
        if (request.get("DevicePositionUpdates") == null || !request.get("DevicePositionUpdates").isArray()) {
            throw validation("DevicePositionUpdates is required.", "DevicePositionUpdates");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Errors");
        return response;
    }

    public ObjectNode forecastGeofenceEvents(String region, String collectionName, JsonNode request) {
        requireCollection(region, collectionName);
        JsonNode state = request.get("DeviceState");
        if (state == null || !isPosition(state.get("Position"))) {
            throw validation("DeviceState.Position is required.", "DeviceState");
        }
        double[] position = position(state.get("Position"));
        String distanceUnit = textOr(request, "DistanceUnit", "Kilometers");
        String speedUnit = textOr(request, "SpeedUnit", "KilometersPerHour");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode events = response.putArray("ForecastedEvents");
        ConcurrentHashMap<String, Geofence> fences = geofences.get(key(region, collectionName));
        if (fences != null) {
            for (Geofence fence : fences.values()) {
                Circle circle = circleOf(fence.geometry);
                if (circle == null) {
                    continue;
                }
                double meters = distanceMeters(position[0], position[1], circle.lon, circle.lat);
                boolean inside = meters <= circle.radiusMeters;
                ObjectNode event = events.addObject();
                event.put("EventId", UUID.randomUUID().toString());
                event.put("GeofenceId", fence.geofenceId);
                event.put("IsDeviceInGeofence", inside);
                event.put("NearestDistance", convertDistance(Math.max(0, meters - circle.radiusMeters), distanceUnit));
                event.put("EventType", inside ? "ENTER" : "IDLE");
            }
        }
        response.put("DistanceUnit", distanceUnit);
        response.put("SpeedUnit", speedUnit);
        return response;
    }

    // ── Place indexes ───────────────────────────────────────────────────────

    public synchronized ObjectNode createPlaceIndex(String region, JsonNode request) {
        String name = requireText(request, "IndexName");
        if (indexes.containsKey(key(region, name))) {
            throw conflict(name);
        }
        NamedResource resource = named(region, name, "place-index", request);
        resource.dataSource = requireText(request, "DataSource");
        JsonNode config = request.get("DataSourceConfiguration");
        resource.intendedUse = config != null
                ? textOr(config, "IntendedUse", "SingleUse")
                : "SingleUse";
        indexes.put(key(region, name), resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("IndexName", resource.name);
        response.put("IndexArn", resource.arn);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describePlaceIndex(String region, String name) {
        NamedResource resource = requireIndex(region, name);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("IndexName", resource.name);
        json.put("IndexArn", resource.arn);
        putOptional(json, "Description", resource.description);
        json.put("CreateTime", resource.createTime);
        json.put("UpdateTime", resource.updateTime);
        json.put("DataSource", resource.dataSource);
        ObjectNode config = json.putObject("DataSourceConfiguration");
        config.put("IntendedUse", resource.intendedUse == null ? "SingleUse" : resource.intendedUse);
        json.set("Tags", tags(resource));
        return json;
    }

    public synchronized ObjectNode updatePlaceIndex(String region, String name, JsonNode request) {
        NamedResource resource = requireIndex(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        JsonNode config = request.get("DataSourceConfiguration");
        if (config != null && config.has("IntendedUse")) {
            resource.intendedUse = textOr(config, "IntendedUse", resource.intendedUse);
        }
        resource.updateTime = now();
        return updateAck(resource.name, resource.arn, resource.updateTime, "IndexName", "IndexArn");
    }

    public synchronized void deletePlaceIndex(String region, String name) {
        if (indexes.remove(key(region, name)) == null) {
            throw notFound(name);
        }
    }

    public ObjectNode listPlaceIndexes(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(indexes, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("IndexName", resource.name);
            putOptional(entry, "Description", resource.description);
            entry.put("DataSource", resource.dataSource);
            entry.put("CreateTime", resource.createTime);
            entry.put("UpdateTime", resource.updateTime);
        }
        return response;
    }

    public ObjectNode searchPlaceIndexForText(String region, String indexName, JsonNode request) {
        NamedResource index = requireIndex(region, indexName);
        String text = requireText(request, "Text");
        int maxResults = maxResults(request);
        List<CatalogPlace> matches = matchByQuery(text, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("Summary");
        summary.put("Text", text);
        summary.put("DataSource", index.dataSource);
        if (request.has("MaxResults")) {
            summary.put("MaxResults", maxResults);
        }
        if (isPosition(request.get("BiasPosition"))) {
            summary.set("BiasPosition", request.get("BiasPosition"));
        }
        ArrayNode results = response.putArray("Results");
        for (CatalogPlace place : matches) {
            ObjectNode result = results.addObject();
            result.set("Place", v1Place(place));
            result.put("Relevance", 1);
            result.put("PlaceId", place.placeId);
        }
        return response;
    }

    public ObjectNode searchPlaceIndexForPosition(String region, String indexName, JsonNode request) {
        NamedResource index = requireIndex(region, indexName);
        JsonNode positionNode = request.get("Position");
        if (!isPosition(positionNode)) {
            throw validation("Position is required.", "Position");
        }
        double[] position = position(positionNode);
        int maxResults = maxResults(request);
        List<CatalogPlace> matches = matchByDistance(position[0], position[1], maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("Summary");
        summary.set("Position", positionNode);
        summary.put("DataSource", index.dataSource);
        if (request.has("MaxResults")) {
            summary.put("MaxResults", maxResults);
        }
        ArrayNode results = response.putArray("Results");
        for (CatalogPlace place : matches) {
            ObjectNode result = results.addObject();
            result.set("Place", v1Place(place));
            result.put("Distance", distanceMeters(position[0], position[1], place.longitude, place.latitude));
            result.put("PlaceId", place.placeId);
        }
        return response;
    }

    public ObjectNode searchPlaceIndexForSuggestions(String region, String indexName, JsonNode request) {
        NamedResource index = requireIndex(region, indexName);
        String text = requireText(request, "Text");
        int maxResults = maxResults(request);
        List<CatalogPlace> matches = matchByQuery(text, maxResults);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("Summary");
        summary.put("Text", text);
        summary.put("DataSource", index.dataSource);
        ArrayNode results = response.putArray("Results");
        for (CatalogPlace place : matches) {
            ObjectNode result = results.addObject();
            result.put("Text", place.title);
            result.put("PlaceId", place.placeId);
        }
        return response;
    }

    public ObjectNode getPlace(String region, String indexName, String placeId) {
        requireIndex(region, indexName);
        CatalogPlace place = findById(decode(placeId))
                .orElseThrow(() -> notFound(placeId));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Place", v1Place(place));
        return response;
    }

    // ── Route calculators ───────────────────────────────────────────────────

    public synchronized ObjectNode createRouteCalculator(String region, JsonNode request) {
        String name = requireText(request, "CalculatorName");
        if (calculators.containsKey(key(region, name))) {
            throw conflict(name);
        }
        NamedResource resource = named(region, name, "route-calculator", request);
        resource.dataSource = requireText(request, "DataSource");
        calculators.put(key(region, name), resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CalculatorName", resource.name);
        response.put("CalculatorArn", resource.arn);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describeRouteCalculator(String region, String name) {
        NamedResource resource = requireCalculator(region, name);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("CalculatorName", resource.name);
        json.put("CalculatorArn", resource.arn);
        putOptional(json, "Description", resource.description);
        json.put("CreateTime", resource.createTime);
        json.put("UpdateTime", resource.updateTime);
        json.put("DataSource", resource.dataSource);
        json.set("Tags", tags(resource));
        return json;
    }

    public synchronized ObjectNode updateRouteCalculator(String region, String name, JsonNode request) {
        NamedResource resource = requireCalculator(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        resource.updateTime = now();
        return updateAck(resource.name, resource.arn, resource.updateTime, "CalculatorName", "CalculatorArn");
    }

    public synchronized void deleteRouteCalculator(String region, String name) {
        if (calculators.remove(key(region, name)) == null) {
            throw notFound(name);
        }
    }

    public ObjectNode listRouteCalculators(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(calculators, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("CalculatorName", resource.name);
            putOptional(entry, "Description", resource.description);
            entry.put("DataSource", resource.dataSource);
            entry.put("CreateTime", resource.createTime);
            entry.put("UpdateTime", resource.updateTime);
        }
        return response;
    }

    public ObjectNode calculateRoute(String region, String calculatorName, JsonNode request) {
        NamedResource calculator = requireCalculator(region, calculatorName);
        if (!isPosition(request.get("DeparturePosition")) || !isPosition(request.get("DestinationPosition"))) {
            throw validation("DeparturePosition and DestinationPosition are required.", "DeparturePosition");
        }
        double[] origin = position(request.get("DeparturePosition"));
        double[] destination = position(request.get("DestinationPosition"));
        String distanceUnit = textOr(request, "DistanceUnit", "Kilometers");
        double meters = distanceMeters(origin[0], origin[1], destination[0], destination[1]);
        double distance = convertDistance(meters, distanceUnit);
        double duration = Math.max(1, meters / METERS_PER_SECOND);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode legs = response.putArray("Legs");
        ObjectNode leg = legs.addObject();
        setPosition(leg.putArray("StartPosition"), origin);
        setPosition(leg.putArray("EndPosition"), destination);
        leg.put("Distance", distance);
        leg.put("DurationSeconds", duration);
        ArrayNode steps = leg.putArray("Steps");
        ObjectNode step = steps.addObject();
        setPosition(step.putArray("StartPosition"), origin);
        setPosition(step.putArray("EndPosition"), destination);
        step.put("Distance", distance);
        step.put("DurationSeconds", duration);
        if (request.path("IncludeLegGeometry").asBoolean(false)) {
            ObjectNode geometry = leg.putObject("Geometry");
            ArrayNode line = geometry.putArray("LineString");
            setPosition(line.addArray(), origin);
            setPosition(line.addArray(), destination);
        }
        ObjectNode summary = response.putObject("Summary");
        ArrayNode bbox = summary.putArray("RouteBBox");
        bbox.add(Math.min(origin[0], destination[0]));
        bbox.add(Math.min(origin[1], destination[1]));
        bbox.add(Math.max(origin[0], destination[0]));
        bbox.add(Math.max(origin[1], destination[1]));
        summary.put("DataSource", calculator.dataSource);
        summary.put("Distance", distance);
        summary.put("DurationSeconds", duration);
        summary.put("DistanceUnit", distanceUnit);
        return response;
    }

    public ObjectNode calculateRouteMatrix(String region, String calculatorName, JsonNode request) {
        NamedResource calculator = requireCalculator(region, calculatorName);
        JsonNode departures = request.get("DeparturePositions");
        JsonNode destinations = request.get("DestinationPositions");
        if (departures == null || !departures.isArray() || destinations == null || !destinations.isArray()) {
            throw validation("DeparturePositions and DestinationPositions are required.", "DeparturePositions");
        }
        String distanceUnit = textOr(request, "DistanceUnit", "Kilometers");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode matrix = response.putArray("RouteMatrix");
        int routes = 0;
        for (JsonNode departure : departures) {
            ArrayNode row = matrix.addArray();
            double[] from = position(departure);
            for (JsonNode destination : destinations) {
                double[] to = position(destination);
                double meters = distanceMeters(from[0], from[1], to[0], to[1]);
                ObjectNode entry = row.addObject();
                entry.put("Distance", convertDistance(meters, distanceUnit));
                entry.put("DurationSeconds", Math.max(1, meters / METERS_PER_SECOND));
                routes++;
            }
        }
        ObjectNode summary = response.putObject("Summary");
        summary.put("DataSource", calculator.dataSource);
        summary.put("RouteCount", routes);
        summary.put("ErrorCount", 0);
        summary.put("DistanceUnit", distanceUnit);
        return response;
    }

    // ── Maps ────────────────────────────────────────────────────────────────

    public synchronized ObjectNode createMap(String region, JsonNode request) {
        String name = requireText(request, "MapName");
        if (maps.containsKey(key(region, name))) {
            throw conflict(name);
        }
        JsonNode configuration = request.get("Configuration");
        if (configuration == null || !configuration.isObject()) {
            throw validation("Configuration is required.", "Configuration");
        }
        String style = requireText(configuration, "Style");
        NamedResource resource = named(region, name, "map", request);
        resource.style = style;
        resource.politicalView = textOrNull(configuration, "PoliticalView");
        resource.dataSource = dataSourceFromStyle(style);
        resource.configuration = configuration.deepCopy();
        maps.put(key(region, name), resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MapName", resource.name);
        response.put("MapArn", resource.arn);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describeMap(String region, String name) {
        NamedResource resource = requireMap(region, name);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("MapName", resource.name);
        json.put("MapArn", resource.arn);
        json.put("DataSource", resource.dataSource);
        putOptional(json, "Description", resource.description);
        json.put("CreateTime", resource.createTime);
        json.put("UpdateTime", resource.updateTime);
        ObjectNode configuration = json.putObject("Configuration");
        configuration.put("Style", resource.style);
        putOptional(configuration, "PoliticalView", resource.politicalView);
        json.set("Tags", tags(resource));
        return json;
    }

    public synchronized ObjectNode updateMap(String region, String name, JsonNode request) {
        NamedResource resource = requireMap(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        JsonNode configuration = request.get("ConfigurationUpdate");
        if (configuration == null) {
            configuration = request.get("Configuration");
        }
        if (configuration != null && configuration.has("PoliticalView")) {
            resource.politicalView = textOrNull(configuration, "PoliticalView");
        }
        resource.updateTime = now();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MapName", resource.name);
        response.put("MapArn", resource.arn);
        response.put("UpdateTime", resource.updateTime);
        response.put("DataSource", resource.dataSource);
        return response;
    }

    public synchronized void deleteMap(String region, String name) {
        if (maps.remove(key(region, name)) == null) {
            throw notFound(name);
        }
    }

    public ObjectNode listMaps(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(maps, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("MapName", resource.name);
            putOptional(entry, "Description", resource.description);
            entry.put("DataSource", resource.dataSource);
            entry.put("CreateTime", resource.createTime);
            entry.put("UpdateTime", resource.updateTime);
        }
        return response;
    }

    public BinaryAsset getMapStyleDescriptor(String region, String mapName) {
        requireMap(region, mapName);
        return new BinaryAsset(STYLE_JSON, CONTENT_TYPE_JSON, CACHE_CONTROL);
    }

    public BinaryAsset getMapGlyphs(String region, String mapName) {
        requireMap(region, mapName);
        return new BinaryAsset(GLYPH_PBF, CONTENT_TYPE_PBF, CACHE_CONTROL);
    }

    public BinaryAsset getMapSprites(String region, String mapName, String fileName) {
        requireMap(region, mapName);
        boolean json = fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".json");
        return new BinaryAsset(json ? SPRITE_JSON : PNG, json ? CONTENT_TYPE_JSON : CONTENT_TYPE_PNG, CACHE_CONTROL);
    }

    public BinaryAsset getMapTile(String region, String mapName) {
        requireMap(region, mapName);
        return new BinaryAsset(MVT, CONTENT_TYPE_MVT, CACHE_CONTROL);
    }

    // ── API keys ────────────────────────────────────────────────────────────

    public synchronized ObjectNode createKey(String region, JsonNode request) {
        String name = requireText(request, "KeyName");
        if (keys.containsKey(key(region, name))) {
            throw conflict(name);
        }
        JsonNode restrictions = request.get("Restrictions");
        if (restrictions == null || !restrictions.isObject()) {
            throw validation("Restrictions is required.", "Restrictions");
        }
        NamedResource resource = named(region, name, "api-key", request);
        resource.configuration = restrictions.deepCopy();
        resource.apiKeyValue = "v1.public." + UUID.randomUUID().toString().replace("-", "");
        resource.expireTime = request.path("NoExpiry").asBoolean(false)
                ? "9999-12-31T23:59:59.000Z"
                : textOr(request, "ExpireTime", "9999-12-31T23:59:59.000Z");
        keys.put(key(region, name), resource);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Key", resource.apiKeyValue);
        response.put("KeyArn", resource.arn);
        response.put("KeyName", resource.name);
        response.put("CreateTime", resource.createTime);
        return response;
    }

    public ObjectNode describeKey(String region, String name) {
        NamedResource resource = requireKey(region, name);
        ObjectNode json = objectMapper.createObjectNode();
        json.put("Key", resource.apiKeyValue);
        json.put("KeyArn", resource.arn);
        json.put("KeyName", resource.name);
        json.set("Restrictions", resource.configuration == null
                ? objectMapper.createObjectNode()
                : resource.configuration);
        json.put("CreateTime", resource.createTime);
        json.put("ExpireTime", resource.expireTime == null ? "9999-12-31T23:59:59.000Z" : resource.expireTime);
        json.put("UpdateTime", resource.updateTime);
        putOptional(json, "Description", resource.description);
        json.set("Tags", tags(resource));
        return json;
    }

    public synchronized ObjectNode updateKey(String region, String name, JsonNode request) {
        NamedResource resource = requireKey(region, name);
        if (request.has("Description")) {
            resource.description = textOrNull(request, "Description");
        }
        if (request.has("Restrictions")) {
            resource.configuration = request.get("Restrictions").deepCopy();
        }
        resource.updateTime = now();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("KeyArn", resource.arn);
        response.put("KeyName", resource.name);
        response.put("UpdateTime", resource.updateTime);
        return response;
    }

    public synchronized void deleteKey(String region, String name) {
        if (keys.remove(key(region, name)) == null) {
            throw notFound(name);
        }
    }

    public ObjectNode listKeys(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (NamedResource resource : listRegion(keys, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("KeyName", resource.name);
            putOptional(entry, "Description", resource.description);
            entry.put("ExpireTime", resource.expireTime == null ? "9999-12-31T23:59:59.000Z" : resource.expireTime);
            entry.put("CreateTime", resource.createTime);
            entry.put("UpdateTime", resource.updateTime);
            if (resource.configuration != null) {
                entry.set("Restrictions", resource.configuration);
            } else {
                ObjectNode restrictions = entry.putObject("Restrictions");
                restrictions.putArray("AllowActions");
                restrictions.putArray("AllowResources");
            }
        }
        return response;
    }

    // ── Jobs ────────────────────────────────────────────────────────────────

    public synchronized ObjectNode startJob(String region, JsonNode request) {
        String action = requireText(request, "Action");
        String role = requireText(request, "ExecutionRoleArn");
        JsonNode input = request.get("InputOptions");
        JsonNode output = request.get("OutputOptions");
        if (input == null || output == null) {
            throw validation("InputOptions and OutputOptions are required.", "InputOptions");
        }
        String id = UUID.randomUUID().toString();
        String timestamp = now();
        Job job = new Job(
                id,
                regionResolver.buildArn(SERVICE, region, "job/" + id),
                action,
                role,
                textOrNull(request, "Name"),
                "COMPLETED",
                timestamp,
                timestamp,
                timestamp,
                input.deepCopy(),
                output.deepCopy(),
                request.get("ActionOptions"),
                readTags(request));
        jobs.put(key(region, id), job);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CreatedAt", job.createdAt);
        response.put("JobArn", job.arn);
        response.put("JobId", job.jobId);
        response.put("Status", job.status);
        return response;
    }

    public ObjectNode listJobs(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        String prefix = region + ":";
        for (Map.Entry<String, Job> entry : jobs.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                entries.add(jobJson(entry.getValue(), false));
            }
        }
        return response;
    }

    public ObjectNode getJob(String region, String jobId) {
        Job job = jobs.get(key(region, jobId));
        if (job == null) {
            throw notFound(jobId);
        }
        return jobJson(job, true);
    }

    public synchronized ObjectNode cancelJob(String region, JsonNode request) {
        String jobId = requireText(request, "JobId");
        Job job = jobs.get(key(region, jobId));
        if (job == null) {
            throw notFound(jobId);
        }
        job.status = "CANCELLED";
        job.updatedAt = now();
        job.endedAt = job.updatedAt;
        return objectMapper.createObjectNode();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private NamedResource named(String region, String name, String kind, JsonNode request) {
        String timestamp = now();
        NamedResource resource = new NamedResource();
        resource.name = name;
        resource.arn = regionResolver.buildArn(SERVICE, region, kind + "/" + name);
        resource.description = textOrNull(request, "Description");
        resource.kmsKeyId = textOrNull(request, "KmsKeyId");
        resource.createTime = timestamp;
        resource.updateTime = timestamp;
        resource.tags.putAll(readTags(request));
        return resource;
    }

    private ObjectNode trackerJson(NamedResource resource, boolean detailed) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("TrackerName", resource.name);
        if (detailed) {
            json.put("TrackerArn", resource.arn);
            json.put("PositionFiltering", resource.positionFiltering == null ? "TimeBased" : resource.positionFiltering);
            json.put("EventBridgeEnabled", resource.eventBridgeEnabled);
            json.put("KmsKeyEnableGeospatialQueries", resource.kmsKeyEnableGeospatialQueries);
            putOptional(json, "KmsKeyId", resource.kmsKeyId);
            json.set("Tags", tags(resource));
        }
        putOptional(json, "Description", resource.description);
        json.put("CreateTime", resource.createTime);
        json.put("UpdateTime", resource.updateTime);
        return json;
    }

    private ObjectNode geofenceJson(Geofence fence) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("GeofenceId", fence.geofenceId);
        json.set("Geometry", fence.geometry);
        json.put("Status", fence.status);
        json.put("CreateTime", fence.createTime);
        json.put("UpdateTime", fence.updateTime);
        if (fence.properties != null && fence.properties.isObject()) {
            json.set("GeofenceProperties", fence.properties);
        }
        return json;
    }

    private ObjectNode devicePositionJson(DeviceSample sample, boolean listEntry) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("DeviceId", sample.deviceId);
        json.put("SampleTime", sample.sampleTime);
        if (!listEntry) {
            json.put("ReceivedTime", sample.receivedTime);
        }
        ArrayNode position = json.putArray("Position");
        position.add(sample.longitude);
        position.add(sample.latitude);
        if (sample.accuracy != null && sample.accuracy.isObject()) {
            json.set("Accuracy", sample.accuracy);
        }
        if (sample.properties != null && sample.properties.isObject()) {
            json.set("PositionProperties", sample.properties);
        }
        return json;
    }

    private ObjectNode jobJson(Job job, boolean detailed) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("Action", job.action);
        json.put("CreatedAt", job.createdAt);
        json.put("ExecutionRoleArn", job.executionRoleArn);
        json.put("JobId", job.jobId);
        json.put("JobArn", job.arn);
        json.put("Status", job.status);
        json.put("UpdatedAt", job.updatedAt);
        putOptional(json, "Name", job.name);
        putOptional(json, "EndedAt", job.endedAt);
        if (job.inputOptions != null) {
            json.set("InputOptions", job.inputOptions);
        }
        if (job.outputOptions != null) {
            json.set("OutputOptions", job.outputOptions);
        }
        if (job.actionOptions != null) {
            json.set("ActionOptions", job.actionOptions);
        }
        if (detailed) {
            json.set("Tags", tagObject(job.tags));
        }
        return json;
    }

    private ObjectNode v1Place(CatalogPlace place) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("Label", place.label);
        ObjectNode geometry = json.putObject("Geometry");
        ArrayNode point = geometry.putArray("Point");
        point.add(place.longitude);
        point.add(place.latitude);
        json.put("AddressNumber", place.addressNumber);
        json.put("Street", place.street);
        json.put("Municipality", place.municipality);
        json.put("Region", place.region);
        json.put("Country", place.country);
        json.put("PostalCode", place.postalCode);
        return json;
    }

    private ObjectNode updateAck(String name, String arn, String updateTime, String nameKey, String arnKey) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put(nameKey, name);
        response.put(arnKey, arn);
        response.put("UpdateTime", updateTime);
        return response;
    }

    private ObjectNode tags(NamedResource resource) {
        return tagObject(resource.tags);
    }

    private ObjectNode tagObject(Map<String, String> tags) {
        ObjectNode json = objectMapper.createObjectNode();
        tags.forEach(json::put);
        return json;
    }

    private NamedResource requireTracker(String region, String name) {
        NamedResource resource = trackers.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private NamedResource requireCollection(String region, String name) {
        NamedResource resource = collections.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private NamedResource requireIndex(String region, String name) {
        NamedResource resource = indexes.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private NamedResource requireCalculator(String region, String name) {
        NamedResource resource = calculators.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private NamedResource requireMap(String region, String name) {
        NamedResource resource = maps.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private NamedResource requireKey(String region, String name) {
        NamedResource resource = keys.get(key(region, name));
        if (resource == null) {
            throw notFound(name);
        }
        return resource;
    }

    private Geofence requireGeofence(String region, String collectionName, String geofenceId) {
        requireCollection(region, collectionName);
        ConcurrentHashMap<String, Geofence> fences = geofences.get(key(region, collectionName));
        Geofence fence = fences == null ? null : fences.get(geofenceId);
        if (fence == null) {
            throw notFound(geofenceId);
        }
        return fence;
    }

    private NamedResource requireByArn(String region, String arn) {
        String resource;
        try {
            resource = io.github.hectorvent.floci.core.common.AwsArnUtils.parse(arn).resource();
        } catch (IllegalArgumentException e) {
            throw notFound(arn);
        }
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw notFound(arn);
        }
        String kind = resource.substring(0, slash);
        String name = resource.substring(slash + 1);
        NamedResource found = switch (kind) {
            case "tracker" -> trackers.get(key(region, name));
            case "geofence-collection" -> collections.get(key(region, name));
            case "place-index" -> indexes.get(key(region, name));
            case "route-calculator" -> calculators.get(key(region, name));
            case "map" -> maps.get(key(region, name));
            case "api-key" -> keys.get(key(region, name));
            default -> null;
        };
        if (found == null) {
            throw notFound(arn);
        }
        return found;
    }

    private Optional<DeviceSample> latest(String region, String trackerName, String deviceId) {
        List<DeviceSample> samples = samples(region, trackerName, deviceId);
        return Optional.ofNullable(latestOf(samples));
    }

    private List<DeviceSample> samples(String region, String trackerName, String deviceId) {
        ConcurrentHashMap<String, CopyOnWriteArrayList<DeviceSample>> devices =
                positions.get(key(region, trackerName));
        if (devices == null) {
            return List.of();
        }
        CopyOnWriteArrayList<DeviceSample> samples = devices.get(deviceId);
        return samples == null ? List.of() : samples;
    }

    private static DeviceSample latestOf(List<DeviceSample> samples) {
        DeviceSample latest = null;
        for (DeviceSample sample : samples) {
            if (latest == null || sample.sampleTime.compareTo(latest.sampleTime) >= 0) {
                latest = sample;
            }
        }
        return latest;
    }

    private List<NamedResource> listRegion(Map<String, NamedResource> store, String region) {
        String prefix = region + ":";
        List<NamedResource> result = new ArrayList<>();
        for (Map.Entry<String, NamedResource> entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(resource -> resource.name));
        return result;
    }

    private List<CatalogPlace> matchByQuery(String query, int maxResults) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<CatalogPlace> ranked = new ArrayList<>();
        for (CatalogPlace place : catalog) {
            if (place.matches(needle)) {
                ranked.add(place);
            }
        }
        if (ranked.isEmpty()) {
            ranked.add(catalog.get(0));
        }
        if (ranked.size() > maxResults) {
            return List.copyOf(ranked.subList(0, maxResults));
        }
        return ranked;
    }

    private List<CatalogPlace> matchByDistance(double longitude, double latitude, int maxResults) {
        List<CatalogPlace> ranked = new ArrayList<>(catalog);
        ranked.sort(Comparator.comparingDouble(
                place -> distanceMeters(longitude, latitude, place.longitude, place.latitude)));
        if (ranked.size() > maxResults) {
            return List.copyOf(ranked.subList(0, maxResults));
        }
        return ranked;
    }

    private Optional<CatalogPlace> findById(String placeId) {
        for (CatalogPlace place : catalog) {
            if (place.placeId.equals(placeId)) {
                return Optional.of(place);
            }
        }
        return Optional.empty();
    }

    private static String dataSourceFromStyle(String style) {
        String lower = style.toLowerCase(Locale.ROOT);
        if (lower.contains("here")) {
            return "Here";
        }
        if (lower.contains("grab")) {
            return "Grab";
        }
        return "Esri";
    }

    private static Circle circleOf(JsonNode geometry) {
        JsonNode circle = geometry.get("Circle");
        if (circle == null || !circle.isObject() || !isPosition(circle.get("Center"))) {
            return null;
        }
        double[] center = position(circle.get("Center"));
        double radius = circle.path("Radius").asDouble(0);
        return new Circle(center[0], center[1], radius);
    }

    private static boolean isPosition(JsonNode node) {
        return node != null && node.isArray() && node.size() >= 2 && node.get(0).isNumber() && node.get(1).isNumber();
    }

    private static double[] position(JsonNode node) {
        return new double[] {node.get(0).asDouble(), node.get(1).asDouble()};
    }

    private static void setPosition(ArrayNode array, double[] position) {
        array.add(position[0]);
        array.add(position[1]);
    }

    private static double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private static double convertDistance(double meters, String unit) {
        if ("Miles".equalsIgnoreCase(unit)) {
            return meters / 1609.344;
        }
        if ("Kilometers".equalsIgnoreCase(unit) || unit == null || unit.isBlank()) {
            return meters / 1000d;
        }
        return meters;
    }

    private static String key(String region, String name) {
        return region + ":" + name;
    }

    private static String now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is required.", field);
        }
        return value;
    }

    private static String textOr(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static ArrayNode array(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }

    private static int maxResults(JsonNode request) {
        int value = request.path("MaxResults").asInt(DEFAULT_MAX_RESULTS);
        if (value <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(value, 50);
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = request.get("Tags");
        if (node != null && node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                JsonNode value = node.get(field);
                if (value != null && value.isTextual()) {
                    tags.put(field, value.asText());
                }
            }
        }
        return tags;
    }

    private static void putOptional(ObjectNode json, String field, String value) {
        if (value != null && !value.isBlank()) {
            json.put(field, value);
        }
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static AwsException notFound(String name) {
        return new AwsException("ResourceNotFoundException", "Resource '" + name + "' not found.", 404);
    }

    private static AwsException conflict(String name) {
        return new AwsException("ConflictException", "Resource '" + name + "' already exists.", 409);
    }

    private static AwsException validation(String message, String field) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("Reason", "Missing");
        extra.put("FieldList", List.of(Map.of("Name", field, "Message", message)));
        return new AwsException("ValidationException", message, 400, extra);
    }

    record BinaryAsset(byte[] body, String contentType, String cacheControl) {
    }

    private static final class NamedResource {
        String name;
        String arn;
        String description;
        String kmsKeyId;
        String createTime;
        String updateTime;
        String positionFiltering;
        boolean eventBridgeEnabled;
        boolean kmsKeyEnableGeospatialQueries;
        String dataSource;
        String intendedUse;
        String style;
        String politicalView;
        JsonNode configuration;
        String apiKeyValue;
        String expireTime;
        final Map<String, String> tags = new LinkedHashMap<>();
        final Set<String> consumers = ConcurrentHashMap.newKeySet();
    }

    private record Geofence(
            String geofenceId,
            JsonNode geometry,
            String status,
            String createTime,
            String updateTime,
            JsonNode properties) {
    }

    private record DeviceSample(
            String deviceId,
            String sampleTime,
            String receivedTime,
            double longitude,
            double latitude,
            JsonNode accuracy,
            JsonNode properties) {
        DeviceSample(
                String deviceId,
                String sampleTime,
                String receivedTime,
                double[] position,
                JsonNode accuracy,
                JsonNode properties) {
            this(deviceId, sampleTime, receivedTime, position[0], position[1], accuracy, properties);
        }
    }

    private static final class Job {
        final String jobId;
        final String arn;
        final String action;
        final String executionRoleArn;
        final String name;
        String status;
        final String createdAt;
        String updatedAt;
        String endedAt;
        final JsonNode inputOptions;
        final JsonNode outputOptions;
        final JsonNode actionOptions;
        final Map<String, String> tags;

        Job(
                String jobId,
                String arn,
                String action,
                String executionRoleArn,
                String name,
                String status,
                String createdAt,
                String updatedAt,
                String endedAt,
                JsonNode inputOptions,
                JsonNode outputOptions,
                JsonNode actionOptions,
                Map<String, String> tags) {
            this.jobId = jobId;
            this.arn = arn;
            this.action = action;
            this.executionRoleArn = executionRoleArn;
            this.name = name;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.endedAt = endedAt;
            this.inputOptions = inputOptions;
            this.outputOptions = outputOptions;
            this.actionOptions = actionOptions;
            this.tags = tags;
        }
    }

    private record CatalogPlace(
            String placeId,
            String title,
            String label,
            String addressNumber,
            String street,
            String municipality,
            String region,
            String country,
            String postalCode,
            double longitude,
            double latitude,
            List<String> keywords) {
        boolean matches(String needle) {
            if (title.toLowerCase(Locale.ROOT).contains(needle)
                    || label.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            for (String keyword : keywords) {
                if (needle.contains(keyword) || keyword.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Circle(double lon, double lat, double radiusMeters) {
    }
}
