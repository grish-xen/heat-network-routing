package ru.hackathon.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.hackathon.heatnetwork.model.Model.*;

import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.hackathon.heatnetwork.model.ObjectId;

class JunctionTurnValidationTest {
    private static final GeometryFactory GF = new GeometryFactory();

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsTurnHiddenByTechnicalNode(Mode mode) {
        rejected(junction(mode, NodeKind.TECHNICAL_NODE, 5, 5));
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsTurnHiddenByChamber(Mode mode) {
        rejected(junction(mode, NodeKind.NEW_CHAMBER, 5, 5));
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksEveryOutgoingBranch(Mode mode) {
        CalculatedVariant v = junction(mode, NodeKind.NEW_CHAMBER, 20, 0);
        node(v, "backward", NodeKind.CONNECTION_POINT, 5, 5);
        edge(v, "backward", "joint", "backward", 10, 0, 5, 5);
        List<Diagnostic> turns = validate(v);
        assertEquals(1, turns.size());
        assertEquals("TURN_VIOLATION", turns.get(0).code);
        assertEquals("backward", turns.get(0).segmentId);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void usesLocalIncomingDirectionInsteadOfTheEdgeChord(Mode mode) {
        CalculatedVariant v = junction(mode, NodeKind.TECHNICAL_NODE, 15, 5);
        v.edges.remove(0);
        // Chord points east, but the final segment points south: the join turns 135 degrees.
        edge(v, "incoming", "root", "joint", 0, 0, 0, 10, 10, 10, 10, 0);
        rejected(v);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void usesLocalOutgoingDirectionInsteadOfTheEdgeChord(Mode mode) {
        CalculatedVariant v = junction(mode, NodeKind.TECHNICAL_NODE, 20, 10);
        v.edges.remove(1);
        edge(v, "outgoing", "joint", "target", 10, 0, 5, 5, 10, 10, 20, 10);
        rejected(v);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsStraightAndRightAngleJoins(Mode mode) {
        assertTrue(validate(junction(mode, NodeKind.TECHNICAL_NODE, 20, 0)).isEmpty());
        assertTrue(validate(junction(mode, NodeKind.TECHNICAL_NODE, 10, 10)).isEmpty());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsTBranchWithoutComparingSiblingDirections(Mode mode) {
        CalculatedVariant v = junction(mode, NodeKind.NEW_CHAMBER, 10, 10);
        node(v, "lower", NodeKind.CONNECTION_POINT, 10, -10);
        edge(v, "lower", "joint", "lower", 10, 0, 10, -10);
        assertTrue(validate(v).isEmpty());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rootBranchesHaveNoIncomingNewEdge(Mode mode) {
        CalculatedVariant v = junction(mode, NodeKind.NEW_CHAMBER, 20, 0);
        node(v, "left", NodeKind.CONNECTION_POINT, -10, 0);
        edge(v, "left", "root", "left", 0, 0, -10, 0);
        assertTrue(validate(v).isEmpty());
    }

    private static List<Diagnostic> validate(CalculatedVariant v) {
        return new DefaultSpatialValidator().validate(new RoutingModuleTest.InMemoryDataset(List.of()), v);
    }

    private static void rejected(CalculatedVariant v) {
        List<Diagnostic> diagnostics = validate(v);
        assertEquals(1, diagnostics.size(), "Only the junction turn should be invalid");
        assertEquals("TURN_VIOLATION", diagnostics.get(0).code);
        assertEquals("outgoing", diagnostics.get(0).segmentId);
    }

    private static CalculatedVariant junction(Mode mode, NodeKind kind, double x, double y) {
        CalculatedVariant v = new CalculatedVariant(); v.mode = mode;
        node(v, "root", NodeKind.NEW_CHAMBER, 0, 0);
        node(v, "joint", kind, 10, 0);
        node(v, "target", NodeKind.CONNECTION_POINT, x, y);
        Attachment attachment = new Attachment(); attachment.rootNodeId = "root";
        attachment.existingObjectId = new ObjectId(TextNode.valueOf("existing"));
        v.attachments.add(attachment);
        edge(v, "incoming", "root", "joint", 0, 0, 10, 0);
        edge(v, "outgoing", "joint", "target", 10, 0, x, y);
        return v;
    }

    private static void node(CalculatedVariant v, String id, NodeKind kind, double x, double y) {
        Node node = new Node(); node.id = id; node.kind = kind;
        node.geometry = GF.createPoint(new Coordinate(x, y));
        if (kind == NodeKind.CONNECTION_POINT) node.inputObjectId = new ObjectId(TextNode.valueOf(id));
        v.nodes.add(node);
    }

    private static void edge(CalculatedVariant v, String id, String from, String to, double... xy) {
        CalculatedEdge edge = new CalculatedEdge(); edge.id = id;
        edge.fromNodeId = from; edge.toNodeId = to;
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = new Coordinate(xy[2*i], xy[2*i+1]);
        edge.geometry = GF.createLineString(coordinates); edge.lengthM = edge.geometry.getLength();
        edge.diameterMm = 80; edge.flowTph = BigDecimal.ONE;
        edge.layingMethod = LayingMethod.BASE; edge.specialCoefficient = BigDecimal.ONE; edge.costRub = BigDecimal.ONE;
        if (v.mode == Mode.DEPTH) { edge.depthStartM = 3.0; edge.depthEndM = 3.0; }
        v.edges.add(edge);
    }
}
