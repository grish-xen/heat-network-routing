package ru.hackathon.heatnetwork.routing;

import java.math.BigDecimal;
import java.util.ArrayDeque;
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
        checkEdgeEndpoints(variant, nodeById, diagnostics);
        checkTurns(variant, diagnostics);
        checkEdgeCrossings(variant, nodeById, diagnostics);
        checkRestrictions(dataset, variant, nodeById, diagnostics);
        if (variant.mode == ru.hackathon.heatnetwork.model.Model.Mode.DEPTH) {
            new DepthSpatialValidation(catalog).validate(dataset, variant, diagnostics);
        }
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

        // Indegree alone permits a disconnected directed cycle. Together with the
        // parent checks above, reachability from valid roots establishes a forest.
        Map<String, List<String>> children = new HashMap<>();
        for (Edge edge : variant.edges) {
            children.computeIfAbsent(edge.fromNodeId, ignored -> new ArrayList<>()).add(edge.toNodeId);
        }
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String rootId : rootIds) {
            Node root = nodeById.get(rootId);
            if (root == null || (root.kind != NodeKind.EXISTING_CHAMBER && root.kind != NodeKind.NEW_CHAMBER)) {
                diagnostics.add(diag(null, "TOPOLOGY_VIOLATION", "Attachment must reference a chamber node: " + rootId));
            } else pending.addLast(rootId);
        }
        Set<String> reachable = new HashSet<>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!reachable.add(id)) continue;
            for (String child : children.getOrDefault(id, List.of())) {
                if (nodeById.containsKey(child)) pending.addLast(child);
            }
        }
        for (Node node : variant.nodes) {
            if (!reachable.contains(node.id)) {
                diagnostics.add(diag(node.inputObjectId, "TOPOLOGY_VIOLATION",
                        "Node " + node.id + " is not reachable from an attachment"));
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

    private void checkEdgeEndpoints(CalculatedVariant variant, Map<String, Node> nodeById,
                                    List<Diagnostic> diagnostics) {
        for (Edge edge : variant.edges) {
            if (edge.geometry == null || edge.geometry.getNumPoints() < 2) {
                diagnostics.add(diag(null, "INVALID_GEOMETRY", "Edge " + edge.id + " is not a valid LineString"));
                continue;
            }
            Node from = nodeById.get(edge.fromNodeId);
            Node to = nodeById.get(edge.toNodeId);
            if (from != null && from.geometry.distance(edge.geometry.getStartPoint()) > TOL) {
                diagnostics.add(diag(from.inputObjectId, "TOPOLOGY_VIOLATION",
                        "Edge " + edge.id + " start does not coincide with from node"));
            }
            if (to != null && to.geometry.distance(edge.geometry.getEndPoint()) > TOL) {
                diagnostics.add(diag(to.inputObjectId, "TOPOLOGY_VIOLATION",
                        "Edge " + edge.id + " end does not coincide with to node"));
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

    private void checkEdgeCrossings(CalculatedVariant variant, Map<String, Node> nodeById,
                                    List<Diagnostic> diagnostics) {
        for (int i = 0; i < variant.edges.size(); i++) {
            for (int j = i + 1; j < variant.edges.size(); j++) {
                Edge a = variant.edges.get(i);
                Edge b = variant.edges.get(j);
                if (!a.geometry.intersects(b.geometry)) {
                    continue;
                }
                Geometry inter = a.geometry.intersection(b.geometry);
                Set<String> sharedNodes = new HashSet<>(List.of(a.fromNodeId, a.toNodeId));
                sharedNodes.retainAll(List.of(b.fromNodeId, b.toNodeId));
                // Only isolated points at IDs shared by BOTH edges are allowed.
                // A line overlap is invalid even when all its vertices are endpoints.
                if (inter.getDimension() == 0 && !sharedNodes.isEmpty()) {
                    boolean onlyAtSharedNodes = true;
                    for (Coordinate c : inter.getCoordinates()) {
                        boolean atShared = false;
                        for (String nodeId : sharedNodes) {
                            Node node = nodeById.get(nodeId);
                            if (node != null && node.geometry.getCoordinate().distance(c) <= TOL) {
                                atShared = true;
                                break;
                            }
                        }
                        if (!atShared) {
                            onlyAtSharedNodes = false;
                            break;
                        }
                    }
                    if (onlyAtSharedNodes) {
                        continue;
                    }
                }
                Diagnostic diagnostic = diag(null, "TOPOLOGY_VIOLATION",
                        "New edges " + a.id + " and " + b.id + " overlap or cross outside a shared node");
                diagnostic.segmentId = a.id;
                diagnostics.add(diagnostic);
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

        Map<String, List<CalculatedEdge>> incoming = new HashMap<>();
        Map<String, List<CalculatedEdge>> outgoing = new HashMap<>();
        for (CalculatedEdge edge : variant.edges) {
            incoming.computeIfAbsent(edge.toNodeId, key -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.fromNodeId, key -> new ArrayList<>()).add(edge);
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
                } else if ("heat_network".equals(restriction.type)
                        && isAttachmentToExistingNetwork(edge, restriction, variant, nodeById)
                        && onlyTouchesAtStart(edge, restriction.geometry)) {
                    // The endpoint at the selected attachment is the permitted tie-in.
                    continue;
                } else if ("forbidden".equals(rule.crossing)) {
                    checkForbiddenClearance(edge, restriction, diagnostics);
                } else {
                    // Special-pass types: the crossing itself is allowed; the sizing and
                    // straightness of the pass are enforced by the calculation module.
                    // The validator still flags passes shorter than the required extension.
                    checkSpecialPassGeometry(edge, restriction, rule, nodeById, incoming, outgoing, diagnostics);
                }
            }
        }
    }

    private boolean onlyTouchesAtStart(CalculatedEdge edge, Geometry existingLine) {
        if (!existingLine.intersects(edge.geometry)) {
            return false;
        }
        Geometry intersection = existingLine.intersection(edge.geometry);
        for (Coordinate coordinate : intersection.getCoordinates()) {
            if (coordinate.distance(edge.geometry.getStartPoint().getCoordinate()) > TOL) {
                return false;
            }
        }
        return true;
    }

    private boolean isAttachmentToExistingNetwork(CalculatedEdge edge, Restriction restriction,
                                                  CalculatedVariant variant, Map<String, Node> nodeById) {
        Node from = nodeById.get(edge.fromNodeId);
        if (from == null || (from.kind != NodeKind.EXISTING_CHAMBER && from.kind != NodeKind.NEW_CHAMBER)) {
            return false;
        }
        for (Attachment attachment : variant.attachments) {
            // The line carrying a new chamber, or any existing line ending in / passing through
            // the attachment chamber, meets the new network there by the connection itself.
            if (attachment.rootNodeId.equals(from.id)
                    && (restriction.id.equals(attachment.existingObjectId)
                        || from.geometry.distance(restriction.geometry) <= TOL)
                    && from.geometry.distance(edge.geometry.getStartPoint()) <= TOL) {
                return true;
            }
        }
        return false;
    }


    private void checkSpecialPassGeometry(CalculatedEdge edge, Restriction restriction,
                                          RulesCatalog.RestrictionRule rule, Map<String, Node> nodeById,
                                          Map<String, List<CalculatedEdge>> incoming,
                                          Map<String, List<CalculatedEdge>> outgoing,
                                          List<Diagnostic> diagnostics) {
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
        // Overlaps split one physical pass into several priced edges (clarification 8).
        // Restore only the straight, connected continuation marked for this restriction.
        line = completeSpecialPass(edge, restriction.id, nodeById, incoming, outgoing);
        if (rule.minAngleDeg != null) {
            double angle = SpecialCrossingAngles.minimum(line, restriction.geometry);
            if (angle + 1e-9 < rule.minAngleDeg) {
                Diagnostic diagnostic = diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                        "Edge " + edge.id + " crosses " + restriction.type
                                + " at " + angle + " degrees; minimum is " + rule.minAngleDeg);
                diagnostic.segmentId = edge.id;
                diagnostics.add(diagnostic);
            }
        }
        double extension = rule.extensionEachSideM == null ? 0.0 : rule.extensionEachSideM;
        Geometry intersection = line.intersection(restriction.geometry);
        Coordinate[] crossings = intersection.getCoordinates();
        if (crossings.length == 0) {
            return;
        }
        for (Coordinate crossing : crossings) {
            if (crossedPointTooShort(line, crossing, extension)) {
                diagnostics.add(diag(restriction.id, "SPECIAL_PASS_VIOLATION",
                        "Edge " + edge.id + " special pass does not extend " + extension
                                + " m on both sides of the crossing"));
                break;
            }
        }
    }

    private LineString completeSpecialPass(CalculatedEdge edge, ObjectId restrictionId,
                                           Map<String, Node> nodeById,
                                           Map<String, List<CalculatedEdge>> incoming,
                                           Map<String, List<CalculatedEdge>> outgoing) {
        Coordinate start = edge.geometry.getCoordinateN(0);
        Coordinate end = edge.geometry.getCoordinateN(1);
        if (!edge.crossedObjectIds.contains(restrictionId)) {
            return edge.geometry;
        }
        Set<String> visited = new HashSet<>();
        visited.add(edge.id);
        for (boolean upstream : new boolean[] {true, false}) {
            CalculatedEdge current = edge;
            while (true) {
                String jointId = upstream ? current.fromNodeId : current.toNodeId;
                Node joint = nodeById.get(jointId);
                List<CalculatedEdge> before = incoming.get(jointId);
                List<CalculatedEdge> after = outgoing.get(jointId);
                if (joint == null || joint.kind != NodeKind.TECHNICAL_NODE
                        || before == null || before.size() != 1 || after == null || after.size() != 1) {
                    break;
                }
                CalculatedEdge next = upstream ? before.get(0) : after.get(0);
                if (visited.contains(next.id)
                        || next.layingMethod != ru.hackathon.heatnetwork.model.Model.LayingMethod.SPECIAL
                        || !next.crossedObjectIds.contains(restrictionId)
                        || next.geometry == null || next.geometry.getNumPoints() != 2) {
                    break;
                }
                Coordinate a = next.geometry.getCoordinateN(0);
                Coordinate b = next.geometry.getCoordinateN(1);
                Coordinate jointPosition = upstream ? start : end;
                if (jointPosition.distance(upstream ? b : a) > TOL) {
                    break;
                }
                Coordinate extended = upstream ? a : b;
                double dx = end.x - start.x;
                double dy = end.y - start.y;
                double length = Math.hypot(dx, dy);
                if (length <= TOL) {
                    break;
                }
                double along = ((extended.x - start.x) * dx + (extended.y - start.y) * dy) / length;
                double offset = Math.abs((extended.x - start.x) * dy - (extended.y - start.y) * dx) / length;
                if (offset > TOL || (upstream ? along >= -TOL : along <= length + TOL)) {
                    break;
                }
                if (upstream) {
                    start = extended;
                } else {
                    end = extended;
                }
                visited.add(next.id);
                current = next;
            }
        }
        return edge.geometry.getFactory().createLineString(new Coordinate[] {start, end});
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
