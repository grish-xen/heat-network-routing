package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;
import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.output.ExportFixtures.*;

class GeoJsonResultExporterTest {
    @TempDir Path temporary;
    private final GeoJsonResultExporter exporter = new GeoJsonResultExporter();

    @Test void exportsTheTeamFixtureUsingRealInputParserAndOriginalReferenceTypes() throws Exception {
        Path input = temporary.resolve("input.geojson");
        Files.write(input, JSON.writeValueAsBytes(fixture("input.geojson")));
        try (Dataset dataset = new GeoJsonInputParser(temporary.resolve("datasets")).parse(input)) {
            JsonNode actual = output(dataset, List.of(variant()));
            JsonNode expected = fixture("expected.geojson");
            assertEquals(5, actual.path("features").size());
            // Generated output IDs are deliberately opaque. Compare everything else against
            // the agreed reference, including all typed input references and coordinate order.
            Map<String, String> renames = new HashMap<>();
            for (int i = 0; i < 5; i++) renames.put(actual.at("/features/" + i + "/properties/id").asText(),
                    expected.at("/features/" + i + "/properties/id").asText());
            for (JsonNode feature : actual.path("features")) {
                ObjectNode properties = (ObjectNode) feature.path("properties");
                for (String field : List.of("id", "start_node_id", "end_node_id")) {
                    JsonNode value = properties.get(field);
                    if (value != null && value.isTextual() && renames.containsKey(value.asText())) properties.put(field, renames.get(value.asText()));
                }
            }
            equivalent(expected, actual, "root");
            assertTrue(dataset.find(new ObjectId(IntNode.valueOf(1))).isPresent(), "Exporter must not close Dataset");
        }
    }

    @Test void outputIdsAvoidEveryInputTypeAndRemainUniqueAcrossVariants() throws Exception {
        Data data = dataset();
        for (int i = 1; i <= 8; i++) {
            InputObject object = new InputObject();
            object.id = id("hnr-" + i);
            object.type = InputType.RESTRICTION;
            data.values.put(object.id, object);
        }
        CalculatedVariant second = variant();
        second.variantId = "вариант \"2\"\n";
        CalculatedVariant third = variant();
        third.variantId = "v3";
        JsonNode actual = output(data, List.of(variant(), second, third));
        Set<ObjectId> ids = new HashSet<>();
        for (JsonNode feature : actual.path("features")) {
            ObjectId id = new ObjectId(feature.at("/properties/id"));
            assertTrue(ids.add(id));
            assertFalse(data.find(id).isPresent());
        }
        assertEquals(15, ids.size());
        assertEquals(1, actual.at("/features/4/properties/rank").asInt());
        assertEquals(2, actual.at("/features/9/properties/rank").asInt());
        assertEquals(3, actual.at("/features/14/properties/rank").asInt());
        assertEquals(second.variantId, actual.at("/features/9/properties/variant_id").asText());
        for (int offset : new int[] {0, 5, 10}) {
            assertEquals(actual.at("/features/" + (offset + 3) + "/properties/id"), actual.at("/features/" + offset + "/properties/end_node_id"));
        }
        assertEquals(3, data.streamCloses);
    }

    @Test void preservesLargeNumericAndStringIdsForConnectedAndUnconnectedPoints() throws Exception {
        Data data = dataset();
        CalculatedVariant variant = variant();
        BigDecimal large = new BigDecimal("12345678901234567890.1234567890123456789");
        ObjectId numeric = new ObjectId(DecimalNode.valueOf(large));
        InputObject point = data.values.remove(new ObjectId(IntNode.valueOf(1)));
        point.id = numeric;
        data.values.put(numeric, point);
        variant.nodes.get(2).inputObjectId = numeric;
        InputObject other = new InputObject();
        other.id = id(large.toPlainString());
        other.type = InputType.OKS_CONNECTION_POINT;
        other.flowTph = BigDecimal.TEN;
        data.values.put(other.id, other);
        variant.unconnectedPointIds.add(other.id);
        variant.summary.unconnectedPenalty = new BigDecimal("105000000");
        variant.summary.calculatedCost = variant.summary.constructionCost.add(variant.summary.unconnectedPenalty);
        variant.summary.score = new BigDecimal("4.2789592");
        JsonNode actual = output(data, List.of(variant));
        assertTrue(actual.at("/features/1/properties/end_node_id").isNumber());
        assertEquals(large, actual.at("/features/1/properties/end_node_id").decimalValue());
        assertEquals(large.toPlainString(), actual.at("/features/4/properties/unconnected_oks_ids/0").textValue());
    }

    @Test void writesSummaryOnlyWhenAllTargetsAreReportedUnconnected() throws Exception {
        Data data = dataset();
        CalculatedVariant variant = new CalculatedVariant();
        variant.variantId = "v1";
        variant.mode = Mode.TWO_D;
        variant.unconnectedPointIds = new ArrayList<>(List.of(new ObjectId(IntNode.valueOf(1)), id("2")));
        Summary summary = new Summary();
        summary.constructionCost = summary.chamberConstructionCost = summary.existingChamberTieInCost = BigDecimal.ZERO;
        summary.calculatedCost = summary.unconnectedPenalty = new BigDecimal("210000000");
        summary.score = new BigDecimal("5.88");
        variant.summary = summary;
        JsonNode result = output(data, List.of(variant));
        assertEquals(1, result.path("features").size());
        assertTrue(result.at("/features/0/geometry").isNull());
        assertTrue(result.at("/features/0/properties/unconnected_oks_ids/0").isNumber());
        assertTrue(result.at("/features/0/properties/unconnected_oks_ids/1").isTextual());
    }

    @Test void writesTechnicalNodesSpecialSectionsAndVerticesWithoutMutatingTheGraph() throws Exception {
        CalculatedVariant variant = variant();
        Node technical = new Node();
        technical.id = "change";
        technical.kind = NodeKind.TECHNICAL_NODE;
        technical.geometry = GEOMETRY.createPoint(new Coordinate(400050, 6170000));
        variant.nodes.add(technical);
        CalculatedEdge first = variant.edges.get(0);
        first.toNodeId = technical.id;
        first.geometry = GEOMETRY.createLineString(new Coordinate[] {
                new Coordinate(400000, 6170000, 12), new Coordinate(400025, 6170000, 20), new Coordinate(400050, 6170000, 12)});
        first.lengthM = 50;
        first.costRub = first.costRub.divide(BigDecimal.valueOf(2));
        CalculatedEdge last = variant().edges.get(0);
        last.id = "trunk-rest";
        last.fromNodeId = technical.id;
        last.geometry = GEOMETRY.createLineString(new Coordinate[] {new Coordinate(400050, 6170000), new Coordinate(400100, 6170000)});
        last.lengthM = 50;
        last.costRub = first.costRub;
        last.layingMethod = LayingMethod.SPECIAL;
        variant.edges.add(last);
        String before = first.geometry.toText();
        JsonNode result = output(dataset(), List.of(variant));
        assertEquals(7, result.path("features").size());
        assertEquals(3, result.at("/features/0/geometry/coordinates").size());
        assertEquals(2, result.at("/features/0/geometry/coordinates/1").size(), "No Z coordinate in 2D");
        assertEquals("special", result.at("/features/3/properties/laying_method").asText());
        assertEquals("technical_node", result.at("/features/5/properties/object_type").asText());
        assertEquals(result.at("/features/5/geometry/coordinates"), result.at("/features/0/geometry/coordinates/2"));
        assertEquals(result.at("/features/5/geometry/coordinates"), result.at("/features/3/geometry/coordinates/0"));
        assertEquals(before, first.geometry.toText());
        assertEquals("change", first.toNodeId);
        assertNull(technical.inputObjectId);
    }

    @Test void snapsOnlySubMillimetreEndpointRoundingAndPreservesMetricValues() throws Exception {
        CalculatedVariant variant = variant();
        CalculatedEdge edge = variant.edges.get(0);
        edge.geometry.getCoordinateSequence().setOrdinate(1, 0, 400100.0001);
        edge.geometry.geometryChanged();
        JsonNode result = output(dataset(), List.of(variant));
        assertEquals(result.at("/features/3/geometry/coordinates"), result.at("/features/0/geometry/coordinates/1"));
        assertEquals(100, result.at("/features/0/properties/length").asDouble());
        assertEquals(400100.0001, edge.geometry.getCoordinateSequence().getX(1));
    }

    @Test void keepsOrdinaryBendsInsideLineStringsWithoutCreatingNodes() throws Exception {
        CalculatedVariant variant = variant();
        CalculatedEdge edge = variant.edges.get(0);
        edge.geometry = GEOMETRY.createLineString(new Coordinate[] {
                new Coordinate(400000, 6170000), new Coordinate(400050, 6170025), new Coordinate(400100, 6170000)});
        edge.lengthM = edge.geometry.getLength();
        variant.summary.newNetworkLength = edge.lengthM + 100;
        JsonNode actual = output(dataset(), List.of(variant));
        assertEquals(5, actual.path("features").size());
        assertEquals(3, actual.at("/features/0/geometry/coordinates").size());
        assertEquals(edge.lengthM, actual.at("/features/0/properties/length").asDouble());
    }

    @ParameterizedTest(name = "Rejects {0} before output") @MethodSource("invalidVariants")
    void rejectsInvalidCalculatedResultsBeforeWriting(String description, Consumer<CalculatedVariant> damage) throws Exception {
        CalculatedVariant variant = variant();
        damage.accept(variant);
        TrackingTarget target = new TrackingTarget();
        assertThrows(InvalidResultException.class, () -> exporter.write(dataset(), List.of(variant), target));
        assertEquals(0, target.size());
        assertFalse(target.closed);
    }

    static Stream<Arguments> invalidVariants() {
        Map<String, Consumer<CalculatedVariant>> cases = new LinkedHashMap<>();
        cases.put("missing summary", value -> value.summary = null);
        cases.put("missing variant ID", value -> value.variantId = " ");
        cases.put("missing depths in DEPTH", value -> value.mode = Mode.DEPTH);
        cases.put("missing mode", value -> value.mode = null);
        cases.put("missing nodes", value -> value.nodes = null);
        cases.put("duplicate node", value -> value.nodes.add(value.nodes.get(0)));
        cases.put("duplicate edge", value -> value.edges.add(value.edges.get(0)));
        cases.put("broken node reference", value -> value.edges.get(0).fromNodeId = "absent");
        cases.put("wrong input type", value -> value.nodes.get(0).inputObjectId = id("source"));
        cases.put("missing input ID", value -> value.nodes.get(0).inputObjectId = null);
        cases.put("input ID on new node", value -> value.nodes.get(1).inputObjectId = id("source"));
        cases.put("wrong SRID", value -> value.edges.get(0).geometry.setSRID(4326));
        cases.put("nonfinite coordinate", value -> value.edges.get(0).geometry.getCoordinateSequence().setOrdinate(0, 0, Double.NaN));
        cases.put("detached endpoint", value -> value.edges.get(0).geometry.getCoordinateSequence().setOrdinate(0, 0, 400010));
        cases.put("false length", value -> value.edges.get(0).lengthM = 90);
        cases.put("length after endpoint alignment", value -> {
            CalculatedEdge edge = value.edges.get(0);
            edge.geometry.getCoordinateSequence().setOrdinate(0, 0, 400000.0008);
            edge.geometry.getCoordinateSequence().setOrdinate(1, 0, 400099.9992);
            edge.geometry.geometryChanged();
            edge.lengthM = edge.geometry.getLength();
            value.summary.newNetworkLength = edge.lengthM + 100;
        });
        cases.put("negative cost", value -> value.edges.get(0).costRub = BigDecimal.ONE.negate());
        cases.put("missing flow", value -> value.edges.get(0).flowTph = null);
        cases.put("zero diameter", value -> value.edges.get(0).diameterMm = 0);
        cases.put("missing laying method", value -> value.edges.get(0).layingMethod = null);
        cases.put("depth in 2D", value -> value.edges.get(0).depthStartM = 1.5);
        cases.put("missing chamber cost", value -> value.newChambers.clear());
        cases.put("duplicate chamber cost", value -> value.newChambers.add(value.newChambers.get(0)));
        cases.put("cost of existing chamber", value -> value.newChambers.get(0).nodeId = "root");
        cases.put("wrong cost sum", value -> value.summary.constructionCost = BigDecimal.ZERO);
        cases.put("wrong chamber sum", value -> value.summary.chamberConstructionCost = BigDecimal.ZERO);
        cases.put("wrong final cost", value -> value.summary.calculatedCost = BigDecimal.ZERO);
        cases.put("wrong total length", value -> value.summary.newNetworkLength = 199);
        cases.put("negative score", value -> value.summary.score = BigDecimal.ONE.negate());
        cases.put("connected and unconnected target", value -> value.unconnectedPointIds.add(id("2")));
        cases.put("unknown unconnected target", value -> value.unconnectedPointIds.add(id("absent")));
        cases.put("lost target", value -> value.nodes.get(3).inputObjectId = new ObjectId(IntNode.valueOf(999)));
        return cases.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @Test void validatesWholeVariantListBeforeOutputAndDoesNotSortForTheCaller() throws Exception {
        for (List<CalculatedVariant> variants : List.of(List.<CalculatedVariant>of(),
                List.of(variant(), variant()), List.of(variant(), variant(), variant(), variant()))) {
            TrackingTarget target = new TrackingTarget();
            assertThrows(InvalidResultException.class, () -> exporter.write(dataset(), variants, target));
            assertEquals(0, target.size());
        }
        CalculatedVariant first = variant();
        CalculatedVariant second = variant();
        second.variantId = "v2";
        second.summary.score = BigDecimal.ZERO;
        TrackingTarget target = new TrackingTarget();
        assertThrows(InvalidResultException.class, () -> exporter.write(dataset(), List.of(first, second), target));
        assertEquals(0, target.size());
    }

    @Test void rejectsOmittedOrDuplicatedUnconnectedTargets() throws Exception {
        Data data = dataset();
        InputObject target = new InputObject();
        target.id = id("extra");
        target.type = InputType.OKS_CONNECTION_POINT;
        data.values.put(target.id, target);
        CalculatedVariant variant = variant();
        assertThrows(InvalidResultException.class, () -> output(data, List.of(variant)));
        variant.unconnectedPointIds.add(target.id);
        variant.unconnectedPointIds.add(target.id);
        assertThrows(InvalidResultException.class, () -> output(data, List.of(variant)));
    }

    @Test void byteLimitIsExactForUtf8AndCallerKeepsOwnershipOnFailure() throws Exception {
        CalculatedVariant variant = variant();
        variant.variantId = "вариант-теплосеть";
        Data data = dataset();
        TrackingTarget unlimited = new TrackingTarget();
        exporter.write(data, List.of(variant), unlimited);
        int bytes = unlimited.size();
        assertTrue(bytes > unlimited.toString(java.nio.charset.StandardCharsets.UTF_8).length());
        TrackingTarget exact = new TrackingTarget();
        new GeoJsonResultExporter(bytes).write(data, List.of(variant), exact);
        assertArrayEquals(unlimited.toByteArray(), exact.toByteArray());
        TrackingTarget limited = new TrackingTarget();
        assertThrows(OutputLimitExceededException.class, () -> new GeoJsonResultExporter(bytes - 1).write(data, List.of(variant), limited));
        assertTrue(limited.size() <= bytes - 1);
        assertFalse(unlimited.closed);
        assertFalse(exact.closed);
        assertFalse(limited.closed);
        assertFalse(data.closed);
    }

    @Test void propagatesIoFailuresAndCancellationWithoutClosingCallerResources() throws Exception {
        Data data = dataset();
        IOException failure = new IOException("Disconnected client");
        OutputStream broken = new OutputStream() { @Override public void write(int value) throws IOException { throw failure; } };
        assertSame(failure, assertThrows(IOException.class, () -> exporter.write(data, List.of(variant()), broken)));
        TrackingTarget target = new TrackingTarget();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> exporter.write(data, List.of(variant()), target));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(0, target.size());
        assertFalse(data.closed);
        assertFalse(target.closed);
    }

    @Test void singletonExporterHasIndependentIdsAndProjectionForConcurrentCalls() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> output(dataset(), List.of(variant()))));
            JsonNode first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<JsonNode> result : results) assertEquals(first, result.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    private JsonNode output(Dataset dataset, List<CalculatedVariant> variants) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exporter.write(dataset, variants, output);
        return JSON.readTree(output.toByteArray());
    }

    private void equivalent(JsonNode expected, JsonNode actual, String path) {
        assertNotNull(actual, path);
        if (expected.isNumber()) {
            assertTrue(actual.isNumber(), path);
            if (path.contains("coordinates")) assertEquals(expected.asDouble(), actual.asDouble(), 1e-8, path);
            else assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path);
        } else if (expected.isObject()) {
            assertEquals(expected.size(), actual.size(), path);
            expected.fields().forEachRemaining(entry -> equivalent(entry.getValue(), actual.get(entry.getKey()), path + "/" + entry.getKey()));
        } else if (expected.isArray()) {
            assertEquals(expected.size(), actual.size(), path);
            for (int i = 0; i < expected.size(); i++) equivalent(expected.get(i), actual.get(i), path + "/" + i);
        } else assertEquals(expected, actual, path);
    }

    private static final class TrackingTarget extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
