package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import ru.hackathon.heatnetwork.model.Model.*;
import static ru.hackathon.heatnetwork.output.ExportFixtures.*;

/** A single long LineString exercises streaming within a feature, not only between features. */
public final class ResultExporterScaleProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).resolve("large-result.geojson");
        int positions = 2_000_000;
        PackedCoordinateSequence.Double coordinates = new PackedCoordinateSequence.Double(positions, 2, 0);
        for (int i = 0; i < positions; i++) {
            coordinates.setOrdinate(i, 0, 400000 + 100.0 * i / (positions - 1));
            coordinates.setOrdinate(i, 1, 6170000);
        }
        Data data = new Data();
        Node root = node(data, "root", NodeKind.EXISTING_CHAMBER, InputType.HEAT_CHAMBER, 400000);
        Node target = node(data, "target", NodeKind.CONNECTION_POINT, InputType.OKS_CONNECTION_POINT, 400100);
        CalculatedEdge edge = new CalculatedEdge();
        edge.id = "line";
        edge.fromNodeId = root.id;
        edge.toNodeId = target.id;
        edge.geometry = GEOMETRY.createLineString(coordinates);
        edge.lengthM = 100;
        edge.flowTph = BigDecimal.TEN;
        edge.diameterMm = 80;
        edge.costRub = new BigDecimal("8353000");
        edge.layingMethod = LayingMethod.BASE;
        Summary summary = new Summary();
        summary.constructionCost = summary.calculatedCost = new BigDecimal("13353000");
        summary.existingChamberTieInCount = 1;
        summary.existingChamberTieInCost = new BigDecimal("5000000");
        summary.chamberConstructionCost = summary.unconnectedPenalty = BigDecimal.ZERO;
        summary.newNetworkLength = 100;
        summary.score = new BigDecimal("0.673884");
        CalculatedVariant variant = new CalculatedVariant();
        variant.variantId = "streaming-probe";
        variant.mode = Mode.TWO_D;
        variant.nodes = List.of(root, target);
        variant.edges = List.of(edge);
        variant.summary = summary;
        try {
            try (OutputStream stream = Files.newOutputStream(output)) {
                new GeoJsonResultExporter().write(data, List.of(variant), stream);
            }
            long bytes = Files.size(output);
            if (bytes <= Runtime.getRuntime().maxMemory()) throw new IllegalStateException("Output must exceed available heap");
            int features = 0;
            try (JsonParser json = JSON.getFactory().createParser(output.toFile())) {
                JsonToken token;
                while ((token = json.nextToken()) != null) {
                    if (token == JsonToken.VALUE_STRING && "Feature".equals(json.getText())) features++;
                }
            }
            if (features != 2 || data.closed) throw new IllegalStateException("Wrong feature count or resource ownership");
            System.out.println("OK: " + positions + " positions; output=" + bytes + " bytes; maxHeap=" + Runtime.getRuntime().maxMemory());
        } finally { Files.deleteIfExists(output); }
    }

    private static Node node(Data data, String id, NodeKind kind, InputType type, double x) {
        Node node = new Node();
        node.id = id;
        node.kind = kind;
        node.inputObjectId = id(id);
        node.geometry = GEOMETRY.createPoint(new Coordinate(x, 6170000));
        InputObject input = new InputObject();
        input.id = node.inputObjectId;
        input.type = type;
        input.geometry = node.geometry;
        input.flowTph = BigDecimal.TEN;
        data.values.put(input.id, input);
        return node;
    }
}
