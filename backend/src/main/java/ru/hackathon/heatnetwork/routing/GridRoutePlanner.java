package ru.hackathon.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;
import ru.hackathon.heatnetwork.model.ObjectId;

/**
 * Grid A* route planner (module 2, contract 1.0).
 *
 * <p>Every connection target gets an A* trace over a regular grid in
 * EPSG:32637. A move is allowed when the segment does not hit buffered forbidden
 * obstacles and stays outside special-pass clearance zones; the target's own OKS
 * polygon is exempt for the final approach (section 2.2). Turn angles above 90° are
 * excluded during expansion, so the trace never needs a camera just to turn back.</p>
 *
 * <p>Tie points: an existing chamber within reach (the 10 m rule is applied to the
 * chosen point on an existing line), otherwise a snapped point on an existing
 * heat_network line where a new chamber will be created. Each trace becomes one
 * tree: root node (chamber) plus the target node, the polyline is the edge geometry
 * with turn vertices inside the LineString. A component per trace keeps the tree
 * property strict; the empty candidate (all targets unconnected) is emitted once so
 * the coordinator can still evaluate the penalty variant.</p>
 */
public final class GridRoutePlanner {

    /** Attempts per target before it is treated as unreachable by this planner. */
    static final int MAX_TRACES_PER_TARGET = 6;
    private static final int MAX_EXPANSIONS = 150_000;
    private static final double COINCIDENT_M = 0.001;

    private final GeometryFactory gf = new GeometryFactory();
    private final Dataset dataset;
    private final RulesCatalog catalog;
    private final RoutingContext context;
    private final Mode mode;
    private final int maxCandidates;
    private final List<ObjectId> orderedTargets;

    private final Map<ObjectId, Boolean> connected = new LinkedHashMap<>();
    private final Map<ObjectId, Trace> acceptedTraces = new LinkedHashMap<>();
    private final Map<ObjectId, Integer> attemptCounters = new HashMap<>();
    private final Map<String, Integer> newEdgesPerRoot = new HashMap<>();
    private final Set<ObjectId> exhausted = new HashSet<>();
    private final Map<ObjectId, Integer> targetDiameter = new HashMap<>();

    private int candidateCounter = 0;
    private int targetCursor = 0;
    private boolean emptyCandidateEmitted = false;
    private boolean closed = false;
    /** Target represented by the last candidate returned from next(). */
    private ObjectId lastEmittedTarget;

    private static final class Trace {
        final ObjectId targetId;
        final TieOption tie;
        final List<Coordinate> points;
        final String rootId;

        Trace(ObjectId targetId, TieOption tie, List<Coordinate> points, String rootId) {
            this.targetId = targetId;
            this.tie = tie;
            this.points = points;
            this.rootId = rootId;
        }
    }

    private enum TieKind { EXISTING_CHAMBER, NEW_CHAMBER_ON_LINE }

    private static final class TieOption {
        final TieKind kind;
        final ObjectId existingObjectId;
        final Coordinate coordinate;
        final double penaltyM;

        TieOption(TieKind kind, ObjectId existingObjectId, Coordinate coordinate, double penaltyM) {
            this.kind = kind;
            this.existingObjectId = existingObjectId;
            this.coordinate = coordinate;
            this.penaltyM = penaltyM;
        }
    }

    public GridRoutePlanner(Dataset dataset, SearchOptions options, RulesCatalog catalog) {
        this.dataset = dataset;
        this.catalog = catalog == null ? RulesCatalog.loadDefault() : catalog;
        this.mode = options.mode;
        if (mode == Mode.DEPTH) {
            throw new UnsupportedOperationException("UNSUPPORTED_MODE: DEPTH is reserved by contract 1.0");
        }
        this.maxCandidates = Math.max(1, options.maxCandidates);

        List<InputObject> objects = new ArrayList<>();
        for (InputType type : InputType.values()) {
            try (java.util.stream.Stream<InputObject> stream = dataset.objects(type)) {
                stream.forEach(objects::add);
            }
        }
        this.context = RoutingContext.build(objects, this.catalog, guessSearchDiameter(objects, this.catalog));

        for (RoutingContext.Target target : context.targets()) {
            targetDiameter.put(target.id, estimateDiameter(target.flowTph));
        }
        List<ObjectId> ids = new ArrayList<>();
        for (RoutingContext.Target target : context.targets()) {
            ids.add(target.id);
        }
        // Deterministic: larger flow first — main corridors are laid by the bigger branches.
        ids.sort((a, b) -> {
            RoutingContext.Target ta = target(a);
            RoutingContext.Target tb = target(b);
            return Double.compare(tb.flowTph, ta.flowTph);
        });
        this.orderedTargets = ids;
        for (ObjectId id : ids) {
            connected.put(id, Boolean.FALSE);
        }
    }

    private static int guessSearchDiameter(List<InputObject> objects, RulesCatalog catalog) {
        double totalFlow = 0.0;
        int max = 200;
        for (InputObject obj : objects) {
            if (obj.type == InputType.OKS_CONNECTION_POINT && obj.flowTph != null) {
                totalFlow += obj.flowTph.doubleValue();
                RulesCatalog.DiameterRow row = catalog.minimalForFlow(obj.flowTph.doubleValue());
                if (row != null) {
                    max = Math.max(max, row.diameterMm);
                }
            }
        }
        // A shared trunk carries the sum of all downstream flows. Use the largest
        // catalog diameter required by that total as the conservative search buffer.
        RulesCatalog.DiameterRow trunk = catalog.minimalForFlow(totalFlow);
        if (trunk != null) {
            max = Math.max(max, trunk.diameterMm);
        }
        return max;
    }

    private int estimateDiameter(double flowTph) {
        RulesCatalog.DiameterRow row = catalog.minimalForFlow(flowTph);
        return row == null ? Integer.MAX_VALUE : row.diameterMm;
    }

    private RoutingContext.Target target(ObjectId id) {
        for (RoutingContext.Target t : context.targets()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    public synchronized Optional<RouteCandidate> next() {
        if (closed) {
            throw new IllegalStateException("SearchSession is closed");
        }
        while (candidateCounter < maxCandidates) {
            ObjectId targetId = nextTraceTarget();
            if (targetId == null) {
                break;
            }
            RoutingContext.Target target = target(targetId);
            int attempt = attemptCounters.merge(targetId, 1, Integer::sum);
            Trace trace = traceToTarget(target, attempt);
            if (trace == null) {
                if (attempt >= MAX_TRACES_PER_TARGET) {
                    exhausted.add(targetId);
                }
                continue;
            }
            accept(trace);
            lastEmittedTarget = targetId;
            candidateCounter++;
            return Optional.of(assembleCandidate());
        }
        if (candidateCounter == 0 && !emptyCandidateEmitted) {
            // Nothing traceable at all: emit the all-unconnected candidate once so the
            // coordinator can evaluate the penalty variant instead of an empty search.
            emptyCandidateEmitted = true;
            candidateCounter++;
            return Optional.of(assembleCandidate());
        }
        return Optional.empty();
    }

    public synchronized void feedback(Evaluation evaluation) {
        if (evaluation == null || evaluation.accepted()) {
            return;
        }
        // A rejected candidate drops its newest trace; the affected target reroutes
        // with the next attempt (different tie option and grid step).
        // A rejected evaluation always corresponds to the candidate returned by the
        // immediately preceding next() call. Do not infer this from map iteration order:
        // target order is flow-sorted, while the latest trace may be any target after retries.
        ObjectId newestTarget = lastEmittedTarget;
        if (newestTarget != null) {
            Trace removed = acceptedTraces.remove(newestTarget);
            connected.put(newestTarget, Boolean.FALSE);
            if (removed != null) {
                String rootKey = rootKey(removed.tie);
                newEdgesPerRoot.merge(rootKey, -1, Integer::sum);
            }
            exhausted.remove(newestTarget);
        }
    }

    private ObjectId nextTraceTarget() {
        for (int i = 0; i < orderedTargets.size(); i++) {
            if (targetCursor >= orderedTargets.size()) {
                targetCursor = 0;
            }
            ObjectId id = orderedTargets.get(targetCursor);
            targetCursor++;
            if (!connected.get(id) && !exhausted.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private Trace traceToTarget(RoutingContext.Target target, int attempt) {
        int optionIndex = (attempt - 1) / 3;
        int stepVariant = (attempt - 1) % 3;
        List<TieOption> options = tieOptions(target, optionIndex);
        if (options.isEmpty()) {
            return null;
        }
        TieOption tie = options.get(Math.min(optionIndex, options.size() - 1));
        List<Coordinate> path = aStar(tie.coordinate, target.point.getCoordinate(),
                target.ownOksPolygonId, targetDiameter.get(target.id), stepVariant);
        if (path == null) {
            return null;
        }
        String rootId = rootId(tie, target);
        return new Trace(target.id, tie, path, rootId);
    }

    private String rootKey(TieOption tie) {
        return tie.kind.name() + ":" + tie.existingObjectId + ":"
                + Math.round(tie.coordinate.x * 1000.0) + ":" + Math.round(tie.coordinate.y * 1000.0);
    }

    private String rootId(TieOption tie, RoutingContext.Target target) {
        return rootKey(tie);
    }

    private int newEdgeBudget(TieOption tie) {
        int existingDegree;
        if (tie.kind == TieKind.EXISTING_CHAMBER) {
            existingDegree = degreeAtChamber(tie.existingObjectId);
        } else {
            existingDegree = 2; // the existing line is split by the new chamber
        }
        int used = newEdgesPerRoot.getOrDefault(rootKey(tie), 0);
        return catalog.maxChamberDegree() - existingDegree - used;
    }

    /** Number of existing line segments meeting this chamber (2 when a line passes through it). */
    private int degreeAtChamber(ObjectId chamberId) {
        RoutingContext.Chamber chamber = null;
        for (RoutingContext.Chamber c : context.existingChambers()) {
            if (c.id.equals(chamberId)) {
                chamber = c;
                break;
            }
        }
        if (chamber == null) {
            return 0;
        }
        int degree = 0;
        double tol = 1e-6;
        for (RoutingContext.HeatLine line : context.existingLines()) {
            Coordinate[] coords = line.line.getCoordinates();
            Point chamberPoint = chamber.point;
            if (chamberPoint.distance(line.line) > tol) {
                continue;
            }
            // A chamber at a line endpoint consumes one adjacency; a chamber
            // lying on the interior splits the existing line and consumes two.
            if (chamberPoint.getCoordinate().distance(coords[0]) <= tol) {
                degree++;
            }
            if (chamberPoint.getCoordinate().distance(coords[coords.length - 1]) <= tol) {
                degree++;
            }
            if (chamberPoint.distance(line.line) <= tol
                    && chamberPoint.getCoordinate().distance(coords[0]) > tol
                    && chamberPoint.getCoordinate().distance(coords[coords.length - 1]) > tol) {
                degree += 2;
            }
        }
        return degree;
    }

    /**
     * Tie options ordered by preference. Chambers with a free degree slot come first
     * (nearest), then line snaps, each converted to an existing chamber when the 10 m
     * rule and the degree limit allow it.
     */
    private List<TieOption> tieOptions(RoutingContext.Target target, int optionIndex) {
        List<TieOption> result = new ArrayList<>();
        Coordinate goal = target.point.getCoordinate();

        for (RoutingContext.Chamber chamber : context.existingChambers()) {
            Coordinate cc = chamber.point.getCoordinate();
            TieOption option = new TieOption(TieKind.EXISTING_CHAMBER, chamber.id, cc, cc.distance(goal));
            if (newEdgeBudget(option) >= 1) {
                result.add(option);
            }
        }
        double radius = snapRadius(optionIndex);
        for (RoutingContext.LineSnap snap : context.snapsOnLines(goal, radius)) {
            if (!insideForbiddenZone(snap.snap, target.ownOksPolygonId)) {
                TieOption option = new TieOption(TieKind.NEW_CHAMBER_ON_LINE, snap.lineId, snap.snap,
                        snap.distanceM * 2.0);
                // The 10 m rule: a suitable existing chamber wins over the raw snap.
                RoutingContext.Chamber near = nearestUsableChamber(snap.snap);
                if (near != null) {
                    TieOption chamberOption = new TieOption(TieKind.EXISTING_CHAMBER, near.id,
                            near.point.getCoordinate(), option.penaltyM);
                    if (newEdgeBudget(chamberOption) >= 1 && !containsOption(result, chamberOption)) {
                        result.add(chamberOption);
                    }
                } else if (newEdgeBudget(option) >= 1) {
                    result.add(option);
                }
            }
        }
        result.sort(Comparator.comparingDouble(o -> o.penaltyM));
        // Deduplicate by root key keeping the order.
        Map<String, TieOption> unique = new LinkedHashMap<>();
        for (TieOption option : result) {
            unique.putIfAbsent(rootKey(option), option);
        }
        return new ArrayList<>(unique.values());
    }

    private boolean containsOption(List<TieOption> options, TieOption probe) {
        String key = rootKey(probe);
        for (TieOption option : options) {
            if (rootKey(option).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private RoutingContext.Chamber nearestUsableChamber(Coordinate snap) {
        RoutingContext.Chamber best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (RoutingContext.Chamber chamber : context.chambersNear(snap, catalog.existingChamberRadiusM())) {
            double d = chamber.point.getCoordinate().distance(snap);
            if (d < bestDistance) {
                TieOption probe = new TieOption(TieKind.EXISTING_CHAMBER, chamber.id, chamber.point.getCoordinate(), 0);
                if (newEdgeBudget(probe) >= 1) {
                    best = chamber;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    private double snapRadius(int optionIndex) {
        switch (Math.min(optionIndex, 3)) {
            case 0: return 400.0;
            case 1: return 1000.0;
            case 2: return 2500.0;
            default: return Double.POSITIVE_INFINITY;
        }
    }

    private boolean insideForbiddenZone(Coordinate c, ObjectId exemptOksPolygonId) {
        for (RoutingContext.Obstacle obstacle : context.obstacles()) {
            if (!obstacle.forbidden) {
                continue;
            }
            if (exemptOksPolygonId != null && obstacle.id.equals(exemptOksPolygonId)) {
                continue;
            }
            if (obstacle.buffered.contains(gf.createPoint(c))) {
                return true;
            }
        }
        return false;
    }

    private double gridStep(int stepVariant) {
        switch (stepVariant) {
            case 0: return 25.0;
            case 1: return 40.0;
            default: return 60.0;
        }
    }

    /** A* over the grid; returns coordinates from the tie point to the goal, endpoints included. */
    private List<Coordinate> aStar(Coordinate start, Coordinate goal, ObjectId exemptOksPolygonId,
                                   int diameterMm, int stepVariant) {
        double step = gridStep(stepVariant);
        Envelope bounds = context.searchBounds(step * 4);
        Map<String, Double> gScore = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();
        Map<String, Coordinate> coords = new HashMap<>();
        Map<String, Double> fScore = new HashMap<>();
        Set<String> open = new HashSet<>();

        String startKey = key(start, step);
        gScore.put(startKey, 0.0);
        coords.put(startKey, start);
        fScore.put(startKey, start.distance(goal));
        open.add(startKey);

        int expansions = 0;
        String currentKey = startKey;
        while (!open.isEmpty()) {
            currentKey = null;
            double bestF = Double.POSITIVE_INFINITY;
            for (String candidate : open) {
                double f = fScore.getOrDefault(candidate, Double.POSITIVE_INFINITY);
                if (f < bestF) {
                    bestF = f;
                    currentKey = candidate;
                }
            }
            if (currentKey == null) {
                return null;
            }
            Coordinate current = coords.get(currentKey);
            if (current.distance(goal) <= step * 1.5) {
                return simplify(reconstruct(cameFrom, currentKey, coords, goal));
            }
            if (++expansions > MAX_EXPANSIONS) {
                return null;
            }
            open.remove(currentKey);

            Coordinate previous = cameFrom.containsKey(currentKey)
                    ? coords.get(cameFrom.get(currentKey)) : null;
            for (Coordinate neighbor : neighbors(current, step, bounds)) {
                if (!moveAllowed(current, neighbor, previous, exemptOksPolygonId, diameterMm)) {
                    continue;
                }
                String neighborKey = key(neighbor, step);
                double tentative = gScore.getOrDefault(currentKey, Double.POSITIVE_INFINITY)
                        + current.distance(neighbor);
                if (tentative < gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY) - 1e-9) {
                    gScore.put(neighborKey, tentative);
                    coords.put(neighborKey, neighbor);
                    cameFrom.put(neighborKey, currentKey);
                    fScore.put(neighborKey, tentative + neighbor.distance(goal));
                    open.add(neighborKey);
                }
            }
        }
        return null;
    }

    private List<Coordinate> neighbors(Coordinate c, double step, Envelope bounds) {
        List<Coordinate> result = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                Coordinate next = new Coordinate(c.x + dx * step, c.y + dy * step);
                if (bounds.contains(next)) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    private boolean moveAllowed(Coordinate from, Coordinate to, Coordinate previous,
                                ObjectId exemptOksPolygonId, int diameterMm) {
        // Turn limit: the change of direction must not exceed 90° (dot product >= 0).
        if (previous != null) {
            double d1x = from.x - previous.x, d1y = from.y - previous.y;
            double d2x = to.x - from.x, d2y = to.y - from.y;
            if (d1x * d2x + d1y * d2y < -1e-9) {
                return false;
            }
        }
        if (context.blockedByForbidden(from, to, exemptOksPolygonId)) {
            return false;
        }
        if (context.violatesSpecialClearance(from, to, diameterMm, exemptOksPolygonId)) {
            return false;
        }
        return !crossesAcceptedTraces(from, to);
    }

    /** New traces must not cross already accepted polylines except at a shared root point. */
    private boolean crossesAcceptedTraces(Coordinate from, Coordinate to) {
        if (acceptedTraces.isEmpty()) {
            return false;
        }
        LineString segment = gf.createLineString(new Coordinate[] {from, to});
        for (Trace trace : acceptedTraces.values()) {
            LineString polyline = gf.createLineString(trace.points.toArray(new Coordinate[0]));
            if (!segment.intersects(polyline)) {
                continue;
            }
            if (from.distance(trace.tie.coordinate) <= COINCIDENT_M) {
                Geometry intersection = segment.intersection(polyline);
                boolean onlyAtRoot = true;
                for (Coordinate c : intersection.getCoordinates()) {
                    if (c.distance(trace.tie.coordinate) > COINCIDENT_M) {
                        onlyAtRoot = false;
                        break;
                    }
                }
                if (onlyAtRoot) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    private List<Coordinate> reconstruct(Map<String, String> cameFrom, String key,
                                         Map<String, Coordinate> coords, Coordinate goal) {
        List<Coordinate> path = new ArrayList<>();
        String current = key;
        while (current != null) {
            path.add(coords.get(current));
            current = cameFrom.get(current);
        }
        Collections.reverse(path);
        path.set(path.size() - 1, goal);
        return path;
    }

    /** Keeps endpoints and direction-change vertices only. */
    private List<Coordinate> simplify(List<Coordinate> path) {
        if (path.size() <= 2) {
            return path;
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i + 1 < path.size(); i++) {
            Coordinate a = result.get(result.size() - 1);
            Coordinate b = path.get(i);
            Coordinate c = path.get(i + 1);
            double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            double dot = (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y);
            if (Math.abs(cross) > 1e-9 || dot < 0) {
                result.add(b);
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    private void accept(Trace trace) {
        acceptedTraces.put(trace.targetId, trace);
        connected.put(trace.targetId, Boolean.TRUE);
        newEdgesPerRoot.merge(rootKey(trace.tie), 1, Integer::sum);
    }

    private RouteCandidate assembleCandidate() {
        RouteCandidate candidate = new RouteCandidate();
        candidate.candidateId = "grid-" + candidateCounter;
        Map<String, Node> nodesById = new LinkedHashMap<>();

        for (ObjectId targetId : orderedTargets) {
            Trace trace = acceptedTraces.get(targetId);
            if (trace == null) {
                candidate.unconnectedPointIds.add(targetId);
                continue;
            }
            Node root = node(nodesById, candidate, trace.tie);
            Node goal = targetNode(nodesById, candidate, target(targetId));
            Edge edge = new Edge();
            edge.id = "e:" + candidate.candidateId + ":t" + targetId;
            edge.fromNodeId = root.id;
            edge.toNodeId = goal.id;
            edge.geometry = gf.createLineString(trace.points.toArray(new Coordinate[0]));
            candidate.edges.add(edge);
        }
        return candidate;
    }

    private Node node(Map<String, Node> nodesById, RouteCandidate candidate, TieOption tie) {
        String id = rootKey(tie);
        Node existing = nodesById.get(id);
        if (existing != null) {
            return existing;
        }
        Node node = new Node();
        node.id = id;
        node.kind = tie.kind == TieKind.EXISTING_CHAMBER ? NodeKind.EXISTING_CHAMBER : NodeKind.NEW_CHAMBER;
        node.geometry = gf.createPoint(tie.coordinate);
        if (tie.kind == TieKind.EXISTING_CHAMBER) {
            node.inputObjectId = tie.existingObjectId;
        }
        nodesById.put(id, node);
        candidate.nodes.add(node);
        Attachment attachment = new Attachment();
        attachment.rootNodeId = id;
        attachment.existingObjectId = tie.existingObjectId;
        candidate.attachments.add(attachment);
        return node;
    }

    private Node targetNode(Map<String, Node> nodesById, RouteCandidate candidate, RoutingContext.Target target) {
        String id = "t:" + target.id;
        Node existing = nodesById.get(id);
        if (existing != null) {
            return existing;
        }
        Node node = new Node();
        node.id = id;
        node.kind = NodeKind.CONNECTION_POINT;
        node.geometry = target.point;
        node.inputObjectId = target.id;
        nodesById.put(id, node);
        candidate.nodes.add(node);
        return node;
    }

    public void close() {
        closed = true;
    }

    /** Quantized grid key of a coordinate for the given step. */
    private static String key(Coordinate c, double step) {
        return Math.round(c.x / step) + ":" + Math.round(c.y / step);
    }
}
