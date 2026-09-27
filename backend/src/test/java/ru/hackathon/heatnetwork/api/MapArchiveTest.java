package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MapArchiveTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test void paginationFindsCrossingLinesWithoutLoadingOrRepeatingUnmatchedFeatures() throws Exception {
        build("" + feature("1", "Point", "[30,50]", "") + ","
                + feature("2", "LineString", "[[36,55.5],[39,55.5]]", "") + ","
                + feature("3", "Point", "[37.5,55.5]", "") + ","
                + feature("4", "Point", "[31,51]", ""), null);
        JsonNode first = page(query("job", "input", "37,55,38,56", null, "1", null), 0);
        assertEquals(2, first.at("/features/0/properties/id").asInt(), "line has no vertex inside viewport, but its envelope intersects");
        String cursor = first.path("nextCursor").asText();
        JsonNode second = page(query("job", "input", "37,55,38,56", null, "1", cursor), 0);
        assertEquals(3, second.at("/features/0/properties/id").asInt());
        assertTrue(second.path("nextCursor").isNull());
        assertEquals(first, page(query("job", "input", "37,55,38,56", null, "1", null), 0));
        assertEquals(0, page(query("job", "input", "0,0,1,1", null, null, null), 0).path("features").size());
    }

    @Test void numericIdsStayExactAndThreeDimensionalPositionsBecomeTwoDimensional() throws Exception {
        String number = "12345678901234567890.1234567890123456789";
        build(feature(number, "Point", "[37.5,55.5,120]", "") + ","
                + feature("\"" + number + "\"", "Point", "[37.6,55.6,121]", ""), null);
        JsonNode result = page(query("job", "input", "37,55,38,56", null, null, null), 0);
        assertEquals(new BigDecimal(number), result.at("/features/0/properties/id").decimalValue());
        assertEquals(number, result.at("/features/1/properties/id").textValue());
        assertEquals(2, result.at("/features/0/geometry/coordinates").size());
    }

    @Test void variantsAreIsolatedAndGeometrylessSummariesDoNotEnterTheIndex() throws Exception {
        build(feature("1", "Point", "[37.5,55.5]", ",\"variant_id\":\"v1\"") + ","
                + feature("2", "Point", "[37.5,55.5]", ",\"variant_id\":\"v2\"") + ","
                + "{\"type\":\"Feature\",\"properties\":{\"object_type\":\"variant_summary\",\"variant_id\":\"v1\"},\"geometry\":null}", List.of("v1", "v2"));
        JsonNode result = page(query("job", "result", "37,55,38,56", "v2", null, null), 2);
        assertEquals(1, result.path("features").size());
        assertEquals(2, result.at("/features/0/properties/id").intValue());
        assertTrue(result.path("nextCursor").isNull());
    }

    @Test void nestedCoordinatesAndGeometryBeforePropertiesAreIndexed() throws Exception {
        build("{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[[[[36,54],[39,54],[39,57],[36,54]]]],\"type\":\"MultiPolygon\"},"
                + "\"properties\":{\"id\":\"polygon\",\"object_type\":\"restriction\"}}", null);
        JsonNode result = page(query("job", "input", "37,55,38,56", null, null, null), 0);
        assertEquals("polygon", result.at("/features/0/properties/id").asText());
        assertEquals("MultiPolygon", result.at("/features/0/geometry/type").asText());
    }

    @Test void byteBudgetPaginatesBeforeTheCountLimitAndRejectsAnOversizedSingleObject() throws Exception {
        build(feature("1", "Point", "[37.5,55.5]", "") + "," + feature("2", "Point", "[37.6,55.6]", ""), null);
        try (MapArchive.Reader archive = reader(); MapArchive.Page page = archive.select(query("job", "input", "37,55,38,56", null, "100", null), 0, 430)) {
            JsonNode result = json(page);
            assertEquals(1, result.path("features").size());
            assertFalse(result.path("nextCursor").isNull());
        }
        try (MapArchive.Reader archive = reader()) {
            assertEquals(413, assertThrows(ApiException.class,
                    () -> archive.select(query("job", "input", "37,55,38,56", null, "100", null), 0, 260)).status);
        }
    }

    @Test void cursorsCannotMoveBetweenJobsViewportsLayersVariantsOrLimits() {
        MapQuery original = query("job", "result", "37,55,38,56", "v1", "1", null);
        String cursor = original.cursor(3);
        assertEquals(3, query("job", "result", "37,55,38,56", "v1", "1", cursor).offset);
        assertThrows(ApiException.class, () -> query("other", "result", "37,55,38,56", "v1", "1", cursor));
        assertThrows(ApiException.class, () -> query("job", "result", "37,55,39,56", "v1", "1", cursor));
        assertThrows(ApiException.class, () -> query("job", "input", "37,55,38,56", null, "1", cursor));
        assertThrows(ApiException.class, () -> query("job", "result", "37,55,38,56", "v2", "1", cursor));
        assertThrows(ApiException.class, () -> query("job", "result", "37,55,38,56", "v1", "2", cursor));
    }

    @ParameterizedTest @ValueSource(strings = {"", "1,2,3", "NaN,0,1,1", "0,0,Infinity,1", "-181,0,1,1", "0,-91,1,1", "0,0,1,91", "1,0,1,2", "2,0,1,1"})
    void invalidBoundsAreRejected(String bbox) {
        assertEquals("INVALID_BBOX", assertThrows(ApiException.class, () -> query("job", "input", bbox, null, null, null)).error.code);
    }

    @ParameterizedTest @ValueSource(strings = {"", "0", "5001", "1.5", "NaN"})
    void invalidPageSizeIsRejected(String limit) {
        assertEquals("INVALID_LIMIT", assertThrows(ApiException.class, () -> query("job", "input", "37,55,38,56", null, limit, null)).error.code);
    }

    private void build(String features, List<String> variants) throws IOException {
        Path source = directory.resolve("source.geojson");
        Files.writeString(source, "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}");
        MapArchive.build(source, directory.resolve("data"), directory.resolve("index"), variants, mapper);
    }
    private static String feature(String id, String type, String coordinates, String extra) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":" + id + ",\"object_type\":\"restriction\"" + extra
                + "},\"geometry\":{\"type\":\"" + type + "\",\"coordinates\":" + coordinates + "}}";
    }
    private MapQuery query(String id, String layer, String bbox, String variant, String limit, String cursor) { return new MapQuery(id, layer, bbox, variant, limit, cursor); }
    private MapArchive.Reader reader() throws IOException { return new MapArchive.Reader(directory.resolve("data"), directory.resolve("index")); }
    private JsonNode page(MapQuery query, int variant) throws IOException {
        try (MapArchive.Reader reader = reader(); MapArchive.Page page = reader.select(query, variant)) { return json(page); }
    }
    private JsonNode json(MapArchive.Page page) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); page.writeTo(output); return mapper.readTree(output.toByteArray());
    }
}
