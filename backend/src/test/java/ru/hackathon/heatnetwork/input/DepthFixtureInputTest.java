package ru.hackathon.heatnetwork.input;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import ru.hackathon.heatnetwork.calculation.DefaultVariantCalculator;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.RulesCatalog;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Checks fixture projections through the real parser; does not claim DEPTH solver support. */
class DepthFixtureInputTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private Path fixture(String name, String file) throws Exception {
        return Path.of(Objects.requireNonNull(getClass().getResource(
                "/fixtures/synthetic/depth/" + name + "/" + file)).toURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void inputAndResultRecoverTheDocumentedMetricGeometry(String name) throws Exception {
        JsonNode metric = json.readTree(fixture(name, "metric-case.json").toFile());
        try (Dataset data = new GeoJsonInputParser(temporary.resolve("datasets"))
                .parse(fixture(name, "input.geojson"))) {
            for (JsonNode expected : metric.path("inputObjects")) {
                var actual = data.find(new ObjectId(expected.path("id"))).orElseThrow();
                assertEquals(32637, actual.geometry.getSRID());
                List<Coordinate> points = new ArrayList<>();
                coordinates(expected.path("geometry").path("coordinates"), points);
                Coordinate[] recovered = actual.geometry.getCoordinates();
                assertEquals(points.size(), recovered.length);
                for (int i = 0; i < recovered.length; i++) {
                    assertTrue(points.get(i).distance(recovered[i]) < 0.00001,
                            name + ": input projection drift at " + expected.path("id") + "/" + i);
                }
                if (expected.has("flow_tph")) assertEquals(0, expected.path("flow_tph").decimalValue().compareTo(actual.flowTph));
            }
        }
        JsonNode result = json.readTree(fixture(name, "expected.geojson").toFile());
        Wgs84Projection projection = new Wgs84Projection();
        int edgeIndex = 0;
        for (JsonNode feature : result.path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) continue;
            JsonNode expected = metric.path("expectedVariant").path("edges").get(edgeIndex++).path("xy");
            JsonNode positions = feature.path("geometry").path("coordinates");
            assertEquals(expected.size(), positions.size());
            for (int i = 0; i < positions.size(); i++) {
                assertEquals(2, positions.get(i).size(), "Official output remains two-dimensional");
                Coordinate xy = projection.project(positions.get(i).get(0).asDouble(), positions.get(i).get(1).asDouble());
                assertTrue(xy.distance(new Coordinate(expected.get(i).get(0).asDouble(), expected.get(i).get(1).asDouble())) < 0.00001,
                        name + ": exported projection drift");
            }
        }
        assertEquals(metric.path("expectedVariant").path("edges").size(), edgeIndex);
    }

    private void coordinates(JsonNode value, List<Coordinate> result) {
        if (value.size() == 2 && value.get(0).isNumber() && value.get(1).isNumber()) {
            result.add(new Coordinate(value.get(0).asDouble(), value.get(1).asDouble()));
        } else {
            for (JsonNode child : value) coordinates(child, result);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void candidateAlsoSatisfiesExistingPlanRules(String name) throws Exception {
        JsonNode definition = json.readTree(fixture(name, "metric-case.json").toFile()).path("candidate");
        Model.RouteCandidate candidate = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(definition, Model.RouteCandidate.class);
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);
        for (int i = 0; i < candidate.nodes.size(); i++) {
            JsonNode xy = definition.path("nodes").get(i).path("xy");
            candidate.nodes.get(i).geometry = gf.createPoint(new Coordinate(xy.get(0).asDouble(), xy.get(1).asDouble()));
        }
        for (int i = 0; i < candidate.edges.size(); i++) {
            List<Coordinate> points = new ArrayList<>();
            coordinates(definition.path("edges").get(i).path("xy"), points);
            candidate.edges.get(i).geometry = gf.createLineString(points.toArray(new Coordinate[0]));
        }
        RulesCatalog rules = RulesCatalog.loadDefault();
        try (Dataset data = new GeoJsonInputParser(temporary.resolve("plan-dataset")).parse(fixture(name, "input.geojson"))) {
            Model.Evaluation evaluation = new DefaultVariantCalculator(rules, new DefaultSpatialValidator(rules))
                    .evaluate(data, candidate, Model.Mode.TWO_D);
            assertTrue(evaluation.accepted(), () -> name + ": " + evaluation.diagnostics.stream()
                    .map(d -> d.code + ":" + d.message).collect(java.util.stream.Collectors.joining("; ")));
        }
    }
}
