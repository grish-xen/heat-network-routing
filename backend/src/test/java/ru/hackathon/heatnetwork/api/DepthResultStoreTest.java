package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.output.DepthExportFixtures.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.output.GeoJsonResultExporter;
import ru.hackathon.heatnetwork.output.InvalidResultException;

/** Real export/storage/map path with a supplied calculated fixture, not a DEPTH job solver. */
class DepthResultStoreTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = JSON.copy().findAndRegisterModules();

    @Test void depthExportSurvivesRestartAndMatchesPaginatedMap() throws Exception {
        String id = UUID.randomUUID().toString();
        Path storage = temporary.resolve("jobs");
        CalculatedVariant variant = variant("gas-below");
        JobView job = new JobView(id, JobView.Status.RUNNING, JobView.Stage.EXPORTING, "depth", List.of());
        try (FileJobStore store = new FileJobStore(storage, mapper);
             Dataset data = new GeoJsonInputParser(temporary.resolve("datasets")).parse(path("gas-below", "input.geojson"))) {
            store.save(job);
            Files.copy(path("gas-below", "input.geojson"), store.input(id));
            store.writeResult(id, data, List.of(variant), new GeoJsonResultExporter());
            store.writeMaps(id);
            assertEquals(409, assertThrows(ApiException.class, () -> store.download(id)).status);
            store.save(job.succeeded(List.of()));
        }
        try (FileJobStore store = new FileJobStore(storage, mapper)) {
            store.recover();
            assertEquals("depth", store.get(id).mode);
            assertEquals(JobView.Status.SUCCEEDED, store.get(id).status);
            JsonNode exported;
            try (InputStream input = store.download(id).stream) { exported = mapper.readTree(input); }
            Map<JsonNode, JsonNode> expected = new HashMap<>();
            for (JsonNode feature : exported.path("features")) {
                if (!feature.path("geometry").isNull()) expected.put(feature.at("/properties/id"), feature);
                else assertTrue(feature.path("properties").equals((left, right) -> {
                    if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue());
                    return left.equals(right) ? 0 : 1;
                }, mapper.valueToTree(store.variants(id).get(0))), "summary values survive storage exactly");
            }
            Set<JsonNode> seen = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            do {
                MapQuery query = new MapQuery(id, "result", "37,55,39,57", "variant-1", "2", cursor);
                try (MapArchive.Reader reader = store.openMap(id, query);
                     MapArchive.Page page = reader.select(query, store.mapVariant(id, query), MapArchive.MAX_PAGE_BYTES)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    page.writeTo(bytes);
                    JsonNode response = mapper.readTree(bytes.toByteArray());
                    for (JsonNode feature : response.path("features")) {
                        JsonNode featureId = feature.at("/properties/id");
                        assertTrue(seen.add(featureId));
                        assertEquals(expected.get(featureId), feature);
                    }
                    cursor = page.nextCursor;
                    if (cursor != null) assertTrue(cursors.add(cursor), "cursor must advance");
                }
            } while (cursor != null);
            assertEquals(expected.keySet(), seen);
            assertFalse(cursors.isEmpty(), "exercise multiple pages");
            assertEquals(3.4, exported.at("/features/2/properties/depth_start").asDouble());
        }
    }

    @Test void invalidProfileIsNotPublished() throws Exception {
        String id = UUID.randomUUID().toString();
        Path storage = temporary.resolve("jobs");
        CalculatedVariant variant = variant("gas-below");
        variant.edges.get(0).depthEndM = null;
        try (FileJobStore store = new FileJobStore(storage, mapper);
             Dataset data = new GeoJsonInputParser(temporary.resolve("datasets")).parse(path("gas-below", "input.geojson"))) {
            store.save(new JobView(id, JobView.Status.RUNNING, JobView.Stage.EXPORTING, "depth", List.of()));
            assertThrows(InvalidResultException.class,
                    () -> store.writeResult(id, data, List.of(variant), new GeoJsonResultExporter()));
            for (String suffix : List.of(".result.geojson", ".result.geojson.tmp", ".variants.json", ".variants.json.tmp")) {
                assertFalse(Files.exists(storage.resolve(id + suffix)));
            }
            assertEquals(409, assertThrows(ApiException.class, () -> store.download(id)).status);
        }
    }
}
