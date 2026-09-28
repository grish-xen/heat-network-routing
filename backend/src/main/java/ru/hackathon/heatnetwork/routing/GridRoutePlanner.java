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
import java.util.PriorityQueue;
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
 * heat_network line where a new chamber will be created. After the nearest tie
 * fails, the planner tries accepted traces before the remaining direct ties: a vertex of a parent polyline
 * becomes a new chamber (the parent edge is split there, keeping each branch node at
 * two split adjacencies plus up to two taps within the four-adjacency limit), and the
 * new edge runs from that chamber to the target. Traces accumulate into one tree per
 * root; the empty candidate (all targets unconnected) is emitted once so the
 * coordinator can still evaluate the penalty variant.</p>
 */
public final class GridRoutePlanner {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GridRoutePlanner.class);

    /** Safety cap on attempts per target; real exhaustion is tracked by the stage machine. */
    static final int MAX_TRACES_PER_TARGET = 100;
    private static final int BASE_MAX_EXPANSIONS = 1_000_000;
    private static final double COINCIDENT_M = 0.001;
    /** Max taps on one branch vertex: 2 (split adjacencies) + 2 taps = 4 total. */
    private static final int MAX_TAPS_PER_VERTEX = 2;

    private final GeometryFactory gf = new GeometryFactory();
    private final Dataset dataset;
    private final RulesCatalog catalog;
    private final RoutingContext context;
    private final Mode mode;
    private final int maxCandidates;
    private final List<ObjectId> orderedTargets;
    private final Map<ObjectId, Integer> targetOrderIndex = new HashMap<>();

    private final Map<ObjectId, Boolean> connected = new LinkedHashMap<>();
    /** Accepted traces in acceptance order; parents always precede their tappers. */
    private final Map<ObjectId, Trace> acceptedTraces = new LinkedHashMap<>();
    private final Map<ObjectId, Integer> attemptCounters = new HashMap<>();
    private final Map<String, Integer> newEdgesPerRoot = new HashMap<>();
    private final Set<ObjectId> exhausted = new HashSet<>();
    private final Map<ObjectId, Integer> targetDiameter = new HashMap<>();
    /** Tap registry: parent target id -> (vertex index -> number of taps). */
    private final Map<ObjectId, Map<Integer, Integer>> tapsByParent = new HashMap<>();

    private int candidateCounter = 0;
    private int targetCursor = 0;
    private boolean emptyCandidateEmitted = false;
    private boolean closed = false;
    /** Target represented by the last candidate returned from next(). */
    private ObjectId lastEmittedTarget;

    private static final class Trace {
        final ObjectId targetId;
        /** Root tie option; null for a tapped trace (branching off an accepted trace). */
        final TieOption tie;
        /** Root→target (direct) or tap point→target (tapped) polyline. */
        final List<Coordinate> points;
        final LineString polyline;
        /** Root key of the tree this trace belongs to. */
        final String rootId;
        /** Parent trace target when tapped; null for a direct trace. */
        final ObjectId tapParentTargetId;
        /** Vertex index on the parent polyline; -1 for a direct trace. */
        final int tapVertexIndex;

        Trace(ObjectId targetId, TieOption tie, List<Coordinate> points, String rootId,
              ObjectId tapParentTargetId, int tapVertexIndex) {
            this.targetId = targetId;
            this.tie = tie;
            this.points = points;
            this.polyline = new GeometryFactory().createLineString(points.toArray(new Coordinate[0]));
            this.rootId = rootId;
            this.tapParentTargetId = tapParentTargetId;
            this.tapVertexIndex = tapVertexIndex;
        }
    }

    private enum TieKind { EXISTING_CHAMBER, NEW_CHAMBER_ON_LINE }

    private static final class TieOption {
        final TieKind kind;
        final ObjectId existingObjectId;
        final Coordinate coordinate;
        final double penaltyM;
        /** Heat network line ID this chamber sits on (for EXISTING_CHAMBER), to exempt its clearance. */
        final ObjectId exemptLineId;

        TieOption(TieKind kind, ObjectId existingObjectId, Coordinate coordinate, double penaltyM, ObjectId exemptLineId) {
            this.kind = kind;
            this.existingObjectId = existingObjectId;
            this.coordinate = coordinate;
            this.penaltyM = penaltyM;
            this.exemptLineId = exemptLineId;
        }
    }

    /** A candidate branch point on an already accepted trace. */
    private static final class TapCandidate {
        final Coordinate point;
        final ObjectId parentTargetId;
        final int vertexIndex;
        final String rootId;
        final String key;

        TapCandidate(Coordinate point, ObjectId parentTargetId, int vertexIndex, String rootId, String key) {
            this.point = point;
            this.parentTargetId = parentTargetId;
            this.vertexIndex = vertexIndex;
            this.rootId = rootId;
            this.key = key;
        }
    }

    /**
     * Per-target attempt machine. Stages: 0 = nearest tie at 25 m (snap radius 400 m),
     * 1 = taps on accepted traces, 2 = remaining ties at 25 m (all radii),
     * 3 = ties at 40 m (all radii), 4 = ties at 60 m (all radii).
     */
    private static final class TargetAttemptState {
        int stage = 0;
        int optionIndex = 0;
        int tieIndex = 0;
        List<TieOption> currentOptions = null;
        final Set<String> triedTaps = new HashSet<>();
        final Set<String> triedTies = new HashSet<>();
        boolean exhaustedAll = false;
    }

    private final Map<ObjectId, TargetAttemptState> attemptStates = new HashMap<>();

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
        for (int i = 0; i < ids.size(); i++) {
            targetOrderIndex.put(ids.get(i), i);
            connected.put(ids.get(i), Boolean.FALSE);
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
            LOG.debug("Search target={} attempt={} accepted={}", targetId.value(), attempt, acceptedTraces.size());
            Trace trace = traceToTarget(target, attempt);
            if (trace == null) {
                TargetAttemptState state = attemptStates.get(targetId);
                if (attempt >= MAX_TRACES_PER_TARGET || (state != null && state.exhaustedAll)) {
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
        if (LOG.isDebugEnabled() && evaluation != null) {
            LOG.debug("Candidate target={} accepted={} diagnostics={}",
                    lastEmittedTarget == null ? null : lastEmittedTarget.value(), evaluation.accepted(),
                    evaluation.diagnostics.stream().map(d -> d.code + ":" + d.message)
                            .collect(java.util.stream.Collectors.toList()));
        }
        if (evaluation == null || evaluation.accepted()) {
            return;
        }
        // A rejected candidate drops its newest trace. Traces that tapped the removed
        // one cascade: their geometry is no longer connected to a root.
        // The rejected evaluation always corresponds to the candidate returned by the
        // immediately preceding next() call. Do not infer this from map iteration order:
        // target order is flow-sorted, while the latest trace may be any target after retries.
        ObjectId newestTarget = lastEmittedTarget;
        if (newestTarget == null) {
            return;
        }
        Set<ObjectId> removed = new HashSet<>();
        removeTrace(newestTarget, newestTarget, removed);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ObjectId tid : new ArrayList<>(acceptedTraces.keySet())) {
                Trace s = acceptedTraces.get(tid);
                if (s != null && s.tapParentTargetId != null && removed.contains(s.tapParentTargetId)) {
                    removeTrace(tid, newestTarget, removed);
                    changed = true;
                }
            }
        }
    }

    /** Removes one accepted trace with all bookkeeping; cascading is done by the caller. */
    private void removeTrace(ObjectId targetId, ObjectId newestTarget, Set<ObjectId> removed) {
        Trace removedTrace = acceptedTraces.remove(targetId);
        if (removedTrace == null) {
            return;
        }
        removed.add(targetId);
        connected.put(targetId, Boolean.FALSE);
        if (removedTrace.tie != null) {
            newEdgesPerRoot.merge(removedTrace.rootId, -1, Integer::sum);
        }
        if (removedTrace.tapParentTargetId != null) {
            Map<Integer, Integer> used = tapsByParent.get(removedTrace.tapParentTargetId);
            if (used != null) {
                used.merge(removedTrace.tapVertexIndex, -1, Integer::sum);
                if (used.getOrDefault(removedTrace.tapVertexIndex, 0) <= 0) {
                    used.remove(removedTrace.tapVertexIndex);
                }
                if (used.isEmpty()) {
                    tapsByParent.remove(removedTrace.tapParentTargetId);
                }
            }
        }
        tapsByParent.remove(targetId);
        exhausted.remove(targetId);
        if (!targetId.equals(newestTarget)) {
            // The cascade victim is not at fault: restart its search from scratch.
            attemptStates.remove(targetId);
        }
        // The newest target keeps its advanced attempt state: the rejected geometry
        // must not be retried, but the remaining combinations stay valid.
    }

    private ObjectId nextTraceTarget() {
        for (int i = 0; i < orderedTargets.size(); i++) {
            if (targetCursor >= orderedTargets.size()) {
                targetCursor = 0;
            }
            ObjectId id = orderedTargets.get(targetCursor);
            targetCursor++;
            Boolean conn = connected.get(id);
            boolean isConnected = conn != null && conn;
            if (!isConnected && !exhausted.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static double stepForStage(int stage) {
        switch (stage) {
            case 3: return 40.0;
            case 4: return 60.0;
            default: return 25.0;
        }
    }

    private Trace traceToTarget(RoutingContext.Target target, int attempt) {
        TargetAttemptState state = attemptStates.computeIfAbsent(target.id, k -> new TargetAttemptState());
        Coordinate goal = target.point.getCoordinate();
        int diameter = targetDiameter.get(target.id);
        while (!state.exhaustedAll) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Route search interrupted");
            }
            if (state.stage == 1) {
                TapCandidate tap = nextTap(target, state);
                if (tap == null) {
                    state.stage = 2;
                    state.optionIndex = 0;
                    state.tieIndex = 0;
                    state.currentOptions = null;
                    continue;
                }
                List<Coordinate> path = aStar(tap.point, goal, target.ownOksPolygonId, diameter,
                        25.0, null, tap.parentTargetId, tap.point);
                if (path == null) {
                    continue;
                }
                path = straightenOwnOksApproach(path, target);
                if (!validOwnOksApproach(path, target)) {
                    continue;
                }
                return new Trace(target.id, null, path, tap.rootId, tap.parentTargetId, tap.vertexIndex);
            }
            double step = stepForStage(state.stage);
            if (state.currentOptions == null) {
                state.currentOptions = tieOptions(target, state.optionIndex);
            }
            if (state.tieIndex >= state.currentOptions.size() || (state.stage == 0 && state.tieIndex >= 1)) {
                int maxOptionIndex = state.stage == 0 ? 0 : 3;
                state.optionIndex++;
                state.tieIndex = 0;
                state.currentOptions = null;
                if (state.optionIndex > maxOptionIndex) {
                    state.stage++;
                    if (state.stage > 4) {
                        state.exhaustedAll = true;
                        break;
                    }
                    state.optionIndex = 0;
                    state.tieIndex = 0;
                }
                continue;
            }
            TieOption tie = state.currentOptions.get(state.tieIndex);
            state.tieIndex++;
            // Wider radii contain the earlier choices. Retry only on a different
            // grid, and recheck capacity because other targets may have connected.
            if (newEdgeBudget(tie) < 1 || !state.triedTies.add(step + ":" + rootKey(tie))) continue;
            List<Coordinate> path = aStar(tie.coordinate, goal, target.ownOksPolygonId, diameter,
                    step, tie.exemptLineId, null, null);
            if (path == null) {
                // Inline advance: same-target retry loop instead of returning to next().
                continue;
            }
            path = straightenOwnOksApproach(path, target);
            if (!validOwnOksApproach(path, target)) {
                continue;
            }
            return new Trace(target.id, tie, path, rootKey(tie), null, -1);
        }
        return null;
    }

    /** Nearest untried tap point on any accepted trace; marks it tried. */
    private TapCandidate nextTap(RoutingContext.Target target, TargetAttemptState state) {
        List<TapCandidate> taps = new ArrayList<>();
        Coordinate goal = target.point.getCoordinate();
        for (Trace parentTrace : acceptedTraces.values()) {
            if (parentTrace.targetId.equals(target.id)) {
                continue;
            }
            Map<Integer, Integer> used = tapsByParent.get(parentTrace.targetId);
            List<Coordinate> pts = parentTrace.points;
            for (int i = 1; i + 1 < pts.size(); i++) {
                // Exclude the parent root (index 0) and the parent target leaf (last).
                if (used != null && used.getOrDefault(i, 0) >= MAX_TAPS_PER_VERTEX) {
                    continue;
                }
                String key = targetOrderIndex.get(parentTrace.targetId) + ":" + i;
                if (state.triedTaps.contains(key)) {
                    continue;
                }
                Coordinate v = pts.get(i);
                if (insideForbiddenZone(v, target.ownOksPolygonId)) {
                    continue;
                }
                taps.add(new TapCandidate(v, parentTrace.targetId, i, parentTrace.rootId, key));
            }
        }
        if (taps.isEmpty()) {
            return null;
        }
        taps.sort(Comparator.comparingDouble(t -> t.point.distance(goal)));
        TapCandidate best = taps.get(0);
        state.triedTaps.add(best.key);
        return best;
    }

    private boolean validOwnOksApproach(List<Coordinate> path, RoutingContext.Target target) {
        if (target.ownOksPolygon == null || path.size() < 2) {
            return true;
        }
        int firstInside = -1;
        for (int i = 0; i < path.size(); i++) {
            if (target.ownOksPolygon.covers(gf.createPoint(path.get(i)))) {
                firstInside = i;
                break;
            }
        }
        if (firstInside < 0) {
            return true;
        }
        if (firstInside == 0) {
            return false;
        }
        // The first point inside the own polygon must be followed only by a
        // straight segment to the target; no turn or re-entry is permitted.
        // Use a tolerance proportional to grid step (approx 1% of 25m = 0.25m)
        // to account for grid quantization.
        Coordinate entry = path.get(firstInside);
        Coordinate targetPoint = target.point.getCoordinate();
        double tolerance = 0.5; // meters, allows small grid quantization deviation
        for (int i = firstInside; i < path.size() - 1; i++) {
            Coordinate a = path.get(i);
            Coordinate b = path.get(i + 1);
            double cross = (targetPoint.x - entry.x) * (b.y - a.y)
                    - (targetPoint.y - entry.y) * (b.x - a.x);
            if (Math.abs(cross) > tolerance) {
                return false;
            }
        }
        return path.get(path.size() - 1).distance(targetPoint) <= 0.001;
    }

    /** Replaces the portion of the path inside own OKS polygon with a straight segment. */
    private List<Coordinate> straightenOwnOksApproach(List<Coordinate> path, RoutingContext.Target target) {
        if (target.ownOksPolygon == null || path.size() < 2) {
            return path;
        }
        int firstInside = -1;
        for (int i = 0; i < path.size(); i++) {
            if (target.ownOksPolygon.covers(gf.createPoint(path.get(i)))) {
                firstInside = i;
                break;
            }
        }
        if (firstInside <= 0 || firstInside >= path.size() - 1) {
            return path;
        }
        // Replace everything from entry point to target with a straight segment
        List<Coordinate> result = new ArrayList<>(firstInside + 2);
        for (int i = 0; i <= firstInside; i++) {
            result.add(path.get(i));
        }
        result.add(target.point.getCoordinate());
        return result;
    }

    private String rootKey(TieOption tie) {
        return tie.kind.name() + ":" + tie.existingObjectId + ":"
                + Math.round(tie.coordinate.x * 1000.0) + ":" + Math.round(tie.coordinate.y * 1000.0);
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
            ObjectId exemptLineId = findHeatNetworkLineForChamber(cc);
            TieOption option = new TieOption(TieKind.EXISTING_CHAMBER, chamber.id, cc, cc.distance(goal), exemptLineId);
            if (newEdgeBudget(option) >= 1) {
                result.add(option);
            }
        }
        double radius = snapRadius(optionIndex);
        for (RoutingContext.LineSnap snap : context.snapsOnLines(goal, radius)) {
            if (!insideForbiddenZone(snap.snap, target.ownOksPolygonId)) {
                TieOption option = new TieOption(TieKind.NEW_CHAMBER_ON_LINE, snap.lineId, snap.snap,
                        snap.distanceM * 2.0, snap.lineId);
                // The 10 m rule: a suitable existing chamber wins over the raw snap.
                RoutingContext.Chamber near = nearestUsableChamber(snap.snap);
                if (near != null) {
                    ObjectId nearExemptLineId = findHeatNetworkLineForChamber(near.point.getCoordinate());
                    TieOption chamberOption = new TieOption(TieKind.EXISTING_CHAMBER, near.id,
                            near.point.getCoordinate(), option.penaltyM, nearExemptLineId);
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

    /** Finds the heat_network line that a chamber coordinate lies on (within 1m). */
    private ObjectId findHeatNetworkLineForChamber(Coordinate chamberCoord) {
        Point chamberPoint = gf.createPoint(chamberCoord);
        for (RoutingContext.HeatLine line : context.existingLines()) {
            if (line.line.distance(chamberPoint) <= 1.0) {
                return line.id;
            }
        }
        return null;
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
                TieOption probe = new TieOption(TieKind.EXISTING_CHAMBER, chamber.id, chamber.point.getCoordinate(), 0, findHeatNetworkLineForChamber(chamber.point.getCoordinate()));
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

    /** A* over the grid; returns coordinates from the start point to the goal, endpoints included. */
    private List<Coordinate> aStar(Coordinate start, Coordinate goal, ObjectId exemptOksPolygonId,
                                   int diameterMm, double step, ObjectId exemptLineId,
                                   ObjectId parentTargetId, Coordinate tapPoint) {
        long began = System.nanoTime();
        LOG.debug("A* start={} goal={} step={} branch={}", start, goal, step, parentTargetId != null);
        List<Coordinate> result = searchGrid(start, goal, exemptOksPolygonId, diameterMm, step, exemptLineId, parentTargetId, tapPoint);
        LOG.debug("A* vertices={} elapsedMs={}", result == null ? 0 : result.size(), (System.nanoTime() - began) / 1_000_000);
        return result;
    }

    private List<Coordinate> searchGrid(Coordinate start, Coordinate goal, ObjectId exemptOksPolygonId,
                                   int diameterMm, double step, ObjectId exemptLineId,
                                   ObjectId parentTargetId, Coordinate tapPoint) {
        // Search bounds: envelope of start/goal/chambers/lines, padded to allow routing around
        // large restrictions (rivers, parks). Use 3x straight-line distance (capped) instead of 10x
        // to avoid excessively large search areas that cause timeouts.
        double straightDist = start.distance(goal);
        double padM = Math.max(500.0, Math.min(5000.0, straightDist * 3.0));
        Envelope bounds = context.searchBounds(padM);
        Map<String, Double> gScore = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();
        Map<String, Coordinate> coords = new HashMap<>();

        String startKey = key(start, step) + ":start";
        gScore.put(startKey, 0.0);
        coords.put(startKey, start);

        // PriorityQueue for O(log n) min extraction instead of O(n) linear scan.
        PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator
                .comparingDouble((OpenEntry entry) -> entry.estimate)
                .thenComparingLong(entry -> entry.sequence));
        long sequence = 0;
        open.add(new OpenEntry(startKey, 0.0, start.distance(goal), sequence++));

        int expansions = 0;
        // Scale expansion limit by inverse step: finer grid has more nodes per meter.
        // Also scale by straight-line distance (capped) so distant targets get more budget
        // but not excessively more. Base limit applies at 25 m step for 1 km distance.
        double distanceFactor = Math.max(0.5, Math.min(5.0, straightDist / 1000.0));
        int maxExpansions = (int) (BASE_MAX_EXPANSIONS * (25.0 / step) * distanceFactor);
        while (!open.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Route search interrupted");
            }
            OpenEntry entry = open.poll();
            String currentKey = entry.key;
            // Queue priorities are immutable. An improved route leaves an old entry
            // behind; discard it without repeating expensive spatial checks.
            if (entry.distance > gScore.get(currentKey) + 1e-9) continue;
            Coordinate current = coords.get(currentKey);
            Coordinate previous = cameFrom.containsKey(currentKey)
                    ? coords.get(cameFrom.get(currentKey)) : null;
            if (current.distance(goal) <= step * 1.5
                    && moveAllowed(current, goal, previous, exemptOksPolygonId, diameterMm,
                            exemptLineId, parentTargetId, tapPoint)) {
                return simplify(reconstruct(cameFrom, currentKey, coords, goal));
            }
            if (++expansions > maxExpansions) {
                return null;
            }

            for (Coordinate neighbor : neighbors(current, step, bounds)) {
                // Turns depend on the incoming direction. Reaching a cell from
                // another side is a distinct state and may permit the only exit.
                String neighborKey = key(neighbor, step) + ":"
                        + (int) Math.signum(neighbor.x - current.x) + ":"
                        + (int) Math.signum(neighbor.y - current.y);
                double tentative = entry.distance + current.distance(neighbor);
                if (tentative >= gScore.getOrDefault(neighborKey, Double.POSITIVE_INFINITY) - 1e-9) {
                    continue;
                }
                if (!moveAllowed(current, neighbor, previous, exemptOksPolygonId, diameterMm, exemptLineId,
                        parentTargetId, tapPoint)) {
                    continue;
                }
                gScore.put(neighborKey, tentative);
                coords.put(neighborKey, neighbor);
                cameFrom.put(neighborKey, currentKey);
                open.add(new OpenEntry(neighborKey, tentative, tentative + neighbor.distance(goal), sequence++));
            }
        }
        return null;
    }

    private static final class OpenEntry {
        final String key;
        final double distance;
        final double estimate;
        final long sequence;

        OpenEntry(String key, double distance, double estimate, long sequence) {
            this.key = key;
            this.distance = distance;
            this.estimate = estimate;
            this.sequence = sequence;
        }
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
                                ObjectId exemptOksPolygonId, int diameterMm, ObjectId exemptLineId,
                                ObjectId parentTargetId, Coordinate tapPoint) {
        // Turn limit: the change of direction must not exceed 90° (dot product >= 0).
        if (previous != null) {
            double d1x = from.x - previous.x, d1y = from.y - previous.y;
            double d2x = to.x - from.x, d2y = to.y - from.y;
            if (d1x * d2x + d1y * d2y < -1e-9) {
                return false;
            }
        }
        // If moving from an existing chamber, also exempt all heat_network lines that touch that chamber.
        Set<ObjectId> exemptLines = new HashSet<>();
        if (exemptLineId != null) {
            exemptLines.add(exemptLineId);
        }
        for (RoutingContext.Chamber chamber : context.existingChambers()) {
            if (chamber.point.getCoordinate().equals2D(from)) {
                for (RoutingContext.HeatLine line : context.existingLines()) {
                    if (line.line.distance(chamber.point) <= 1.0) {
                        exemptLines.add(line.id);
                    }
                }
                break;
            }
        }
        if (context.blockedByForbidden(from, to, exemptOksPolygonId, exemptLines)) {
            return false;
        }
        if (context.violatesSpecialClearance(from, to, diameterMm, exemptOksPolygonId, exemptLines)) {
            return false;
        }
        return !crossesAcceptedTraces(from, to, parentTargetId, tapPoint);
    }

    /**
     * New traces must not cross already accepted polylines except at a shared root
     * point — or, for a tap search, at the tap point on the parent trace.
     */
    private boolean crossesAcceptedTraces(Coordinate from, Coordinate to,
                                          ObjectId parentTargetId, Coordinate tapPoint) {
        if (acceptedTraces.isEmpty()) {
            return false;
        }
        LineString segment = gf.createLineString(new Coordinate[] {from, to});
        for (Trace trace : acceptedTraces.values()) {
            LineString polyline = trace.polyline;
            if (!segment.getEnvelopeInternal().intersects(polyline.getEnvelopeInternal())) continue;
            if (!segment.intersects(polyline)) {
                continue;
            }
            if (parentTargetId != null && trace.targetId.equals(parentTargetId)) {
                // Touching the parent polyline is allowed only at the tap point itself.
                Geometry intersection = segment.intersection(polyline);
                boolean onlyAtTap = true;
                for (Coordinate c : intersection.getCoordinates()) {
                    if (c.distance(tapPoint) > COINCIDENT_M) {
                        onlyAtTap = false;
                        break;
                    }
                }
                if (onlyAtTap) {
                    continue;
                }
                return true;
            }
            Coordinate anchor = trace.tie != null ? trace.tie.coordinate : trace.points.get(0);
            if (from.distance(anchor) <= COINCIDENT_M) {
                Geometry intersection = segment.intersection(polyline);
                boolean onlyAtAnchor = true;
                for (Coordinate c : intersection.getCoordinates()) {
                    if (c.distance(anchor) > COINCIDENT_M) {
                        onlyAtAnchor = false;
                        break;
                    }
                }
                if (onlyAtAnchor) {
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
        // Preserve the checked grid path. Replacing its last point with the goal
        // would create an unchecked shortcut from the preceding vertex.
        if (!path.get(path.size() - 1).equals2D(goal) || path.size() == 1) {
            path.add(goal);
        }
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
        // Only direct traces occupy a root adjacency; a tap edge hangs off a branch
        // chamber created by splitting the parent polyline, not off the root.
        if (trace.tie != null) {
            newEdgesPerRoot.merge(trace.rootId, 1, Integer::sum);
        }
        if (trace.tapParentTargetId != null) {
            tapsByParent.computeIfAbsent(trace.tapParentTargetId, k -> new LinkedHashMap<>())
                    .merge(trace.tapVertexIndex, 1, Integer::sum);
        }
    }

    private RouteCandidate assembleCandidate() {
        RouteCandidate candidate = new RouteCandidate();
        candidate.candidateId = "grid-" + candidateCounter;
        Map<String, Node> nodesById = new LinkedHashMap<>();
        int[] edgeCounter = {0};
        // Acceptance order guarantees a parent trace is assembled before its tappers.
        for (Trace trace : acceptedTraces.values()) {
            RoutingContext.Target t = target(trace.targetId);
            List<Coordinate> pts = trace.points;
            Node fromNode;
            if (trace.tie == null) {
                String branchId = branchNodeId(trace.tapParentTargetId, trace.tapVertexIndex);
                fromNode = nodesById.get(branchId);
                if (fromNode == null) {
                    fromNode = branchNode(nodesById, candidate, branchId, pts.get(0));
                }
            } else {
                fromNode = node(nodesById, candidate, trace.tie);
            }
            // Split the polyline at registered tap vertices; each branch point becomes
            // a new chamber between the prefix and suffix edges.
            Map<Integer, Integer> taps = tapsByParent.get(trace.targetId);
            int prev = 0;
            if (taps != null && !taps.isEmpty()) {
                List<Integer> indices = new ArrayList<>(taps.keySet());
                Collections.sort(indices);
                for (int vIdx : indices) {
                    if (vIdx <= prev || vIdx >= pts.size() - 1) {
                        continue;
                    }
                    String bid = branchNodeId(trace.targetId, vIdx);
                    Node bn = nodesById.get(bid);
                    if (bn == null) {
                        bn = branchNode(nodesById, candidate, bid, pts.get(vIdx));
                    }
                    emitEdge(candidate, edgeCounter[0]++, fromNode, bn, pts.subList(prev, vIdx + 1));
                    fromNode = bn;
                    prev = vIdx;
                }
            }
            Node goalNode = targetNode(nodesById, candidate, t);
            emitEdge(candidate, edgeCounter[0]++, fromNode, goalNode, pts.subList(prev, pts.size()));
        }
        for (ObjectId id : orderedTargets) {
            if (!acceptedTraces.containsKey(id)) {
                candidate.unconnectedPointIds.add(id);
            }
        }
        return candidate;
    }

    private void emitEdge(RouteCandidate candidate, int seq, Node from, Node to, List<Coordinate> coords) {
        Edge edge = new Edge();
        edge.id = "e:" + candidate.candidateId + ":" + seq;
        edge.fromNodeId = from.id;
        edge.toNodeId = to.id;
        edge.geometry = gf.createLineString(coords.toArray(new Coordinate[0]));
        candidate.edges.add(edge);
    }

    private String branchNodeId(ObjectId parentTargetId, int vertexIndex) {
        return "b:" + targetOrderIndex.get(parentTargetId) + ":" + vertexIndex;
    }

    private Node branchNode(Map<String, Node> nodesById, RouteCandidate candidate, String id, Coordinate at) {
        Node node = new Node();
        node.id = id;
        node.kind = NodeKind.NEW_CHAMBER;
        node.geometry = gf.createPoint(at);
        nodesById.put(id, node);
        candidate.nodes.add(node);
        return node;
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
