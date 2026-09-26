package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.*;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

/** Changing one part of a split pass must not borrow length from an invalid neighbour. */
class SpecialPassValidationTest {
    private final RulesCatalog catalog = RulesCatalog.loadDefault();
    private final DefaultSpatialValidator validator = new DefaultSpatialValidator(catalog);

    private Scene scene(boolean gas) {
        Scene scene = new Scene().line("L", 300, xy(0, -100), xy(0, 0)).chamber("C", xy(0, 0))
                .point(id(1), 10, xy(100, 0)).restriction("road", "road", rectangle(40, -30, 50, 30));
        if (gas) {
            scene.restriction("gas", "gas_pipeline",
                    GF.createLineString(new Coordinate[] {xy(52, -30), xy(52, 30)}));
        }
        return scene;
    }

    private CalculatedVariant calculate(Scene scene) {
        RouteCandidate candidate = new CandidateBuilder("pass")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();
        Evaluation result = new DefaultVariantCalculator(catalog, validator).evaluate(scene, candidate, Mode.TWO_D);
        assertNotNull(result.variant, () -> "Valid pass rejected: " + result.diagnostics);
        return result.variant;
    }

    private void assertInvalid(Scene scene, CalculatedVariant variant) {
        assertTrue(validator.validate(scene, variant).stream()
                .anyMatch(d -> "SPECIAL_PASS_VIOLATION".equals(d.code)));
    }

    private void moveNode(CalculatedVariant variant, String nodeId, Coordinate at) {
        variant.nodes.stream().filter(n -> n.id.equals(nodeId)).forEach(n -> n.geometry = GF.createPoint(at));
        for (CalculatedEdge edge : variant.edges) {
            Coordinate[] coordinates = edge.geometry.getCoordinates();
            if (edge.fromNodeId.equals(nodeId)) {
                coordinates[0] = at;
            }
            if (edge.toNodeId.equals(nodeId)) {
                coordinates[coordinates.length - 1] = at;
            }
            edge.geometry = GF.createLineString(coordinates);
            edge.lengthM = edge.geometry.getLength();
        }
    }

    @Test
    void splitPassStillNeedsTheFullExtensionAfterTheGasCrossing() {
        Scene scene = scene(true);
        CalculatedVariant variant = calculate(scene);
        moveNode(variant, variant.edges.get(3).toNodeId, xy(53.5, 0));
        assertInvalid(scene, variant);
    }

    @Test
    void baseLayingCannotExtendASpecialPass() {
        Scene scene = scene(true);
        CalculatedVariant variant = calculate(scene);
        variant.edges.get(3).layingMethod = LayingMethod.BASE;
        assertInvalid(scene, variant);
    }

    @Test
    void neighbourMustNameTheSameRestriction() {
        Scene scene = scene(true);
        CalculatedVariant variant = calculate(scene);
        variant.edges.get(2).crossedObjectIds.remove(id("road"));
        assertInvalid(scene, variant);
    }

    @Test
    void bendAtATechnicalNodeCannotExtendAStraightPass() {
        Scene scene = scene(true);
        CalculatedVariant variant = calculate(scene);
        moveNode(variant, variant.edges.get(1).toNodeId, xy(50, 1));
        assertInvalid(scene, variant);
    }

    @Test
    void polygonExtensionMustBePresentOnEachSideNotOnlyInTotal() {
        Scene scene = scene(false);
        CalculatedVariant variant = calculate(scene);
        moveNode(variant, variant.edges.get(1).fromNodeId, xy(39, 0));
        moveNode(variant, variant.edges.get(1).toNodeId, xy(55, 0));
        assertInvalid(scene, variant);
    }
}
