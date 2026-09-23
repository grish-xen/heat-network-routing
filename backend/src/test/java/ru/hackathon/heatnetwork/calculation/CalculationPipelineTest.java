package ru.hackathon.heatnetwork.calculation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.Mode;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.output.GeoJsonResultExporter;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.routing.GridRoutePlannerFactory;
import ru.hackathon.heatnetwork.routing.RoutePlanner;
import ru.hackathon.heatnetwork.routing.RulesCatalog;

/** Real input parser, planner, calculator, validator and exporter on the shared synthetic files. */
class CalculationPipelineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RulesCatalog CATALOG = RulesCatalog.loadDefault();
    private final DefaultVariantCalculator calculator =
            new DefaultVariantCalculator(CATALOG, new DefaultSpatialValidator(CATALOG));

    @TempDir Path storage;

    private Path fixture(String name) throws Exception {
        return Path.of(Objects.requireNonNull(getClass().getResource("/fixtures/" + name)).toURI());
    }

    @Test
    void metricReferenceCandidateIsCalculatedAgainstTheParsedInput() throws Exception {
        try (Dataset dataset = new GeoJsonInputParser(storage).parse(fixture("synthetic/two-consumers/input.geojson"))) {
            Evaluation evaluation = calculator.evaluate(dataset, referenceCandidate(), Mode.TWO_D);

            assertTrue(evaluation.accepted(), () -> evaluation.diagnostics.stream()
                    .map(d -> d.code + " " + d.message).collect(Collectors.joining("; ")));
            JsonNode expected = expectedSummary();
            CalculatedVariant variant = evaluation.variant;
            assertEquals(0, expected.path("construction_cost").decimalValue().compareTo(variant.summary.constructionCost));
            assertEquals(0, expected.path("score").decimalValue().compareTo(variant.summary.score));
            assertEquals(expected.path("new_network_length").asDouble(), variant.summary.newNetworkLength, 0.001);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new GeoJsonResultExporter().write(dataset, Collections.singletonList(variant), output);
            JsonNode written = JSON.readTree(output.toByteArray());
            assertEquals(5, written.path("features").size(), "3 pipes, 1 chamber, 1 summary");
        }
    }

    @Test
    void plannerCandidatesAreCalculatedAndExported() throws Exception {
        try (Dataset dataset = new GeoJsonInputParser(storage).parse(fixture("synthetic/two-consumers/input.geojson"))) {
            SearchOptions options = new SearchOptions();
            options.maxCandidates = 20;
            List<CalculatedVariant> accepted = new ArrayList<>();
            List<String> rejections = new ArrayList<>();
            try (RoutePlanner.SearchSession session = new GridRoutePlannerFactory(CATALOG).open(dataset, options)) {
                for (Optional<RouteCandidate> next = session.next(); next.isPresent(); next = session.next()) {
                    Evaluation evaluation = calculator.evaluate(dataset, next.get(), Mode.TWO_D);
                    session.feedback(evaluation);
                    if (evaluation.accepted()) {
                        accepted.add(evaluation.variant);
                    } else {
                        evaluation.diagnostics.forEach(d -> rejections.add(d.code + " " + d.message));
                    }
                }
            }
            assertFalse(accepted.isEmpty(), () -> "no accepted candidate: " + rejections);
            accepted.sort(Comparator.comparing(v -> v.summary.score));
            CalculatedVariant best = accepted.get(0);
            assertTrue(best.unconnectedPointIds.isEmpty(), () -> "best leaves points unconnected; " + rejections);
            assertEquals(0, BigDecimal.ZERO.compareTo(best.summary.unconnectedPenalty));

            List<CalculatedVariant> top = accepted.subList(0, Math.min(3, accepted.size()));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new GeoJsonResultExporter().write(dataset, top, output);
            assertTrue(JSON.readTree(output.toByteArray()).path("features").size() > top.size());
        }
    }

    private RouteCandidate referenceCandidate() throws Exception {
        JsonNode metric = read("synthetic/two-consumers/metric-case.json");
        GeometryFactory planar = new GeometryFactory();
        RouteCandidate candidate = new RouteCandidate();
        candidate.candidateId = metric.path("candidateId").asText();
        for (JsonNode value : metric.path("nodes")) {
            Node node = new Node();
            node.id = value.path("id").asText();
            node.kind = NodeKind.valueOf(value.path("kind").asText());
            if (!value.path("inputObjectId").isNull()) {
                node.inputObjectId = new ObjectId(value.path("inputObjectId"));
            }
            node.geometry = planar.createPoint(xy(value.path("xy")));
            candidate.nodes.add(node);
        }
        for (JsonNode value : metric.path("edges")) {
            Edge edge = new Edge();
            edge.id = value.path("id").asText();
            edge.fromNodeId = value.path("fromNodeId").asText();
            edge.toNodeId = value.path("toNodeId").asText();
            List<Coordinate> points = new ArrayList<>();
            value.path("xy").forEach(point -> points.add(xy(point)));
            edge.geometry = planar.createLineString(points.toArray(new Coordinate[0]));
            candidate.edges.add(edge);
        }
        for (JsonNode value : metric.path("attachments")) {
            Attachment attachment = new Attachment();
            attachment.rootNodeId = value.path("rootNodeId").asText();
            attachment.existingObjectId = new ObjectId(value.path("existingObjectId"));
            candidate.attachments.add(attachment);
        }
        return candidate;
    }

    private JsonNode expectedSummary() throws Exception {
        for (JsonNode feature : read("synthetic/two-consumers/expected.geojson").path("features")) {
            if ("variant_summary".equals(feature.path("properties").path("object_type").asText())) {
                return feature.path("properties");
            }
        }
        throw new AssertionError("expected.geojson has no summary");
    }

    private JsonNode read(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/" + name)) {
            return JSON.readTree(input);
        }
    }

    private static Coordinate xy(JsonNode value) {
        return new Coordinate(value.get(0).asDouble(), value.get(1).asDouble());
    }
}
