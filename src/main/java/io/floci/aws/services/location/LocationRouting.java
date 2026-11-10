package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Routes without a road network: a leg follows the great circle between its ends, its distance is
 * that length times a road detour factor of 1.25, and its duration is that distance at the travel
 * mode's typical speed (car 50 km/h, truck 45, scooter and motorcycle 30 or 50, bicycle 15,
 * walking 5). Geometry is the leg's line, sampled every kilometer, as a GeoJSON line or a HERE
 * flexible polyline. Matrices, isolines, waypoint sequencing and road snapping follow the same
 * model.
 */
@ApplicationScoped
public class LocationRouting {
    static final double DETOUR = 1.25;
    private static final Map<String, Double> SPEEDS = Map.of(
            "Car", 50 / 3.6, "Truck", 45 / 3.6, "Motorcycle", 50 / 3.6, "Scooter", 30 / 3.6,
            "Bicycle", 15 / 3.6, "Walking", 5 / 3.6, "Pedestrian", 5 / 3.6);

    private final ObjectMapper mapper;

    @Inject
    public LocationRouting(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static double speed(String mode) {
        Double speed = SPEEDS.get(mode);
        if (speed == null) {
            throw LocationService.validation("TravelMode " + mode + " is not supported.");
        }
        return speed;
    }

    static double roadMeters(double[] a, double[] b) {
        return LocationGeometry.distance(a, b) * DETOUR;
    }

    private static int samples(double meters) {
        return (int) Math.max(1, Math.min(200, Math.ceil(meters / 1000)));
    }

    // ── Location v1 ────────────────────────────────────────────────────────

    public ObjectNode calculateRoute(JsonNode request, String dataSource) {
        String mode = request.path("TravelMode").asText("Car");
        double speed = speed(mode);
        String unit = request.path("DistanceUnit").asText("Kilometers");
        List<double[]> stops = new ArrayList<>();
        stops.add(LocationTrackingService.position(request.path("DeparturePosition"), "DeparturePosition"));
        JsonNode waypoints = request.path("WaypointPositions");
        if (waypoints.size() > 23) {
            throw LocationService.validation("At most 23 WaypointPositions are allowed.");
        }
        waypoints.forEach(w -> stops.add(LocationTrackingService.position(w, "WaypointPositions")));
        stops.add(LocationTrackingService.position(request.path("DestinationPosition"), "DestinationPosition"));
        boolean geometry = request.path("IncludeLegGeometry").asBoolean(false);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode legs = response.putArray("Legs");
        double totalMeters = 0;
        double totalSeconds = 0;
        List<double[]> all = new ArrayList<>();
        for (int i = 0; i + 1 < stops.size(); i++) {
            double[] from = stops.get(i);
            double[] to = stops.get(i + 1);
            double meters = roadMeters(from, to);
            double seconds = meters / speed;
            totalMeters += meters;
            totalSeconds += seconds;
            List<double[]> line = LocationGeometry.line(from, to, samples(meters));
            all.addAll(line);
            ObjectNode leg = legs.addObject();
            leg.set("StartPosition", point(from));
            leg.set("EndPosition", point(to));
            leg.put("Distance", round(LocationTrackingService.toUnit(meters, unit)));
            leg.put("DurationSeconds", round(seconds));
            if (geometry) {
                ArrayNode lineString = leg.putObject("Geometry").putArray("LineString");
                line.forEach(p -> lineString.add(point(p)));
            }
            ObjectNode step = leg.putArray("Steps").addObject();
            step.set("StartPosition", point(from));
            step.set("EndPosition", point(to));
            step.put("Distance", round(LocationTrackingService.toUnit(meters, unit)));
            step.put("DurationSeconds", round(seconds));
            if (geometry) {
                step.put("GeometryOffset", 0);
            }
        }
        ObjectNode summary = response.putObject("Summary");
        double[] box = LocationGeometry.bbox(all);
        summary.set("RouteBBox", mapper.valueToTree(box));
        summary.put("DataSource", dataSource);
        summary.put("Distance", round(LocationTrackingService.toUnit(totalMeters, unit)));
        summary.put("DurationSeconds", round(totalSeconds));
        summary.put("DistanceUnit", unit);
        return response;
    }

    public ObjectNode calculateMatrix(JsonNode request, String dataSource) {
        String mode = request.path("TravelMode").asText("Car");
        double speed = speed(mode);
        String unit = request.path("DistanceUnit").asText("Kilometers");
        List<double[]> departures = positions(request.path("DeparturePositions"), "DeparturePositions", 350);
        List<double[]> destinations = positions(request.path("DestinationPositions"), "DestinationPositions", 350);
        if ("Esri".equals(dataSource) && (departures.size() > 10 || destinations.size() > 10)) {
            throw LocationService.validation("An Esri route matrix is at most 10 by 10.");
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode matrix = response.putArray("RouteMatrix");
        for (double[] from : departures) {
            ArrayNode row = matrix.addArray();
            for (double[] to : destinations) {
                double meters = roadMeters(from, to);
                row.addObject().put("Distance", round(LocationTrackingService.toUnit(meters, unit)))
                        .put("DurationSeconds", round(meters / speed));
            }
        }
        ArrayNode snappedFrom = response.putArray("SnappedDeparturePositions");
        departures.forEach(p -> snappedFrom.add(point(p)));
        ArrayNode snappedTo = response.putArray("SnappedDestinationPositions");
        destinations.forEach(p -> snappedTo.add(point(p)));
        ObjectNode summary = response.putObject("Summary");
        summary.put("DataSource", dataSource);
        summary.put("RouteCount", departures.size() * destinations.size());
        summary.put("ErrorCount", 0);
        summary.put("DistanceUnit", unit);
        return response;
    }

    // ── Geo Routes (v2) ────────────────────────────────────────────────────

    public ObjectNode calculateRoutes(JsonNode request) {
        String mode = request.path("TravelMode").asText("Car");
        double speed = speed(mode);
        String format = request.path("LegGeometryFormat").asText("FlexiblePolyline");
        List<double[]> stops = new ArrayList<>();
        stops.add(LocationTrackingService.position(request.path("Origin"), "Origin"));
        for (JsonNode waypoint : request.path("Waypoints")) {
            stops.add(LocationTrackingService.position(waypoint.path("Position"), "Waypoints.Position"));
        }
        stops.add(LocationTrackingService.position(request.path("Destination"), "Destination"));
        Instant departure = request.hasNonNull("DepartureTime") ? Instant.parse(request.get("DepartureTime").asText())
                : Instant.now();
        ObjectNode response = mapper.createObjectNode();
        response.put("LegGeometryFormat", format);
        response.putArray("Notices");
        ArrayNode routes = response.putArray("Routes");
        ObjectNode route = routes.addObject();
        ArrayNode legs = route.putArray("Legs");
        route.putArray("MajorRoadLabels");
        long totalMeters = 0;
        long totalSeconds = 0;
        boolean pedestrian = "Pedestrian".equals(mode);
        for (int i = 0; i + 1 < stops.size(); i++) {
            double[] from = stops.get(i);
            double[] to = stops.get(i + 1);
            long meters = Math.round(roadMeters(from, to));
            long seconds = Math.round(meters / speed);
            ObjectNode leg = legs.addObject();
            leg.set("Geometry", legGeometry(LocationGeometry.line(from, to, samples(meters)), format));
            leg.put("TravelMode", pedestrian ? "Pedestrian" : mode);
            leg.put("Type", pedestrian ? "Pedestrian" : "Vehicle");
            ObjectNode details = leg.putObject(pedestrian ? "PedestrianLegDetails" : "VehicleLegDetails");
            details.putArray("AfterTravelSteps");
            Instant legDeparture = departure.plusSeconds(totalSeconds);
            details.putObject("Departure").put("Time", legDeparture.toString()).putObject("Place")
                    .set("Position", point(from));
            details.putObject("Arrival").put("Time", legDeparture.plusSeconds(seconds).toString()).putObject("Place")
                    .set("Position", point(to));
            details.putArray("Notices");
            details.putArray("PassThroughWaypoints");
            details.putArray("Spans");
            details.putObject("Summary").putObject("Overview").put("Distance", meters).put("Duration", seconds);
            ArrayNode steps = details.putArray("TravelSteps");
            steps.addObject().put("Type", "Depart").put("Distance", meters).put("Duration", seconds)
                    .put("GeometryOffset", 0).put("Instruction", "Head " + compass(from, to) + ".");
            steps.addObject().put("Type", "Arrive").put("Distance", 0).put("Duration", 0)
                    .put("GeometryOffset", samples(meters)).put("Instruction", "Arrive at your destination.");
            if (!pedestrian) {
                details.putArray("Incidents");
                details.putArray("Tolls");
                details.putArray("TollSystems");
                details.putArray("TruckRoadTypes");
                details.putArray("Zones");
            }
            totalMeters += meters;
            totalSeconds += seconds;
        }
        route.putObject("Summary").put("Distance", totalMeters).put("Duration", totalSeconds);
        return response;
    }

    public ObjectNode calculateRouteMatrixV2(JsonNode request) {
        double speed = speed(request.path("TravelMode").asText("Car"));
        List<double[]> origins = new ArrayList<>();
        for (JsonNode origin : request.path("Origins")) {
            origins.add(LocationTrackingService.position(origin.path("Position"), "Origins.Position"));
        }
        List<double[]> destinations = new ArrayList<>();
        for (JsonNode destination : request.path("Destinations")) {
            destinations.add(LocationTrackingService.position(destination.path("Position"), "Destinations.Position"));
        }
        if (origins.isEmpty() || destinations.isEmpty()) {
            throw LocationService.validation("Origins and Destinations need at least one position each.");
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode matrix = response.putArray("RouteMatrix");
        for (double[] from : origins) {
            ArrayNode row = matrix.addArray();
            for (double[] to : destinations) {
                long meters = Math.round(roadMeters(from, to));
                row.addObject().put("Distance", meters).put("Duration", Math.round(meters / speed));
            }
        }
        response.put("ErrorCount", 0);
        JsonNode boundary = request.path("RoutingBoundary");
        response.set("RoutingBoundary", boundary.isObject() ? boundary.deepCopy()
                : mapper.createObjectNode().put("Unbounded", true));
        return response;
    }

    public ObjectNode calculateIsolines(JsonNode request) {
        double speed = speed(request.path("TravelMode").asText("Car"));
        String format = request.path("IsolineGeometryFormat").asText("FlexiblePolyline");
        boolean fromOrigin = request.has("Origin");
        if (fromOrigin == request.has("Destination")) {
            throw LocationService.validation("Specify exactly one of Origin and Destination.");
        }
        double[] center = LocationTrackingService.position(request.path(fromOrigin ? "Origin" : "Destination"),
                fromOrigin ? "Origin" : "Destination");
        JsonNode thresholds = request.path("Thresholds");
        if (thresholds.path("Distance").isEmpty() == thresholds.path("Time").isEmpty()) {
            throw LocationService.validation("Thresholds needs exactly one of Distance and Time.");
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("IsolineGeometryFormat", format);
        ArrayNode isolines = response.putArray("Isolines");
        boolean byTime = !thresholds.path("Time").isEmpty();
        for (JsonNode threshold : thresholds.path(byTime ? "Time" : "Distance")) {
            double meters = (byTime ? threshold.asDouble() * speed : threshold.asDouble()) / DETOUR;
            ObjectNode isoline = isolines.addObject();
            isoline.put(byTime ? "TimeThreshold" : "DistanceThreshold", threshold.asLong());
            isoline.putArray("Connections");
            List<double[]> ring = LocationGeometry.circle(center, meters, 36);
            ObjectNode geometry = isoline.putArray("Geometries").addObject();
            if ("Simple".equals(format)) {
                ArrayNode linear = geometry.putArray("Polygon").addArray();
                ring.forEach(p -> linear.add(point(p)));
            } else {
                geometry.putArray("PolylinePolygon").add(FlexiblePolyline.encode(ring));
            }
        }
        response.set(fromOrigin ? "SnappedOrigin" : "SnappedDestination", point(center));
        return response;
    }

    /** Nearest-neighbor order from the origin, ending at the destination when one is given. */
    public ObjectNode optimizeWaypoints(JsonNode request) {
        double speed = speed(request.path("TravelMode").asText("Car"));
        double[] origin = LocationTrackingService.position(request.path("Origin"), "Origin");
        record Stop(String id, double[] position, long service) {}
        List<Stop> pending = new ArrayList<>();
        int index = 0;
        for (JsonNode waypoint : request.path("Waypoints")) {
            pending.add(new Stop(waypoint.path("Id").asText("Waypoint" + index++),
                    LocationTrackingService.position(waypoint.path("Position"), "Waypoints.Position"),
                    waypoint.path("ServiceDuration").asLong(0)));
        }
        List<Stop> order = new ArrayList<>();
        order.add(new Stop("Origin", origin, 0));
        double[] current = origin;
        while (!pending.isEmpty()) {
            Stop next = pending.getFirst();
            for (Stop candidate : pending) {
                if (LocationGeometry.distance(current, candidate.position())
                        < LocationGeometry.distance(current, next.position())) {
                    next = candidate;
                }
            }
            pending.remove(next);
            order.add(next);
            current = next.position();
        }
        if (request.has("Destination")) {
            order.add(new Stop("Destination", LocationTrackingService.position(request.path("Destination"),
                    "Destination"), 0));
        }
        Instant clock = request.hasNonNull("DepartureTime") ? Instant.parse(request.get("DepartureTime").asText())
                : Instant.now();
        ObjectNode response = mapper.createObjectNode();
        ArrayNode optimized = response.putArray("OptimizedWaypoints");
        ArrayNode connections = response.putArray("Connections");
        long distance = 0;
        long travel = 0;
        long service = 0;
        for (int i = 0; i < order.size(); i++) {
            Stop stop = order.get(i);
            ObjectNode waypoint = optimized.addObject();
            waypoint.put("Id", stop.id());
            waypoint.set("Position", point(stop.position()));
            if (i > 0) {
                Stop previous = order.get(i - 1);
                long meters = Math.round(roadMeters(previous.position(), stop.position()));
                long seconds = Math.round(meters / speed);
                connections.addObject().put("Distance", meters).put("From", previous.id()).put("To", stop.id())
                        .put("RestDuration", 0).put("TravelDuration", seconds).put("WaitDuration", 0);
                clock = clock.plusSeconds(seconds);
                waypoint.put("ArrivalTime", clock.toString());
                distance += meters;
                travel += seconds;
            }
            clock = clock.plusSeconds(stop.service());
            service += stop.service();
            waypoint.put("DepartureTime", clock.toString());
        }
        response.put("Distance", distance);
        response.put("Duration", travel + service);
        response.putArray("ImpedingWaypoints");
        response.putObject("TimeBreakdown").put("RestDuration", 0).put("ServiceDuration", service)
                .put("TravelDuration", travel).put("WaitDuration", 0);
        return response;
    }

    /** With no road network, every trace point is already on the road it reports. */
    public ObjectNode snapToRoads(JsonNode request) {
        String format = request.path("SnappedGeometryFormat").asText("FlexiblePolyline");
        JsonNode trace = request.path("TracePoints");
        if (trace.size() < 2 || trace.size() > 5000) {
            throw LocationService.validation("TracePoints must hold 2 to 5000 points.");
        }
        List<double[]> points = new ArrayList<>();
        ObjectNode response = mapper.createObjectNode();
        ArrayNode snapped = response.putArray("SnappedTracePoints");
        for (JsonNode point : trace) {
            double[] position = LocationTrackingService.position(point.path("Position"), "TracePoints.Position");
            points.add(position);
            snapped.addObject().put("Confidence", 1.0).<ObjectNode>set("OriginalPosition", point(position))
                    .set("SnappedPosition", point(position));
        }
        response.putArray("Notices");
        response.put("SnappedGeometryFormat", format);
        response.set("SnappedGeometry", legGeometry(points, format));
        return response;
    }

    private ObjectNode legGeometry(List<double[]> line, String format) {
        ObjectNode geometry = mapper.createObjectNode();
        if ("Simple".equals(format)) {
            ArrayNode lineString = geometry.putArray("LineString");
            line.forEach(p -> lineString.add(point(p)));
        } else {
            geometry.put("Polyline", FlexiblePolyline.encode(line));
        }
        return geometry;
    }

    private static String compass(double[] from, double[] to) {
        String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return names[(int) Math.round(LocationGeometry.bearing(from, to) / 45) % 8];
    }

    private static List<double[]> positions(JsonNode list, String member, int max) {
        if (!list.isArray() || list.isEmpty() || list.size() > max) {
            throw LocationService.validation(member + " must hold 1 to " + max + " positions.");
        }
        List<double[]> positions = new ArrayList<>();
        list.forEach(p -> positions.add(LocationTrackingService.position(p, member)));
        return positions;
    }

    private ArrayNode point(double[] p) {
        return mapper.createArrayNode().add(LocationGeometry.round(p[0])).add(LocationGeometry.round(p[1]));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /** HERE's flexible polyline encoding, two dimensions at precision 6. */
    static final class FlexiblePolyline {
        private static final String TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        private static final int PRECISION = 6;

        private FlexiblePolyline() {
        }

        static String encode(List<double[]> points) {
            StringBuilder out = new StringBuilder();
            unsigned(out, 1);
            unsigned(out, PRECISION);
            long lastLat = 0;
            long lastLng = 0;
            double factor = Math.pow(10, PRECISION);
            for (double[] p : points) {
                long lat = Math.round(p[1] * factor);
                long lng = Math.round(p[0] * factor);
                signed(out, lat - lastLat);
                signed(out, lng - lastLng);
                lastLat = lat;
                lastLng = lng;
            }
            return out.toString();
        }

        private static void unsigned(StringBuilder out, long value) {
            while (value > 0x1F) {
                out.append(TABLE.charAt((int) ((value & 0x1F) | 0x20)));
                value >>= 5;
            }
            out.append(TABLE.charAt((int) value));
        }

        private static void signed(StringBuilder out, long value) {
            long zigzag = value << 1;
            if (value < 0) {
                zigzag = ~zigzag;
            }
            unsigned(out, zigzag);
        }
    }
}
