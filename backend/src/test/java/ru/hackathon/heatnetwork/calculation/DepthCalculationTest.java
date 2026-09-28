package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.GF;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.id;
import static ru.hackathon.heatnetwork.calculation.CalculationFixtures.xy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.CandidateBuilder;
import ru.hackathon.heatnetwork.calculation.CalculationFixtures.Scene;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.LayingMethod;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.output.GeoJsonResultExporter;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

/** Depth mode of the calculator on the shared depth fixtures and small synthetic scenes. */
class DepthCalculationTest {
    private static final RulesCatalog CATALOG = RulesCatalog.loadDefault();
    private static final double SLACK = 1e-6;
    private final DefaultVariantCalculator calculator =
            new DefaultVariantCalculator(CATALOG, new DefaultSpatialValidator(CATALOG));
    private final ObjectMapper json = new ObjectMapper();

    @TempDir Path temporary;

    // ------------------------------------------------------------------ shared fixtures

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void sharedFixtureGetsAnAdmissibleProfileNoDearerThanTheIllustratedOne(String name) throws Exception {
        JsonNode metric = json.readTree(fixture(name, "metric-case.json").toFile());
        try (Dataset data = new GeoJsonInputParser(temporary.resolve(name)).parse(fixture(name, "input.geojson"))) {
            CalculatedVariant variant = accepted(calculator.evaluate(data, candidate(metric.path("candidate")), Mode.DEPTH));

            assertEquals(Mode.DEPTH, variant.mode);
            assertProfileRules(variant);
            assertVerticalClearances(data, variant);
            BigDecimal illustrated = metric.path("expectedVariant").path("summary").path("calculatedCost").decimalValue();
            assertTrue(variant.summary.calculatedCost.compareTo(illustrated) <= 0,
                    () -> name + ": " + variant.summary.calculatedCost + " > " + illustrated);
            assertEquals(metric.path("expectedVariant").path("summary").path("newNetworkLength").asDouble(),
                    variant.summary.newNetworkLength, 0.001);
            if (Arrays.asList("flat", "road", "branch", "gas-above").contains(name)) {
                assertEquals(0, illustrated.compareTo(variant.summary.calculatedCost),
                        () -> name + ": same cost as the fixture, got " + variant.summary.calculatedCost);
            }

            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            new GeoJsonResultExporter().write(data, java.util.Collections.singletonList(variant), output);
            for (JsonNode feature : json.readTree(output.toByteArray()).path("features")) {
                if ("heat_network".equals(feature.path("properties").path("object_type").asText())) {
                    assertTrue(feature.path("properties").path("depth_start").isNumber(), name + ": exported depth");
                }
            }
        }
    }

    @Test
    void withoutCrossingsTheDepthVariantCostsTheSameAs2d() throws Exception {
        JsonNode metric = json.readTree(fixture("branch", "metric-case.json").toFile());
        try (Dataset data = new GeoJsonInputParser(temporary.resolve("same")).parse(fixture("branch", "input.geojson"))) {
            CalculatedVariant flat = accepted(calculator.evaluate(data, candidate(metric.path("candidate")), Mode.TWO_D));
            CalculatedVariant deep = accepted(calculator.evaluate(data, candidate(metric.path("candidate")), Mode.DEPTH));
            assertEquals(0, flat.summary.calculatedCost.compareTo(deep.summary.calculatedCost));
            assertTrue(flat.edges.stream().allMatch(e -> e.depthStartM == null && e.depthEndM == null));
            assertTrue(deep.edges.stream().allMatch(e -> e.depthStartM == 3.0 && e.depthEndM == 3.0));
        }
    }

    // ----------------------------------------------------------------- profile-units.json

    @Test
    void profileUnitCostsMatchTheSharedArithmetic() throws Exception {
        DepthRules rules = new DepthRules(RulesCatalog.loadDepth());
        JsonNode units = json.readTree(Path.of(Objects.requireNonNull(
                getClass().getResource("/fixtures/synthetic/depth/profile-units.json")).toURI()).toFile());
        for (JsonNode unit : units.path("costCases")) {
            double weighted = rules.weightedLength(unit.path("depthStartM").asDouble(),
                    unit.path("depthEndM").asDouble(), unit.path("lengthM").asDouble());
            BigDecimal cost = BigDecimal.valueOf(weighted * CATALOG.row(unit.path("diameterMm").asInt()).newCostRubPerM
                    * unit.path("specialCoefficient").asDouble()).setScale(2, RoundingMode.HALF_UP);
            assertEquals(0, unit.path("expectedCostRub").decimalValue().compareTo(cost), unit.path("id").asText());
        }
        JsonNode interpolation = units.path("interpolationCase");
        List<double[]> profile = Arrays.asList(new double[] {0, 3}, new double[] {100, 4});
        assertEquals(3.1, DefaultVariantCalculator.depthAt(profile, 10), SLACK, "share of length, not of vertices");
    }

    @Test
    void tooShortStretchForTheTransitionIsReportedAsProfileNotFound() {
        // Gas 5 m before the consumer: the pass ends 3 m before it, a return needs 4 m (below) or 5.6 m (above).
        Scene scene = attachment().point(id(1), 10, xy(60, 0)).restriction("gas", "gas_pipeline", vertical(55));
        RouteCandidate candidate = new CandidateBuilder("short")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(60, 0))
                .edge("e", "root", "p", xy(0, 0), xy(60, 0)).build();

        accepted(calculator.evaluate(scene, candidate, Mode.TWO_D));
        Evaluation depth = calculator.evaluate(scene, candidate, Mode.DEPTH);
        assertNull(depth.variant);
        assertTrue(depth.diagnostics.stream().anyMatch(d -> "DEPTH_PROFILE_NOT_FOUND".equals(d.code) && "e".equals(d.segmentId)),
                () -> depth.diagnostics.stream().map(d -> d.code + " " + d.message).collect(Collectors.joining("; ")));
    }

    // ----------------------------------------------------------------------- scenes

    @Test
    void closeObstaclesKeepTheChangedDepthBetweenThem() {
        Scene scene = attachment().point(id(1), 10, xy(100, 0))
                .restriction("g1", "gas_pipeline", vertical(50)).restriction("g2", "gas_pipeline", vertical(56));
        RouteCandidate candidate = new CandidateBuilder("close")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.DEPTH));
        assertProfileRules(variant);
        for (CalculatedEdge edge : variant.edges) {
            double from = edge.geometry.getCoordinateN(0).x - CalculationFixtures.X0;
            if (from >= 48 - SLACK && from < 58 - SLACK) {
                assertEquals(2.44, edge.depthStartM, SLACK, "no rise between passes 2 m apart at " + from);
                assertEquals(2.44, edge.depthEndM, SLACK);
            }
        }
    }

    @Test
    void chamberDepthIsChosenTogetherWithItsBranches() {
        // The pass starts 1 m after the branching chamber: from 3 m there is no room for the transition.
        Scene scene = attachment().point(id(1), 10, xy(100, 0)).point(id(2), 15, xy(50, 50))
                .restriction("gas", "gas_pipeline", GF.createLineString(new Coordinate[] {xy(53, -20), xy(53, 20)}));
        RouteCandidate candidate = new CandidateBuilder("junction")
                .existingRoot("root", "C", xy(0, 0)).chamber("b", xy(50, 0))
                .target("p1", id(1), xy(100, 0)).target("p2", id(2), xy(50, 50))
                .edge("trunk", "root", "b", xy(0, 0), xy(50, 0))
                .edge("a", "b", "p1", xy(50, 0), xy(100, 0))
                .edge("c", "b", "p2", xy(50, 0), xy(50, 50)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.DEPTH));
        assertProfileRules(variant);
        CalculatedEdge intoChamber = variant.edges.stream().filter(e -> "b".equals(e.toNodeId)).findFirst().orElseThrow();
        assertEquals(2.44, intoChamber.depthEndM, SLACK);
        assertTrue(variant.edges.stream().filter(e -> "b".equals(e.fromNodeId)).allMatch(e -> e.depthStartM == 2.44));
    }

    @Test
    void passBelowIsCostedWithTheDepthCoefficient() {
        // ДУ1400 is 1.6 m high: above a cable (top 2.7 m, clearance 0.5 m) it would be at 0.6 m < 0.7 m.
        Scene scene = attachment().point(id(1), 16000, xy(100, 0)).restriction("cable", "power_cable", vertical(50));
        RouteCandidate candidate = new CandidateBuilder("below")
                .existingRoot("root", "C", xy(0, 0)).target("p", id(1), xy(100, 0))
                .edge("e", "root", "p", xy(0, 0), xy(100, 0)).build();

        CalculatedVariant variant = accepted(calculator.evaluate(scene, candidate, Mode.DEPTH));
        assertProfileRules(variant);
        CalculatedEdge pass = variant.edges.stream().filter(e -> e.layingMethod == LayingMethod.SPECIAL)
                .findFirst().orElseThrow();
        assertEquals(1400, pass.diameterMm);
        assertEquals(3.4, pass.depthStartM, SLACK);
        assertEquals(3.4, pass.depthEndM, SLACK);
        BigDecimal expected = BigDecimal.valueOf(pass.lengthM).multiply(BigDecimal.valueOf(683417))
                .multiply(new BigDecimal("1.15")).multiply(new BigDecimal("1.04")).setScale(2, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(pass.costRub), () -> expected + " vs " + pass.costRub);
        List<Double> ramps = variant.edges.stream().filter(e -> !e.depthStartM.equals(e.depthEndM))
                .map(e -> e.lengthM).collect(Collectors.toList());
        assertEquals(2, ramps.size());
        ramps.forEach(length -> assertEquals(4.0, length, 1e-5, "0.4 m at slope 0.10"));
    }

    @Test
    void anUnknownModeIsNotCalculated() {
        Scene scene = attachment().point(id(1), 3, xy(100, 0));
        Evaluation evaluation = calculator.evaluate(scene, new CandidateBuilder("none").unconnected(id(1)).build(), null);
        assertNull(evaluation.variant);
        assertEquals("UNSUPPORTED_MODE", evaluation.diagnostics.get(0).code);
    }

    // ------------------------------------------------------------------- checks

    /** P1, P2 (as continuity), slope, minimum depth, P3 and no part crossing 3 m. */
    private static void assertProfileRules(CalculatedVariant variant) {
        Map<String, Node> nodes = new HashMap<>();
        variant.nodes.forEach(node -> nodes.put(node.id, node));
        Map<String, List<Double>> atNode = new HashMap<>();
        for (CalculatedEdge edge : variant.edges) {
            assertTrue(Double.isFinite(edge.depthStartM) && Double.isFinite(edge.depthEndM), edge.id);
            assertTrue(edge.depthStartM >= 0.7 - SLACK && edge.depthEndM >= 0.7 - SLACK, edge.id);
            // As strict as the exporter: only floating-point noise is tolerated at the slope limit.
            assertTrue(Math.abs(edge.depthEndM - edge.depthStartM) <= 0.10 * edge.lengthM + 1e-12, "slope " + edge.id);
            assertTrue((edge.depthStartM - 3) * (edge.depthEndM - 3) >= -SLACK, "3 m inside " + edge.id);
            if (edge.layingMethod == LayingMethod.SPECIAL) {
                assertEquals(edge.depthStartM, edge.depthEndM, SLACK, "P3 " + edge.id);
            }
            atNode.computeIfAbsent(edge.fromNodeId, key -> new ArrayList<>()).add(edge.depthStartM);
            atNode.computeIfAbsent(edge.toNodeId, key -> new ArrayList<>()).add(edge.depthEndM);
        }
        atNode.forEach((nodeId, depths) -> {
            depths.forEach(d -> assertEquals(depths.get(0), d, SLACK, "continuity at " + nodeId));
            NodeKind kind = nodes.get(nodeId).kind;
            if (kind == NodeKind.CONNECTION_POINT || kind == NodeKind.EXISTING_CHAMBER
                    || variant.attachments.stream().anyMatch(a -> a.rootNodeId.equals(nodeId))) {
                assertEquals(3.0, depths.get(0), SLACK, "P1 at " + nodeId);
            }
        });
    }

    /** Independent statement of section 3 of the depth contract for every crossed object. */
    private static void assertVerticalClearances(Dataset data, CalculatedVariant variant) {
        for (CalculatedEdge edge : variant.edges) {
            double h = edge.depthStartM;
            double height = CATALOG.row(edge.diameterMm).heightM;
            for (ObjectId crossed : edge.crossedObjectIds) {
                InputObject object = data.find(crossed).orElseThrow();
                String type = object.restrictionType == null ? "heat_network" : object.restrictionType;
                boolean ok;
                switch (type) {
                    case "road": ok = h >= 1.0 - SLACK; break;
                    case "tram_tracks": ok = h >= 1.2 - SLACK; break;
                    case "gas_pipeline": ok = h + height <= 2.8 - 0.2 + SLACK || h >= 2.8 + 0.4 + 0.2 - SLACK; break;
                    case "power_cable": ok = h + height <= 2.7 - 0.5 + SLACK || h >= 2.7 + 0.2 + 0.5 - SLACK; break;
                    default:
                        double own = CATALOG.row(object.diameterMm).heightM;
                        ok = h + height <= 3.0 - 0.5 + SLACK || h >= 3.0 + own + 0.5 - SLACK;
                }
                assertTrue(ok, type + " at depth " + h + " on " + edge.id);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Scene attachment() {
        return new Scene().line("L", 300, xy(0, -100), xy(0, 0)).chamber("C", xy(0, 0));
    }

    private static org.locationtech.jts.geom.LineString vertical(double dx) {
        return GF.createLineString(new Coordinate[] {xy(dx, -30), xy(dx, 30)});
    }

    private static CalculatedVariant accepted(Evaluation evaluation) {
        assertTrue(evaluation.accepted(), () -> "rejected: " + evaluation.diagnostics.stream()
                .map(d -> d.code + " " + d.message).collect(Collectors.joining("; ")));
        return evaluation.variant;
    }

    private Path fixture(String name, String file) throws Exception {
        return Path.of(Objects.requireNonNull(getClass().getResource(
                "/fixtures/synthetic/depth/" + name + "/" + file)).toURI());
    }

    private RouteCandidate candidate(JsonNode definition) throws Exception {
        RouteCandidate candidate = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(definition, RouteCandidate.class);
        GeometryFactory planar = new GeometryFactory(new PrecisionModel(), 32637);
        for (int i = 0; i < candidate.nodes.size(); i++) {
            JsonNode point = definition.path("nodes").get(i).path("xy");
            candidate.nodes.get(i).geometry = planar.createPoint(new Coordinate(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        for (int i = 0; i < candidate.edges.size(); i++) {
            List<Coordinate> points = new ArrayList<>();
            definition.path("edges").get(i).path("xy").forEach(p -> points.add(new Coordinate(p.get(0).asDouble(), p.get(1).asDouble())));
            candidate.edges.get(i).geometry = planar.createLineString(points.toArray(new Coordinate[0]));
        }
        return candidate;
    }
}
