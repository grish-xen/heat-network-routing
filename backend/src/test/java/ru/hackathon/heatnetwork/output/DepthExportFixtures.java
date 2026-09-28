package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;

/** Test adapter for the shared metric examples; no production solver substitutions. */
public final class DepthExportFixtures {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);

    private DepthExportFixtures() { }

    public static Path path(String name, String file) throws Exception {
        return Path.of(Objects.requireNonNull(DepthExportFixtures.class.getResource(
                "/fixtures/synthetic/depth/" + name + "/" + file)).toURI());
    }

    public static CalculatedVariant variant(String name) throws Exception {
        JsonNode definition = JSON.readTree(path(name, "metric-case.json").toFile()).path("expectedVariant");
        CalculatedVariant result = JSON.treeToValue(definition, CalculatedVariant.class);
        for (int i = 0; i < result.nodes.size(); i++) {
            result.nodes.get(i).geometry = GF.createPoint(xy(definition.path("nodes").get(i).path("xy")));
        }
        for (int i = 0; i < result.edges.size(); i++) {
            JsonNode points = definition.path("edges").get(i).path("xy");
            Coordinate[] coordinates = new Coordinate[points.size()];
            for (int j = 0; j < coordinates.length; j++) coordinates[j] = xy(points.get(j));
            result.edges.get(i).geometry = GF.createLineString(coordinates);
        }
        return result;
    }

    private static Coordinate xy(JsonNode value) {
        return new Coordinate(value.get(0).asDouble(), value.get(1).asDouble());
    }
}
