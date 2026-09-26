package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.output.OutputLimitExceededException;
import ru.hackathon.heatnetwork.output.ResultExporter;

class ResultStoreTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Dataset dataset = mock(Dataset.class);

    @Test void resultsArePrivateUntilSuccessAndExpireTogetherWithStatus() throws Exception {
        String id = UUID.randomUUID().toString();
        try (FileJobStore store = new FileJobStore(temporary, mapper)) {
            JobView job = queued(id);
            store.save(job);
            assertEquals(409, assertThrows(ApiException.class, () -> store.download(id)).status);
            store.writeResult(id, dataset, List.of(new CalculatedVariant()), fixtureExporter());
            assertEquals(409, assertThrows(ApiException.class, () -> store.variants(id)).status);
            store.save(job.succeeded(List.of()));
            assertEquals(1, store.variants(id).size());
            store.expire(Instant.now().plusSeconds(1), Set.of());
            assertEquals(404, assertThrows(ApiException.class, () -> store.download(id)).status);
            try (java.util.stream.Stream<Path> files = Files.list(temporary)) {
                assertEquals(1, files.count(), "only lock remains");
            }
        }
    }

    @Test void truncatedExportAndSizeLimitNeverPublishAResult() throws Exception {
        String id = UUID.randomUUID().toString();
        try (FileJobStore store = new FileJobStore(temporary, mapper)) {
            store.save(queued(id));
            assertThrows(OutputLimitExceededException.class, () -> store.writeResult(id, dataset, List.of(new CalculatedVariant()),
                    (d, variants, output) -> { output.write("{partial".getBytes()); throw new OutputLimitExceededException(1); }));
            assertFalse(Files.exists(temporary.resolve(id + ".result.geojson")));
            assertFalse(Files.exists(temporary.resolve(id + ".result.geojson.tmp")));
            assertEquals(409, assertThrows(ApiException.class, () -> store.download(id)).status);
        }
    }

    @Test void restartDiscardsFinishedFilesWhenSuccessWasNeverCommitted() throws Exception {
        String id = UUID.randomUUID().toString();
        try (FileJobStore store = new FileJobStore(temporary, mapper)) {
            store.save(queued(id));
            store.writeResult(id, dataset, List.of(new CalculatedVariant()), fixtureExporter());
        }
        try (FileJobStore store = new FileJobStore(temporary, mapper)) {
            store.recover();
            assertEquals("SERVER_RESTARTED", store.get(id).diagnostics.get(0).code);
            assertFalse(Files.exists(temporary.resolve(id + ".result.geojson")));
            assertFalse(Files.exists(temporary.resolve(id + ".variants.json")));
        }
    }

    @Test void missingResultAfterRestartIsAnExplicitFailure() throws Exception {
        String id = UUID.randomUUID().toString();
        try (FileJobStore store = new FileJobStore(temporary, mapper)) { store.save(queued(id).succeeded(List.of())); }
        try (FileJobStore store = new FileJobStore(temporary, mapper)) {
            store.recover();
            assertEquals("RESULT_UNAVAILABLE", store.get(id).diagnostics.get(0).code);
        }
    }

    private JobView queued(String id) { return new JobView(id, JobView.Status.QUEUED, JobView.Stage.QUEUED, "2d", List.of()); }
    private ResultExporter fixtureExporter() {
        return (d, variants, output) -> {
            try (InputStream input = getClass().getResourceAsStream("/fixtures/synthetic/two-consumers/expected.geojson")) {
                assertNotNull(input);
                input.transferTo(output);
            }
        };
    }
}
