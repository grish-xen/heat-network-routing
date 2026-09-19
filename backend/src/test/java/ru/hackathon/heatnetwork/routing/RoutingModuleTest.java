package ru.hackathon.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;
import ru.hackathon.heatnetwork.model.ObjectId;

/**
 * Module 2 tests on synthetic metric scenes (EPSG:32637) plus the shared two-consumers
 * example in WGS 84. The input parser belongs to module 1; these tests build
 * {@link Model.InputObject}s directly, which is also how the coordinator will feed
 * the planner in unit contexts.
 */
class RoutingModuleTest {

    private static final GeometryFactory GF = new GeometryFactory();

    // ------------------------------------------------------------ fixtures

    private static InputObject restriction(String id, String type, Geometry geom) {
        InputObject obj = new InputObject();
        obj.id = new ObjectId(new TextNode(id));
        obj.type = InputType.RESTRICTION;
        obj.restrictionType = type;
        obj.geometry = geom;
        return obj;
    }

    private static InputObject line(String id, int diameter, Coordinate... coords) {
        InputObject obj = new InputObject();
        obj.id = new ObjectId(new TextNode(id));
        obj.type = InputType.HEAT_NETWORK;
        obj.diameterMm = diameter;
        obj.geometry = GF.createLineString(coords);
        return obj;
    }

    private static InputObject chamber(String id, Coordinate c) {
        InputObject obj = new InputObject();
        obj.id = new ObjectId(new TextNode(id));
        obj.type = InputType.HEAT_CHAMBER;
        obj.geometry = GF.createPoint(c);
        return obj;
    }

    private static ObjectId numId(int id) {
        return new ObjectId(new DoubleNode(id));
    }

    private static InputObject connectionPoint(int id, double flow, Coordinate c) {
        InputObject obj = new InputObject();
        obj.id = numId(id);
        obj.type = InputType.OKS_CONNECTION_POINT;
        obj.flowTph = BigDecimal.valueOf(flow);
        obj.geometry = GF.createPoint(c);
        return obj;
    }

    private static InputObject connectionPoint(String id, double flow, Coordinate c) {
        InputObject obj = new InputObject();
        obj.id = new ObjectId(new TextNode(id));
        obj.type = InputType.OKS_CONNECTION_POINT;
        obj.flowTph = BigDecimal.valueOf(flow);
        obj.geometry = GF.createPoint(c);
        return obj;
    }

    private static Polygon square(double x1, double y1, double x2, double y2) {
        return GF.createPolygon(new Coordinate[] {
                new Coordinate(x1, y1), new Coordinate(x2, y1),
                new Coordinate(x2, y2), new Coordinate(x1, y2), new Coordinate(x1, y1)});
    }

    private static SearchOptions twoD(int maxCandidates) {
        SearchOptions options = new SearchOptions();
        options.mode = Model.Mode.TWO_D;
        options.maxCandidates = maxCandidates;
        return options;
    }

    private static List<Model.RouteCandidate> drain(GridRoutePlanner planner) {
        List<Model.RouteCandidate> candidates = new ArrayList<>();
        while (true) {
            Optional<Model.RouteCandidate> next = planner.next();
            if (!next.isPresent()) {
                break;
            }
            candidates.add(next.get());
        }
        return candidates;
    }

    private static void assertPointConnected(Model.RouteCandidate candidate, ObjectId targetId) {
        boolean found = false;
        for (Model.Node node : candidate.nodes) {
            if (node.kind == Model.NodeKind.CONNECTION_POINT && targetId.equals(node.inputObjectId)) {
                found = true;
            }
        }
        assertTrue(found, "connection point " + targetId + " must be present as a node");
        assertFalse(candidate.unconnectedPointIds.contains(targetId),
                "connection point " + targetId + " must not be unconnected");
    }

    // --------------------------------------------------------------- tests

    @Test
    void singleTargetWithoutObstaclesConnectsByStraightLine() {
        // Existing line along y=0 from (0,0) to (400,0), chamber at (0,0),
        // target at (200,300): expected trace is a near-straight path upward.
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("L1", 300, new Coordinate(0, 0), new Coordinate(400, 0)));
        objects.add(chamber("C1", new Coordinate(0, 0)));
        objects.add(connectionPoint(1, 10, new Coordinate(200, 300)));

        GridRoutePlanner planner = new GridRoutePlanner(new InMemoryDataset(objects), twoD(10), null);
        List<Model.RouteCandidate> candidates = drain(planner);
        assertFalse(candidates.isEmpty(), "at least one candidate expected");
        Model.RouteCandidate best = candidates.get(0);
        assertPointConnected(best, numId(1));
        assertEquals(1, best.attachments.size(), "one component with one attachment");
        assertFalse(best.edges.isEmpty());
        for (Model.Edge edge : best.edges) {
            assertTrue(edge.geometry.getCoordinateN(0).distance(edge.geometry.getEndPoint().getCoordinate()) > 1.0,
                    "edge must have positive length");
        }
        planner.close();
    }

    @Test
    void plannerRoutesAroundForbiddenPark() {
        // Direct corridor from the line to the target is blocked by a park square.
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("L1", 300, new Coordinate(0, 0), new Coordinate(600, 0)));
        objects.add(chamber("C1", new Coordinate(0, 0)));
        // park: forbidden, 1 m clearance
        objects.add(restriction("P1", "park", square(150, 100, 450, 400)));
        objects.add(connectionPoint(1, 10, new Coordinate(300, 500)));

        GridRoutePlanner planner = new GridRoutePlanner(new InMemoryDataset(objects), twoD(10), null);
        List<Model.RouteCandidate> candidates = drain(planner);
        assertFalse(candidates.isEmpty());
        Model.RouteCandidate best = candidates.get(0);
        assertPointConnected(best, numId(1));
        // The trace must not cross the park polygon body.
        for (Model.Edge edge : best.edges) {
            Geometry park = square(150, 100, 450, 400);
            assertFalse(edge.geometry.intersects(park), "trace must not cross the park");
        }
        planner.close();
    }

    @Test
    void turnAngleNeverExceeds90Degrees() {
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("L1", 300, new Coordinate(0, 0), new Coordinate(600, 0)));
        objects.add(chamber("C1", new Coordinate(0, 0)));
        objects.add(connectionPoint(1, 10, new Coordinate(300, 400)));

        GridRoutePlanner planner = new GridRoutePlanner(new InMemoryDataset(objects), twoD(10), null);
        List<Model.RouteCandidate> candidates = drain(planner);
        assertFalse(candidates.isEmpty());
        for (Model.RouteCandidate candidate : candidates) {
            for (Model.Edge edge : candidate.edges) {
                Coordinate[] coords = edge.geometry.getCoordinates();
                for (int i = 1; i + 1 < coords.length; i++) {
                    double d1x = coords[i].x - coords[i - 1].x;
                    double d1y = coords[i].y - coords[i - 1].y;
                    double d2x = coords[i + 1].x - coords[i].x;
                    double d2y = coords[i + 1].y - coords[i].y;
                    assertTrue(d1x * d2x + d1y * d2y >= -1e-9,
                            "turn above 90 degrees at vertex " + i);
                }
            }
        }
        planner.close();
    }

    @Test
    void ownOksPolygonAllowsFinalApproach() {
        // Target inside its own OKS polygon; the final segment may enter it.
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("L1", 300, new Coordinate(0, 0), new Coordinate(600, 0)));
        objects.add(chamber("C1", new Coordinate(0, 0)));
        objects.add(restriction("OKS1", "oks", square(200, 200, 400, 400)));
        objects.add(connectionPoint(1, 10, new Coordinate(300, 300)));

        GridRoutePlanner planner = new GridRoutePlanner(new InMemoryDataset(objects), twoD(10), null);
        List<Model.RouteCandidate> candidates = drain(planner);
        assertFalse(candidates.isEmpty());
        Model.RouteCandidate best = candidates.get(0);
        assertPointConnected(best, numId(1));
        planner.close();
    }

    @Test
    void depthModeIsRejected() {
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("L1", 300, new Coordinate(0, 0), new Coordinate(400, 0)));
        objects.add(connectionPoint(1, 10, new Coordinate(200, 100)));
        SearchOptions options = twoD(5);
        options.mode = Model.Mode.DEPTH;
        // Contract 1.0: DEPTH is reserved; opening a session must fail with UNSUPPORTED_MODE.
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> new GridRoutePlanner(new InMemoryDataset(objects), options, null));
    }

    @Test
    void validatorPassesTreeAndFlagsBadTurn() {
        // Build a valid two-edge tree by hand and check it; then a bad-turn variant.
        Model.CalculatedVariant variant = new Model.CalculatedVariant();
        variant.mode = Model.Mode.TWO_D;

        Model.Node root = new Model.Node();
        root.id = "root";
        root.kind = Model.NodeKind.NEW_CHAMBER;
        root.geometry = GF.createPoint(new Coordinate(0, 0));
        variant.nodes.add(root);
        Model.Attachment attachment = new Model.Attachment();
        attachment.rootNodeId = root.id;
        variant.attachments.add(attachment);

        Model.Node leaf = new Model.Node();
        leaf.id = "leaf";
        leaf.kind = Model.NodeKind.CONNECTION_POINT;
        leaf.geometry = GF.createPoint(new Coordinate(200, 0));
        variant.nodes.add(leaf);

        Model.CalculatedEdge edge = new Model.CalculatedEdge();
        edge.id = "e1";
        edge.fromNodeId = "root";
        edge.toNodeId = "leaf";
        edge.diameterMm = 100;
        edge.geometry = GF.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(100, 50), new Coordinate(200, 0)});
        variant.edges.add(edge);

        DefaultSpatialValidator validator = new DefaultSpatialValidator(RulesCatalog.loadDefault());
        List<Model.Diagnostic> diagnostics = validator.validate(new InMemoryDataset(new ArrayList<>()), variant);
        assertTrue(diagnostics.isEmpty(), "valid tree must pass, got " + diagnostics);

        // Now an edge with a 180-degree turn (zigzag back).
        edge.geometry = GF.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(0, 0), new Coordinate(200, 0)});
        diagnostics = validator.validate(new InMemoryDataset(new ArrayList<>()), variant);
        assertTrue(diagnostics.stream().anyMatch(d -> "TURN_VIOLATION".equals(d.code)),
                "180-degree zigzag must be flagged");
    }

    @Test
    void validatorFlagsForbiddenCrossing() {
        Model.CalculatedVariant variant = new Model.CalculatedVariant();
        variant.mode = Model.Mode.TWO_D;

        Model.Node root = new Model.Node();
        root.id = "root";
        root.kind = Model.NodeKind.NEW_CHAMBER;
        root.geometry = GF.createPoint(new Coordinate(0, 0));
        variant.nodes.add(root);
        Model.Attachment attachment = new Model.Attachment();
        attachment.rootNodeId = root.id;
        variant.attachments.add(attachment);

        Model.Node leaf = new Model.Node();
        leaf.id = "leaf";
        leaf.kind = Model.NodeKind.CONNECTION_POINT;
        leaf.geometry = GF.createPoint(new Coordinate(300, 0));
        variant.nodes.add(leaf);

        Model.CalculatedEdge edge = new Model.CalculatedEdge();
        edge.id = "e1";
        edge.fromNodeId = "root";
        edge.toNodeId = "leaf";
        edge.diameterMm = 100;
        edge.geometry = GF.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(300, 0)});
        variant.edges.add(edge);

        List<InputObject> objects = new ArrayList<>();
        objects.add(restriction("W1", "water", square(100, -50, 200, 50)));

        DefaultSpatialValidator validator = new DefaultSpatialValidator(RulesCatalog.loadDefault());
        List<Model.Diagnostic> diagnostics = validator.validate(new InMemoryDataset(objects), variant);
        assertTrue(diagnostics.stream().anyMatch(d -> "CLEARANCE_VIOLATION".equals(d.code)),
                "crossing water must be flagged, got " + diagnostics);
    }

    @Test
    void twoConsumersExampleProducesTreeWithBothTargets() {
        // Metric scene of the shared two-consumers example: existing chamber at the root,
        // targets 100 m east then 50 m north/south. The planner should connect both.
        List<InputObject> objects = new ArrayList<>();
        objects.add(line("existing-line", 300,
                new Coordinate(400000, 6170000), new Coordinate(399900, 6170000)));
        objects.add(chamber("existing-chamber", new Coordinate(400000, 6170000)));
        objects.add(connectionPoint(1, 10, new Coordinate(400100, 6170050)));
        objects.add(connectionPoint("2", 15, new Coordinate(400100, 6169950)));

        GridRoutePlanner planner = new GridRoutePlanner(new InMemoryDataset(objects), twoD(10), null);
        List<Model.RouteCandidate> candidates = drain(planner);
        assertFalse(candidates.isEmpty(), "candidate expected for the two-consumers scene");
        // Candidates are incremental (one new trace each); the last must connect both targets.
        Model.RouteCandidate last = candidates.get(candidates.size() - 1);
        assertPointConnected(last, numId(1));
        assertPointConnected(last, new ObjectId(new TextNode("2")));
        // Tree property: every edge endpoints' set is covered, no node has two parents.
        planner.close();
    }

    // ------------------------------------------------------- in-memory dataset

    /** Minimal Dataset for tests; module 1 provides the production implementation. */
    static final class InMemoryDataset implements ru.hackathon.heatnetwork.model.Dataset {
        private final Map<ObjectId, InputObject> objects = new ConcurrentHashMap<>();

        InMemoryDataset(List<InputObject> input) {
            for (InputObject obj : input) {
                objects.put(obj.id, obj);
            }
        }

        @Override
        public Stream<InputObject> objects(InputType type) {
            return objects.values().stream()
                    .filter(obj -> type == null || obj.type == type);
        }

        @Override
        public Optional<InputObject> find(ObjectId id) {
            return Optional.ofNullable(objects.get(id));
        }

        @Override
        public Stream<InputObject> query(Envelope bounds) {
            return objects.values().stream()
                    .filter(obj -> obj.geometry != null && obj.geometry.getEnvelopeInternal().intersects(bounds));
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
