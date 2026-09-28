package ru.hackathon.heatnetwork.routing;

import java.util.*;
import java.util.stream.Stream;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DepthCatalog;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DepthCatalog.CrossingRule;

/** Independent final-profile checks. Does not call or reuse the calculation profile builder. */
final class DepthSpatialValidation {
    private static final double JOINT_TOL = 1e-6;
    private static final double XY_TOL = 0.001;
    private final RulesCatalog catalog;
    private final DepthCatalog depth = RulesCatalog.loadDepth();
    private final Map<String, CrossingRule> rules = new HashMap<>();

    DepthSpatialValidation(RulesCatalog catalog) {
        this.catalog = catalog;
        for (CrossingRule rule : depth.crossings) rules.put(rule.type, rule);
    }

    void validate(Dataset dataset, CalculatedVariant variant, List<Diagnostic> diagnostics) {
        Map<String, double[]> joints = new HashMap<>();
        Map<String, Node> nodes = new HashMap<>();
        Set<String> roots = new HashSet<>();
        for (Node node : variant.nodes) nodes.put(node.id, node);
        for (Attachment attachment : variant.attachments) roots.add(attachment.rootNodeId);
        List<InputObject> obstacles = new ArrayList<>();
        for (InputType type : List.of(InputType.RESTRICTION, InputType.HEAT_NETWORK)) {
            try (Stream<InputObject> stream = dataset.objects(type)) {
                stream.filter(o -> rules.containsKey(type(o))).forEach(obstacles::add);
            }
        }
        for (CalculatedEdge edge : variant.edges) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (!valid(edge.depthStartM) || !valid(edge.depthEndM)) {
                add(diagnostics, edge, null, "DEPTH_VALUE_INVALID", "Отсутствующая или недопустимая глубина.");
                continue;
            }
            double a = edge.depthStartM, b = edge.depthEndM;
            double length = edge.geometry.getLength();
            double allowed = length * depth.maximumSlope;
            if (!Double.isFinite(length) || length <= 0 || Math.abs(b - a) > allowed + roundoff(a, b, allowed)) {
                add(diagnostics, edge, null, "DEPTH_SLOPE_VIOLATION", "Превышен уклон по горизонтальной длине.");
            }
            if (Math.min(a, b) < depth.cost.thresholdM && Math.max(a, b) > depth.cost.thresholdM) {
                add(diagnostics, edge, null, "DEPTH_VALUE_INVALID", "Требуется деление на пороге коэффициента глубины.");
            }
            joint(edge, edge.fromNodeId, a, joints, nodes, roots, diagnostics);
            joint(edge, edge.toNodeId, b, joints, nodes, roots, diagnostics);
            if (edge.layingMethod == LayingMethod.SPECIAL && Math.abs(a - b) > JOINT_TOL) {
                add(diagnostics, edge, null, "DEPTH_CONTINUITY_VIOLATION", "P3: глубина спецпрохода должна быть постоянной.");
            }
            RulesCatalog.DiameterRow row = catalog.row(edge.diameterMm);
            if (length > 0) for (InputObject obstacle : obstacles) {
                checkOverlap(variant, edge, obstacle, row, diagnostics);
            }
        }
        checkSpecialChains(variant, diagnostics);
    }

    private void checkSpecialChains(CalculatedVariant variant, List<Diagnostic> diagnostics) {
        Map<String, List<CalculatedEdge>> adjacent = new HashMap<>();
        for (CalculatedEdge edge : variant.edges) {
            if (edge.layingMethod == LayingMethod.SPECIAL && valid(edge.depthStartM) && valid(edge.depthEndM)) {
                adjacent.computeIfAbsent(edge.fromNodeId, ignored -> new ArrayList<>()).add(edge);
                adjacent.computeIfAbsent(edge.toNodeId, ignored -> new ArrayList<>()).add(edge);
            }
        }
        Set<String> seen = new HashSet<>();
        for (CalculatedEdge start : variant.edges) {
            if (start.layingMethod != LayingMethod.SPECIAL || !valid(start.depthStartM) || !valid(start.depthEndM)
                    || !seen.add(start.id)) continue;
            Deque<CalculatedEdge> pending = new ArrayDeque<>();
            pending.add(start);
            double min = start.depthStartM, max = start.depthStartM;
            while (!pending.isEmpty()) {
                CalculatedEdge edge = pending.removeFirst();
                min = Math.min(min, Math.min(edge.depthStartM, edge.depthEndM));
                max = Math.max(max, Math.max(edge.depthStartM, edge.depthEndM));
                for (String node : List.of(edge.fromNodeId, edge.toNodeId)) {
                    for (CalculatedEdge next : adjacent.getOrDefault(node, List.of())) {
                        if (seen.add(next.id)) pending.addLast(next);
                    }
                }
            }
            if (max - min > JOINT_TOL) add(diagnostics, start, null, "DEPTH_CONTINUITY_VIOLATION",
                    "P3: глубина меняется вдоль непрерывной цепочки спецпрохода.");
        }
    }

    private boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value >= depth.minimumDepthM;
    }

    private void joint(CalculatedEdge edge, String id, double value, Map<String, double[]> joints,
                       Map<String, Node> nodes, Set<String> roots, List<Diagnostic> diagnostics) {
        double[] range = joints.computeIfAbsent(id, ignored -> new double[]{value, value});
        range[0] = Math.min(range[0], value);
        range[1] = Math.max(range[1], value);
        Node node = nodes.get(id);
        if (range[1] - range[0] > JOINT_TOL
                || ((roots.contains(id) || (node != null && node.kind == NodeKind.CONNECTION_POINT))
                    && Math.abs(value - depth.ordinaryDepthM) > JOINT_TOL)) {
            add(diagnostics, edge, node == null ? null : node.inputObjectId,
                    "DEPTH_CONTINUITY_VIOLATION", "Разрыв глубин в узле или нарушение P1: " + id);
        }
    }

    private void checkOverlap(CalculatedVariant variant, CalculatedEdge edge, InputObject obstacle,
                              RulesCatalog.DiameterRow newPipe, List<Diagnostic> diagnostics) {
        CrossingRule rule = rules.get(type(obstacle));
        RulesCatalog.DiameterRow oldPipe = null;
        if (obstacle.type == InputType.HEAT_NETWORK) {
            // Same conservative table convention as the calculator for an existing non-table DN.
            for (RulesCatalog.DiameterRow row : catalog.diameters()) {
                if (obstacle.diameterMm != null && row.diameterMm >= obstacle.diameterMm) { oldPipe = row; break; }
            }
            if (oldPipe == null) throw new IllegalStateException("No envelope for existing network " + obstacle.id);
        }
        double half = newPipe.widthM / 2 + (oldPipe != null ? oldPipe.widthM / 2
                : rule.profileWidthM == null ? 0 : rule.profileWidthM / 2);
        Envelope bounds = new Envelope(obstacle.geometry.getEnvelopeInternal());
        bounds.expandBy(half);
        if (!bounds.intersects(edge.geometry.getEnvelopeInternal())) return;
        // Expand the polygonal approximation outwards so arcs never shrink the envelope.
        Geometry envelope = obstacle.geometry.buffer(half / Math.cos(Math.PI / 64), 16);
        List<double[]> intervals = overlapIntervals(edge.geometry, envelope);
        double height = oldPipe == null ? (rule.profileHeightM == null ? 0 : rule.profileHeightM) : oldPipe.heightM;
        for (double[] interval : intervals) {
            if (obstacle.type == InputType.HEAT_NETWORK && interval[0] <= XY_TOL
                    && reachesAttachment(variant, edge, obstacle, envelope, new HashSet<>())) continue;
            double h1 = at(edge, interval[0]), h2 = at(edge, interval[1]);
            double min = Math.min(h1, h2), max = Math.max(h1, h2);
            boolean clear;
            if (rule.minimumNewTopDepthM != null) {
                clear = min >= rule.minimumNewTopDepthM;
            } else {
                double above = rule.existingTopDepthM - rule.minimumVerticalClearanceM;
                double below = rule.existingTopDepthM + height + rule.minimumVerticalClearanceM;
                // A whole connected overlap must stay on one side, not tunnel through the object.
                clear = max + newPipe.heightM <= above + roundoff(max, newPipe.heightM, above)
                        || min + roundoff(min, below, 0) >= below;
            }
            if (!clear) {
                add(diagnostics, edge, obstacle.id, "DEPTH_CLEARANCE_VIOLATION",
                        "Недостаточный вертикальный просвет в области перекрытия габаритов: " + type(obstacle));
                break;
            }
        }
    }

    // Only the connected overlap starting at the selected tie-in is exempt. A later crossing is not.
    private boolean reachesAttachment(CalculatedVariant variant, CalculatedEdge edge, InputObject obstacle,
                                      Geometry envelope, Set<String> visited) {
        if (!visited.add(edge.id)) return false;
        for (Attachment attachment : variant.attachments) {
            if (attachment.rootNodeId.equals(edge.fromNodeId)
                    && edge.geometry.getStartPoint().distance(obstacle.geometry) <= XY_TOL) return true;
        }
        for (CalculatedEdge parent : variant.edges) {
            if (parent.toNodeId.equals(edge.fromNodeId) && envelope.covers(parent.geometry)) {
                return reachesAttachment(variant, parent, obstacle, envelope, visited);
            }
        }
        return false;
    }

    /** Project each geometric segment separately: depths interpolate by cumulative metric length. */
    private static List<double[]> overlapIntervals(LineString line, Geometry envelope) {
        List<double[]> intervals = new ArrayList<>();
        double offset = 0;
        for (int i = 1; i < line.getNumPoints(); i++) {
            Coordinate a = line.getCoordinateN(i - 1), b = line.getCoordinateN(i);
            double length = a.distance(b);
            if (length == 0) continue;
            Geometry intersection = line.getFactory().createLineString(new Coordinate[]{a, b}).intersection(envelope);
            for (int j = 0; j < intersection.getNumGeometries(); j++) {
                Geometry component = intersection.getGeometryN(j);
                if (component.isEmpty()) continue;
                double low = length, high = 0;
                for (Coordinate p : component.getCoordinates()) {
                    double s = Math.max(0, Math.min(length, ((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / length));
                    low = Math.min(low, s); high = Math.max(high, s);
                }
                intervals.add(new double[]{offset + low, offset + high});
            }
            offset += length;
        }
        intervals.sort(Comparator.comparingDouble(x -> x[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : intervals) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else merged.add(interval);
        }
        return merged;
    }

    private static double at(CalculatedEdge edge, double position) {
        return edge.depthStartM + (edge.depthEndM - edge.depthStartM) * position / edge.geometry.getLength();
    }

    private static String type(InputObject object) {
        return object.type == InputType.HEAT_NETWORK ? "heat_network" : object.restrictionType;
    }

    private static double roundoff(double a, double b, double c) {
        return Math.min(1e-9, 8 * Math.max(Math.ulp(a), Math.max(Math.ulp(b), Math.ulp(c))));
    }

    private static void add(List<Diagnostic> diagnostics, CalculatedEdge edge, ObjectId objectId, String code, String message) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.code = code; diagnostic.message = message;
        diagnostic.segmentId = edge.id; diagnostic.inputObjectId = objectId;
        diagnostics.add(diagnostic);
    }
}
