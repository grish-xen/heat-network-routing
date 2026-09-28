package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import static ru.hackathon.heatnetwork.output.DepthExportFixtures.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hackathon.heatnetwork.calculation.DefaultVariantCalculator;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.output.GeoJsonResultExporter;
import ru.hackathon.heatnetwork.routing.*;

/** Real planner, calculator, independent validator, coordinator, export and restarted storage. */
class DepthIntegrationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void realSearchProducesPersistentProfiles(String name) throws Exception {
        RulesCatalog catalog = RulesCatalog.loadDefault();
        CalculationCoordinator coordinator = new CalculationCoordinator(new GridRoutePlannerFactory(catalog),
                new DefaultVariantCalculator(catalog, new DefaultSpatialValidator(catalog)), new JobProperties());
        ObjectMapper mapper = JSON.copy().findAndRegisterModules();
        String id = UUID.randomUUID().toString();
        Path storage = temporary.resolve("jobs");
        JsonNode exported;
        try (Dataset data = new GeoJsonInputParser(temporary.resolve("input")).parse(path(name, "input.geojson"));
             FileJobStore store = new FileJobStore(storage, mapper)) {
            List<CalculatedVariant> variants = coordinator.calculate(data, Mode.DEPTH, stage -> { });
            assertFalse(variants.isEmpty());
            assertTrue(variants.get(0).unconnectedPointIds.isEmpty(), name + " must connect every consumer");
            for (CalculatedVariant variant : variants) {
                assertEquals(Mode.DEPTH, variant.mode);
                assertFalse(variant.edges.isEmpty());
                assertTrue(new DefaultSpatialValidator(catalog).validate(data, variant).isEmpty());
                for (CalculatedEdge edge : variant.edges) {
                    assertNotNull(edge.depthStartM); assertNotNull(edge.depthEndM);
                }
            }
            store.save(new JobView(id, JobView.Status.RUNNING, JobView.Stage.EXPORTING, "depth", List.of()));
            Files.copy(path(name, "input.geojson"), store.input(id));
            store.writeResult(id, data, variants, new GeoJsonResultExporter());
            store.writeMaps(id);
            store.save(store.get(id).succeeded(List.of()));
            try (InputStream input = store.download(id).stream) { exported = mapper.readTree(input); }
        }
        try (FileJobStore store = new FileJobStore(storage, mapper)) {
            store.recover();
            assertEquals("depth", store.get(id).mode);
            assertEquals(JobView.Status.SUCCEEDED, store.get(id).status);
            try (InputStream input = store.download(id).stream) { assertEquals(exported, mapper.readTree(input)); }
            Map<JsonNode, JsonNode> expected = new HashMap<>();
            for (JsonNode feature : exported.path("features")) {
                if (feature.at("/properties/variant_id").asText().equals("variant-1") && !feature.path("geometry").isNull()) {
                    expected.put(feature.at("/properties/id"), feature);
                }
            }
            Set<JsonNode> seen = new HashSet<>();
            String cursor = null;
            do {
                MapQuery query = new MapQuery(id, "result", "-180,-90,180,90", "variant-1", "2", cursor);
                try (MapArchive.Reader reader = store.openMap(id, query);
                     MapArchive.Page page = reader.select(query, store.mapVariant(id, query), MapArchive.MAX_PAGE_BYTES)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    page.writeTo(bytes);
                    for (JsonNode feature : mapper.readTree(bytes.toByteArray()).path("features")) {
                        JsonNode featureId = feature.at("/properties/id");
                        assertTrue(seen.add(featureId));
                        assertEquals(expected.get(featureId), feature);
                    }
                    cursor = page.nextCursor;
                }
            } while (cursor != null);
            assertEquals(expected.keySet(), seen);
        }
    }
}
