package ru.hackathon.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.output.DepthExportFixtures.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;

class DepthSpatialValidationTest {
    @TempDir Path temporary;
    private final DefaultSpatialValidator validator = new DefaultSpatialValidator();

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-above", "gas-below", "cable-below", "network-below", "road", "branch"})
    void acceptsPreparedProfilesIndependentlyOfTheBuilder(String name) throws Exception {
        CalculatedVariant variant = variant(name);
        try (Dataset dataset = input(name)) {
            assertTrue(validator.validate(dataset, variant).isEmpty(), () -> messages(validator.validate(dataset, variant)));
        }
    }

    @Test void commonNegativeExamplesAreRejected() throws Exception {
        JsonNode cases = JSON.readTree(getClass().getResource("/fixtures/synthetic/depth/negative-cases.json")).path("cases");
        for (JsonNode test : cases) {
            String name = test.path("base").asText();
            CalculatedVariant variant = variant(name);
            Iterator<Map.Entry<String, JsonNode>> changes = test.path("replace").fields();
            boolean invalidJsonType = false;
            while (changes.hasNext()) {
                Map.Entry<String, JsonNode> change = changes.next();
                String[] pointer = change.getKey().split("/");
                if (change.getValue().isBoolean()) {
                    assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                            () -> JSON.readValue("{\"depthStartM\":true}", CalculatedEdge.class));
                    invalidJsonType = true;
                    continue;
                }
                Double value = change.getValue().isNull() ? null : change.getValue().doubleValue();
                CalculatedEdge edge = variant.edges.get(Integer.parseInt(pointer[2]));
                if (pointer[3].equals("depthStartM")) edge.depthStartM = value;
                else edge.depthEndM = value;
            }
            if (invalidJsonType) continue; // A Java Double cannot contain a boolean.
            try (Dataset dataset = input(name)) {
                List<Diagnostic> diagnostics = validator.validate(dataset, variant);
                assertTrue(diagnostics.stream().anyMatch(d -> test.path("expectedCode").asText().equals(d.code)),
                        test.path("id").asText() + ": " + messages(diagnostics));
                assertTrue(diagnostics.stream().filter(d -> d.code.startsWith("DEPTH_")).allMatch(d -> d.segmentId != null));
            }
        }
    }

    @Test void nonFiniteDepthAndZeroLengthAreRejected() throws Exception {
        for (Double value : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            CalculatedVariant variant = variant("flat");
            variant.edges.get(0).depthStartM = value;
            try (Dataset data = input("flat")) {
                assertCode("DEPTH_VALUE_INVALID", validator.validate(data, variant));
            }
        }
        CalculatedVariant variant = variant("flat");
        CalculatedEdge edge = variant.edges.get(0);
        edge.geometry = edge.geometry.getFactory().createLineString(new org.locationtech.jts.geom.Coordinate[]{
                edge.geometry.getCoordinateN(0), edge.geometry.getCoordinateN(0)});
        try (Dataset data = input("flat")) {
            assertCode("DEPTH_SLOPE_VIOLATION", validator.validate(data, variant));
        }
    }

    @Test void checksP1AndConstantSpecialPassEvenWithValidClearance() throws Exception {
        CalculatedVariant variant = variant("gas-below");
        variant.edges.get(2).depthStartM = 3.41;
        variant.edges.get(2).depthEndM = 3.42;
        try (Dataset data = input("gas-below")) {
            assertCode("DEPTH_CONTINUITY_VIOLATION", validator.validate(data, variant));
        }
        variant = variant("flat");
        variant.edges.get(0).depthStartM = 2.9;
        try (Dataset data = input("flat")) {
            assertCode("DEPTH_CONTINUITY_VIOLATION", validator.validate(data, variant));
        }
    }

    @Test void clearanceDoesNotTrustCrossedObjectIdsOrJustTheAxisIntersection() throws Exception {
        CalculatedVariant variant = variant("gas-below");
        CalculatedEdge crossing = variant.edges.get(2);
        // At the central axis h=3.4, but one side of the overlap is too shallow.
        crossing.depthStartM = 3.39;
        crossing.depthEndM = 3.41;
        crossing.crossedObjectIds.clear();
        try (Dataset data = input("gas-below")) {
            assertCode("DEPTH_CLEARANCE_VIOLATION", validator.validate(data, variant));
        }
    }

    private Dataset input(String name) throws Exception {
        return new GeoJsonInputParser(temporary.resolve(UUID.randomUUID().toString())).parse(path(name, "input.geojson"));
    }

    @Test void tinyChangesCannotAccumulateAcrossASpecialChain() throws Exception {
        CalculatedVariant variant = variant("gas-below");
        // Every individual edge and joint is within 1e-6; the complete chain is not.
        for (int i = 0; i < variant.edges.size(); i++) {
            CalculatedEdge edge = variant.edges.get(i);
            edge.layingMethod = LayingMethod.SPECIAL;
            edge.depthStartM = 3.4 + i * 0.8e-6;
            edge.depthEndM = edge.depthStartM;
        }
        try (Dataset data = input("gas-below")) {
            assertTrue(validator.validate(data, variant).stream().anyMatch(d -> d.message.contains("цепочки спецпрохода")));
        }
    }
    private static void assertCode(String code, List<Diagnostic> diagnostics) {
        assertTrue(diagnostics.stream().anyMatch(d -> code.equals(d.code)), messages(diagnostics));
    }
    private static String messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.code + ":" + d.message).collect(java.util.stream.Collectors.joining("; "));
    }
}
