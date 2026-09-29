package ru.hackathon.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.model.Model.*;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.model.ObjectId;

class SpecialCrossingAngleTest {
    private static final GeometryFactory GF = new GeometryFactory();
    private static final ObjectId ROAD = new ObjectId(TextNode.valueOf("road"));

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsShallowLocalCrossingDespitePerpendicularChord(Mode mode) {
        check(mode, line(0,-10, -10,-5, 10,5, 0,10), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsPerpendicularLocalCrossingDespiteShallowChord(Mode mode) {
        check(mode, line(-20,-5, 0,-5, 0,5, 20,5), true, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksEveryCrossingOfALine(Mode mode) {
        check(mode, line(-10,-10, -10,10, 0,10, 40,-10), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksAllMultiLineComponents(Mode mode) {
        check(mode, GF.createMultiLineString(new LineString[]{line(-20,-10, -20,10),
                line(0,-5, 20,5)}), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void doesNotInventSegmentsBetweenComponents(Mode mode) {
        check(mode, GF.createMultiLineString(new LineString[]{line(-20,-10, -20,10),
                line(20,10, 20,-10), line(-10,5, 10,5)}), true, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsShallowPolygonEntry(Mode mode) {
        check(mode, shallowPolygon(), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksPolygonEntryRatherThanExit(Mode mode) {
        check(mode, asymmetricPolygon(), true, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void reversingRouteChangesPolygonEntry(Mode mode) {
        check(mode, asymmetricPolygon(), false, true, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksEntryIntoEachPolygonComponent(Mode mode) {
        Polygon first = polygon(-30,-5, -25,-5, -25,5, -30,5, -30,-5);
        check(mode, GF.createMultiPolygon(new Polygon[]{first, shallowPolygon()}), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksReentryAfterAHole(Mode mode) {
        LinearRing outer = GF.createLinearRing(line(-15,-10, 15,-10, 15,10, -15,10, -15,-10).getCoordinates());
        LinearRing hole = GF.createLinearRing(line(-10,0, 0,3, 10,0, 0,-3, -10,0).getCoordinates());
        check(mode, GF.createPolygon(outer, new LinearRing[]{hole}), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsExactlyFortyFiveDegrees(Mode mode) {
        check(mode, line(-10,-10, 10,10), true, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsJustBelowMinimumWithoutUsingMetreToleranceAsDegrees(Mode mode) {
        double y = 10 * Math.tan(Math.toRadians(44.9995));
        check(mode, line(-10,-y, 10,y), false, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void restoresSplitPassBeforeCheckingPolygonEntry(Mode mode) {
        check(mode, asymmetricPolygon(), true, false, true);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void splitPassDoesNotHideBadAngle(Mode mode) {
        check(mode, shallowPolygon(), false, false, true);
    }

    private static void check(Mode mode, Geometry obstacle, boolean accepted, boolean reverse, boolean split) {
        for (String type : List.of("road", "tram_tracks")) {
            InputObject road = new InputObject(); road.id = ROAD; road.type = InputType.RESTRICTION;
            road.restrictionType = type; road.geometry = obstacle;
            CalculatedVariant v = new CalculatedVariant(); v.mode = mode;
            double start = reverse ? 60 : -60, end = -start;
            node(v, "root", NodeKind.NEW_CHAMBER, start);
            node(v, "target", NodeKind.CONNECTION_POINT, end);
            Attachment attachment = new Attachment(); attachment.rootNodeId = "root";
            attachment.existingObjectId = new ObjectId(TextNode.valueOf("network")); v.attachments.add(attachment);
            if (split) {
                node(v, "joint", NodeKind.TECHNICAL_NODE, 0);
                edge(v, "a", "root", "joint", start, 0);
                edge(v, "b", "joint", "target", 0, end);
            } else edge(v, "pass", "root", "target", start, end);
            // Exercise numerical geometry at production UTM magnitudes as well.
            var shift = org.locationtech.jts.geom.util.AffineTransformation.translationInstance(399123.25, 6169456.75);
            road.geometry = shift.transform(road.geometry);
            for (Node node : v.nodes) node.geometry = (Point) shift.transform(node.geometry);
            for (CalculatedEdge edge : v.edges) edge.geometry = (LineString) shift.transform(edge.geometry);
            List<Diagnostic> diagnostics = new DefaultSpatialValidator().validate(
                    new RoutingModuleTest.InMemoryDataset(List.of(road)), v);
            if (accepted) assertTrue(diagnostics.isEmpty(), type + ": " + diagnostics.stream()
                    .map(d -> d.message).collect(java.util.stream.Collectors.toList()));
            else {
                assertFalse(diagnostics.isEmpty(), "Expected invalid crossing angle for " + type);
                for (Diagnostic d : diagnostics) {
                    assertEquals("SPECIAL_PASS_VIOLATION", d.code);
                    assertEquals(ROAD, d.inputObjectId);
                    assertNotNull(d.segmentId);
                }
            }
        }
    }

    private static Polygon shallowPolygon() { return polygon(-14,-5, 6,5, 14,5, -6,-5, -14,-5); }
    private static Polygon asymmetricPolygon() { return polygon(-5,-10, -5,10, 40,10, 0,-10, -5,-10); }
    private static Polygon polygon(double... xy) { return GF.createPolygon(line(xy).getCoordinates()); }
    private static LineString line(double... xy) {
        Coordinate[] points = new Coordinate[xy.length / 2];
        for (int i = 0; i < points.length; i++) points[i] = new Coordinate(xy[2*i], xy[2*i+1]);
        return GF.createLineString(points);
    }
    private static void node(CalculatedVariant v, String id, NodeKind kind, double x) {
        Node node = new Node(); node.id = id; node.kind = kind; node.geometry = GF.createPoint(new Coordinate(x, 0));
        if (kind == NodeKind.CONNECTION_POINT) node.inputObjectId = new ObjectId(TextNode.valueOf(id));
        v.nodes.add(node);
    }
    private static void edge(CalculatedVariant v, String id, String from, String to, double start, double end) {
        CalculatedEdge edge = new CalculatedEdge(); edge.id = id; edge.fromNodeId = from; edge.toNodeId = to;
        edge.geometry = line(start,0, end,0); edge.lengthM = edge.geometry.getLength();
        edge.diameterMm = 80; edge.layingMethod = LayingMethod.SPECIAL; edge.crossedObjectIds.add(ROAD);
        if (v.mode == Mode.DEPTH) { edge.depthStartM = 3.0; edge.depthEndM = 3.0; }
        v.edges.add(edge);
    }
}
