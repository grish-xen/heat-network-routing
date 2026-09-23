package ru.hackathon.heatnetwork.calculation;

import static ru.hackathon.heatnetwork.calculation.Rejection.diag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import ru.hackathon.heatnetwork.calculation.SpecialPasses.Obstacle;
import ru.hackathon.heatnetwork.calculation.SpecialPasses.Piece;
import ru.hackathon.heatnetwork.calculation.SpecialPasses.Polyline;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.ChamberCost;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.Model.LayingMethod;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.Model.Summary;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RulesCatalog;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DiameterRow;
import ru.hackathon.heatnetwork.routing.RulesCatalog.RestrictionRule;
import ru.hackathon.heatnetwork.routing.SpatialValidator;

/**
 * Module 3: engineering calculation of a route candidate in the mandatory 2D mode.
 *
 * <p>Steps: structural check of the candidate tree; downstream flows; diameters by flow,
 * maximum length and monotonicity; special passes with technical nodes; chamber
 * adjacencies and the 10 m rule; clearances to special-pass objects outside their
 * crossings; costs, penalty and score; final SpatialValidator check. A rejected
 * candidate returns diagnostics and no variant. Unexpected failures are thrown, never
 * turned into an unconnected result.</p>
 */
public final class DefaultVariantCalculator implements VariantCalculator {
    /** Team tolerance for coincident geometry, metres; it never reduces normative clearances. */
    static final double TOLERANCE_M = 0.001;
    private static final int SCORE_SCALE = 10;
    private static final String HEAT_NETWORK = "heat_network";
    private static final double DEPARTURE_STEP_M = 0.05;

    private final GeometryFactory geometry = new GeometryFactory(new PrecisionModel(), Model.METRIC_SRID);
    private final RulesCatalog catalog;
    private final SpatialValidator validator;
    private final double searchMarginM;

    public DefaultVariantCalculator(RulesCatalog catalog, SpatialValidator validator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.validator = Objects.requireNonNull(validator, "validator");
        double margin = 0;
        for (RestrictionRule rule : catalog.restrictionRules()) {
            if ("special".equals(rule.crossing)) {
                margin = Math.max(margin, catalog.clearanceFor(rule.type, largestDiameter()));
            }
        }
        this.searchMarginM = margin + 2 * widestProfile() + 1;
    }

    @Override
    public Evaluation evaluate(Dataset dataset, RouteCandidate candidate, Mode mode) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(candidate, "candidate");
        Evaluation evaluation = new Evaluation();
        evaluation.candidateId = candidate.candidateId;
        try {
            if (mode != Mode.TWO_D) {
                throw Rejection.of("UNSUPPORTED_MODE", "Режим с глубиной не реализован; доступен только 2D.", null, null);
            }
            CalculatedVariant variant = calculate(dataset, candidate);
            List<Diagnostic> spatial = validator.validate(dataset, variant);
            Rejection.throwIfAny(spatial);
            evaluation.variant = variant;
        } catch (Rejection rejection) {
            for (Diagnostic diagnostic : rejection.diagnostics) {
                diagnostic.candidateId = candidate.candidateId;
                evaluation.diagnostics.add(diagnostic);
            }
        }
        return evaluation;
    }

    private CalculatedVariant calculate(Dataset dataset, RouteCandidate candidate) throws Rejection {
        CandidateTree tree = CandidateTree.build(dataset, candidate, TOLERANCE_M);
        Map<String, Polyline> lines = new HashMap<>();
        Map<String, Double> lengths = new HashMap<>();
        for (Edge edge : candidate.edges) {
            Polyline line = new Polyline(geometry, tree.coordinates.get(edge.id));
            lines.put(edge.id, line);
            lengths.put(edge.id, line.length);
        }
        Map<String, Integer> diameters = DiameterSelector.select(tree, lengths, catalog);

        CalculatedVariant variant = new CalculatedVariant();
        variant.variantId = candidate.candidateId;
        variant.mode = Mode.TWO_D;
        Set<String> usedIds = new HashSet<>();
        Map<String, Node> finalNodes = new LinkedHashMap<>();
        for (Node node : tree.nodes.values()) {
            Node copy = new Node();
            copy.id = node.id;
            copy.kind = node.kind;
            copy.inputObjectId = node.inputObjectId;
            copy.geometry = geometry.createPoint(tree.positions.get(node.id));
            finalNodes.put(copy.id, copy);
            variant.nodes.add(copy);
            usedIds.add(copy.id);
        }
        usedIds.addAll(lengths.keySet());
        for (Attachment attachment : tree.attachmentByRoot.values()) {
            Attachment copy = new Attachment();
            copy.rootNodeId = attachment.rootNodeId;
            copy.existingObjectId = attachment.existingObjectId;
            variant.attachments.add(copy);
        }
        variant.unconnectedPointIds.addAll(tree.unconnectedFlow.keySet());

        Map<CalculatedEdge, Piece> pieceOf = new HashMap<>();
        Map<CalculatedEdge, List<Obstacle>> nearOf = new HashMap<>();
        Map<ObjectId, List<Coordinate>> permittedContacts = new HashMap<>();
        for (Edge edge : candidate.edges) {
            Polyline line = lines.get(edge.id);
            List<Obstacle> near = specialObstacles(dataset, line);
            Coordinate rootPoint = tree.isRoot(edge.fromNodeId) ? tree.positions.get(edge.fromNodeId) : null;
            List<Piece> pieces = SpecialPasses.split(edge.id, line, near, rootPoint, TOLERANCE_M);
            if (rootPoint != null) {
                Point root = geometry.createPoint(rootPoint);
                for (Obstacle obstacle : near) {
                    if (HEAT_NETWORK.equals(obstacle.type) && obstacle.geometry.distance(root) <= TOLERANCE_M) {
                        permittedContacts.computeIfAbsent(obstacle.id, key -> new ArrayList<>()).add(rootPoint);
                    }
                }
            }
            String from = edge.fromNodeId;
            for (int i = 0; i < pieces.size(); i++) {
                Piece piece = pieces.get(i);
                String to = edge.toNodeId;
                if (i + 1 < pieces.size()) {
                    Node technical = new Node();
                    technical.id = unique(edge.id + "/n" + (i + 1), usedIds);
                    technical.kind = NodeKind.TECHNICAL_NODE;
                    technical.geometry = geometry.createPoint(line.pointAt(piece.end));
                    finalNodes.put(technical.id, technical);
                    variant.nodes.add(technical);
                    to = technical.id;
                }
                String id = pieces.size() == 1 ? edge.id : unique(edge.id + "/" + (i + 1), usedIds);
                CalculatedEdge calculated = calculatedEdge(id, from, to, line, piece, diameters.get(edge.id),
                        tree.downstreamFlow.get(edge.toNodeId));
                variant.edges.add(calculated);
                pieceOf.put(calculated, piece);
                nearOf.put(calculated, near);
                for (ObjectId crossed : piece.crossed.keySet()) {
                    List<Coordinate> contacts = permittedContacts.computeIfAbsent(crossed, key -> new ArrayList<>());
                    contacts.add(calculated.geometry.getCoordinateN(0));
                    contacts.add(calculated.geometry.getCoordinateN(calculated.geometry.getNumPoints() - 1));
                }
                from = to;
            }
        }
        checkSpecialClearances(variant, pieceOf, nearOf, permittedContacts);
        costChambers(dataset, tree, variant, finalNodes);
        variant.summary = summary(tree, variant);
        return variant;
    }

    private CalculatedEdge calculatedEdge(String id, String from, String to, Polyline line, Piece piece,
                                          int diameterMm, BigDecimal flow) {
        CalculatedEdge edge = new CalculatedEdge();
        edge.id = id;
        edge.fromNodeId = from;
        edge.toNodeId = to;
        edge.geometry = geometry.createLineString(line.extract(piece.start, piece.end, TOLERANCE_M));
        edge.flowTph = flow;
        edge.diameterMm = diameterMm;
        edge.lengthM = edge.geometry.getLength();
        edge.depthStartM = null;
        edge.depthEndM = null;
        BigDecimal coefficient = BigDecimal.ONE;
        for (Obstacle obstacle : piece.crossed.values()) {
            BigDecimal value = BigDecimal.valueOf(obstacle.rule.specialCoefficient);
            if (value.compareTo(coefficient) > 0) {
                coefficient = value;
            }
            edge.crossedObjectIds.add(obstacle.id);
        }
        edge.layingMethod = piece.crossed.isEmpty() ? LayingMethod.BASE : LayingMethod.SPECIAL;
        edge.specialCoefficient = coefficient;
        edge.costRub = money(BigDecimal.valueOf(edge.lengthM)
                .multiply(BigDecimal.valueOf(catalog.row(diameterMm).newCostRubPerM))
                .multiply(coefficient));
        return edge;
    }

    /** Road, tram, gas, power cable and existing heat network objects near the edge. */
    private List<Obstacle> specialObstacles(Dataset dataset, Polyline line) {
        Envelope bounds = new Envelope();
        for (Coordinate c : line.points) {
            bounds.expandToInclude(c);
        }
        bounds.expandBy(searchMarginM);
        List<Obstacle> result = new ArrayList<>();
        try (Stream<InputObject> objects = dataset.query(bounds)) {
            Iterator<InputObject> iterator = objects.iterator();
            while (iterator.hasNext()) {
                InputObject object = iterator.next();
                if (object.geometry == null || object.geometry.isEmpty()) {
                    continue;
                }
                if (object.type == InputType.RESTRICTION) {
                    RestrictionRule rule = catalog.rule(object.restrictionType);
                    if (rule != null && "special".equals(rule.crossing)) {
                        double half = rule.profileWidthM == null ? 0 : rule.profileWidthM / 2;
                        result.add(new Obstacle(object.id, rule.type, object.geometry, rule, half));
                    }
                } else if (object.type == InputType.HEAT_NETWORK && object.geometry instanceof LineString) {
                    RestrictionRule rule = catalog.rule(HEAT_NETWORK);
                    if (rule != null) {
                        double half = profileRow(object.diameterMm).widthM / 2;
                        result.add(new Obstacle(object.id, HEAT_NETWORK, object.geometry, rule, half));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Horizontal clearance to special-pass objects where the new network passes near them.
     * Inside a crossing the clearance is not checked (section 4). From the end of a pass
     * over the object, and from the attachment to an existing line, the route is exempt
     * while it steadily moves away from the object: that is the approach, not "passing
     * near". A route that runs along the object is still a violation.
     */
    private void checkSpecialClearances(CalculatedVariant variant, Map<CalculatedEdge, Piece> pieceOf,
                                        Map<CalculatedEdge, List<Obstacle>> nearOf,
                                        Map<ObjectId, List<Coordinate>> permittedContacts) throws Rejection {
        List<Diagnostic> problems = new ArrayList<>();
        for (CalculatedEdge edge : variant.edges) {
            Piece piece = pieceOf.get(edge);
            for (Obstacle obstacle : nearOf.get(edge)) {
                if (piece.crossed.containsKey(obstacle.id)) {
                    continue;
                }
                double need = catalog.clearanceFor(obstacle.type, edge.diameterMm)
                        + catalog.halfWidthM(edge.diameterMm) + obstacle.ownHalfWidthM;
                if (obstacle.geometry.distance(edge.geometry) >= need - TOLERANCE_M) {
                    continue;
                }
                Polyline line = new Polyline(geometry, edge.geometry.getCoordinates());
                List<Coordinate> contacts = permittedContacts.getOrDefault(obstacle.id, new ArrayList<>());
                double from = touches(contacts, line.points[0])
                        ? departure(line, obstacle.geometry, need, false) : 0;
                double to = touches(contacts, line.points[line.points.length - 1])
                        ? line.length - departure(line, obstacle.geometry, need, true) : line.length;
                boolean violated = to - from > TOLERANCE_M && obstacle.geometry.distance(
                        geometry.createLineString(line.extract(from, to, TOLERANCE_M))) < need - TOLERANCE_M;
                if (violated) {
                    problems.add(diag("CLEARANCE_VIOLATION", String.format(java.util.Locale.ROOT,
                            "Участок %s ближе %.3f м к %s вне специального прохода.", edge.id, need, obstacle.type),
                            obstacle.id, edge.id));
                }
            }
        }
        Rejection.throwIfAny(problems);
    }

    private static boolean touches(List<Coordinate> contacts, Coordinate end) {
        for (Coordinate contact : contacts) {
            if (contact.distance(end) <= TOLERANCE_M) {
                return true;
            }
        }
        return false;
    }

    /** Length from one end of the line over which it strictly moves away from the object, up to the clearance. */
    private double departure(Polyline line, Geometry object, double need, boolean fromEnd) {
        double step = DEPARTURE_STEP_M;
        double previous = -1;
        for (double s = 0; s < line.length; s += step) {
            double distance = object.distance(geometry.createPoint(line.pointAt(fromEnd ? line.length - s : s)));
            if (distance >= need - TOLERANCE_M) {
                return s;
            }
            if (previous >= 0 && distance <= previous + 1e-9) {
                return s - step;
            }
            previous = distance;
        }
        return line.length;
    }

    /** Adjacency limit, the 10 m rule for new attachment chambers, chamber and tie-in costs. */
    private void costChambers(Dataset dataset, CandidateTree tree, CalculatedVariant variant,
                              Map<String, Node> finalNodes) throws Rejection {
        Map<String, Integer> newAdjacent = new HashMap<>();
        Map<String, Integer> maxDiameter = new HashMap<>();
        for (CalculatedEdge edge : variant.edges) {
            for (String id : new String[] {edge.fromNodeId, edge.toNodeId}) {
                newAdjacent.merge(id, 1, Integer::sum);
                maxDiameter.merge(id, edge.diameterMm, Math::max);
            }
        }
        Map<ObjectId, Integer> newAtExistingChamber = new HashMap<>();
        for (Node node : variant.nodes) {
            if (node.kind == NodeKind.EXISTING_CHAMBER) {
                newAtExistingChamber.put(node.inputObjectId, newAdjacent.getOrDefault(node.id, 0));
            }
        }
        List<Diagnostic> problems = new ArrayList<>();
        for (Node node : variant.nodes) {
            if (node.kind != NodeKind.EXISTING_CHAMBER && node.kind != NodeKind.NEW_CHAMBER) {
                continue;
            }
            ExistingLines existing = existingLinesAt(dataset, node.geometry.getCoordinate());
            int total = existing.adjacent + newAdjacent.getOrDefault(node.id, 0);
            if (total > catalog.maxChamberDegree()) {
                problems.add(diag("TOPOLOGY_VIOLATION", "К камере " + node.id + " примыкает " + total
                        + " линейных участков, допускается " + catalog.maxChamberDegree() + ".",
                        node.inputObjectId, null));
            }
            if (node.kind == NodeKind.NEW_CHAMBER) {
                if (tree.isRoot(node.id)) {
                    checkTenMetreRule(dataset, node, tree.children(node.id).size(), newAtExistingChamber, problems);
                }
                int diameter = Math.max(maxDiameter.getOrDefault(node.id, 0), existing.maxDiameterMm);
                ChamberCost cost = new ChamberCost();
                cost.nodeId = node.id;
                cost.diameterMm = diameter;
                cost.costRub = money(BigDecimal.valueOf(catalog.chamberCost(diameter)));
                variant.newChambers.add(cost);
            }
        }
        Rejection.throwIfAny(problems);
    }

    private void checkTenMetreRule(Dataset dataset, Node root, int newEdges, Map<ObjectId, Integer> newAtExisting,
                                   List<Diagnostic> problems) {
        double radius = catalog.existingChamberRadiusM();
        Envelope bounds = new Envelope(root.geometry.getCoordinate());
        bounds.expandBy(radius);
        try (Stream<InputObject> objects = dataset.query(bounds)) {
            Iterator<InputObject> iterator = objects.iterator();
            while (iterator.hasNext()) {
                InputObject chamber = iterator.next();
                if (chamber.type != InputType.HEAT_CHAMBER || !(chamber.geometry instanceof Point)
                        || chamber.geometry.distance(root.geometry) > radius + 1e-9) {
                    continue;
                }
                int after = existingLinesAt(dataset, chamber.geometry.getCoordinate()).adjacent
                        + newAtExisting.getOrDefault(chamber.id, 0) + newEdges;
                if (after <= catalog.maxChamberDegree()) {
                    problems.add(diag("TOPOLOGY_VIOLATION", "Новая камера " + root.id
                            + " находится не далее " + radius + " м от существующей камеры со свободными примыканиями;"
                            + " присоединение должно выполняться в существующую камеру.", chamber.id, null));
                    return;
                }
            }
        }
    }

    /** Existing line parts ending at the point: 1 per line end, 2 for a line passing through. */
    private ExistingLines existingLinesAt(Dataset dataset, Coordinate point) {
        ExistingLines result = new ExistingLines();
        Envelope bounds = new Envelope(point);
        bounds.expandBy(TOLERANCE_M);
        Point location = geometry.createPoint(point);
        try (Stream<InputObject> objects = dataset.query(bounds)) {
            Iterator<InputObject> iterator = objects.iterator();
            while (iterator.hasNext()) {
                InputObject object = iterator.next();
                if (object.type != InputType.HEAT_NETWORK || !(object.geometry instanceof LineString)
                        || object.geometry.distance(location) > TOLERANCE_M) {
                    continue;
                }
                LineString line = (LineString) object.geometry;
                int ends = 0;
                if (line.getCoordinateN(0).distance(point) <= TOLERANCE_M) {
                    ends++;
                }
                if (line.getCoordinateN(line.getNumPoints() - 1).distance(point) <= TOLERANCE_M) {
                    ends++;
                }
                result.adjacent += ends == 0 ? 2 : ends;
                if (object.diameterMm != null) {
                    result.maxDiameterMm = Math.max(result.maxDiameterMm, object.diameterMm);
                }
            }
        }
        return result;
    }

    private Summary summary(CandidateTree tree, CalculatedVariant variant) {
        BigDecimal pipes = BigDecimal.ZERO;
        double length = 0;
        for (CalculatedEdge edge : variant.edges) {
            pipes = pipes.add(edge.costRub);
            length += edge.lengthM;
        }
        BigDecimal chambers = BigDecimal.ZERO;
        for (ChamberCost chamber : variant.newChambers) {
            chambers = chambers.add(chamber.costRub);
        }
        Set<String> existingChambers = new HashSet<>();
        for (Node node : variant.nodes) {
            if (node.kind == NodeKind.EXISTING_CHAMBER) {
                existingChambers.add(node.id);
            }
        }
        int tieIns = 0;
        for (CalculatedEdge edge : variant.edges) {
            if (existingChambers.contains(edge.fromNodeId)) {
                tieIns++;
            }
            if (existingChambers.contains(edge.toNodeId)) {
                tieIns++;
            }
        }
        BigDecimal penalty = BigDecimal.ZERO;
        for (BigDecimal flow : tree.unconnectedFlow.values()) {
            penalty = penalty.add(catalog.unconnectedPenalty(flow.doubleValue()));
        }
        Summary summary = new Summary();
        summary.chamberConstructionCost = money(chambers);
        summary.existingChamberTieInCount = tieIns;
        summary.existingChamberTieInCost = money(BigDecimal.valueOf(catalog.tieInCostRub()).multiply(BigDecimal.valueOf(tieIns)));
        summary.constructionCost = money(pipes.add(summary.chamberConstructionCost).add(summary.existingChamberTieInCost));
        summary.unconnectedPenalty = money(penalty);
        summary.calculatedCost = summary.constructionCost.add(summary.unconnectedPenalty);
        summary.newNetworkLength = length;
        summary.score = score(summary.calculatedCost, length);
        return summary;
    }

    /** S = 0.7 · C / 25 000 000 + 0.3 · L / 100 (section 6). */
    BigDecimal score(BigDecimal calculatedCost, double lengthM) {
        RulesCatalog.Ranking ranking = catalog.ranking();
        BigDecimal cost = calculatedCost.multiply(BigDecimal.valueOf(ranking.costWeight))
                .divide(BigDecimal.valueOf(ranking.costBaseRub), SCORE_SCALE + 4, RoundingMode.HALF_UP);
        BigDecimal length = BigDecimal.valueOf(lengthM).multiply(BigDecimal.valueOf(ranking.lengthWeight))
                .divide(BigDecimal.valueOf(ranking.lengthBaseM), SCORE_SCALE + 4, RoundingMode.HALF_UP);
        return cost.add(length).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String unique(String base, Set<String> used) {
        String id = base;
        for (int i = 2; !used.add(id); i++) {
            id = base + "_" + i;
        }
        return id;
    }

    /** Table 1 row for an existing line profile: its diameter or the next larger catalog one. */
    private DiameterRow profileRow(Integer diameterMm) {
        List<DiameterRow> rows = catalog.diameters();
        if (diameterMm != null) {
            for (DiameterRow row : rows) {
                if (row.diameterMm >= diameterMm) {
                    return row;
                }
            }
        }
        return rows.get(rows.size() - 1);
    }

    private int largestDiameter() {
        List<DiameterRow> rows = catalog.diameters();
        return rows.get(rows.size() - 1).diameterMm;
    }

    private double widestProfile() {
        double widest = 0;
        for (DiameterRow row : catalog.diameters()) {
            widest = Math.max(widest, row.widthM);
        }
        return widest;
    }

    private static final class ExistingLines {
        int adjacent;
        int maxDiameterMm;
    }
}
