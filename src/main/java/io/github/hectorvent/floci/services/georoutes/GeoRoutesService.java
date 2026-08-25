package io.github.hectorvent.floci.services.georoutes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local Amazon Location Routes (geo-routes) stub. Pay-per-call restJson1 APIs
 * with no persisted resources: distances use haversine on the WGS84 sphere and
 * durations assume a constant 13.4 m/s (about 30 mph) road speed.
 *
 * @see <a href="https://docs.aws.amazon.com/location/latest/APIReference/API_Operations_Amazon_Location_Service_Routes.html">Geo Routes API</a>
 */
@ApplicationScoped
public class GeoRoutesService {

    static final String PRICING_BUCKET = "Standard";
    static final String PRICING_BUCKET_HEADER = "x-amz-geo-pricing-bucket";
    private static final double EARTH_RADIUS_METERS = 6_371_000d;
    private static final double METERS_PER_SECOND = 13.4d;
    private static final double METERS_PER_DEGREE = 111_320d;

    private final ObjectMapper objectMapper;

    @Inject
    public GeoRoutesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode calculateRoutes(JsonNode request) {
        requireObject(request);
        double[] origin = requirePosition(request, "Origin");
        double[] destination = requirePosition(request, "Destination");
        List<double[]> path = new ArrayList<>();
        path.add(origin);
        path.addAll(waypointPositions(request.get("Waypoints")));
        path.add(destination);
        LegMetrics metrics = metrics(path);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("LegGeometryFormat", "Simple");
        response.set("Notices", objectMapper.createArrayNode());
        ArrayNode routes = response.putArray("Routes");
        ObjectNode route = routes.addObject();
        ArrayNode legs = route.putArray("Legs");
        ObjectNode leg = legs.addObject();
        ObjectNode geometry = leg.putObject("Geometry");
        geometry.set("LineString", lineString(path));
        leg.put("TravelMode", textOr(request, "TravelMode", "Car"));
        leg.put("Type", "Vehicle");
        route.set("MajorRoadLabels", objectMapper.createArrayNode());
        ObjectNode summary = route.putObject("Summary");
        summary.put("Distance", metrics.distanceMeters());
        summary.put("Duration", metrics.durationSeconds());
        return response;
    }

    public ObjectNode calculateIsolines(JsonNode request) {
        requireObject(request);
        JsonNode originNode = request.get("Origin");
        JsonNode destinationNode = request.get("Destination");
        if (!isPosition(originNode) && !isPosition(destinationNode)) {
            throw validation("Missing", "Origin",
                    "CalculateIsolines requires Origin or Destination.");
        }
        double[] center = isPosition(originNode)
                ? position(originNode, "Origin")
                : position(destinationNode, "Destination");
        JsonNode thresholds = request.get("Thresholds");
        if (thresholds == null || !thresholds.isObject()) {
            throw validation("Missing", "Thresholds", "Thresholds is required.");
        }
        Integer time = firstInt(thresholds.get("Time"));
        Integer distance = firstInt(thresholds.get("Distance"));
        if (time == null && distance == null) {
            throw validation("Missing", "Thresholds",
                    "Thresholds.Time or Thresholds.Distance is required.");
        }
        int radiusMeters = distance != null
                ? Math.max(1, distance)
                : Math.max(1, (int) Math.round(time * METERS_PER_SECOND));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("IsolineGeometryFormat", "Simple");
        ArrayNode isolines = response.putArray("Isolines");
        ObjectNode isoline = isolines.addObject();
        isoline.set("Connections", objectMapper.createArrayNode());
        if (distance != null) {
            isoline.put("DistanceThreshold", distance);
        }
        if (time != null) {
            isoline.put("TimeThreshold", time);
        }
        ArrayNode geometries = isoline.putArray("Geometries");
        ObjectNode shape = geometries.addObject();
        shape.set("Polygon", circlePolygon(center[0], center[1], radiusMeters));
        if (isPosition(originNode)) {
            response.set("SnappedOrigin", copyPosition(originNode));
        }
        if (isPosition(destinationNode)) {
            response.set("SnappedDestination", copyPosition(destinationNode));
        }
        return response;
    }

    public ObjectNode calculateRouteMatrix(JsonNode request) {
        requireObject(request);
        List<double[]> origins = matrixPositions(request.get("Origins"), "Origins");
        List<double[]> destinations = matrixPositions(request.get("Destinations"), "Destinations");
        if (origins.isEmpty()) {
            throw validation("Missing", "Origins", "Origins is required.");
        }
        if (destinations.isEmpty()) {
            throw validation("Missing", "Destinations", "Destinations is required.");
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ErrorCount", 0);
        ArrayNode matrix = response.putArray("RouteMatrix");
        for (double[] origin : origins) {
            ArrayNode row = matrix.addArray();
            for (double[] destination : destinations) {
                LegMetrics metrics = metrics(List.of(origin, destination));
                ObjectNode entry = row.addObject();
                entry.put("Distance", metrics.distanceMeters());
                entry.put("Duration", metrics.durationSeconds());
            }
        }
        JsonNode boundary = request.get("RoutingBoundary");
        if (boundary != null && !boundary.isNull()) {
            response.set("RoutingBoundary", boundary.deepCopy());
        } else {
            response.putObject("RoutingBoundary").put("Unbounded", true);
        }
        return response;
    }

    public ObjectNode optimizeWaypoints(JsonNode request) {
        requireObject(request);
        double[] origin = requirePosition(request, "Origin");
        JsonNode originOptions = request.get("OriginOptions");
        String originId = textFrom(originOptions, "Id", "Origin");
        JsonNode destinationNode = request.get("Destination");
        double[] destination = isPosition(destinationNode)
                ? position(destinationNode, "Destination")
                : null;
        JsonNode destinationOptions = request.get("DestinationOptions");
        String destinationId = textFrom(destinationOptions, "Id", "Destination");

        List<NamedPoint> stops = new ArrayList<>();
        JsonNode waypoints = request.get("Waypoints");
        if (waypoints != null && waypoints.isArray()) {
            int index = 1;
            for (JsonNode waypoint : waypoints) {
                if (waypoint == null || !waypoint.isObject()) {
                    continue;
                }
                double[] position = requirePosition(waypoint, "Position");
                String id = textFrom(waypoint, "Id", "Waypoint-" + index);
                stops.add(new NamedPoint(id, position));
                index++;
            }
        }

        List<NamedPoint> ordered = nearestNeighbor(origin, stops);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode optimized = response.putArray("OptimizedWaypoints");
        addOptimized(optimized, originId, origin);
        for (NamedPoint stop : ordered) {
            addOptimized(optimized, stop.id(), stop.position());
        }
        List<double[]> path = new ArrayList<>();
        path.add(origin);
        for (NamedPoint stop : ordered) {
            path.add(stop.position());
        }
        if (destination != null) {
            addOptimized(optimized, destinationId, destination);
            path.add(destination);
        }
        LegMetrics metrics = metrics(path);
        response.put("Distance", metrics.distanceMeters());
        response.put("Duration", metrics.durationSeconds());
        response.set("Connections", objectMapper.createArrayNode());
        response.set("ImpedingWaypoints", objectMapper.createArrayNode());
        ObjectNode breakdown = response.putObject("TimeBreakdown");
        breakdown.put("RestDuration", 0);
        breakdown.put("ServiceDuration", 0);
        breakdown.put("TravelDuration", metrics.durationSeconds());
        breakdown.put("WaitDuration", 0);
        return response;
    }

    public ObjectNode snapToRoads(JsonNode request) {
        requireObject(request);
        JsonNode traces = request.get("TracePoints");
        if (traces == null || !traces.isArray() || traces.isEmpty()) {
            throw validation("Missing", "TracePoints", "TracePoints is required.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Notices", objectMapper.createArrayNode());
        response.put("SnappedGeometryFormat", "Simple");
        ArrayNode snapped = response.putArray("SnappedTracePoints");
        ArrayNode line = objectMapper.createArrayNode();
        for (int i = 0; i < traces.size(); i++) {
            JsonNode point = traces.get(i);
            if (point == null || !point.isObject()) {
                throw validation("FieldValidationFailed", "TracePoints." + i,
                        "TracePoints member must be an object.");
            }
            double[] position = requirePosition(point, "Position");
            ObjectNode snappedPoint = snapped.addObject();
            snappedPoint.put("Confidence", 1.0d);
            snappedPoint.set("OriginalPosition", copyDoubles(position));
            snappedPoint.set("SnappedPosition", copyDoubles(position));
            line.add(copyDoubles(position));
        }
        ObjectNode geometry = response.putObject("SnappedGeometry");
        geometry.set("LineString", line);
        return response;
    }

    private void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw validation("CannotParse", "body", "Request body must be a JSON object.");
        }
    }

    private double[] requirePosition(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw validation("Missing", field, field + " is required.");
        }
        return position(node, field);
    }

    private double[] position(JsonNode node, String field) {
        if (!isPosition(node)) {
            throw validation("FieldValidationFailed", field,
                    field + " must be a [longitude, latitude] pair.");
        }
        return new double[] {node.get(0).asDouble(), node.get(1).asDouble()};
    }

    private static boolean isPosition(JsonNode node) {
        return node != null && node.isArray() && node.size() >= 2
                && node.get(0).isNumber() && node.get(1).isNumber();
    }

    private List<double[]> waypointPositions(JsonNode waypoints) {
        List<double[]> path = new ArrayList<>();
        if (waypoints == null || !waypoints.isArray()) {
            return path;
        }
        int index = 0;
        for (JsonNode waypoint : waypoints) {
            if (waypoint != null && waypoint.isObject() && waypoint.has("Position")) {
                path.add(requirePosition(waypoint, "Position"));
            } else if (isPosition(waypoint)) {
                path.add(position(waypoint, "Waypoints." + index));
            }
            index++;
        }
        return path;
    }

    private List<double[]> matrixPositions(JsonNode list, String field) {
        if (list == null || list.isNull()) {
            return List.of();
        }
        if (!list.isArray()) {
            throw validation("FieldValidationFailed", field, field + " must be an array.");
        }
        List<double[]> positions = new ArrayList<>();
        int index = 0;
        for (JsonNode item : list) {
            String path = field + "." + index;
            if (item != null && item.isObject()) {
                positions.add(requirePosition(item, "Position"));
            } else if (isPosition(item)) {
                positions.add(position(item, path));
            } else {
                throw validation("FieldValidationFailed", path,
                        "Matrix location must include Position.");
            }
            index++;
        }
        return positions;
    }

    private LegMetrics metrics(List<double[]> path) {
        double distance = 0d;
        for (int i = 1; i < path.size(); i++) {
            distance += haversine(path.get(i - 1), path.get(i));
        }
        long meters = Math.max(1, Math.round(distance));
        long seconds = Math.max(1, Math.round(distance / METERS_PER_SECOND));
        return new LegMetrics(meters, seconds);
    }

    private static double haversine(double[] a, double[] b) {
        double lon1 = Math.toRadians(a[0]);
        double lat1 = Math.toRadians(a[1]);
        double lon2 = Math.toRadians(b[0]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(h)));
    }

    private ArrayNode lineString(List<double[]> path) {
        ArrayNode line = objectMapper.createArrayNode();
        for (double[] point : path) {
            line.add(copyDoubles(point));
        }
        return line;
    }

    private ArrayNode circlePolygon(double lon, double lat, double radiusMeters) {
        double dLat = radiusMeters / METERS_PER_DEGREE;
        double cos = Math.cos(Math.toRadians(lat));
        double dLon = radiusMeters / (METERS_PER_DEGREE * (Math.abs(cos) < 1e-6 ? 1e-6 : cos));
        ArrayNode ring = objectMapper.createArrayNode();
        int steps = 8;
        for (int i = 0; i <= steps; i++) {
            double angle = 2d * Math.PI * i / steps;
            ArrayNode point = objectMapper.createArrayNode();
            point.add(lon + dLon * Math.cos(angle));
            point.add(lat + dLat * Math.sin(angle));
            ring.add(point);
        }
        ArrayNode polygon = objectMapper.createArrayNode();
        polygon.add(ring);
        return polygon;
    }

    private ArrayNode copyDoubles(double[] position) {
        ArrayNode node = objectMapper.createArrayNode();
        node.add(position[0]);
        node.add(position[1]);
        return node;
    }

    private JsonNode copyPosition(JsonNode node) {
        ArrayNode copy = objectMapper.createArrayNode();
        copy.add(node.get(0).asDouble());
        copy.add(node.get(1).asDouble());
        return copy;
    }

    private void addOptimized(ArrayNode list, String id, double[] position) {
        ObjectNode waypoint = list.addObject();
        waypoint.put("Id", id);
        waypoint.set("Position", copyDoubles(position));
    }

    private List<NamedPoint> nearestNeighbor(double[] origin, List<NamedPoint> stops) {
        List<NamedPoint> remaining = new ArrayList<>(stops);
        List<NamedPoint> ordered = new ArrayList<>();
        double[] current = origin;
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                double distance = haversine(current, remaining.get(i).position());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            NamedPoint next = remaining.remove(best);
            ordered.add(next);
            current = next.position();
        }
        return ordered;
    }

    private static Integer firstInt(JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty() || !list.get(0).isNumber()) {
            return null;
        }
        return list.get(0).asInt();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }

    private static String textFrom(JsonNode node, String field, String fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        return textOr(node, field, fallback);
    }

    private static AwsException validation(String reason, String field, String message) {
        Map<String, Object> fieldEntry = new LinkedHashMap<>();
        fieldEntry.put("Name", field);
        fieldEntry.put("Message", message);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("Reason", reason);
        extra.put("FieldList", List.of(fieldEntry));
        return new AwsException("ValidationException", message, 400, extra);
    }

    private record LegMetrics(long distanceMeters, long durationSeconds) {}

    private record NamedPoint(String id, double[] position) {}
}
