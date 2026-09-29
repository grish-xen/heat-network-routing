package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.GF;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.id;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.rectangle;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.xy;

import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.CandidateBuilder;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.Scene;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.GridRoutePlannerFactory;
import ru.hackathon.heatnetwork.routing.RoutePlanner;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

/**
 * Section 2.2 / clarification 3: the own OKS polygon is entered only by one final straight segment from
 * its boundary; the planner takes the boundary nearest to the connection point when it is reachable.
 * The point (300, 380) lies 20 m below the top side of its 200 × 200 m building, so the approach comes
 * from above through (300, 400).
 */
class OwnOksApproachTest {
    private static final RulesCatalog CATALOG = RulesCatalog.loadDefault();
    private final DefaultVariantCalculator calculator =
            new DefaultVariantCalculator(CATALOG, new DefaultSpatialValidator(CATALOG));

    private static Scene scene() {
        return new Scene().line("L", 300, xy(0, -100), xy(0, 0)).chamber("C", xy(0, 0))
                .restriction("building", "oks", rectangle(200, 200, 400, 400))
                .point(id(1), 10, xy(300, 380));
    }

    private static RouteCandidate route(String name, Coordinate... points) {
        return new CandidateBuilder(name).existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(300, 380))
                .edge("e", "root", "p", points).build();
    }

    private static String reasons(Evaluation evaluation) {
        return evaluation.diagnostics.stream().map(d -> d.code + " " + d.message).collect(Collectors.joining("; "));
    }

    @Test
    void approachThroughTheNearestBoundaryIsAccepted() {
        Evaluation evaluation = calculator.evaluate(scene(),
                route("nearest", xy(0, 0), xy(0, 410), xy(300, 410), xy(300, 380)), Mode.TWO_D);
        assertTrue(evaluation.accepted(), () -> reasons(evaluation));
    }

    @Test
    void bendInsideTheBuildingIsRejected() {
        Evaluation evaluation = calculator.evaluate(scene(),
                route("bend", xy(0, 0), xy(0, 410), xy(250, 410), xy(250, 390), xy(300, 380)), Mode.TWO_D);
        assertRejected(evaluation, "before its final straight approach");
    }

    @Test
    void clearanceToTheOwnBuildingStillAppliesBeforeTheFinalSegment() {
        // 3 m above the roof line: the 5 m OKS clearance is waived only on the final segment.
        Evaluation evaluation = calculator.evaluate(scene(),
                route("close", xy(0, 0), xy(0, 403), xy(300, 403), xy(300, 380)), Mode.TWO_D);
        assertRejected(evaluation, "clearance to the own OKS polygon");
    }

    @Test
    void plannerApproachesThroughTheNearestBoundary() {
        Scene scene = scene();
        SearchOptions options = new SearchOptions();
        options.maxCandidates = 10;
        CalculatedVariant best = null;
        try (RoutePlanner.SearchSession session = new GridRoutePlannerFactory(CATALOG).open(scene, options)) {
            for (Optional<RouteCandidate> next = session.next(); next.isPresent(); next = session.next()) {
                Evaluation evaluation = calculator.evaluate(scene, next.get(), Mode.TWO_D);
                session.feedback(evaluation);
                if (evaluation.accepted() && evaluation.variant.unconnectedPointIds.isEmpty()) {
                    best = evaluation.variant;
                }
            }
        }
        assertTrue(best != null, "the planner must connect the point by a valid approach");
        Geometry building = rectangle(200, 200, 400, 400);
        CalculatedEdge last = best.edges.stream()
                .filter(e -> e.geometry.getEndPoint().getCoordinate().distance(xy(300, 380)) < 0.001)
                .findFirst().orElseThrow(AssertionError::new);
        LineString line = last.geometry;
        Geometry inside = building.intersection(line);
        double entry = 0;
        for (Coordinate c : inside.getCoordinates()) {
            entry = Math.max(entry, c.distance(xy(300, 380)));
        }
        assertEquals(20.0, entry, 0.01, "enters through the top side, 20 m from the point");
        int n = line.getNumPoints();
        LineString before = GF.createLineString(java.util.Arrays.copyOfRange(line.getCoordinates(), 0, n - 1));
        assertFalse(n > 2 && building.intersects(before), "only the final segment touches the building");
    }

    private static void assertRejected(Evaluation evaluation, String reason) {
        assertNull(evaluation.variant);
        assertTrue(evaluation.diagnostics.stream().anyMatch(d -> "CLEARANCE_VIOLATION".equals(d.code)
                && d.message.contains(reason)), () -> reasons(evaluation));
    }
}
