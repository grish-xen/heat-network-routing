package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.*;
import static ru.hackathon.heatnetwork.model.Model.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.CandidateBuilder;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.Scene;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

class PolygonEntryCalculationTest {
    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsPerpendicularEntryWithShallowExit(Mode mode) {
        check(mode, asymmetric(), false, true);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void rejectsSamePolygonWhenShallowExitBecomesEntry(Mode mode) {
        check(mode, asymmetric(), true, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksEntryIntoEveryPolygonComponent(Mode mode) {
        Polygon second = polygon(50,-5, 70,5, 85,5, 65,-5, 50,-5);
        check(mode, GF.createMultiPolygon(new Polygon[]{rectangle(20,-10,30,10), second}), false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void checksReentryAfterAHole(Mode mode) {
        LinearRing hole = GF.createLinearRing(new Coordinate[]{xy(40,-5), xy(40,5), xy(70,5), xy(50,-5), xy(40,-5)});
        Polygon road = GF.createPolygon(rectangle(20,-10,90,10).getExteriorRing(), new LinearRing[]{hole});
        check(mode, road, false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void stillRejectsShallowLinearCrossing(Mode mode) {
        check(mode, GF.createLineString(new Coordinate[]{xy(40,-5), xy(60,5)}), false, false);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void acceptsFortyFiveDegreeEntryWithShallowExit(Mode mode) {
        check(mode, polygon(30,-10, 50,10, 90,10, 50,-10, 30,-10), false, true);
    }

    private static void check(Mode mode, Geometry obstacle, boolean reverse, boolean accepted) {
        for (String type : new String[]{"road", "tram_tracks"}) {
            double start = reverse ? 120 : 0, end = 120 - start;
            Scene scene = new Scene().line("network", 300, xy(start,-100), xy(start,0))
                    .chamber("chamber", xy(start,0)).point(id(1), 10, xy(end,0))
                    .restriction("crossing", type, obstacle);
            RouteCandidate candidate = new CandidateBuilder("entry")
                    .existingRoot("root", "chamber", xy(start,0)).target("target", id(1), xy(end,0))
                    .edge("edge", "root", "target", xy(start,0), xy(end,0)).build();
            RulesCatalog rules = RulesCatalog.loadDefault();
            Evaluation evaluation = new DefaultVariantCalculator(rules, new DefaultSpatialValidator(rules))
                    .evaluate(scene, candidate, mode);
            if (accepted) {
                assertTrue(evaluation.accepted(), () -> type + ": " + evaluation.diagnostics.stream()
                        .map(d -> d.code + " " + d.message).collect(java.util.stream.Collectors.toList()));
                assertEquals(mode, evaluation.variant.mode);
                assertTrue(evaluation.variant.unconnectedPointIds.isEmpty());
                assertEquals(120, evaluation.variant.edges.stream().mapToDouble(e -> e.lengthM).sum(), 1e-7);
                assertTrue(evaluation.variant.edges.stream().anyMatch(e -> e.layingMethod == LayingMethod.SPECIAL
                        && e.crossedObjectIds.contains(id("crossing"))));
                if (mode == Mode.DEPTH) assertTrue(evaluation.variant.edges.stream().allMatch(
                        e -> e.depthStartM != null && e.depthEndM != null));
            } else {
                assertFalse(evaluation.accepted());
                assertTrue(evaluation.diagnostics.stream().anyMatch(d -> "SPECIAL_PASS_VIOLATION".equals(d.code)
                        && id("crossing").equals(d.inputObjectId) && "edge".equals(d.segmentId)));
            }
        }
    }

    private static Polygon asymmetric() { return polygon(40,-10, 40,10, 80,10, 50,-10, 40,-10); }
    private static Polygon polygon(double... xy) {
        Coordinate[] points = new Coordinate[xy.length / 2];
        for (int i = 0; i < points.length; i++) points[i] = xy(xy[2*i], xy[2*i+1]);
        return GF.createPolygon(points);
    }
}
