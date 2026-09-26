package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import ru.hackathon.heatnetwork.input.GeoJsonInputParser;
import ru.hackathon.heatnetwork.input.InputParser;
import ru.hackathon.heatnetwork.input.InvalidInputException;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RoutePlanner;
import ru.hackathon.heatnetwork.routing.RulesCatalog;
import ru.hackathon.heatnetwork.routing.GridRoutePlannerFactory;
import ru.hackathon.heatnetwork.routing.DefaultSpatialValidator;
import ru.hackathon.heatnetwork.calculation.DefaultVariantCalculator;
import ru.hackathon.heatnetwork.output.GeoJsonResultExporter;
import java.util.Optional;
import static ru.hackathon.heatnetwork.api.TestPolling.eventually;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobServiceTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void boundedAdmissionKeepsQueuedWorkAndReleasesSlotsAfterFailure() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Dataset dataset = mock(Dataset.class);
        InputParser parser = path -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException exception) { throw new InterruptedIOException(); }
            return dataset;
        };
        JobProperties properties = properties();
        properties.setWorkers(1);
        properties.setQueueCapacity(1);
        JobService jobs = noRoutes(parser, properties);
        try {
            JobView first = jobs.submit(upload(), "2d");
            assertEquals(JobView.Status.QUEUED, first.status);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(JobView.Stage.VALIDATING, jobs.get(first.jobId).stage);
            JobView second = jobs.submit(upload(), "2d");
            assertEquals(JobView.Status.QUEUED, jobs.get(second.jobId).status);
            ApiException full = assertThrows(ApiException.class, () -> jobs.submit(upload(), "2d"));
            assertEquals(503, full.status);
            release.countDown();
            terminal(jobs, first.jobId);
            terminal(jobs, second.jobId);
            JobView third = jobs.submit(upload(), "2d");
            assertEquals("ROUTE_NOT_FOUND", terminal(jobs, third.jobId).diagnostics.get(0).code);
            verify(dataset, times(3)).close();
            assertEquals(0, countInputs());
        } finally { release.countDown(); jobs.destroy(); }
    }

    @Test void actualPipelineClosesDatasetAndKeepsDownloadAfterRestart() throws Exception {
        Path datasets = temporary.resolve("datasets");
        JobService jobs = realPipeline(new GeoJsonInputParser(datasets), properties());
        String jobId;
        byte[] originalResult;
        try {
            byte[] bytes;
            try (InputStream input = getClass().getResourceAsStream("/fixtures/synthetic/two-consumers/input.geojson")) {
                assertNotNull(input);
                bytes = input.readAllBytes();
            }
            JobView created = jobs.submit(new MockMultipartFile("file", "../../outside.json", "application/json", bytes), "2d");
            JobView result = terminal(jobs, created.jobId);
            jobId = result.jobId;
            assertEquals(JobView.Status.SUCCEEDED, result.status);
            assertEquals(1, jobs.variants(jobId).size(), "partial intermediate candidate must be discarded");
            try (InputStream download = jobs.download(jobId).stream) { originalResult = download.readAllBytes(); }
            try (Stream<Path> files = Files.list(datasets)) { assertEquals(0, files.count()); }
            assertEquals(0, countInputs());
        } finally { jobs.destroy(); }
        JobService restarted = realPipeline(new GeoJsonInputParser(datasets), properties());
        try {
            assertEquals(JobView.Status.SUCCEEDED, restarted.get(jobId).status);
            assertEquals(1, restarted.variants(jobId).size());
            try (InputStream download = restarted.download(jobId).stream) { assertArrayEquals(originalResult, download.readAllBytes()); }
        } finally { restarted.destroy(); }
    }

    @Test void diagnosticObjectIdsKeepTheirExactNumberOrStringTypesAcrossDiskRoundTrip() throws Exception {
        BigDecimal number = new BigDecimal("12345678901234567890.1234567890123456789");
        Diagnostic numeric = diagnostic(new ObjectId(com.fasterxml.jackson.databind.node.DecimalNode.valueOf(number)));
        Diagnostic string = diagnostic(new ObjectId(com.fasterxml.jackson.databind.node.TextNode.valueOf(number.toPlainString())));
        JobService jobs = noRoutes(path -> { throw new InvalidInputException(List.of(numeric, string)); }, properties());
        try {
            JobView view = terminal(jobs, jobs.submit(upload(), "2d").jobId);
            assertEquals(number, view.diagnostics.get(0).details.get(0).get("inputObjectId"));
            assertEquals(number.toPlainString(), view.diagnostics.get(1).details.get(0).get("inputObjectId"));
        } finally { jobs.destroy(); }
    }

    @Test void rejectedOrBrokenUploadLeavesNoJobOrFileAndRestoresCapacity() throws Exception {
        JobProperties properties = properties();
        properties.setWorkers(1);
        properties.setQueueCapacity(0);
        properties.setMaxFileBytes(4);
        JobService jobs = noRoutes(path -> { throw new IOException("PRIVATE server path"); }, properties);
        try {
            MockMultipartFile misleading = new MockMultipartFile("file", new byte[5]) {
                @Override public long getSize() { return 1; }
            };
            assertEquals(413, assertThrows(ApiException.class, () -> jobs.submit(misleading, "2d")).status);
            MockMultipartFile broken = new MockMultipartFile("file", new byte[1]) {
                @Override public InputStream getInputStream() throws IOException { throw new IOException("Read failed"); }
            };
            assertThrows(IOException.class, () -> jobs.submit(broken, "2d"));
            try (Stream<Path> files = Files.list(properties.getStorageDirectory())) {
                assertEquals(0, files.filter(path -> !path.getFileName().toString().equals(".lock")).count());
            }
            JobView failed = terminal(jobs, jobs.submit(new MockMultipartFile("file", new byte[1]), "2d").jobId);
            assertEquals("INTERNAL_ERROR", failed.diagnostics.get(0).code);
            assertFalse(mapper.writeValueAsString(failed).contains("PRIVATE"));
        } finally { jobs.destroy(); }
    }

    @Test void shutdownMarksQueuedAndRunningJobsFailedAndPersistsThemForNextStart() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        InputParser blocked = path -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) { throw new InterruptedIOException(); }
            throw new AssertionError("Unreachable");
        };
        JobProperties properties = properties();
        properties.setWorkers(1);
        JobService jobs = noRoutes(blocked, properties);
        String running;
        String queued;
        try {
            running = jobs.submit(upload(), "2d").jobId;
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queued = jobs.submit(upload(), "2d").jobId;
        } finally { jobs.destroy(); }
        JobService restarted = noRoutes(blocked, properties);
        try {
            assertEquals("JOB_INTERRUPTED", restarted.get(running).diagnostics.get(0).code);
            assertEquals("JOB_INTERRUPTED", restarted.get(queued).diagnostics.get(0).code);
            assertEquals(0, countInputs());
        } finally { restarted.destroy(); }
    }

    @Test void storeRecoversAbandonedJobsAndExpiresOnlyCompletedInactiveJobs() throws Exception {
        Path directory = properties().getStorageDirectory();
        String pending = UUID.randomUUID().toString();
        String completed = UUID.randomUUID().toString();
        Path unrelated = directory.resolve("keep.geojson");
        try (FileJobStore store = new FileJobStore(directory, mapper)) {
            store.save(queued(pending));
            store.save(queued(completed).failed(List.of(new ApiError("INVALID_INPUT", "test"))));
            Files.writeString(store.input(pending), "unfinished upload");
            Files.writeString(unrelated, "do not delete");
            assertThrows(IOException.class, () -> new FileJobStore(directory, mapper));
        }
        try (FileJobStore store = new FileJobStore(directory, mapper)) {
            store.recover();
            assertEquals("SERVER_RESTARTED", store.get(pending).diagnostics.get(0).code);
            assertEquals("INVALID_INPUT", store.get(completed).diagnostics.get(0).code);
            assertFalse(Files.exists(store.input(pending)));
            store.expire(Instant.now().plusSeconds(1), Set.of(pending));
            assertNotNull(store.get(pending));
            assertFalse(Files.exists(directory.resolve(completed + ".json")));
            assertTrue(Files.exists(unrelated));
        }
    }

    private JobProperties properties() {
        JobProperties properties = new JobProperties();
        properties.setStorageDirectory(temporary.resolve("jobs"));
        return properties;
    }

    @Test void exportFailureClosesDatasetAndCannotLeaveAnAccessibleResult() throws Exception {
        Dataset dataset = mock(Dataset.class);
        RoutePlanner.SearchSession session = mock(RoutePlanner.SearchSession.class);
        ru.hackathon.heatnetwork.model.Model.RouteCandidate candidate = new ru.hackathon.heatnetwork.model.Model.RouteCandidate();
        candidate.candidateId = "test";
        when(session.next()).thenReturn(Optional.of(candidate), Optional.empty());
        ru.hackathon.heatnetwork.model.Model.CalculatedVariant variant = new ru.hackathon.heatnetwork.model.Model.CalculatedVariant();
        variant.summary = new ru.hackathon.heatnetwork.model.Model.Summary();
        variant.summary.score = BigDecimal.ONE;
        ru.hackathon.heatnetwork.model.Model.Evaluation evaluation = new ru.hackathon.heatnetwork.model.Model.Evaluation();
        evaluation.variant = variant;
        JobProperties properties = properties();
        JobService jobs = new JobService(path -> dataset, mapper, properties,
                new CalculationCoordinator((d, o) -> session, (d, c, m) -> evaluation, properties),
                (d, variants, output) -> {
                    output.write(123);
                    throw new ru.hackathon.heatnetwork.output.OutputLimitExceededException(1);
                });
        try {
            JobView failed = terminal(jobs, jobs.submit(upload(), "2d").jobId);
            assertEquals(JobView.Status.FAILED, failed.status);
            assertEquals("OUTPUT_LIMIT_EXCEEDED", failed.diagnostics.get(0).code);
            assertEquals(409, assertThrows(ApiException.class, () -> jobs.download(failed.jobId)).status);
            assertEquals(0, countInputs());
            assertFalse(Files.exists(properties.getStorageDirectory().resolve(failed.jobId + ".result.geojson.tmp")));
            verify(dataset).close();
            verify(session).close();
        } finally { jobs.destroy(); }
    }

    private JobService noRoutes(InputParser parser, JobProperties properties) throws IOException {
        RoutePlanner planner = (dataset, options) -> {
            RoutePlanner.SearchSession session = mock(RoutePlanner.SearchSession.class);
            when(session.next()).thenReturn(Optional.empty());
            return session;
        };
        return new JobService(parser, mapper, properties,
                new CalculationCoordinator(planner, (d, c, m) -> { throw new AssertionError("No candidates expected"); }, properties),
                new GeoJsonResultExporter());
    }

    private JobService realPipeline(InputParser parser, JobProperties properties) throws IOException {
        RulesCatalog rules = RulesCatalog.loadDefault();
        return new JobService(parser, mapper, properties,
                new CalculationCoordinator(new GridRoutePlannerFactory(rules),
                        new DefaultVariantCalculator(rules, new DefaultSpatialValidator(rules)), properties),
                new GeoJsonResultExporter());
    }

    private MockMultipartFile upload() { return new MockMultipartFile("file", "input.geojson", "application/geo+json", new byte[] {1, 2}); }
    private JobView queued(String id) { return new JobView(id, JobView.Status.QUEUED, JobView.Stage.QUEUED, "2d", List.of()); }
    private Diagnostic diagnostic(ObjectId id) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.code = "INVALID_GEOMETRY";
        diagnostic.message = "Некорректная геометрия";
        diagnostic.inputObjectId = id;
        return diagnostic;
    }
    private JobView terminal(JobService jobs, String id) throws IOException {
        eventually(() -> jobs.get(id).terminal());
        return jobs.get(id);
    }
    private long countInputs() throws IOException {
        try (Stream<Path> files = Files.list(properties().getStorageDirectory())) {
            return files.filter(path -> path.toString().endsWith(".geojson") && !path.toString().endsWith(".result.geojson")).count();
        }
    }
}
