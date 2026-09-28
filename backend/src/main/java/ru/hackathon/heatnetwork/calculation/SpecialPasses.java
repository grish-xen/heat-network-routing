package ru.hackathon.heatnetwork.calculation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygonal;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RulesCatalog.RestrictionRule;

/**
 * Special passes of one edge (section 4, table 2, clarifications 6–8, 10).
 *
 * <p>Every separate crossing becomes an interval along the edge: the part inside a
 * polygon plus the extension beyond each boundary, or the extension on each side of
 * a line crossing point. The interval must fit inside the edge and lie on one straight
 * segment; the minimum crossing angle is checked where the rule defines it. The edge is
 * then cut at every interval boundary so that each piece has a constant set of crossed
 * objects: base pieces have none, special pieces use the largest Kspec of their set.</p>
 */
final class SpecialPasses {
    private SpecialPasses() { }

    /** A restriction subject to a special pass: road, tram tracks, gas, power cable, existing heat network. */
    static final class Obstacle {
        final ObjectId id;
        final String type;
        final Geometry geometry;
        final RestrictionRule rule;
        /** Half of the object's own design profile, 0 when the table gives none. */
        final double ownHalfWidthM;
        /** Height of the object's own design profile where table 1 gives it (existing heat network), else 0. */
        final double ownHeightM;

        Obstacle(ObjectId id, String type, Geometry geometry, RestrictionRule rule, double ownHalfWidthM,
                 double ownHeightM) {
            this.id = id;
            this.type = type;
            this.geometry = geometry;
            this.rule = rule;
            this.ownHalfWidthM = ownHalfWidthM;
            this.ownHeightM = ownHeightM;
        }
    }

    static final class Piece {
        final double start;
        final double end;
        final Map<ObjectId, Obstacle> crossed;

        Piece(double start, double end, Map<ObjectId, Obstacle> crossed) {
            this.start = start;
            this.end = end;
            this.crossed = crossed;
        }
    }

    private static final class Pass {
        final Obstacle obstacle;
        final double start;
        final double end;

        Pass(Obstacle obstacle, double start, double end) {
            this.obstacle = obstacle;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Splits the edge into pieces. {@code rootPoint} is the attachment chamber when the edge starts in it:
     * existing heat networks touching it there are the permitted connection, not a crossing.
     */
    static List<Piece> split(String edgeId, Polyline line, List<Obstacle> obstacles, Coordinate rootPoint,
                             double tolerance) throws Rejection {
        List<Pass> passes = new ArrayList<>();
        for (Obstacle obstacle : obstacles) {
            for (double[] crossing : crossings(line, obstacle.geometry, tolerance)) {
                if (rootPoint != null && "heat_network".equals(obstacle.type) && crossing[1] <= tolerance
                        && obstacle.geometry.distance(line.factory.createPoint(rootPoint)) <= tolerance) {
                    continue;
                }
                checkAngle(edgeId, line, obstacle, crossing, tolerance);
                double extension = obstacle.rule.extensionEachSideM == null ? 0 : obstacle.rule.extensionEachSideM;
                double start = crossing[0] - extension;
                double end = crossing[1] + extension;
                if (start < -tolerance || end > line.length + tolerance) {
                    throw Rejection.of("SPECIAL_PASS_VIOLATION", "Специальный проход через " + obstacle.type
                            + " не помещается в участок " + edgeId + " вместе с продолжением "
                            + extension + " м за границей.", obstacle.id, edgeId);
                }
                start = Math.max(0, start);
                end = Math.min(line.length, end);
                if (line.hasVertexInside(start, end, tolerance)) {
                    throw Rejection.of("SPECIAL_PASS_VIOLATION", "Специальный проход через " + obstacle.type
                            + " на участке " + edgeId + " должен быть одним прямым отрезком.", obstacle.id, edgeId);
                }
                passes.add(new Pass(obstacle, start, end));
            }
        }
        return pieces(line.length, passes, tolerance);
    }

    private static List<Piece> pieces(double length, List<Pass> passes, double tolerance) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(length);
        for (Pass pass : passes) {
            cuts.add(pass.start);
            cuts.add(pass.end);
        }
        cuts.sort(Comparator.naturalOrder());
        List<Double> unique = new ArrayList<>();
        for (double cut : cuts) {
            if (unique.isEmpty() || cut - unique.get(unique.size() - 1) > tolerance) {
                unique.add(cut);
            }
        }
        if (length - unique.get(unique.size() - 1) > 0) {
            unique.set(unique.size() - 1, length);
        }
        List<Piece> result = new ArrayList<>();
        for (int i = 0; i + 1 < unique.size(); i++) {
            double start = unique.get(i);
            double end = unique.get(i + 1);
            Map<ObjectId, Obstacle> crossed = new LinkedHashMap<>();
            for (Pass pass : passes) {
                if (pass.start <= start + tolerance && pass.end >= end - tolerance) {
                    crossed.put(pass.obstacle.id, pass.obstacle);
                }
            }
            Piece previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && previous.crossed.keySet().equals(crossed.keySet())) {
                result.set(result.size() - 1, new Piece(previous.start, end, previous.crossed));
            } else {
                result.add(new Piece(start, end, crossed));
            }
        }
        return result;
    }

    /** Separate crossings as [start, end] positions along the edge; a point crossing has start == end. */
    static List<double[]> crossings(Polyline line, Geometry obstacle, double tolerance) {
        List<double[]> raw = new ArrayList<>();
        for (int i = 0; i + 1 < line.points.length; i++) {
            LineString segment = line.segment(i);
            if (!segment.intersects(obstacle)) {
                continue;
            }
            collect(segment.intersection(obstacle), line, i, raw);
        }
        raw.sort(Comparator.comparingDouble(interval -> interval[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : raw) {
            double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && interval[0] <= last[1] + tolerance) {
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new double[] {interval[0], interval[1]});
            }
        }
        return merged;
    }

    private static void collect(Geometry part, Polyline line, int segment, List<double[]> out) {
        if (part.isEmpty()) {
            return;
        }
        if (part.getNumGeometries() > 1) {
            for (int i = 0; i < part.getNumGeometries(); i++) {
                collect(part.getGeometryN(i), line, segment, out);
            }
            return;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Coordinate c : part.getCoordinates()) {
            double s = line.offsets[segment] + line.points[segment].distance(c);
            min = Math.min(min, s);
            max = Math.max(max, s);
        }
        out.add(new double[] {min, max});
    }

    private static void checkAngle(String edgeId, Polyline line, Obstacle obstacle, double[] crossing,
                                   double tolerance) throws Rejection {
        Double minimum = obstacle.rule.minAngleDeg;
        if (minimum == null) {
            return;
        }
        Geometry reference = obstacle.geometry instanceof Polygonal ? obstacle.geometry.getBoundary() : obstacle.geometry;
        for (double position : new double[] {crossing[0], crossing[1]}) {
            int segment = line.segmentAt(position);
            Coordinate a = line.points[segment];
            Coordinate b = line.points[segment + 1];
            Coordinate at = line.pointAt(position);
            double angle = angleToNearestSegment(a, b, at, reference);
            if (angle < minimum - 1e-9) {
                throw Rejection.of("SPECIAL_PASS_VIOLATION", String.format(java.util.Locale.ROOT,
                        "Участок %s пересекает %s под углом %.1f°, минимум %.0f°.", edgeId, obstacle.type,
                        angle, minimum), obstacle.id, edgeId);
            }
        }
    }

    /** Acute angle, degrees, between the route segment and the obstacle segment closest to the crossing. */
    private static double angleToNearestSegment(Coordinate a, Coordinate b, Coordinate at, Geometry reference) {
        double best = Double.POSITIVE_INFINITY;
        double angle = 90;
        for (int g = 0; g < reference.getNumGeometries(); g++) {
            Coordinate[] coords = reference.getGeometryN(g).getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                double distance = org.locationtech.jts.algorithm.Distance.pointToSegment(at, coords[i], coords[i + 1]);
                if (distance < best - 1e-9) {
                    best = distance;
                    angle = acuteAngle(a, b, coords[i], coords[i + 1]);
                } else if (Math.abs(distance - best) <= 1e-9) {
                    angle = Math.min(angle, acuteAngle(a, b, coords[i], coords[i + 1]));
                }
            }
        }
        return angle;
    }

    private static double acuteAngle(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double ux = b.x - a.x, uy = b.y - a.y, vx = d.x - c.x, vy = d.y - c.y;
        double norm = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (norm == 0) {
            return 0;
        }
        double cos = Math.abs(ux * vx + uy * vy) / norm;
        return Math.toDegrees(Math.acos(Math.min(1, cos)));
    }

    /** Edge polyline with arc-length positions of its vertices. */
    static final class Polyline {
        final GeometryFactory factory;
        final Coordinate[] points;
        final double[] offsets;
        final double length;

        Polyline(GeometryFactory factory, Coordinate[] points) {
            this.factory = factory;
            this.points = points;
            this.offsets = new double[points.length];
            for (int i = 1; i < points.length; i++) {
                offsets[i] = offsets[i - 1] + points[i - 1].distance(points[i]);
            }
            this.length = offsets[points.length - 1];
        }

        LineString segment(int i) {
            return factory.createLineString(new Coordinate[] {points[i], points[i + 1]});
        }

        int segmentAt(double position) {
            for (int i = 0; i + 2 < points.length; i++) {
                if (position <= offsets[i + 1]) {
                    return i;
                }
            }
            return points.length - 2;
        }

        Coordinate pointAt(double position) {
            if (position <= 0) {
                return new Coordinate(points[0]);
            }
            if (position >= length) {
                return new Coordinate(points[points.length - 1]);
            }
            int i = segmentAt(position);
            double span = offsets[i + 1] - offsets[i];
            double t = span == 0 ? 0 : (position - offsets[i]) / span;
            return new Coordinate(points[i].x + t * (points[i + 1].x - points[i].x),
                    points[i].y + t * (points[i + 1].y - points[i].y));
        }

        boolean hasVertexInside(double start, double end, double tolerance) {
            for (int i = 1; i + 1 < points.length; i++) {
                if (offsets[i] > start + tolerance && offsets[i] < end - tolerance) {
                    return true;
                }
            }
            return false;
        }

        /** Sub-line between two positions; interior vertices closer than the tolerance to the ends are dropped. */
        Coordinate[] extract(double start, double end, double tolerance) {
            List<Coordinate> result = new ArrayList<>();
            result.add(pointAt(start));
            for (int i = 1; i + 1 < points.length; i++) {
                if (offsets[i] > start + tolerance && offsets[i] < end - tolerance) {
                    result.add(new Coordinate(points[i]));
                }
            }
            result.add(pointAt(end));
            return result.toArray(new Coordinate[0]);
        }
    }
}
