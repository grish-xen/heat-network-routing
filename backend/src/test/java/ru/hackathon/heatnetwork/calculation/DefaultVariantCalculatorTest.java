package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.GF;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.id;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.rectangle;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.xy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.CandidateBuilder;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.Scene;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.LayingMethod;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

class DefaultVariantCalculatorTest {
    private static final RulesCatalog CATALOG = RulesCatalog.loadDefault();
    private final DefaultVariantCalculator calculator =
            new DefaultVariantCalculator(CATALOG, new DefaultSpatialValidator(CATALOG));
    /** Isolates calculator rules from module 2 checks where a scene targets only them. */
    private final DefaultVariantCalculator withoutValidator =
            new DefaultVariantCalculator(CATALOG, (dataset, variant) -> new ArrayList<>());

    /** Existing line ending in chamber C at the origin, the usual attachment of these scenes. */
    private static Scene baseScene() {
        return new Scene().line("L", 300, xy(0, -100), xy(0, 0)).chamber("C", xy(0, 0));
    }

    private static CalculatedVariant accepted(Evaluation evaluation) {
        assertTrue(evaluation.accepted(), () -> "rejected: " + evaluation.diagnostics.stream()
                .map(d -> d.code + " " + d.message).collect(Collectors.joining("; ")));
        return evaluation.variant;
    }

    private static void assertRejected(Evaluation evaluation, String code) {
        assertNull(evaluation.variant);
        assertTrue(evaluation.diagnostics.stream().anyMatch(d -> code.equals(d.code)),
                () -> "expected " + code + ", got " + evaluation.diagnostics.stream()
                        .map(d -> d.code + " " + d.message).collect(Collectors.joining("; ")));
        assertTrue(evaluation.diagnostics.stream().allMatch(d -> evaluation.candidateId.equals(d.candidateId)));
    }

    private static CalculatedEdge edge(CalculatedVariant variant, String id) {
        return variant.edges.stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow(AssertionError::new);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + ", got " + actual);
    }

    @Test
    void referenceTwoConsumersExampleMatchesTheAgreedCalculation() {
        Scene scene = baseScene()
                .point(id(1), 10, xy(100, 50))
                .point(id("2"), 15, xy(100, -50));
        RouteCandidate candidate = new CandidateBuilder("two-consumers")
                .existingRoot("root", "C", xy(0, 0))
                .chamber("branch", xy(100, 0))
                .target("a", id(1), xy(100, 50))
                .target("b", id("2"), xy(100, -50))
                .edge("trunk", "root", "branch", xy(0, 0), xy(100, 0))
                .edge("to-a", "branch", "a", xy(100, 0), xy(100, 50))
                .edge("to-b", "branch", "b", xy(100, 0), xy(100, -50))
                .build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertEquals("two-consumers", variant.variantId);
        assertEquals(125, edge(variant, "trunk").diameterMm);
        assertMoney("25", edge(variant, "trunk").flowTph);
        assertMoney("9727500", edge(variant, "trunk").costRub);
        assertEquals(80, edge(variant, "to-a").diameterMm);
        assertMoney("4176500", edge(variant, "to-a").costRub);
        assertEquals(100, edge(variant, "to-b").diameterMm);
        assertMoney("4487400", edge(variant, "to-b").costRub);
        assertEquals(1, variant.newChambers.size());
        assertEquals(125, variant.newChambers.get(0).diameterMm);
        assertMoney("3000000", variant.newChambers.get(0).costRub);
        assertEquals(1, variant.summary.existingChamberTieInCount);
        assertMoney("5000000", variant.summary.existingChamberTieInCost);
        assertMoney("26391400", variant.summary.constructionCost);
        assertMoney("26391400", variant.summary.calculatedCost);
        assertMoney("0", variant.summary.unconnectedPenalty);
        assertEquals(200, variant.summary.newNetworkLength, 1e-9);
        assertMoney("1.3389592", variant.summary.score);
        assertTrue(variant.edges.stream().allMatch(e -> e.layingMethod == LayingMethod.BASE
                && e.depthStartM == null && e.depthEndM == null && e.geometry.getSRID() == 32637));
    }

    @Test
    void lengthLimitSelectsTheNextDiameter() {
        Scene scene = baseScene().point(id(1), 3, xy(200, 0));
        RouteCandidate candidate = new CandidateBuilder("long")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(200, 0))
                .edge("e", "root", "p", xy(0, 0), xy(200, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertEquals(65, edge(variant, "e").diameterMm, "ДУ 50 allows only 181 m");
        assertMoney("15726200", edge(variant, "e").costRub);
        assertMoney("1.1803336", variant.summary.score);
    }

    @Test
    void constantFlowKeepsOneDiameterThroughAChamberWithoutBranching() {
        Scene scene = baseScene().point(id(1), 3, xy(200, 0));
        RouteCandidate candidate = new CandidateBuilder("chain")
                .existingRoot("root", "C", xy(0, 0)).chamber("m", xy(100, 0)).target("p", id(1), xy(200, 0))
                .edge("e1", "root", "m", xy(0, 0), xy(100, 0))
                .edge("e2", "m", "p", xy(100, 0), xy(200, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertEquals(65, edge(variant, "e1").diameterMm);
        assertEquals(65, edge(variant, "e2").diameterMm, "the count does not restart without a diameter change");
    }

    @Test
    void diameterNeverDecreasesTowardsTheAttachmentAndSharedPathsCountTheTrunk() {
        CalculatedVariant shortTrunk = accepted(calculator.evaluate(branchScene(20), branchCandidate(20), Mode.TWO_D));
        assertEquals(80, edge(shortTrunk, "to-a").diameterMm, "300 m at 3 t/h needs ДУ 80");
        assertEquals(50, edge(shortTrunk, "to-c").diameterMm);
        assertEquals(80, edge(shortTrunk, "trunk").diameterMm, "6 t/h fits ДУ 65, but ДУ may not decrease");

        CalculatedVariant longTrunk = accepted(calculator.evaluate(branchScene(50), branchCandidate(50), Mode.TWO_D));
        assertEquals(80, edge(longTrunk, "to-a").diameterMm);
        assertEquals(100, edge(longTrunk, "trunk").diameterMm, "50 + 300 m of ДУ 80 exceeds 327 m");
    }

    private static Scene branchScene(double trunk) {
        return baseScene().point(id("a"), 3, xy(trunk, 300)).point(id("c"), 3, xy(trunk, -50));
    }

    private static RouteCandidate branchCandidate(double trunk) {
        return new CandidateBuilder("branch-" + trunk)
                .existingRoot("root", "C", xy(0, 0)).chamber("b", xy(trunk, 0))
                .target("a", id("a"), xy(trunk, 300)).target("c", id("c"), xy(trunk, -50))
                .edge("trunk", "root", "b", xy(0, 0), xy(trunk, 0))
                .edge("to-a", "b", "a", xy(trunk, 0), xy(trunk, 300))
                .edge("to-c", "b", "c", xy(trunk, 0), xy(trunk, -50))
                .build();
    }

    @Test
    void roadCrossingBecomesAStraightSpecialPassWithTechnicalNodes() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0)).restriction("road", "road", rectangle(40, -30, 50, 30));
        RouteCandidate candidate = new CandidateBuilder("road")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertEquals(3, variant.edges.size());
        assertEquals(2, variant.nodes.stream().filter(n -> n.kind == NodeKind.TECHNICAL_NODE).count());
        CalculatedEdge before = variant.edges.get(0);
        CalculatedEdge pass = variant.edges.get(1);
        CalculatedEdge after = variant.edges.get(2);
        assertEquals(LayingMethod.BASE, before.layingMethod);
        assertEquals(37, before.lengthM, 1e-9);
        assertEquals(LayingMethod.SPECIAL, pass.layingMethod);
        assertEquals(16, pass.lengthM, 1e-9, "10 m road plus 3 m on each side");
        assertEquals(List.of(id("road")), pass.crossedObjectIds);
        assertMoney("1.6", pass.specialCoefficient);
        assertMoney("2138368", pass.costRub);
        assertEquals(47, after.lengthM, 1e-9);
        assertEquals(before.toNodeId, pass.fromNodeId);
        assertEquals(pass.toNodeId, after.fromNodeId);
        assertEquals("root", before.fromNodeId);
        assertEquals("p", after.toNodeId);
        assertMoney("14154888", variant.summary.constructionCost);
        assertEquals(100, variant.summary.newNetworkLength, 1e-9);
    }

    @Test
    void gasCrossingUsesTwoMetresFromTheCrossingPoint() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0))
                .restriction("gas", "gas_pipeline", GF.createLineString(new Coordinate[] {xy(60, -30), xy(60, 30)}));
        RouteCandidate candidate = new CandidateBuilder("gas")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        CalculatedEdge pass = variant.edges.get(1);
        assertEquals(LayingMethod.SPECIAL, pass.layingMethod);
        assertEquals(4, pass.lengthM, 1e-9);
        assertEquals(58, pass.geometry.getCoordinateN(0).x - CalculationFixtures.X0, 1e-9);
        assertMoney("1.25", pass.specialCoefficient);
    }

    @Test
    void overlappingPassesSplitByCrossedSetAndUseTheLargestCoefficient() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0))
                .restriction("road", "road", rectangle(40, -30, 50, 30))
                .restriction("gas", "gas_pipeline", GF.createLineString(new Coordinate[] {xy(52, -30), xy(52, 30)}));
        RouteCandidate candidate = new CandidateBuilder("overlap")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(withoutValidator.evaluate(scene, candidate, Mode.TWO_D));

        List<Double> lengths = variant.edges.stream().map(e -> e.lengthM).collect(Collectors.toList());
        assertEquals(5, lengths.size(), lengths.toString());
        assertEquals(List.of(37.0, 13.0, 3.0, 1.0, 46.0),
                lengths.stream().map(l -> Math.round(l * 1000) / 1000.0).collect(Collectors.toList()));
        assertEquals(List.of(id("road"), id("gas")), variant.edges.get(2).crossedObjectIds);
        assertMoney("1.6", variant.edges.get(2).specialCoefficient);
        assertMoney("1.25", variant.edges.get(3).specialCoefficient);
        assertEquals(LayingMethod.BASE, variant.edges.get(4).layingMethod);
    }

    @Test
    void roadCrossedAtAShallowAngleIsRejected() {
        double c = Math.cos(Math.toRadians(30)) * 40;
        Scene scene = baseScene().point(id(1), 10, xy(150, 0)).restriction("road", "road", GF.createPolygon(
                new Coordinate[] {xy(40 - c, -20), xy(50 - c, -20), xy(50 + c, 20), xy(40 + c, 20), xy(40 - c, -20)}));
        RouteCandidate candidate = new CandidateBuilder("shallow")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(150, 0))
                .edge("e", "root", "p", xy(0, 0), xy(150, 0)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.TWO_D), "SPECIAL_PASS_VIOLATION");
    }

    @Test
    void specialPassWithATurnIsRejected() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 30)).restriction("road", "road", rectangle(40, -30, 50, 60));
        RouteCandidate candidate = new CandidateBuilder("bent")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 30))
                .edge("e", "root", "p", xy(0, 0), xy(45, 0), xy(100, 30)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.TWO_D), "SPECIAL_PASS_VIOLATION");
    }

    @Test
    void passingAlongAGasPipelineViolatesItsClearance() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0))
                .restriction("gas", "gas_pipeline", GF.createLineString(new Coordinate[] {xy(20, 1.5), xy(80, 1.5)}));
        RouteCandidate candidate = new CandidateBuilder("parallel")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.TWO_D), "CLEARANCE_VIOLATION");
    }

    @Test
    void leavingTheAttachmentLineAtAnAngleIsNotPassingNearIt() {
        Scene scene = new Scene().line("L", 300, xy(0, -100), xy(0, 100)).point(id(1), 10, xy(100, 100));
        RouteCandidate diagonal = new CandidateBuilder("diagonal")
                .newRoot("root", "L", xy(0, 0)).target("p", id(1), xy(100, 100))
                .edge("e", "root", "p", xy(0, 0), xy(100, 100)).build();
        accepted(calculator.evaluate(scene, diagonal, Mode.TWO_D));

        RouteCandidate alongside = new CandidateBuilder("alongside")
                .newRoot("root", "L", xy(0, 0)).target("p", id(1), xy(100, 100))
                .edge("e", "root", "p", xy(0, 0), xy(1.5, 1.5), xy(1.5, 100), xy(100, 100)).build();
        assertRejected(calculator.evaluate(scene, alongside, Mode.TWO_D), "CLEARANCE_VIOLATION");
    }

    @Test
    void unconnectedPointsArePenalised() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0));
        RouteCandidate candidate = new CandidateBuilder("none").unconnected(id(1)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertTrue(variant.edges.isEmpty());
        assertEquals(List.of(id(1)), variant.unconnectedPointIds);
        assertMoney("105000000", variant.summary.unconnectedPenalty);
        assertMoney("105000000", variant.summary.calculatedCost);
        assertMoney("0", variant.summary.constructionCost);
        assertMoney("2.94", variant.summary.score);
    }

    @Test
    void newChamberOnALineIsSizedByTheExistingPipeToo() {
        Scene scene = new Scene().line("L", 300, xy(0, -100), xy(0, 100)).chamber("far", xy(0, -15))
                .point(id(1), 10, xy(100, 0));
        RouteCandidate candidate = new CandidateBuilder("new-chamber")
                .newRoot("root", "L", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));

        assertEquals(300, variant.newChambers.get(0).diameterMm);
        assertMoney("5000000", variant.newChambers.get(0).costRub);
        assertEquals(0, variant.summary.existingChamberTieInCount);
    }

    @Test
    void newChamberNearAnExistingChamberWithFreeAdjacenciesIsRejected() {
        Scene scene = new Scene().line("L", 300, xy(0, -100), xy(0, 100)).chamber("near", xy(0, -5))
                .point(id(1), 10, xy(100, 0));
        RouteCandidate candidate = new CandidateBuilder("ten-metres")
                .newRoot("root", "L", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.TWO_D), "TOPOLOGY_VIOLATION");
    }

    @Test
    void chamberAdjacencyCountsExistingLines() {
        Scene scene = baseScene()
                .point(id(1), 3, xy(100, 0)).point(id(2), 3, xy(-100, 0))
                .point(id(3), 3, xy(0, 100)).point(id(4), 3, xy(70, 70));
        RouteCandidate candidate = new CandidateBuilder("five")
                .existingRoot("root", "C", xy(0, 0))
                .target("p1", id(1), xy(100, 0)).target("p2", id(2), xy(-100, 0))
                .target("p3", id(3), xy(0, 100)).target("p4", id(4), xy(70, 70))
                .edge("e1", "root", "p1", xy(0, 0), xy(100, 0))
                .edge("e2", "root", "p2", xy(0, 0), xy(-100, 0))
                .edge("e3", "root", "p3", xy(0, 0), xy(0, 100))
                .edge("e4", "root", "p4", xy(0, 0), xy(70, 70)).build();

        Evaluation evaluation = withoutValidator.evaluate(scene, candidate, Mode.TWO_D);

        assertRejected(evaluation, "TOPOLOGY_VIOLATION");
        assertTrue(evaluation.diagnostics.get(0).message.contains("5"), evaluation.diagnostics.get(0).message);
    }

    @Test
    void structuralErrorsAreRejected() {
        Scene scene = baseScene().point(id(1), 3, xy(100, 0)).point(id(2), 3, xy(100, 50));
        RouteCandidate lost = new CandidateBuilder("lost")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();
        assertRejected(calculator.evaluate(scene, lost, Mode.TWO_D), "TOPOLOGY_VIOLATION");

        RouteCandidate twoParents = new CandidateBuilder("two-parents")
                .existingRoot("root", "C", xy(0, 0)).chamber("m", xy(50, 0))
                .target("p", id(1), xy(100, 0)).unconnected(id(2))
                .edge("e1", "root", "m", xy(0, 0), xy(50, 0))
                .edge("e2", "m", "p", xy(50, 0), xy(100, 0))
                .edge("e3", "root", "p", xy(0, 0), xy(0, 20), xy(100, 20), xy(100, 0)).build();
        assertRejected(calculator.evaluate(scene, twoParents, Mode.TWO_D), "TOPOLOGY_VIOLATION");

        RouteCandidate displaced = new CandidateBuilder("displaced")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0.5)).unconnected(id(2))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0.5)).build();
        assertRejected(calculator.evaluate(scene, displaced, Mode.TWO_D), "INVALID_GEOMETRY");
    }

    @Test
    void flowAboveTheLargestPipeIsADiameterLimit() {
        Scene scene = baseScene().point(id(1), 30000, xy(100, 0));
        RouteCandidate candidate = new CandidateBuilder("huge")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.TWO_D), "DIAMETER_LIMIT");
    }

    @Test
    void depthModeIsNotReportedAs2d() {
        Scene scene = baseScene().point(id(1), 3, xy(100, 0));
        RouteCandidate candidate = new CandidateBuilder("depth").unconnected(id(1)).build();

        assertRejected(calculator.evaluate(scene, candidate, Mode.DEPTH), "UNSUPPORTED_MODE");
    }

    @Test
    void candidateIsNotModified() {
        Scene scene = baseScene().point(id(1), 10, xy(100, 0)).restriction("road", "road", rectangle(40, -30, 50, 30));
        RouteCandidate candidate = new CandidateBuilder("immutable")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        assertNotNull(accepted(calculator.evaluate(scene, candidate, Mode.TWO_D)));
        assertEquals(2, candidate.nodes.size());
        assertEquals(1, candidate.edges.size());
        assertEquals(2, candidate.edges.get(0).geometry.getNumPoints());
        ObjectId unchanged = candidate.attachments.get(0).existingObjectId;
        assertEquals(id("C"), unchanged);
    }
}
