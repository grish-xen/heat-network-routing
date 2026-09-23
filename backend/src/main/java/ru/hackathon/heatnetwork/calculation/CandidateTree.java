package ru.hackathon.heatnetwork.calculation;

import static ru.hackathon.heatnetwork.calculation.Rejection.diag;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.ObjectId;

/**
 * Structurally checked view of a RouteCandidate: a forest of trees rooted in attachment
 * chambers, leaves are connection points, downstream flows are summed from the Dataset.
 * The candidate itself is not modified.
 */
final class CandidateTree {
    final RouteCandidate candidate;
    final Map<String, Node> nodes = new LinkedHashMap<>();
    /** Exact node positions: input objects keep their Dataset coordinates. */
    final Map<String, Coordinate> positions = new HashMap<>();
    final Map<String, Attachment> attachmentByRoot = new LinkedHashMap<>();
    final Map<String, List<Edge>> children = new HashMap<>();
    final Map<String, Edge> parentEdge = new HashMap<>();
    /** Edge coordinates with ends snapped to the nodes and repeated positions removed. */
    final Map<String, Coordinate[]> coordinates = new HashMap<>();
    /** Nodes in breadth-first order from the roots: every parent precedes its children. */
    final List<String> order = new ArrayList<>();
    /** Sum of flow_tph of the connection points at or below the node. */
    final Map<String, BigDecimal> downstreamFlow = new HashMap<>();
    final Map<ObjectId, BigDecimal> unconnectedFlow = new LinkedHashMap<>();

    private CandidateTree(RouteCandidate candidate) {
        this.candidate = candidate;
    }

    List<Edge> children(String nodeId) {
        return children.getOrDefault(nodeId, Collections.emptyList());
    }

    boolean isRoot(String nodeId) {
        return attachmentByRoot.containsKey(nodeId);
    }

    static CandidateTree build(Dataset dataset, RouteCandidate candidate, double tolerance) throws Rejection {
        CandidateTree tree = new CandidateTree(candidate);
        List<Diagnostic> problems = new ArrayList<>();
        if (candidate.candidateId == null || candidate.candidateId.trim().isEmpty()) {
            problems.add(diag("INVALID_INPUT", "У кандидата нет candidateId.", null, null));
        }
        if (candidate.nodes == null || candidate.edges == null || candidate.attachments == null
                || candidate.unconnectedPointIds == null) {
            throw Rejection.of("INVALID_INPUT", "Списки кандидата не могут быть null.", null, null);
        }
        Map<ObjectId, BigDecimal> pointFlows = pointFlows(dataset, problems);
        Set<ObjectId> connectedPoints = new HashSet<>();
        tree.readNodes(dataset, pointFlows, connectedPoints, tolerance, problems);
        Rejection.throwIfAny(problems);
        tree.readEdges(tolerance, problems);
        tree.readAttachments(dataset, tolerance, problems);
        Rejection.throwIfAny(problems);
        tree.checkShape(problems);
        tree.readUnconnected(pointFlows, connectedPoints, problems);
        Rejection.throwIfAny(problems);
        tree.sumFlows(pointFlows);
        return tree;
    }

    private static Map<ObjectId, BigDecimal> pointFlows(Dataset dataset, List<Diagnostic> problems) {
        Map<ObjectId, BigDecimal> flows = new LinkedHashMap<>();
        try (Stream<InputObject> points = dataset.objects(InputType.OKS_CONNECTION_POINT)) {
            Iterator<InputObject> iterator = points.iterator();
            while (iterator.hasNext()) {
                InputObject point = iterator.next();
                if (point.flowTph == null || point.flowTph.signum() < 0) {
                    problems.add(diag("INVALID_INPUT", "У точки подключения нет корректного flow_tph.", point.id, null));
                } else {
                    flows.put(point.id, point.flowTph);
                }
            }
        }
        return flows;
    }

    private void readNodes(Dataset dataset, Map<ObjectId, BigDecimal> pointFlows, Set<ObjectId> connectedPoints,
                           double tolerance, List<Diagnostic> problems) {
        Set<ObjectId> referenced = new HashSet<>();
        for (Node node : candidate.nodes) {
            if (node == null || node.id == null || node.id.trim().isEmpty() || node.kind == null) {
                problems.add(diag("INVALID_INPUT", "Узел без ID или вида.", null, null));
                continue;
            }
            if (nodes.put(node.id, node) != null) {
                problems.add(diag("INVALID_ID", "Повторяющийся ID узла " + node.id + ".", null, null));
                continue;
            }
            if (!finitePoint(node.geometry)) {
                problems.add(diag("INVALID_GEOMETRY", "Узел " + node.id + " должен быть конечной точкой.", null, null));
                continue;
            }
            Coordinate position = node.geometry.getCoordinate();
            if (node.kind == NodeKind.CONNECTION_POINT || node.kind == NodeKind.EXISTING_CHAMBER) {
                InputType expected = node.kind == NodeKind.CONNECTION_POINT
                        ? InputType.OKS_CONNECTION_POINT : InputType.HEAT_CHAMBER;
                InputObject source = node.inputObjectId == null ? null : dataset.find(node.inputObjectId).orElse(null);
                if (source == null || source.type != expected || !finitePoint(source.geometry)) {
                    problems.add(diag("INVALID_ID", "Узел " + node.id + " ссылается на отсутствующий объект типа "
                            + expected + ".", node.inputObjectId, null));
                    continue;
                }
                if (!referenced.add(node.inputObjectId)) {
                    problems.add(diag("TOPOLOGY_VIOLATION", "Входной объект представлен несколькими узлами.",
                            node.inputObjectId, null));
                    continue;
                }
                Coordinate original = source.geometry.getCoordinate();
                if (original.distance(position) > tolerance) {
                    problems.add(diag("INVALID_GEOMETRY", "Узел " + node.id + " смещён относительно входного объекта.",
                            node.inputObjectId, null));
                    continue;
                }
                position = original;
                if (node.kind == NodeKind.CONNECTION_POINT) {
                    connectedPoints.add(node.inputObjectId);
                    if (!pointFlows.containsKey(node.inputObjectId)) {
                        problems.add(diag("INVALID_INPUT", "Нет расхода точки подключения.", node.inputObjectId, null));
                    }
                }
            } else if (node.inputObjectId != null) {
                problems.add(diag("INVALID_ID", "Новый узел " + node.id + " не должен ссылаться на входной объект.",
                        node.inputObjectId, null));
            }
            positions.put(node.id, new Coordinate(position.x, position.y));
        }
    }

    private void readEdges(double tolerance, List<Diagnostic> problems) {
        Set<String> ids = new HashSet<>();
        for (Edge edge : candidate.edges) {
            if (edge == null || edge.id == null || edge.id.trim().isEmpty() || !ids.add(edge.id)) {
                problems.add(diag("INVALID_ID", "Участок без ID или с повторяющимся ID.", null, edge == null ? null : edge.id));
                continue;
            }
            if (!nodes.containsKey(edge.fromNodeId) || !nodes.containsKey(edge.toNodeId)
                    || edge.fromNodeId.equals(edge.toNodeId)) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Участок " + edge.id
                        + " ссылается на отсутствующие или совпадающие узлы.", null, edge.id));
                continue;
            }
            Coordinate[] points = normalized(edge, tolerance, problems);
            if (points == null) {
                continue;
            }
            coordinates.put(edge.id, points);
            if (parentEdge.put(edge.toNodeId, edge) != null) {
                problems.add(diag("TOPOLOGY_VIOLATION", "У узла " + edge.toNodeId
                        + " несколько входящих участков: образуется контур или второй путь.", null, edge.id));
            }
            children.computeIfAbsent(edge.fromNodeId, key -> new ArrayList<>()).add(edge);
        }
    }

    private Coordinate[] normalized(Edge edge, double tolerance, List<Diagnostic> problems) {
        if (edge.geometry == null || edge.geometry.getNumPoints() < 2) {
            problems.add(diag("INVALID_GEOMETRY", "Участок " + edge.id + " должен быть LineString.", null, edge.id));
            return null;
        }
        Coordinate[] raw = edge.geometry.getCoordinates();
        for (Coordinate c : raw) {
            if (!Double.isFinite(c.x) || !Double.isFinite(c.y)) {
                problems.add(diag("INVALID_GEOMETRY", "Участок " + edge.id + " содержит нечисловые координаты.", null, edge.id));
                return null;
            }
        }
        Coordinate start = positions.get(edge.fromNodeId);
        Coordinate end = positions.get(edge.toNodeId);
        if (raw[0].distance(start) > tolerance || raw[raw.length - 1].distance(end) > tolerance) {
            problems.add(diag("TOPOLOGY_VIOLATION", "Концы участка " + edge.id + " не совпадают с его узлами.", null, edge.id));
            return null;
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(new Coordinate(start.x, start.y));
        for (int i = 1; i < raw.length - 1; i++) {
            if (raw[i].distance(result.get(result.size() - 1)) > tolerance) {
                result.add(new Coordinate(raw[i].x, raw[i].y));
            }
        }
        if (result.size() > 1 && result.get(result.size() - 1).distance(end) <= tolerance) {
            result.remove(result.size() - 1);
        }
        result.add(new Coordinate(end.x, end.y));
        if (result.size() < 2 || start.distance(end) <= tolerance && result.size() == 2) {
            problems.add(diag("INVALID_GEOMETRY", "Участок " + edge.id + " имеет нулевую длину.", null, edge.id));
            return null;
        }
        return result.toArray(new Coordinate[0]);
    }

    private void readAttachments(Dataset dataset, double tolerance, List<Diagnostic> problems) {
        for (Attachment attachment : candidate.attachments) {
            if (attachment == null || attachment.rootNodeId == null || !nodes.containsKey(attachment.rootNodeId)) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Присоединение ссылается на отсутствующий узел.", null, null));
                continue;
            }
            if (attachmentByRoot.put(attachment.rootNodeId, attachment) != null) {
                problems.add(diag("TOPOLOGY_VIOLATION", "У корня " + attachment.rootNodeId
                        + " несколько присоединений.", null, null));
                continue;
            }
            Node root = nodes.get(attachment.rootNodeId);
            if (root.kind == NodeKind.EXISTING_CHAMBER) {
                if (!root.inputObjectId.equals(attachment.existingObjectId)) {
                    problems.add(diag("TOPOLOGY_VIOLATION", "Присоединение к существующей камере должно ссылаться на неё.",
                            attachment.existingObjectId, null));
                }
            } else if (root.kind == NodeKind.NEW_CHAMBER) {
                InputObject line = attachment.existingObjectId == null ? null
                        : dataset.find(attachment.existingObjectId).orElse(null);
                if (line == null || line.type != InputType.HEAT_NETWORK || line.geometry == null) {
                    problems.add(diag("INVALID_ID", "Новая камера присоединения должна ссылаться на существующий heat_network.",
                            attachment.existingObjectId, null));
                } else if (line.geometry.distance(root.geometry.getFactory().createPoint(positions.get(root.id)))
                        > tolerance) {
                    problems.add(diag("TOPOLOGY_VIOLATION", "Новая камера " + root.id
                            + " не лежит на существующем участке присоединения.", attachment.existingObjectId, null));
                }
            } else {
                problems.add(diag("TOPOLOGY_VIOLATION", "Корень " + root.id
                        + " должен быть тепловой камерой.", null, null));
            }
        }
    }

    private void checkShape(List<Diagnostic> problems) {
        for (Node node : nodes.values()) {
            boolean root = isRoot(node.id);
            boolean hasParent = parentEdge.containsKey(node.id);
            int childCount = children(node.id).size();
            if (root && hasParent) {
                problems.add(diag("TOPOLOGY_VIOLATION", "У корня " + node.id + " не может быть входящего участка.",
                        node.inputObjectId, null));
            }
            if (!root && !hasParent) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Узел " + node.id
                        + " не связан с местом присоединения.", node.inputObjectId, null));
            }
            if (node.kind == NodeKind.EXISTING_CHAMBER && !root) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Существующая камера " + node.id
                        + " может быть только местом присоединения.", node.inputObjectId, null));
            }
            if (root && childCount == 0) {
                problems.add(diag("TOPOLOGY_VIOLATION", "К корню " + node.id + " не примыкает ни один новый участок.",
                        node.inputObjectId, null));
            }
            if (node.kind == NodeKind.CONNECTION_POINT && childCount > 0) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Точка подключения должна быть концом ветви.",
                        node.inputObjectId, null));
            }
            if (node.kind != NodeKind.CONNECTION_POINT && !root && childCount == 0) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Ветвь заканчивается узлом " + node.id
                        + " без точки подключения.", null, null));
            }
            if (node.kind == NodeKind.TECHNICAL_NODE && childCount != 1) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Разветвление допускается только в тепловой камере: "
                        + node.id + ".", null, null));
            }
        }
        if (!problems.isEmpty()) {
            return;
        }
        Deque<String> queue = new ArrayDeque<>(attachmentByRoot.keySet());
        Set<String> seen = new HashSet<>(attachmentByRoot.keySet());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            order.add(id);
            for (Edge edge : children(id)) {
                if (seen.add(edge.toNodeId)) {
                    queue.addLast(edge.toNodeId);
                }
            }
        }
        if (order.size() != nodes.size()) {
            problems.add(diag("TOPOLOGY_VIOLATION", "Новая сеть содержит замкнутый контур.", null, null));
        }
    }

    private void readUnconnected(Map<ObjectId, BigDecimal> pointFlows, Set<ObjectId> connectedPoints,
                                 List<Diagnostic> problems) {
        for (ObjectId id : candidate.unconnectedPointIds) {
            if (id == null || !pointFlows.containsKey(id)) {
                problems.add(diag("INVALID_ID", "Неподключённая точка отсутствует во входных данных.", id, null));
            } else if (connectedPoints.contains(id) || unconnectedFlow.put(id, pointFlows.get(id)) != null) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Точка указана дважды или одновременно подключена.", id, null));
            }
        }
        for (ObjectId id : pointFlows.keySet()) {
            if (!connectedPoints.contains(id) && !unconnectedFlow.containsKey(id)) {
                problems.add(diag("TOPOLOGY_VIOLATION", "Точка подключения потеряна кандидатом.", id, null));
            }
        }
    }

    private void sumFlows(Map<ObjectId, BigDecimal> pointFlows) {
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            Node node = nodes.get(id);
            BigDecimal flow = node.kind == NodeKind.CONNECTION_POINT ? pointFlows.get(node.inputObjectId) : BigDecimal.ZERO;
            for (Edge edge : children(id)) {
                flow = flow.add(downstreamFlow.get(edge.toNodeId));
            }
            downstreamFlow.put(id, flow);
        }
    }

    private static boolean finitePoint(org.locationtech.jts.geom.Geometry geometry) {
        if (!(geometry instanceof Point) || geometry.isEmpty()) {
            return false;
        }
        Coordinate c = geometry.getCoordinate();
        return Double.isFinite(c.x) && Double.isFinite(c.y);
    }
}
