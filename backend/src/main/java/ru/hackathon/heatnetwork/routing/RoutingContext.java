package ru.hackathon.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.ObjectId;

/**
 * Metric scene built from the Dataset in EPSG:32637.
 *
 * <p>Obstacle geometry for the planner: forbidden and special restrictions buffered
 * by their clearance plus the nominal half-width of the pipe pair. During search the
 * half-width of the actually chosen diameter is checked exactly by the validator, so
 * buffering here uses the maximum plausible diameter of the local branch to stay
 * conservative. Crossing an OKS polygon is never allowed; the polygon that contains
 * the target connection point is exempted for that target only.</p>
 */
public final class RoutingContext {

    /** Grid step of the A* search, meters. Configurable for tests. */
    public static final double DEFAULT_GRID_STEP = 25.0;

    private final GeometryFactory gf = new GeometryFactory();
    private final RulesCatalog catalog;
    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<HeatLine> existingLines = new ArrayList<>();
    private final List<Chamber> existingChambers = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();

    public static final class Obstacle {
        public final ObjectId id;
        public final String restrictionType;
        /** true: cannot be crossed at all; false: special pass required. */
        public final boolean forbidden;
        /** Buffered geometry used during search. */
        public final Geometry buffered;
        /** Exact original geometry for validator-like checks. */
        public final Geometry exact;

        Obstacle(ObjectId id, String restrictionType, boolean forbidden, Geometry buffered, Geometry exact) {
            this.id = id;
            this.restrictionType = restrictionType;
            this.forbidden = forbidden;
            this.buffered = buffered;
            this.exact = exact;
        }
    }

    public static final class HeatLine {
        public final ObjectId id;
        public final LineString line;
        public final int diameterMm;

        HeatLine(ObjectId id, LineString line, int diameterMm) {
            this.id = id;
            this.line = line;
            this.diameterMm = diameterMm;
        }
    }

    public static final class Chamber {
        public final ObjectId id;
        public final Point point;
        public int occupiedDegree;

        Chamber(ObjectId id, Point point) {
            this.id = id;
            this.point = point;
        }
    }

    public static final class Target {
        public final ObjectId id;
        public final Point point;
        public final double flowTph;
        /** OKS polygon containing this point, or null. */
        public final ObjectId ownOksPolygonId;
        public final Geometry ownOksPolygon;

        Target(ObjectId id, Point point, double flowTph, ObjectId ownOksPolygonId, Geometry ownOksPolygon) {
            this.id = id;
            this.point = point;
            this.flowTph = flowTph;
            this.ownOksPolygonId = ownOksPolygonId;
            this.ownOksPolygon = ownOksPolygon;
        }
    }

    private RoutingContext(RulesCatalog catalog) {
        this.catalog = catalog;
    }

    public RulesCatalog catalog() {
        return catalog;
    }

    public List<Obstacle> obstacles() {
        return Collections.unmodifiableList(obstacles);
    }

    public List<HeatLine> existingLines() {
        return Collections.unmodifiableList(existingLines);
    }

    public List<Chamber> existingChambers() {
        return Collections.unmodifiableList(existingChambers);
    }

    public List<Target> targets() {
        return Collections.unmodifiableList(targets);
    }

    /** Shared geometry factory (EPSG:32637 metric coordinates). */
    public GeometryFactory geometryFactory() {
        return gf;
    }

    /**
     * Builds the context. {@code searchWidthMm} — the diameter used to size obstacle
     * buffers during search; the branch-specific diameter is checked exactly later.
     */
    public static RoutingContext build(Collection<InputObject> objects, RulesCatalog catalog, int searchDiameterMm) {
        RoutingContext ctx = new RoutingContext(catalog);
        double halfWidth = catalog.halfWidthM(searchDiameterMm);
        for (InputObject obj : objects) {
            if (obj.type == InputType.RESTRICTION) {
                String rt = obj.restrictionType == null ? "" : obj.restrictionType;
                RulesCatalog.RestrictionRule rule = catalog.rule(rt);
                if (rule == null) {
                    // Unknown restriction types are not mandatory (clarification 9); ignore during search.
                    continue;
                }
                boolean forbidden = "forbidden".equals(rule.crossing);
                double clearance = catalog.clearanceFor(rt, searchDiameterMm);
                double buffer = clearance + halfWidth;
                Geometry buffered = obj.geometry.buffer(buffer);
                ctx.obstacles.add(new Obstacle(obj.id, rt, forbidden, buffered, obj.geometry));
            } else if (obj.type == InputType.HEAT_NETWORK) {
                if (obj.geometry instanceof LineString) {
                    int diameter = obj.diameterMm == null ? 0 : obj.diameterMm;
                    ctx.existingLines.add(new HeatLine(obj.id, (LineString) obj.geometry, diameter));
                    // Crossing an existing network without an attachment is a mandatory
                    // special pass (table 2), not a free corridor and not a forbidden wall.
                    RulesCatalog.RestrictionRule networkRule = catalog.rule("heat_network");
                    if (networkRule != null && diameter > 0) {
                        double networkClearance = catalog.clearanceFor("heat_network", searchDiameterMm);
                        double networkHalfWidth = catalog.halfWidthM(diameter);
                        ctx.obstacles.add(new Obstacle(obj.id, "heat_network", false,
                                obj.geometry.buffer(networkClearance + halfWidth + networkHalfWidth),
                                obj.geometry));
                    }
                }
            } else if (obj.type == InputType.HEAT_CHAMBER) {
                if (obj.geometry instanceof Point) {
                    ctx.existingChambers.add(new Chamber(obj.id, (Point) obj.geometry));
                }
            } else if (obj.type == InputType.OKS_CONNECTION_POINT) {
                double flow = obj.flowTph == null ? 0.0 : obj.flowTph.doubleValue();
                ObjectId own = null;
                Geometry ownGeom = null;
                for (InputObject other : objects) {
                    if (other.type == InputType.RESTRICTION && "oks".equals(other.restrictionType)
                            && other.geometry != null && other.geometry.contains(obj.geometry)) {
                        own = other.id;
                        ownGeom = other.geometry;
                        break;
                    }
                }
                ctx.targets.add(new Target(obj.id, (Point) obj.geometry, flow, own, ownGeom));
            }
        }
        return ctx;
    }

    /** True when the segment is blocked by a non-exempt forbidden obstacle or crosses a non-exempt OKS polygon. */
    public boolean blockedByForbidden(Coordinate a, Coordinate b, ObjectId exemptOksPolygonId, Set<ObjectId> exemptLineIds) {
        // Cheap bbox pre-filter: segment envelope (grown by max buffer) vs obstacle envelope.
        double pad = 20.0;
        double minX = Math.min(a.x, b.x) - pad, maxX = Math.max(a.x, b.x) + pad;
        double minY = Math.min(a.y, b.y) - pad, maxY = Math.max(a.y, b.y) + pad;
        LineString segment = gf.createLineString(new Coordinate[] {a, b});
        for (Obstacle obstacle : obstacles) {
            if (!obstacle.forbidden) {
                continue;
            }
            if (exemptOksPolygonId != null && obstacle.id.equals(exemptOksPolygonId)) {
                continue;
            }
            if (exemptLineIds != null && exemptLineIds.contains(obstacle.id)) {
                continue;
            }
            Envelope oe = obstacle.buffered.getEnvelopeInternal();
            if (oe.getMaxX() < minX || oe.getMinX() > maxX || oe.getMaxY() < minY || oe.getMinY() > maxY) {
                continue;
            }
            if (obstacle.buffered.intersects(segment)) {
                return true;
            }
        }
        // Crossing an OKS polygon body (inside, not just the buffered clearance) is forbidden as well.
        for (Obstacle obstacle : obstacles) {
            if (!"oks".equals(obstacle.restrictionType) || obstacle.forbidden) {
                continue;
            }
            if (exemptOksPolygonId != null && obstacle.id.equals(exemptOksPolygonId)) {
                continue;
            }
            if (exemptLineIds != null && exemptLineIds.contains(obstacle.id)) {
                continue;
            }
            Envelope oe = obstacle.exact.getEnvelopeInternal();
            if (oe.getMaxX() < minX || oe.getMinX() > maxX || oe.getMaxY() < minY || oe.getMinY() > maxY) {
                continue;
            }
            if (obstacle.exact.intersects(segment)) {
                return true;
            }
            if (obstacle.buffered.intersects(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Restricted clearance-zone hit for a segment with a specific diameter (special passes allowed, clearance still applies). */
    public boolean violatesSpecialClearance(Coordinate a, Coordinate b, int diameterMm, ObjectId exemptOksPolygonId, Set<ObjectId> exemptLineIds) {
        // Cheap bbox pre-filter before exact geometry work.
        double pad = 30.0;
        double minX = Math.min(a.x, b.x) - pad, maxX = Math.max(a.x, b.x) + pad;
        double minY = Math.min(a.y, b.y) - pad, maxY = Math.max(a.y, b.y) + pad;
        LineString segment = gf.createLineString(new Coordinate[] {a, b});
        double halfWidth = catalog.halfWidthM(diameterMm);
        for (Obstacle obstacle : obstacles) {
            if (exemptOksPolygonId != null && obstacle.id.equals(exemptOksPolygonId)) {
                continue;
            }
            if (exemptLineIds != null && exemptLineIds.contains(obstacle.id)) {
                continue;
            }
            Envelope oe = obstacle.exact.getEnvelopeInternal();
            if (oe.getMaxX() < minX || oe.getMinX() > maxX || oe.getMaxY() < minY || oe.getMinY() > maxY) {
                continue;
            }
            double clearance = catalog.clearanceFor(obstacle.restrictionType, diameterMm);
            double need = clearance + halfWidth;
            if (need <= 0) {
                continue;
            }
            // Exact check: distance between the obstacle and the segment axis must exceed clearance + half width
            // unless the segment legitimately crosses a special obstacle (crossing itself is validated later).
            if (obstacle.exact.intersects(segment)) {
                continue;
            }
            if (obstacle.exact.distance(segment) < need - 1e-9) {
                return true;
            }
        }
        return false;
    }

    /** Distance from the point to the nearest forbidden obstacle body (not its clearance zone). */
    public double distanceToForbiddenBody(Coordinate c, ObjectId exemptOksPolygonId) {
        Point p = gf.createPoint(c);
        double best = Double.POSITIVE_INFINITY;
        for (Obstacle obstacle : obstacles) {
            if (exemptOksPolygonId != null && obstacle.id.equals(exemptOksPolygonId)) {
                continue;
            }
            best = Math.min(best, obstacle.exact.distance(p));
        }
        return best;
    }

    /**
     * Existing chambers within the tie-in radius of the point; degree limit already respected.
     */
    public List<Chamber> chambersNear(Coordinate c, double radiusM) {
        List<Chamber> result = new ArrayList<>();
        Point p = gf.createPoint(c);
        for (Chamber chamber : existingChambers) {
            if (chamber.occupiedDegree + 1 > catalog.maxChamberDegree()) {
                continue;
            }
            if (p.distance(chamber.point) <= radiusM + 1e-9) {
                result.add(chamber);
            }
        }
        return result;
    }

    /** Candidates on existing lines for tie-in; returns snap points with the source line. */
    public List<LineSnap> snapsOnLines(Coordinate c, double maxDistanceM) {
        List<LineSnap> result = new ArrayList<>();
        Point p = gf.createPoint(c);
        for (HeatLine line : existingLines) {
            // nearest point on the line
            Coordinate[] coords = line.line.getCoordinates();
            for (int i = 0; i + 1 < coords.length; i++) {
                Coordinate snapped = closestPoint(coords[i], coords[i + 1], c);
                double d = p.distance(gf.createPoint(snapped));
                if (d <= maxDistanceM + 1e-9) {
                    result.add(new LineSnap(line.id, snapped, d, line.line));
                }
            }
        }
        return result;
    }

    private static Coordinate closestPoint(Coordinate a, Coordinate b, Coordinate p) {
        double ax = b.x - a.x, ay = b.y - a.y;
        double lenSq = ax * ax + ay * ay;
        if (lenSq <= 1e-12) {
            return new Coordinate(a.x, a.y);
        }
        double t = ((p.x - a.x) * ax + (p.y - a.y) * ay) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Coordinate(a.x + t * ax, a.y + t * ay);
    }

    public static final class LineSnap {
        public final ObjectId lineId;
        public final Coordinate snap;
        public final double distanceM;
        public final LineString line;

        LineSnap(ObjectId lineId, Coordinate snap, double distanceM, LineString line) {
            this.lineId = lineId;
            this.snap = snap;
            this.distanceM = distanceM;
            this.line = line;
        }
    }

    /** Envelope of all input geometry, padded; the search stays within it. */
    public Envelope searchBounds(double padM) {
        Envelope env = new Envelope();
        for (HeatLine line : existingLines) {
            env.expandToInclude(line.line.getEnvelopeInternal());
        }
        for (Target t : targets) {
            env.expandToInclude(t.point.getEnvelopeInternal());
        }
        for (Chamber c : existingChambers) {
            env.expandToInclude(c.point.getEnvelopeInternal());
        }
        env.expandBy(padM);
        return env;
    }
}
