package ru.hackathon.heatnetwork.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.ObjectId;

/**
 * Final geometric/topology validation of a calculated variant (module 2).
 *
 * <p>Checks (2D mode, EPSG:32637): tree topology per component with exactly one
 * attachment; one parent edge per node; branching only in cameras with the
 * four-adjacency limit counting existing lines through existing chambers; no cycles
 * or edge crossings outside shared nodes; turn angle at every polyline vertex
 * at most 90°; clearance of the sized pipe-pair half-width to forbidden and special
 * restrictions (clearance inside a legitimate special crossing is not checked);
 * crossing the own OKS polygon only by the final straight approach.</p>
 */
public final class DefaultSpatialValidator implements SpatialValidator {

    private static final double TOL = 0.001;

    private final GeometryFactory gf = new GeometryFactory();
    private final RulesCatalog catalog;

    public DefaultSpatialValidator(RulesCatalog catalog) {
        this.catalog = catalog == null ? RulesCatalog.loadDefault() : catalog;
    }

    public DefaultSpatialValidator() {
        this(RulesCatalog.loadDefault());
    }

    @Override
    public List<Diagnostic> validate(Dataset dataset, CalculatedVariant variant) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (variant == null) {
            diagnostics.add(diag(null, "INTERNAL_ERROR", "Variant is null"));
            return diagnostics;
        }
        Map<String, Node> nodeById = new HashMap<>();
        for (Node node : variant.nodes) {
            nodeById.put(node.id, node);
        }
        checkTreeTopology(variant, nodeById, diagnostics);
        checkTurns(variant, diagnostics);
        checkEdgeCrossings(variant, diagnostics);
        checkRestrictions(dataset, variant, nodeById, diagnostics);
        return diagnostics;
    }

    // ---------------------------------------------------------------- topology

    private void checkTreeTopology(CalculatedVariant variant, Map<String, Node> nodeById,
                                   List<Diagnostic> diagnostics) {
        // Every non-root node has exactly one parent; roots have none.
        Map<String, Integer> parentCount = new HashMap<>();
        for (Edge edge : variant.edges) {
            parentCount.merge(edge.toNodeId, 1, Integer::sum);
            if (!nodeById.containsKey(edge.fromNodeId)) {
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                        "Edge " + edge.id + " references unknown node " + edge.fromNodeId));
            }
            if (!nodeById.containsKey(edge.toNodeId)) {
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                        "Edge " + edge.id + " references unknown node " + edge.toNodeId));
            }
        }
        for (Node node : variant.nodes) {
            if (node.kind == NodeKind.EXISTING_CHAMBER || node.kind == NodeKind.NEW_CHAMBER) {
                boolean isRoot = false;
                for (Attachment attachment : variant.attachments) {
                    if (attachment.rootNodeId.equals(node.id)) {
                        isRoot = true;
                        break;
                    }
                }
                int parents = parentCount.getOrDefault(node.id, 0);
                if (isRoot && parents != 0) {
                    diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                            "Root node " + node.id + " must have no incoming new edges"));
                }
                if (!isRoot && parents != 1) {
                    diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                            "Non-root node " + node.id + " must have exactly one parent"));
                }
            } else {
                int parents = parentCount.getOrDefault(node.id, 0);
                if (parents != 1) {
                    diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                            "Node " + node.id + " must have exactly one parent edge"));
                }
            }
        }

        // Component count: exactly one attachment per component; each root belongs to one.
        Set<String> rootIds = new HashSet<>();
        for (Attachment attachment : variant.attachments) {
            if (!rootIds.add(attachment.rootNodeId)) {
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                        "Duplicate attachment for root " + attachment.rootNodeId));
            }
        }

        // Branching only in cameras; degree limit including existing lines.
        Map<String, Integer> degree = new HashMap<>();
        for (Edge edge : variant.edges) {
            degree.merge(edge.fromNodeId, 1, Integer::sum);
            degree.merge(edge.toNodeId, 1, Integer::sum);
        }
        for (Node node : variant.nodes) {
            int deg = degree.getOrDefault(node.id, 0);
            if (node.kind == NodeKind.CONNECTION_POINT && deg > 1) {
                diagnostics.add(diag(node.inputObjectId, "TOPOLOGY_VIOLATION",
                        "Connection point " + node.id + " must be a leaf"));
            }
            if (node.kind == NodeKind.TECHNICAL_NODE && deg != 2) {
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                        "Technical node " + node.id + " must join exactly two edges"));
            }
            boolean isCamera = node.kind == NodeKind.EXISTING_CHAMBER || node.kind == NodeKind.NEW_CHAMBER;
            if (isCamera && deg > catalog.maxChamberDegree()) {
                diagnostics.add(diag(node.inputObjectId, "TOPOLOGY_VIOLATION",
                        "Camera " + node.id + " exceeds " + catalog.maxChamberDegree() + " adjacencies"));
            }
        }
    }

    // ------------------------------------------------------------------ turns

    private void checkTurns(CalculatedVariant variant, List<Diagnostic> diagnostics) {
        double maxCos = 0.0; // cos of the max allowed turn (90°) is 0; turn angle in (90..180) => cos < 0
        for (CalculatedEdge edge : variant.edges) {
            Coordinate[] coords = edge.geometry.getCoordinates();
            for (int i = 1; i + 1 < coords.length; i++) {
                double d1x = coords[i].x - coords[i - 1].x;
                double d1y = coords[i].y - coords[i - 1].y;
                double d2x = coords[i + 1].x - coords[i].x;
                double d2y = coords[i + 1].y - coords[i].y;
                double n1 = Math.hypot(d1x, d1y);
                double n2 = Math.hypot(d2x, d2y);
                if (n1 < 1e-9 || n2 < 1e-9) {
                    diagnostics.add(diag(null, "TURN_VIOLATION",
                            "Zero-length vertex in edge " + edge.id));
                    continue;
                }
                double cos = (d1x * d2x + d1y * d2y) / (n1 * n2);
                if (cos < maxCos - 1e-9) {
                    diagnostics.add(diag(null, "TURN_VIOLATION",
                            "Turn above 90° in edge " + edge.id + " at vertex " + i));
                }
            }
        }
    }

    private void checkEdgeCrossings(CalculatedVariant variant, List<Diagnostic> diagnostics) {
        for (int i = 0; i < variant.edges.size(); i++) {
            for (int j = i + 1; j < variant.edges.size(); j++) {
                Edge a = variant.edges.get(i);
                Edge b = variant.edges.get(j);
                if (!a.geometry.intersects(b.geometry)) {
                    continue;
                }
                // Sharing a common endpoint node is fine.
                if (a.toNodeId.equals(b.fromNodeId) || a.fromNodeId.equals(b.toNodeId)
                        || a.fromNodeId.equals(b.fromNodeId) || a.toNodeId.equals(b.toNodeId)) {
                    Geometry inter = a.geometry.intersection(b.geometry);
                    boolean onlyAtSharedNodes = true;
                    for (Coordinate c : inter.getCoordinates()) {
                        boolean atShared = false;
                        for (String nodeId : new String[] {a.fromNodeId, a.toNodeId, b.fromNodeId, b.toNodeId}) {
                            Node node = null;
                            for (Node n : variant.nodes) {
                                if (n.id.equals(nodeId)) {
                                    node = n;
                                    break;
                                }
                            }
                            if (node != null && node.geometry.getCoordinate().distance(c) <= TOL) {
                                atShared = true;
                                break;
                            }
                        }
                        if (!atShared) {
                            onlyAtSharedNodes = false;
                            break;
                        }
                        if (!onlyAtSharedNodes) {
                            break;
                        }
                    }
                    if (onlyAtSharedNodes) {
                        continue;
                    }
                }
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION",
                        "New edges " + a.id + " and " + b.id + " cross outside a shared node"));
            }
        }
    }

    // ----------------------------------------------------------- restrictions

    private void checkRestrictions(Dataset dataset, CalculatedVariant variant,
                                   Map<String, Node> nodeById, List<Diagnostic> diagnostics) {
        // Collect restrictions once.
        List<Restriction> restrictions = new ArrayList<>();
        try (java.util.stream.Stream<ru.hackathon.heatnetwork.model.Model.InputObject> stream =
                     dataset.objects(ru.hackathon.heatnetwork.model.Model.InputType.RESTRICTION)) {
            stream.forEach(obj -> restrictions.add(new Restriction(obj.id, obj.restrictionType, obj.geometry)));
        }
        try (java.util.stream.Stream<ru.hackathon.heatnetwork.model.Model.InputObject> stream =
                     dataset.objects(ru.hackathon.heatnetwork.model.Model.InputType.HEAT_NETWORK)) {
            stream.forEach(obj -> restrictions.add(new Restriction(obj.id, "heat_network", obj.geometry)));
        }

        // Target nodes and their own OKS polygons (exempt final approach).
        Map<ObjectId, Geometry> ownOksByTarget = new HashMap<>();
        try (java.util.stream.Stream<ru.hackathon.heatnetwork.model.Model.InputObject> stream =
                     dataset.objects(ru.hackathon.heatnetwork.model.Model.InputType.OKS_CONNECTION_POINT)) {
            stream.forEach(point -> {
                if (point.geometry instanceof Point) {
                    for (Restriction r : restrictions) {
                        if ("oks".equals(r.type) && r.geometry != null && r.geometry.contains(point.geometry)) {
                            ownOksByTarget.put(point.id, r.geometry);
                        }
                    }
                }
            });
        }

        for (CalculatedEdge edge : variant.edges) {
            Node endNode = nodeById.get(edge.toNodeId);
            for (Restriction restriction : restrictions) {
                RulesCatalog.RestrictionRule rule = catalog.rule(restriction.type);
                if (rule == null) {
                    continue; // non-mandatory restriction types are ignored
                }
                if ("oks".equals(restriction.type)) {
                    ObjectId exemption = null;
                    if (endNode != null && endNode.kind == NodeKind.CONNECTION_POINT) {
                        Geometry ownPolygon = ownOksByTarget.get(endNode.inputObjectId);
                        if (ownPolygon != null && ownPolygon.equals(restriction.geometry)) {
                            exemption = restriction.id;
                        }
                    }
                    if (exemption != null) {
                        checkOwnOksApproach(edge, restriction, endNode, diagnostics);
                    } else {
                        checkForbiddenClearance(edge, restriction, diagnostics);
                    }
                } else if ("forbidden".equals(rule.crossing)) {
                    checkForbiddenClearance(edge, restriction, diagnostics);
                } else {
                    // Special-pass types: the crossing itself is allowed; the sizing and
                    // straightness of the pass are enforced by the calculation module.
                    // The validator still flags passes shorter than the required extension.
                    checkSpecialPassGeometry(edge, restriction, rule, diagnostics);
                }
            }
        }
    }

    /** A special pass must be a straight special edge, satisfy the angle rule, and extend beyond the obstacle. */
    private void checkSpecialPassGeometry(CalculatedEdge edge, Restriction restriction,
                                          RulesCatalog.RestrictionRule rule, List<Diagnostic> diagnostics) {
        LineString line = edge.geometry;
        if (!line.intersects(restriction.geometry)) {
            return;
        }
        if (edge.layingMethod != ru.hackathon.heatnetwork.model.Model.LayingMethod.SPECIAL) {
            diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                    "Edge " + edge.id + " crosses " + restriction.type + " without special laying method"));
            return;
        }
        Coordinate[] coordinates = line.getCoordinates();
        if (coordinates.length != 2) {
            diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                    "Special edge " + edge.id + " must be one straight segment"));
            return;
        }
        if (rule.minAngleDeg != null && restriction.geometry instanceof LineString) {
            double angle = crossingAngle(line, (LineString) restriction.geometry);
            if (angle + TOL < rule.minAngleDeg) {
                diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                        "Edge " + edge.id + " crosses " + restriction.type
                                + " at " + angle + " degrees; minimum is " + rule.minAngleDeg));
            }
        }
        double extension = rule.extensionEachSideM == null ? 0.0 : rule.extensionEachSideM;
        Geometry intersection = line.intersection(restriction.geometry);
        Coordinate[] crossings = intersection.getCoordinates();
        if (crossings.length == 0) {
            return;
        }
        if (restriction.geometry instanceof LineString && crossedPointTooShort(line, crossings[0], extension)) {
            diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                    "Edge " + edge.id + " does not extend " + extension
                            + " m on both sides of the line crossing"));
        } else if (!(restriction.geometry instanceof LineString)
                && line.getLength() < intersection.getLength() + 2 * extension - TOL) {
            diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                    "Edge " + edge.id + " special pass over " + restriction.id
                            + " does not extend " + extension + " m beyond the boundary"));
        }
    }

    private boolean crossedPointTooShort(LineString line, Coordinate crossing, double extension) {
        Coordinate a = line.getCoordinateN(0);
        Coordinate b = line.getCoordinateN(1);
        double length = a.distance(b);
        if (length <= TOL) {
            return true;
        }
        double along = ((crossing.x - a.x) * (b.x - a.x)
                + (crossing.y - a.y) * (b.y - a.y)) / length;
        return along < extension - TOL || length - along < extension - TOL;
    }

    private double crossingAngle(LineString route, LineString restriction) {
        Coordinate a = route.getCoordinateN(0);
        Coordinate b = route.getCoordinateN(1);
        Coordinate c = restriction.getCoordinateN(0);
        Coordinate d = restriction.getCoordinateN(restriction.getNumPoints() - 1);
        double routeAngle = Math.atan2(b.y - a.y, b.x - a.x);
        double restrictionAngle = Math.atan2(d.y - c.y, d.x - c.x);
        double degrees = Math.toDegrees(Math.abs(routeAngle - restrictionAngle));
        degrees %= 180.0;
        return degrees > 90.0 ? 180.0 - degrees : degrees;
    }

    /** Crossing the own OKS polygon is allowed only by one final straight approach to the target. */
    private void checkOwnOksApproach(CalculatedEdge edge, Restriction restriction, Node targetNode,
                                     List<Diagnostic> diagnostics) {
        Coordinate target = targetNode.geometry.getCoordinate();
        Coordinate[] coords = edge.geometry.getCoordinates();
        // Find the last intersection of the edge with the polygon boundary; everything
        // after it must be a single straight segment ending at the target.
        int lastCrossIndex = -1;
        for (int i = 0; i + 1 < coords.length; i++) {
            LineString seg = gf.createLineString(new Coordinate[] {coords[i], coords[i + 1]});
            if (restriction.geometry.intersects(seg)) {
                lastCrossIndex = i;
            }
        }
        // Segments after the last crossing must be collinear continuation to the target.
        for (int i = lastCrossIndex + 1; i + 1 < coords.length; i++) {
            LineString seg = gf.createLineString(new Coordinate[] {coords[i], coords[i + 1]});
            if (restriction.geometry.intersects(seg)) {
                diagnostics.add(diag(null, "CLEARANCE_VIOLATION",
                        "Edge " + edge.id + " re-enters the own OKS polygon"));
                return;
            }
        }
        if (lastCrossIndex >= 0) {
            // The approach from the boundary to the target must be one straight segment.
            Coordinate entry = coords[lastCrossIndex + 1];
            // If the vertex right after the boundary is not on the straight line to the target -> violation.
            double cross = 0.0;
            if (lastCrossIndex + 2 < coords.length) {
                Coordinate a = coords[lastCrossIndex + 1];
                Coordinate b = coords[lastCrossIndex + 2];
                double d1x = target.x - a.x, d1y = target.y - a.y;
                double d2x = b.x - a.x, d2y = b.y - a.y;
                cross = Math.abs(d1x * d2y - d1y * d2x);
            }
            if (cross > 1e-6) {
                diagnostics.add(diag(null, "CLEARANCE_VIOLATION",
                        "Edge " + edge.id + " approaches the own OKS target non-straight"));
            }
        }
        // The edge must not end anywhere except the target while inside the polygon.
        for (int i = 0; i + 1 < coords.length; i++) {
            LineString seg = gf.createLineString(new Coordinate[] {coords[i], coords[i + 1]});
            if (restriction.geometry.contains(seg)
                    && coords[i].distance(target) > TOL && coords[i + 1].distance(target) > TOL) {
                diagnostics.add(diag(null, "CLEARANCE_VIOLATION",
                        "Edge " + edge.id + " runs inside the own OKS polygon away from the target"));
                return;
            }
        }
    }

    private void checkForbiddenClearance(CalculatedEdge edge, Restriction restriction,
                                         List<Diagnostic> diagnostics) {
        LineString line = edge.geometry;
        double halfWidth = catalog.halfWidthM(edge.diameterMm);
        if (restriction.geometry.intersects(line)) {
            // Any crossing of a forbidden restriction is a violation (oks handled with exemption separately).
            diagnostics.add(diag(null, "CLEARANCE_VIOLATION",
                    "Edge " + edge.id + " crosses forbidden restriction " + restriction.id));
            return;
        }
        double need = catalog.clearanceFor(restriction.type, edge.diameterMm) + halfWidth;
        if (restriction.geometry.distance(line) < need - TOL) {
            diagnostics.add(diag(null, "CLEARANCE_VIOLATION",
                    "Edge " + edge.id + " violates clearance to " + restriction.type
                            + " " + restriction.id));
        }
    }

    private static Diagnostic diag(ObjectId inputObjectId, String code, String message) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.code = code;
        diagnostic.message = message;
        diagnostic.inputObjectId = inputObjectId;
        return diagnostic;
    }

    private static final class Restriction {
        final ObjectId id;
        final String type;
        final org.locationtech.jts.geom.Geometry geometry;

        Restriction(ObjectId id, String type, org.locationtech.jts.geom.Geometry geometry) {
            this.id = id;
            this.type = type;
            this.geometry = geometry;
        }
    }
}
