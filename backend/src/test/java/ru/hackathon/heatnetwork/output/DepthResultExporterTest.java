package ru.hackathon.heatnetwork.output;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.output.DepthExportFixtures.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

class DepthResultExporterTest {
    @TempDir Path directory;
    private final GeoJsonResultExporter exporter = new GeoJsonResultExporter();

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void exportsSharedDepthExamplesWithExactValuesAndTypedReferences(String name) throws Exception {
        CalculatedVariant variant = variant(name);
        List<String> geometryBefore = new ArrayList<>();
        for (CalculatedEdge edge : variant.edges) geometryBefore.add(edge.geometry.toText());
        try (Dataset data = new GeoJsonInputParser(directory).parse(path(name, "input.geojson"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            exporter.write(data, List.of(variant), bytes);
            JsonNode actual = JSON.readTree(bytes.toByteArray());
            JsonNode expected = JSON.readTree(path(name, "expected.geojson").toFile());
            assertEquals(expected.path("features").size(), actual.path("features").size());
            Map<String, String> ids = new HashMap<>();
            for (int i = 0; i < actual.path("features").size(); i++) {
                ids.put(actual.at("/features/" + i + "/properties/id").asText(),
                        expected.at("/features/" + i + "/properties/id").asText());
            }
            for (JsonNode feature : actual.path("features")) {
                ObjectNode props = (ObjectNode) feature.path("properties");
                for (String field : List.of("id", "start_node_id", "end_node_id")) {
                    JsonNode value = props.get(field);
                    if (value != null && value.isTextual() && ids.containsKey(value.asText())) props.put(field, ids.get(value.asText()));
                }
            }
            equivalent(expected, actual);
            for (int i = 0; i < variant.edges.size(); i++) {
                assertEquals(geometryBefore.get(i), variant.edges.get(i).geometry.toText());
                assertEquals(variant.edges.get(i).depthStartM,
                        actual.at("/features/" + i + "/properties/depth_start").doubleValue());
                assertEquals(variant.edges.get(i).depthEndM,
                        actual.at("/features/" + i + "/properties/depth_end").doubleValue());
                for (JsonNode position : actual.at("/features/" + i + "/geometry/coordinates")) assertEquals(2, position.size());
            }
            assertTrue(data.find(variant.nodes.get(0).inputObjectId).isPresent(), "Dataset remains open");
        }
    }

    @ParameterizedTest(name = "rejects {0} before writing")
    @MethodSource("invalidDepths")
    void rejectsInvalidProfilesBeforeWritingAnyByte(String description, Consumer<CalculatedVariant> damage) throws Exception {
        CalculatedVariant variant = variant("gas-below");
        damage.accept(variant);
        try (Dataset data = new GeoJsonInputParser(directory).parse(path("gas-below", "input.geojson"))) {
            TrackingTarget output = new TrackingTarget();
            InvalidResultException error = assertThrows(InvalidResultException.class,
                    () -> exporter.write(data, List.of(variant), output));
            if (description.equals("unsplit cost threshold")) {
                assertTrue(error.getMessage().contains("границе коэффициента глубины"));
            }
            assertEquals(0, output.size());
            assertFalse(output.closed);
        }
    }

    static Stream<Arguments> invalidDepths() {
        Map<String, Consumer<CalculatedVariant>> cases = new LinkedHashMap<>();
        cases.put("missing start", v -> v.edges.get(0).depthStartM = null);
        cases.put("missing end", v -> v.edges.get(0).depthEndM = null);
        cases.put("NaN", v -> v.edges.get(0).depthStartM = Double.NaN);
        cases.put("positive infinity", v -> v.edges.get(0).depthEndM = Double.POSITIVE_INFINITY);
        cases.put("negative infinity", v -> v.edges.get(0).depthStartM = Double.NEGATIVE_INFINITY);
        cases.put("below minimum", v -> v.edges.get(0).depthStartM = 0.69);
        cases.put("excess slope", v -> v.edges.get(1).depthEndM = 3.41);
        cases.put("node discontinuity", v -> { v.edges.get(2).depthStartM = 3.41; v.edges.get(2).depthEndM = 3.41; });
        cases.put("unsplit cost threshold", v -> {
            v.edges.get(1).depthStartM = 2.99;
            v.edges.get(1).depthEndM = 3.39; // admissible slope; must fail specifically at the threshold
        });
        cases.put("finite depth subtraction overflow", v -> v.edges.get(0).depthEndM = Double.MAX_VALUE);
        return cases.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @Test void rejectsMixedModesEvenWhenEachVariantIsIndividuallyValid() throws Exception {
        CalculatedVariant depth = variant("flat");
        CalculatedVariant flat = variant("flat");
        flat.variantId = "flat-variant";
        flat.mode = Mode.TWO_D;
        flat.edges.forEach(e -> { e.depthStartM = null; e.depthEndM = null; });
        try (Dataset data = new GeoJsonInputParser(directory).parse(path("flat", "input.geojson"))) {
            for (List<CalculatedVariant> variants : List.of(List.of(flat,depth), List.of(depth,flat))) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                InvalidResultException error = assertThrows(InvalidResultException.class, () -> exporter.write(data, variants, output));
                assertTrue(error.getMessage().contains("смешивать"));
                assertEquals(0, output.size());
            }
        }
    }

    @Test void keepsDifferentProfilesOfTheSamePlanAndDoesNotRecalculatePrices() throws Exception {
        CalculatedVariant first = variant("flat");
        CalculatedVariant second = variant("flat");
        second.variantId = "variant-2";
        // Export is a serialization boundary. Boundary policy and price validity
        // belong to the upstream calculator/validator, not to a second solver here.
        second.edges.get(0).depthStartM = 50.0;
        second.edges.get(0).depthEndM = 50.0;
        try (Dataset data = new GeoJsonInputParser(directory).parse(path("flat", "input.geojson"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            exporter.write(data, List.of(first,second), output);
            JsonNode result = JSON.readTree(output.toByteArray());
            assertEquals(3, result.at("/features/0/properties/depth_start").asDouble());
            assertEquals(50, result.at("/features/2/properties/depth_start").asDouble());
            assertEquals(first.edges.get(0).costRub, result.at("/features/2/properties/cost").decimalValue());
            assertEquals(2, result.at("/features/3/properties/rank").asInt());
        }
    }

    private void equivalent(JsonNode expected, JsonNode actual) {
        if (expected.isNumber()) {
            assertTrue(actual.isNumber());
            assertEquals(expected.doubleValue(), actual.doubleValue(), 1e-8);
        } else if (expected.isObject()) {
            assertEquals(expected.size(), actual.size());
            expected.fields().forEachRemaining(e -> equivalent(e.getValue(), actual.path(e.getKey())));
        } else if (expected.isArray()) {
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) equivalent(expected.get(i), actual.get(i));
        } else assertEquals(expected,actual);
    }

    private static final class TrackingTarget extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }
}
