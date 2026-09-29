package ru.hackathon.heatnetwork.routing;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;

/** Local plan angles of a straight special pass; independent of the calculation module. */
final class SpecialCrossingAngles {
    private static final double NUMERIC_TOL = 1e-9;

    private SpecialCrossingAngles() { }

    static double minimum(LineString route, Geometry restriction) {
        List<LineString> segments = new ArrayList<>();
        boolean polygon = restriction instanceof Polygonal;
        collectSegments(polygon ? restriction.getBoundary() : restriction, segments);
        double minimum = 90;
        if (!polygon) {
            for (LineString segment : segments) {
                if (segment.intersects(route)) minimum = Math.min(minimum, angle(route, segment));
            }
            return minimum;
        }
        // Each connected interval inside the polygon has its own entry, including
        // re-entry after holes and gaps. JTS may return its coordinates in either order.
        List<Coordinate> entries = new ArrayList<>();
        collectEntries(route.intersection(restriction), route, entries);
        for (Coordinate entry : entries) {
            Point at = route.getFactory().createPoint(entry);
            double nearest = Double.POSITIVE_INFINITY;
            double entryAngle = 90;
            for (LineString segment : segments) {
                double distance = segment.distance(at);
                if (distance < nearest - NUMERIC_TOL) {
                    nearest = distance;
                    entryAngle = angle(route, segment);
                } else if (Math.abs(distance - nearest) <= NUMERIC_TOL) {
                    // At a corner both incident boundary segments constrain entry.
                    entryAngle = Math.min(entryAngle, angle(route, segment));
                }
            }
            // A route starting inside an obstacle has no boundary entry here;
            // the independent extension check rejects the incomplete special pass.
            if (nearest <= NUMERIC_TOL) minimum = Math.min(minimum, entryAngle);
        }
        return minimum;
    }

    private static void collectSegments(Geometry geometry, List<LineString> segments) {
        if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                collectSegments(geometry.getGeometryN(i), segments);
            }
        } else if (geometry instanceof LineString) {
            Coordinate[] points = geometry.getCoordinates();
            for (int i = 1; i < points.length; i++) {
                if (!points[i - 1].equals2D(points[i])) {
                    segments.add(geometry.getFactory().createLineString(new Coordinate[]{points[i - 1], points[i]}));
                }
            }
        }
    }

    private static void collectEntries(Geometry intersection, LineString route, List<Coordinate> entries) {
        if (intersection.isEmpty()) return;
        if (intersection instanceof GeometryCollection) {
            for (int i = 0; i < intersection.getNumGeometries(); i++) {
                collectEntries(intersection.getGeometryN(i), route, entries);
            }
            return;
        }
        Coordinate start = route.getCoordinateN(0), end = route.getCoordinateN(1);
        double dx = end.x - start.x, dy = end.y - start.y;
        Coordinate entry = null;
        double first = Double.POSITIVE_INFINITY;
        for (Coordinate point : intersection.getCoordinates()) {
            double along = (point.x - start.x) * dx + (point.y - start.y) * dy;
            if (along < first) { first = along; entry = point; }
        }
        if (entry != null) entries.add(entry);
    }

    private static double angle(LineString route, LineString segment) {
        Coordinate a = route.getCoordinateN(0), b = route.getCoordinateN(1);
        Coordinate c = segment.getCoordinateN(0), d = segment.getCoordinateN(1);
        double ux = b.x - a.x, uy = b.y - a.y, vx = d.x - c.x, vy = d.y - c.y;
        return Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), Math.abs(ux * vx + uy * vy)));
    }
}
