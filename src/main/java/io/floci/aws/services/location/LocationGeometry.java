package io.floci.aws.services.location;

import java.util.ArrayList;
import java.util.List;

/**
 * Spherical geometry on WGS84 longitude/latitude pairs: great-circle distance and destination,
 * interpolation along a line, point-in-polygon with holes, and circles as polygons.
 */
final class LocationGeometry {
    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private LocationGeometry() {
    }

    /** Great-circle distance in meters between two {@code [lon, lat]} points. */
    static double distance(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** The point {@code meters} from {@code origin} on {@code bearingDegrees} (clockwise from north). */
    static double[] destination(double[] origin, double bearingDegrees, double meters) {
        double angular = meters / EARTH_RADIUS_METERS;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(origin[1]);
        double lon1 = Math.toRadians(origin[0]);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
        double lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{normalizeLon(Math.toDegrees(lon2)), Math.toDegrees(lat2)};
    }

    /** Initial bearing in degrees from {@code a} to {@code b}. */
    static double bearing(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLon = Math.toRadians(b[0] - a[0]);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** {@code segments + 1} points evenly spaced from {@code a} to {@code b}, both included. */
    static List<double[]> line(double[] a, double[] b, int segments) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            points.add(new double[]{round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t)});
        }
        return points;
    }

    /** A closed ring approximating a circle, counter-clockwise, as GeoJSON wants an exterior ring. */
    static List<double[]> circle(double[] center, double radiusMeters, int vertices) {
        List<double[]> ring = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            double[] point = destination(center, 360.0 - 360.0 * i / vertices, radiusMeters);
            ring.add(new double[]{round(point[0]), round(point[1])});
        }
        ring.add(ring.getFirst());
        return ring;
    }

    /** Whether {@code point} is inside the polygon (first ring exterior, the rest holes). */
    static boolean inPolygon(double[] point, List<List<double[]>> rings) {
        if (rings.isEmpty() || !inRing(point, rings.getFirst())) {
            return false;
        }
        for (int i = 1; i < rings.size(); i++) {
            if (inRing(point, rings.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean inRing(double[] point, List<double[]> ring) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i);
            double[] b = ring.get(j);
            if ((a[1] > point[1]) != (b[1] > point[1])
                    && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Meters from {@code point} to the nearest edge of a ring (zero on the edge). */
    static double distanceToRing(double[] point, List<double[]> ring) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < ring.size(); i++) {
            best = Math.min(best, distanceToSegment(point, ring.get(i), ring.get(i + 1)));
        }
        return best;
    }

    private static double distanceToSegment(double[] p, double[] a, double[] b) {
        double[] closest;
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            closest = a;
        } else {
            double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSquared));
            closest = new double[]{a[0] + t * dx, a[1] + t * dy};
        }
        return distance(p, closest);
    }

    /** {@code [minLon, minLat, maxLon, maxLat]} of the points. */
    static double[] bbox(List<double[]> points) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] p : points) {
            box[0] = Math.min(box[0], p[0]);
            box[1] = Math.min(box[1], p[1]);
            box[2] = Math.max(box[2], p[0]);
            box[3] = Math.max(box[3], p[1]);
        }
        return box;
    }

    static boolean validPosition(double[] position) {
        return position != null && position.length == 2 && position[0] >= -180 && position[0] <= 180
                && position[1] >= -90 && position[1] <= 90;
    }

    static double round(double value) {
        return Math.round(value * 1e7) / 1e7;
    }

    private static double normalizeLon(double lon) {
        return ((lon + 540) % 360) - 180;
    }
}
