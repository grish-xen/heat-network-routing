package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;

final class ExportFixtures {
    static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 32637);
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private ExportFixtures() { }

    static JsonNode fixture(String file) throws IOException {
        try (InputStream input = ExportFixtures.class.getResourceAsStream("/fixtures/synthetic/two-consumers/" + file)) {
            if (input == null) throw new IOException("Fixture is absent: " + file);
            return JSON.readTree(input);
        }
    }

    static CalculatedVariant variant() throws IOException {
        JsonNode metric = fixture("metric-case.json");
        CalculatedVariant variant = new CalculatedVariant();
        variant.variantId = "v1";
        variant.mode = Mode.TWO_D;
        for (JsonNode value : metric.path("nodes")) {
            Node node = new Node();
            node.id = value.path("id").asText();
            node.kind = NodeKind.valueOf(value.path("kind").asText());
            if (!value.path("inputObjectId").isNull()) node.inputObjectId = new ObjectId(value.path("inputObjectId"));
            node.geometry = GEOMETRY.createPoint(xy(value.path("xy")));
            variant.nodes.add(node);
        }
        for (JsonNode value : metric.path("edges")) {
            CalculatedEdge edge = new CalculatedEdge();
            edge.id = value.path("id").asText();
            edge.fromNodeId = value.path("fromNodeId").asText();
            edge.toNodeId = value.path("toNodeId").asText();
            Coordinate[] points = new Coordinate[value.path("xy").size()];
            for (int i = 0; i < points.length; i++) points[i] = xy(value.path("xy").get(i));
            edge.geometry = GEOMETRY.createLineString(points);
            edge.flowTph = value.path("flowTph").decimalValue();
            edge.diameterMm = value.path("diameterMm").asInt();
            edge.lengthM = value.path("lengthM").asDouble();
            edge.costRub = value.path("costRub").decimalValue();
            edge.layingMethod = LayingMethod.BASE;
            edge.specialCoefficient = BigDecimal.ONE;
            variant.edges.add(edge);
        }
        for (JsonNode value : metric.path("attachments")) {
            Attachment attachment = new Attachment();
            attachment.rootNodeId = value.path("rootNodeId").asText();
            attachment.existingObjectId = new ObjectId(value.path("existingObjectId"));
            variant.attachments.add(attachment);
        }
        for (JsonNode feature : fixture("expected.geojson").path("features")) {
            JsonNode value = feature.path("properties");
            if ("heat_chamber".equals(value.path("object_type").asText())) {
                ChamberCost chamber = new ChamberCost();
                chamber.nodeId = variant.nodes.stream().filter(node -> node.kind == NodeKind.NEW_CHAMBER).findFirst().orElseThrow().id;
                chamber.diameterMm = value.path("diameter").asInt();
                chamber.costRub = value.path("cost").decimalValue();
                variant.newChambers.add(chamber);
            } else if ("variant_summary".equals(value.path("object_type").asText())) {
                variant.summary = JSON.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(value, Summary.class);
            }
        }
        return variant;
    }

    static Data dataset() throws IOException {
        Data data = new Data();
        for (Node node : variant().nodes) {
            if (node.inputObjectId == null) continue;
            InputObject object = new InputObject();
            object.id = node.inputObjectId;
            object.type = node.kind == NodeKind.EXISTING_CHAMBER ? InputType.HEAT_CHAMBER : InputType.OKS_CONNECTION_POINT;
            object.geometry = node.geometry;
            object.flowTph = BigDecimal.TEN;
            data.values.put(object.id, object);
        }
        InputObject source = new InputObject();
        source.id = id("source");
        source.type = InputType.SOURCE;
        source.geometry = GEOMETRY.createPoint(new Coordinate(400000, 6169900));
        data.values.put(source.id, source);
        return data;
    }

    static ObjectId id(String value) { return new ObjectId(TextNode.valueOf(value)); }
    static Coordinate xy(JsonNode value) { return new Coordinate(value.get(0).asDouble(), value.get(1).asDouble()); }

    static final class Data implements Dataset {
        final Map<ObjectId, InputObject> values = new LinkedHashMap<>();
        boolean closed;
        int streamCloses;
        @Override public Optional<InputObject> find(ObjectId id) { return Optional.ofNullable(values.get(id)); }
        @Override public Stream<InputObject> objects(InputType type) {
            return values.values().stream().filter(object -> object.type == type).onClose(() -> streamCloses++);
        }
        @Override public Stream<InputObject> query(Envelope bounds) { throw new UnsupportedOperationException(); }
        @Override public void close() { closed = true; }
    }
}
