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
import org.locationtech.jts.operation.distance.DistanceOp;
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
 * obstacles and stays outside special-pass clearance zones. The target's own OKS
 * polygon is an ordinary obstacle; the search ends in the corridor of one straight
 * final segment through its nearest reachable boundary (section 2.2, see
 * docs/OWN_OKS_APPROACH.md). Turn angles above 90° are
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
    /** Cheapest insertion: branch searches compared with a found direct trace, nearest taps first. */
    private static final int MAX_INSERTION_TAPS = 4;
    /** Final approach into the own OKS polygon: entry sampling, alternatives and the outward corridor. */
    private static final double ENTRY_SAMPLE_M = 1.0;
    private static final double ENTRY_SEPARATION_M = 5.0;
    private static final int MAX_APPROACHES = 3;
    private static final double APPROACH_STEP_M = 0.25;
    private static final double MAX_APPROACH_OFFSET_M = 60.0;
    private static final double CORRIDOR_SPACING_M = 2.0;
    /** An approach corridor counts as reachable when the grid leads this far away from the target. */
    private static final double REACH_RADIUS_M = 200.0;
    private static final int REACH_BUDGET = 20_000;
    private static final double REACH_STEP_M = 25.0;
    /** Tap searches are local: a smaller budget keeps hopeless branch attempts cheap. */
    private static final int TAP_MAX_EXPANSIONS = 150_000;
    /**
     * With an approach corridor, repeated failures at one grid step mean the corridor is out of reach of
     * that grid, not that the next tie will help: move on to the next stage instead of trying every tie.
     */
    private static final int MAX_APPROACH_FAILURES_PER_STAGE = 6;
    private static final int MAX_APPROACH_TAP_FAILURES = 10;

    private final GeometryFactory gf = new GeometryFactory();
    private final Dataset dataset;
    private final RulesCatalog catalog;
    private final RoutingContext context;
    private final Mode mode;
    private final int maxCandidates;
    private final Strategy strategy;

    /**
     * Search strategy selected by {@code SearchOptions.seed} (modulo the number of strategies). Each one
     * yields a substantively different scheme for the variant list (section 6 of the appendix):
     * JOINT connects consumers into shared trees, largest flow first; LINE_TIES connects at the nearest
     * points of existing lines rather than at farther existing chambers (the 10 m rule still turns a
     * nearby point into that chamber); NEAR_FIRST grows the network outwards, connecting the consumers
     * nearest to the existing network first, so other consumers branch from different trunks.
     */
    enum Strategy {
        JOINT, LINE_TIES, NEAR_FIRST
    }

    /** Order in which consumers are connected. */
    enum Order {
        /** Larger flow first: main corridors are laid by the bigger branches. */
        FLOW,
        /** Nearest to the existing network first (larger flow breaks ties). */
        NEAR_FIRST,
        /** Farthest from the existing network first: long trunks come first, nearer consumers branch off. */
        FAR_FIRST,
        /** A fixed pseudo-random order derived from the seed. */
        SHUFFLED
    }

    /**
     * One start of the multi-start search, selected by {@code SearchOptions.seed}. Seeds 0, 3 and 5 are the
     * three base strategies. The others connect every consumer the cheapest way: a direct trace from the
     * network or a branch from an accepted trace, whichever costs less in terms of the score; they differ in
     * the consumer order. The coordinator keeps the best distinct schemes of all starts; the order of the seeds
     * puts the starts that most often give the cheapest scheme first, for machines with few processors.
     */
    static final class Start {
        final Strategy strategy;
        final Order order;
        final boolean cheapestInsertion;
        final long seed;

        private Start(Strategy strategy, Order order, boolean cheapestInsertion, long seed) {
            this.strategy = strategy;
            this.order = order;
            this.cheapestInsertion = cheapestInsertion;
            this.seed = seed;
        }

        static Start of(long seed) {
            long s = Math.max(0, seed);
            switch ((int) Math.min(s, 6)) {
                case 0: return new Start(Strategy.JOINT, Order.FLOW, false, s);
                case 1: return new Start(Strategy.JOINT, Order.FAR_FIRST, true, s);
                case 2: return new Start(Strategy.JOINT, Order.NEAR_FIRST, true, s);
                case 3: return new Start(Strategy.LINE_TIES, Order.FLOW, false, s);
                case 4: return new Start(Strategy.JOINT, Order.FLOW, true, s);
                case 5: return new Start(Strategy.NEAR_FIRST, Order.NEAR_FIRST, false, s);
                default: return new Start(Strategy.JOINT, Order.SHUFFLED, true, s);
            }
        }
    }

    /**
     * The result must not depend on the order of objects in the input file or on the format of their IDs:
     * the planner works on the objects in the order of their geometry (then flow, restriction type and ID
     * for coinciding geometry).
     */
    private static final Comparator<InputObject> CANONICAL_ORDER = Comparator
            .comparing((InputObject o) -> o.type)
            .thenComparing((a, b) -> a.geometry == null || b.geometry == null
                    ? Boolean.compare(a.geometry == null, b.geometry == null) : a.geometry.compareTo(b.geometry))
            .thenComparing(o -> o.flowTph, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(o -> o.restrictionType, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(o -> String.valueOf(o.id == null ? null : o.id.value()));

    private final Start start;
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
    private boolean exhaustionReported = false;
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
        /** Index of the way into the own OKS polygon being tried (see findApproaches). */
        int approachIndex = 0;
        /** Failed searches towards the approach corridor in the current stage. */
        int stageFailures = 0;
    }

    private final Map<ObjectId, TargetAttemptState> attemptStates = new HashMap<>();
    /** Admissible ways into the own OKS polygon per target, nearest entry first. */
    private final Map<ObjectId, List<Approach>> approaches = new HashMap<>();

    public GridRoutePlanner(Dataset dataset, SearchOptions options, RulesCatalog catalog) {
        this.dataset = dataset;
        this.catalog = catalog == null ? RulesCatalog.loadDefault() : catalog;
        this.mode = options.mode;
        if (mode == null) throw new IllegalArgumentException("Search mode is required");
        this.maxCandidates = Math.max(1, options.maxCandidates);
        this.start = Start.of(options.seed);
        this.strategy = start.strategy;

        List<InputObject> objects = new ArrayList<>();
        for (InputType type : InputType.values()) {
            try (java.util.stream.Stream<InputObject> stream = dataset.objects(type)) {
                stream.forEach(objects::add);
            }
        }
        objects.sort(CANONICAL_ORDER);
        this.context = RoutingContext.build(objects, this.catalog, guessSearchDiameter(objects, this.catalog));

        for (RoutingContext.Target target : context.targets()) {
            targetDiameter.put(target.id, estimateDiameter(target.flowTph));
        }
        List<ObjectId> ids = new ArrayList<>();
        for (RoutingContext.Target target : context.targets()) {
            ids.add(target.id);
        }
        // Deterministic for a start: see Order. Larger flow breaks ties.
        Map<ObjectId, Double> networkDistance = new HashMap<>();
        for (RoutingContext.Target target : context.targets()) {
            double nearest = Double.POSITIVE_INFINITY;
            for (RoutingContext.HeatLine line : context.existingLines()) {
                nearest = Math.min(nearest, line.line.distance(target.point));
            }
            networkDistance.put(target.id, nearest);
        }
        ids.sort((a, b) -> {
            if (start.order == Order.NEAR_FIRST || start.order == Order.FAR_FIRST) {
                int byDistance = Double.compare(networkDistance.get(a), networkDistance.get(b));
                if (byDistance != 0) {
                    return start.order == Order.NEAR_FIRST ? byDistance : -byDistance;
                }
            }
            RoutingContext.Target ta = target(a);
            RoutingContext.Target tb = target(b);
            return Double.compare(tb.flowTph, ta.flowTph);
        });
        if (start.order == Order.SHUFFLED) {
            Collections.shuffle(ids, new java.util.Random(start.seed));
        }
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
        if (!exhausted.isEmpty() && !exhaustionReported) {
            exhaustionReported = true;
            emptyCandidateEmitted = true;
            RouteCandidate finalCandidate = assembleCandidate();
            ru.hackathon.heatnetwork.model.Model.Diagnostic diagnostic = new ru.hackathon.heatnetwork.model.Model.Diagnostic();
            diagnostic.code = "SEARCH_BUDGET_EXHAUSTED";
            diagnostic.message = "Исчерпаны ограниченные попытки планового поиска для оставшихся точек; это не доказывает невозможность подключения.";
            diagnostic.candidateId = finalCandidate.candidateId;
            finalCandidate.diagnostics.add(diagnostic);
            lastEmittedTarget = null;
            return Optional.of(finalCandidate);
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
        // Section 2.2: the own OKS polygon is entered only by one straight segment from its boundary
        // nearest to the target. The grid search ends in the outward corridor of that segment and treats
        // the own polygon as an ordinary forbidden obstacle; farther entries are tried only after the
        // nearer ones (see findApproaches).
        List<Approach> ways = approaches(target);
        if (target.ownOksPolygon != null && state.approachIndex >= ways.size()) {
            state.exhaustedAll = true;
            return null;
        }
        while (target.ownOksPolygon != null && state.approachIndex < ways.size()
                && !approachOpen(ways.get(state.approachIndex), diameter)) {
            state.approachIndex++;
        }
        if (target.ownOksPolygon != null && state.approachIndex >= ways.size()) {
            state.exhaustedAll = true;
            return null;
        }
        Approach approach = target.ownOksPolygon == null ? null : ways.get(state.approachIndex);
        List<Coordinate> searchGoals = approach == null ? Collections.singletonList(goal) : approach.corridor;
        Coordinate after = approach == null ? null : goal;
        while (!state.exhaustedAll) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("Route search interrupted");
            }
            if (state.stage == 1) {
                TapCandidate tap = approach != null && state.stageFailures >= MAX_APPROACH_TAP_FAILURES
                        ? null : nextTap(target, state);
                if (tap == null) {
                    state.stageFailures = 0;
                    state.stage = 2;
                    state.optionIndex = 0;
                    state.tieIndex = 0;
                    state.currentOptions = null;
                    continue;
                }
                List<Coordinate> path = aStar(tap.point, searchGoals, null, diameter,
                        25.0, null, tap.parentTargetId, tap.point,
                        acceptedTraces.get(tap.parentTargetId).points.get(tap.vertexIndex - 1), after);
                if (path == null) {
                    state.stageFailures++;
                    continue;
                }
                path = withFinalApproach(path, goal, approach);
                if (!validOwnOksApproach(path, target) || finalSegmentCrossesTraces(path, tap.parentTargetId, tap.point)) {
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
                    state.stageFailures = 0;
                    if (state.stage > 4) {
                        int nextApproach = state.approachIndex + 1;
                        while (approach != null && nextApproach < ways.size()
                                && !approachOpen(ways.get(nextApproach), diameter)) {
                            nextApproach++;
                        }
                        if (approach != null && nextApproach < ways.size()) {
                            // No route reaches this entry: try the next nearest way into the building.
                            state.approachIndex = nextApproach;
                            state.stage = 0;
                            state.triedTaps.clear();
                            state.triedTies.clear();
                            approach = ways.get(state.approachIndex);
                            searchGoals = approach.corridor;
                            state.optionIndex = 0;
                            state.tieIndex = 0;
                            continue;
                        }
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
            List<Coordinate> path = aStar(tie.coordinate, searchGoals, null, diameter,
                    step, tie.exemptLineId, null, null, null, after);
            if (path == null) {
                // Inline advance: same-target retry loop instead of returning to next().
                if (approach != null && ++state.stageFailures >= MAX_APPROACH_FAILURES_PER_STAGE) {
                    state.optionIndex = state.stage == 0 ? 0 : 3;
                    state.tieIndex = Integer.MAX_VALUE;
                }
                continue;
            }
            path = withFinalApproach(path, goal, approach);
            if (!validOwnOksApproach(path, target) || finalSegmentCrossesTraces(path, null, null)) {
                continue;
            }
            if (start.cheapestInsertion && state.stage == 0 && !acceptedTraces.isEmpty()) {
                double directCost = length(path) * metrePrice(diameter) + (tie.kind == TieKind.EXISTING_CHAMBER
                        ? catalog.tieInCostRub() : catalog.chamberCost(diameter));
                Trace branch = cheaperTap(target, state, approach, searchGoals, after, diameter, directCost);
                if (branch != null) {
                    // The direct trace stays available to the later stages if the branch is rejected.
                    state.triedTies.remove(step + ":" + rootKey(tie));
                    return branch;
                }
            }
            return new Trace(target.id, tie, path, rootKey(tie), null, -1);
        }
        return null;
    }

    /**
     * Cheapest insertion: a branch from an accepted trace replaces the found direct trace when it costs less
     * (pipe, chamber and the length term of the score). The nearest untried taps are searched while even their
     * straight distance could still beat the best price; only the chosen tap is marked tried, the others stay
     * available to the tap stage.
     */
    private Trace cheaperTap(RoutingContext.Target target, TargetAttemptState state, Approach approach,
                             List<Coordinate> searchGoals, Coordinate after, int diameter, double directCost) {
        Coordinate goal = target.point.getCoordinate();
        double price = metrePrice(diameter);
        double chamber = catalog.chamberCost(diameter);
        double bestCost = directCost;
        Trace best = null;
        TapCandidate bestTap = null;
        int searched = 0;
        for (TapCandidate tap : tapCandidates(target, state)) {
            if (searched >= MAX_INSERTION_TAPS || tap.point.distance(goal) * price + chamber >= bestCost) {
                break;
            }
            searched++;
            Coordinate previous = acceptedTraces.get(tap.parentTargetId).points.get(tap.vertexIndex - 1);
            List<Coordinate> path = aStar(tap.point, searchGoals, null, diameter, 25.0, null,
                    tap.parentTargetId, tap.point, previous, after);
            if (path == null) {
                continue;
            }
            path = withFinalApproach(path, goal, approach);
            if (!validOwnOksApproach(path, target) || finalSegmentCrossesTraces(path, tap.parentTargetId, tap.point)) {
                continue;
            }
            double cost = length(path) * price + chamber;
            if (cost < bestCost) {
                bestCost = cost;
                bestTap = tap;
                best = new Trace(target.id, null, path, tap.rootId, tap.parentTargetId, tap.vertexIndex);
            }
        }
        if (bestTap != null) {
            state.triedTaps.add(bestTap.key);
        }
        return best;
    }

    /**
     * Price of one metre of new pipe in roubles as the score sees it: the construction rate of the diameter
     * plus the length term of the score (section 6 of the appendix) converted to roubles.
     */
    private double metrePrice(int diameterMm) {
        RulesCatalog.Ranking ranking = catalog.ranking();
        double lengthRub = (ranking.lengthWeight / ranking.lengthBaseM) / (ranking.costWeight / ranking.costBaseRub);
        List<RulesCatalog.DiameterRow> rows = catalog.diameters();
        RulesCatalog.DiameterRow row = rows.get(rows.size() - 1);
        for (RulesCatalog.DiameterRow candidate : rows) {
            if (candidate.diameterMm >= diameterMm) {
                row = candidate;
                break;
            }
        }
        return row.newCostRubPerM + lengthRub;
    }

    private static double length(List<Coordinate> path) {
        double total = 0.0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distance(path.get(i));
        }
        return total;
    }

    /** Nearest untried tap point on any accepted trace; marks it tried. */
    private TapCandidate nextTap(RoutingContext.Target target, TargetAttemptState state) {
        List<TapCandidate> taps = tapCandidates(target, state);
        if (taps.isEmpty()) {
            return null;
        }
        TapCandidate best = taps.get(0);
        state.triedTaps.add(best.key);
        return best;
    }

    /** Untried tap points on accepted traces, nearest to the target first. */
    private List<TapCandidate> tapCandidates(RoutingContext.Target target, TargetAttemptState state) {
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
                if (insideForbiddenZone(v, null)) {
                    continue;
                }
                taps.add(new TapCandidate(v, parentTrace.targetId, i, parentTrace.rootId, key));
            }
        }
        taps.sort(Comparator.comparingDouble(t -> t.point.distance(goal)));
        return taps;
    }

    /**
     * Defensive check of the final approach: nothing but the last segment touches the own polygon and the
     * last segment enters it once.
     */
    private boolean validOwnOksApproach(List<Coordinate> path, RoutingContext.Target target) {
        Coordinate goal = target.point.getCoordinate();
        if (path.size() < 2 || path.get(path.size() - 1).distance(goal) > COINCIDENT_M) {
            return false;
        }
        if (target.ownOksPolygon == null) {
            return true;
        }
        if (path.size() > 2) {
            Coordinate[] before = path.subList(0, path.size() - 1).toArray(new Coordinate[0]);
            if (target.ownOksPolygon.intersects(gf.createLineString(before))) {
                return false;
            }
        }
        LineString last = gf.createLineString(new Coordinate[] {path.get(path.size() - 2), goal});
        Geometry inside = target.ownOksPolygon.intersection(last);
        if (inside.getNumGeometries() > 1) {
            return false;
        }
        return true;
    }

    /** Appends the final straight segment to the target; a collinear approach start is merged into it. */
    private List<Coordinate> withFinalApproach(List<Coordinate> path, Coordinate goal, Approach approach) {
        if (approach == null) {
            return path;
        }
        List<Coordinate> result = new ArrayList<>(path);
        result.add(goal);
        return simplify(result);
    }

    /** One way into the own OKS polygon: boundary entry point and the outward corridor where the final segment may start. */
    private static final class Approach {
        final Coordinate entry;
        final List<Coordinate> corridor;

        Approach(Coordinate entry, List<Coordinate> corridor) {
            this.entry = entry;
            this.corridor = corridor;
        }
    }

    private boolean finalSegmentCrossesTraces(List<Coordinate> path, ObjectId parentTargetId, Coordinate tapPoint) {
        return path.size() >= 2 && crossesAcceptedTraces(path.get(path.size() - 2), path.get(path.size() - 1),
                parentTargetId, tapPoint);
    }

    /**
     * Whether the grid can still leave the approach corridor without crossing accepted traces: it reaches
     * open ground far from the target or a vertex of an accepted trace where a branch may start.
     */
    private boolean approachOpen(Approach approach, int diameter) {
        if (acceptedTraces.isEmpty()) {
            return true;
        }
        List<Coordinate> taps = new ArrayList<>();
        for (Trace trace : acceptedTraces.values()) {
            taps.addAll(trace.points.subList(0, Math.max(0, trace.points.size() - 1)));
        }
        Coordinate target = approach.corridor.get(0);
        java.util.ArrayDeque<Coordinate> queue = new java.util.ArrayDeque<>(approach.corridor);
        Set<String> seen = new HashSet<>();
        Envelope bounds = context.searchBounds(REACH_RADIUS_M);
        while (!queue.isEmpty() && seen.size() < REACH_BUDGET) {
            Coordinate current = queue.poll();
            if (current.distance(target) >= REACH_RADIUS_M) {
                return true;
            }
            for (Coordinate vertex : taps) {
                if (current.distance(vertex) <= REACH_STEP_M * 1.5) {
                    return true;
                }
            }
            for (Coordinate next : neighbors(current, REACH_STEP_M, bounds)) {
                Coordinate snapped = new Coordinate(Math.round(next.x / REACH_STEP_M) * REACH_STEP_M,
                        Math.round(next.y / REACH_STEP_M) * REACH_STEP_M);
                if (!seen.add(key(snapped, REACH_STEP_M))) {
                    continue;
                }
                if (context.blockedByForbidden(current, snapped, null, Collections.emptySet())
                        || crossesAcceptedTraces(current, snapped, null, null)) {
                    continue;
                }
                queue.add(snapped);
            }
        }
        return false;
    }

    private List<Approach> approaches(RoutingContext.Target target) {
        if (target.ownOksPolygon == null) {
            return Collections.emptyList();
        }
        return approaches.computeIfAbsent(target.id, id -> findApproaches(target));
    }

    /**
     * Ways into the own polygon ordered by the length of the final segment inside it: the nearest boundary
     * point first, then farther entries only where a nearer one is geometrically impossible (an inner
     * courtyard, a bay whose outward ray hits the building again, another obstacle on the approach line).
     * Only exterior rings are used: a hole is enclosed by the building and cannot be reached.
     */
    private List<Approach> findApproaches(RoutingContext.Target target) {
        Geometry polygon = target.ownOksPolygon;
        Coordinate goal = target.point.getCoordinate();
        List<Coordinate> entries = new ArrayList<>();
        for (int i = 0; i < polygon.getNumGeometries(); i++) {
            Geometry part = polygon.getGeometryN(i);
            if (!(part instanceof org.locationtech.jts.geom.Polygon)) {
                continue;
            }
            LineString ring = ((org.locationtech.jts.geom.Polygon) part).getExteriorRing();
            entries.add(DistanceOp.nearestPoints(ring, target.point)[0]);
            Coordinate[] coords = ring.getCoordinates();
            for (int k = 0; k + 1 < coords.length; k++) {
                double length = coords[k].distance(coords[k + 1]);
                int samples = Math.max(1, (int) Math.ceil(length / ENTRY_SAMPLE_M));
                for (int j = 0; j < samples; j++) {
                    double t = (double) j / samples;
                    entries.add(new Coordinate(coords[k].x + t * (coords[k + 1].x - coords[k].x),
                            coords[k].y + t * (coords[k + 1].y - coords[k].y)));
                }
            }
        }
        entries.sort(Comparator.comparingDouble(c -> c.distance(goal)));
        int diameter = targetDiameter.get(target.id);
        List<Approach> result = new ArrayList<>();
        for (Coordinate entry : entries) {
            if (result.size() >= MAX_APPROACHES) {
                break;
            }
            boolean close = false;
            for (Approach known : result) {
                close |= known.entry.distance(entry) < ENTRY_SEPARATION_M;
            }
            if (close) {
                continue;
            }
            Approach approach = approachThrough(target, entry, diameter);
            if (approach != null && reachable(approach, target, diameter)) {
                result.add(approach);
            }
        }
        return result;
    }

    /**
     * Cheap flood over the search grid from the corridor outwards: a corridor in an enclosed bay or yard
     * would otherwise make every later A* run exhaust its whole budget.
     */
    private boolean reachable(Approach approach, RoutingContext.Target target, int diameter) {
        Coordinate goal = target.point.getCoordinate();
        java.util.ArrayDeque<Coordinate> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (Coordinate c : approach.corridor) {
            queue.add(c);
            seen.add(key(c, REACH_STEP_M) + ":seed");
        }
        Envelope bounds = context.searchBounds(REACH_RADIUS_M);
        while (!queue.isEmpty() && seen.size() < REACH_BUDGET) {
            Coordinate current = queue.poll();
            if (current.distance(goal) >= REACH_RADIUS_M) {
                return true;
            }
            for (Coordinate next : neighbors(current, REACH_STEP_M, bounds)) {
                Coordinate snapped = new Coordinate(Math.round(next.x / REACH_STEP_M) * REACH_STEP_M,
                        Math.round(next.y / REACH_STEP_M) * REACH_STEP_M);
                if (!seen.add(key(snapped, REACH_STEP_M))) {
                    continue;
                }
                if (context.blockedByForbidden(current, snapped, null, Collections.emptySet())
                        || context.violatesSpecialClearance(current, snapped, diameter, null, Collections.emptySet())) {
                    continue;
                }
                queue.add(snapped);
            }
        }
        return false;
    }

    private Approach approachThrough(RoutingContext.Target target, Coordinate entry, int diameter) {
        Geometry polygon = target.ownOksPolygon;
        Coordinate goal = target.point.getCoordinate();
        double length = entry.distance(goal);
        double dx;
        double dy;
        if (length < 1e-9) {
            // The target lies on the boundary: leave outwards, away from the polygon centre.
            Point centre = polygon.getCentroid();
            dx = goal.x - centre.getX();
            dy = goal.y - centre.getY();
            length = Math.hypot(dx, dy);
            if (length < 1e-9) {
                return null;
            }
        } else {
            dx = entry.x - goal.x;
            dy = entry.y - goal.y;
            if (!polygon.covers(gf.createLineString(new Coordinate[] {entry, goal}))) {
                return null; // a concave outline: the straight segment would leave the building
            }
        }
        dx /= length;
        dy /= length;
        List<Coordinate> corridor = new ArrayList<>();
        for (double offset = APPROACH_STEP_M; offset <= MAX_APPROACH_OFFSET_M; offset += APPROACH_STEP_M) {
            Coordinate start = new Coordinate(entry.x + dx * offset, entry.y + dy * offset);
            if (polygon.intersection(gf.createLineString(new Coordinate[] {entry, start})).getLength() > 1e-6) {
                break; // the outward ray runs into the same building again
            }
            if (insideForbiddenZone(start, null)) {
                if (corridor.isEmpty()) {
                    continue;
                }
                break;
            }
            if (context.blockedByForbidden(start, goal, target.ownOksPolygonId, Collections.emptySet())
                    || context.violatesSpecialClearance(start, goal, diameter, target.ownOksPolygonId,
                            Collections.emptySet())) {
                break;
            }
            if (corridor.isEmpty() || start.distance(corridor.get(corridor.size() - 1)) >= CORRIDOR_SPACING_M) {
                corridor.add(start);
            }
        }
        return corridor.isEmpty() ? null : new Approach(entry, corridor);
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
            if (strategy == Strategy.LINE_TIES) {
                break; // chambers enter only through the 10 m rule below
            }
            Coordinate cc = chamber.point.getCoordinate();
            ObjectId exemptLineId = findHeatNetworkLineForChamber(cc);
            TieOption option = new TieOption(TieKind.EXISTING_CHAMBER, chamber.id, cc, cc.distance(goal), exemptLineId);
            if (newEdgeBudget(option) >= 1) {
                result.add(option);
            }
        }
        double radius = snapRadius(optionIndex);
        for (RoutingContext.LineSnap snap : context.snapsOnLines(goal, radius)) {
            if (!insideForbiddenZone(snap.snap, null)) {
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
    private List<Coordinate> aStar(Coordinate start, List<Coordinate> goals, ObjectId exemptOksPolygonId,
                                   int diameterMm, double step, ObjectId exemptLineId,
                                   ObjectId parentTargetId, Coordinate tapPoint, Coordinate initialPrevious,
                                   Coordinate after) {
        long began = System.nanoTime();
        LOG.debug("A* start={} goals={} step={} branch={}", start, goals.size(), step, parentTargetId != null);
        List<Coordinate> result = searchGrid(start, goals, exemptOksPolygonId, diameterMm, step,
                exemptLineId, parentTargetId, tapPoint, initialPrevious, after);
        LOG.debug("A* vertices={} elapsedMs={}", result == null ? 0 : result.size(), (System.nanoTime() - began) / 1_000_000);
        return result;
    }

    /**
     * Grid A* to any of the goals (a single target, or the points of an approach corridor).
     * {@code after}, when set, is the next vertex after the reached goal: the turn there must not exceed 90 degrees.
     */
    private List<Coordinate> searchGrid(Coordinate start, List<Coordinate> goals, ObjectId exemptOksPolygonId,
                                   int diameterMm, double step, ObjectId exemptLineId,
                                   ObjectId parentTargetId, Coordinate tapPoint, Coordinate initialPrevious,
                                   Coordinate after) {
        // Search bounds: envelope of start/goal/chambers/lines, padded to allow routing around
        // large restrictions (rivers, parks). Use 3x straight-line distance (capped) instead of 10x
        // to avoid excessively large search areas that cause timeouts.
        double straightDist = distanceToGoals(start, goals);
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
        open.add(new OpenEntry(startKey, 0.0, straightDist, sequence++));

        int expansions = 0;
        // Scale expansion limit by inverse step: finer grid has more nodes per meter.
        // Also scale by straight-line distance (capped) so distant targets get more budget
        // but not excessively more. Base limit applies at 25 m step for 1 km distance.
        double distanceFactor = Math.max(0.5, Math.min(5.0, straightDist / 1000.0));
        int maxExpansions = (int) (BASE_MAX_EXPANSIONS * (25.0 / step) * distanceFactor);
        if (parentTargetId != null) {
            maxExpansions = Math.min(maxExpansions, TAP_MAX_EXPANSIONS);
        }
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
            // A branch starts with its parent's incoming direction. Keep that
            // context outside cameFrom so reconstruction still starts at the tap.
            Coordinate previous = cameFrom.containsKey(currentKey)
                    ? coords.get(cameFrom.get(currentKey)) : initialPrevious;
            for (Coordinate goal : goals) {
                if (current.distance(goal) <= step * 1.5
                        && (after == null || (goal.x - current.x) * (after.x - goal.x)
                                + (goal.y - current.y) * (after.y - goal.y) >= -1e-9)
                        && moveAllowed(current, goal, previous, exemptOksPolygonId, diameterMm,
                                exemptLineId, parentTargetId, tapPoint)) {
                    return simplify(reconstruct(cameFrom, currentKey, coords, goal));
                }
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
                open.add(new OpenEntry(neighborKey, tentative, tentative + distanceToGoals(neighbor, goals), sequence++));
            }
        }
        return null;
    }

    private static double distanceToGoals(Coordinate c, List<Coordinate> goals) {
        double best = Double.POSITIVE_INFINITY;
        for (Coordinate goal : goals) {
            best = Math.min(best, c.distance(goal));
        }
        return best;
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
