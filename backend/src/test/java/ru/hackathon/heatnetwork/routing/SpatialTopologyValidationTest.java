package ru.hackathon.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.model.Model.*;

import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.hackathon.heatnetwork.model.ObjectId;

class SpatialTopologyValidationTest {
    private static final GeometryFactory GF = new GeometryFactory();
    private final DefaultSpatialValidator validator = new DefaultSpatialValidator();

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsOverlappingPipesEvenWhenTheyShareARoot(Mode mode) {
        CalculatedVariant v = rooted(mode);
        node(v, "a", NodeKind.CONNECTION_POINT, 10, 0);
        node(v, "b", NodeKind.CONNECTION_POINT, 20, 0);
        edge(v, "a", "root", "a", 0, 0, 10, 0);
        edge(v, "b", "root", "b", 0, 0, 20, 0);
        rejected(v);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void anotherEndpointIsNotASharedNode(Mode mode) {
        CalculatedVariant v = rooted(mode);
        node(v, "a", NodeKind.CONNECTION_POINT, 10, 0);
        node(v, "b", NodeKind.CONNECTION_POINT, 20, 0);
        edge(v, "a", "root", "a", 0, 0, 10, 0);
        edge(v, "b", "root", "b", 0, 0, 0, 10, 10, 10, 10, 0, 20, 0);
        rejected(v);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsDisconnectedCycleAlongsideAValidTree(Mode mode) {
        CalculatedVariant v = rooted(mode);
        node(v, "target", NodeKind.CONNECTION_POINT, 0, 10);
        edge(v, "tree", "root", "target", 0, 0, 0, 10);
        node(v, "a", NodeKind.TECHNICAL_NODE, 20, 20);
        node(v, "b", NodeKind.TECHNICAL_NODE, 30, 20);
        node(v, "c", NodeKind.TECHNICAL_NODE, 30, 30);
        node(v, "d", NodeKind.TECHNICAL_NODE, 20, 30);
        edge(v, "ab", "a", "b", 20, 20, 30, 20);
        edge(v, "bc", "b", "c", 30, 20, 30, 30);
        edge(v, "cd", "c", "d", 30, 30, 20, 30);
        edge(v, "da", "d", "a", 20, 30, 20, 20);
        rejected(v);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsBranchingAtTheActualCommonRoot(Mode mode) {
        CalculatedVariant v = rooted(mode);
        node(v, "a", NodeKind.CONNECTION_POINT, 10, 0);
        node(v, "b", NodeKind.CONNECTION_POINT, 0, 10);
        edge(v, "a", "root", "a", 0, 0, 10, 0);
        edge(v, "b", "root", "b", 0, 0, 0, 10);
        assertTrue(validate(v).isEmpty());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsSeparateRootedComponentsAndEmptyPenaltyVariant(Mode mode) {
        CalculatedVariant empty = new CalculatedVariant();
        empty.mode = mode;
        empty.unconnectedPointIds.add(new ObjectId(TextNode.valueOf("missing")));
        assertTrue(validate(empty).isEmpty());
        CalculatedVariant v = rooted(mode);
        node(v, "a", NodeKind.CONNECTION_POINT, 10, 0);
        edge(v, "a", "root", "a", 0, 0, 10, 0);
        node(v, "other-root", NodeKind.NEW_CHAMBER, 20, 0);
        attach(v, "other-root");
        node(v, "b", NodeKind.CONNECTION_POINT, 30, 0);
        edge(v, "b", "other-root", "b", 20, 0, 30, 0);
        assertTrue(validate(v).isEmpty());
    }

    private List<Diagnostic> validate(CalculatedVariant v) {
        return validator.validate(new RoutingModuleTest.InMemoryDataset(List.of()), v);
    }

    private void rejected(CalculatedVariant v) {
        List<Diagnostic> diagnostics = validate(v);
        assertTrue(diagnostics.stream().anyMatch(d -> "TOPOLOGY_VIOLATION".equals(d.code)),
                "Expected topology rejection; got " + diagnostics.stream().map(d -> d.code).collect(java.util.stream.Collectors.toList()));
    }

    private static CalculatedVariant rooted(Mode mode) {
        CalculatedVariant v = new CalculatedVariant(); v.mode = mode;
        node(v, "root", NodeKind.NEW_CHAMBER, 0, 0);
        attach(v, "root");
        return v;
    }

    private static void attach(CalculatedVariant v, String root) {
        Attachment a = new Attachment(); a.rootNodeId = root;
        a.existingObjectId = new ObjectId(TextNode.valueOf("existing-" + root));
        v.attachments.add(a);
    }

    private static void node(CalculatedVariant v, String id, NodeKind kind, double x, double y) {
        Node n = new Node(); n.id = id; n.kind = kind; n.geometry = GF.createPoint(new Coordinate(x, y));
        if (kind == NodeKind.CONNECTION_POINT) n.inputObjectId = new ObjectId(TextNode.valueOf(id));
        v.nodes.add(n);
    }

    private static void edge(CalculatedVariant v, String id, String from, String to, double... xy) {
        CalculatedEdge e = new CalculatedEdge(); e.id = id; e.fromNodeId = from; e.toNodeId = to;
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = new Coordinate(xy[2*i], xy[2*i+1]);
        e.geometry = GF.createLineString(coordinates); e.lengthM = e.geometry.getLength();
        e.diameterMm = 80; e.flowTph = BigDecimal.ONE; e.layingMethod = LayingMethod.BASE;
        e.specialCoefficient = BigDecimal.ONE; e.costRub = BigDecimal.ONE;
        if (v.mode == Mode.DEPTH) { e.depthStartM = 3.0; e.depthEndM = 3.0; }
        v.edges.add(e);
    }
}
