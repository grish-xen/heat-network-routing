package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.hackathon.heatnetwork.input.InputParser;
import ru.hackathon.heatnetwork.input.InvalidInputException;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.output.ResultExporter;
import ru.hackathon.heatnetwork.output.OutputLimitExceededException;

@Service
public final class JobService implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);
    private final InputParser parser;
    private final CalculationCoordinator coordinator;
    private final ResultExporter exporter;
    private final JobProperties properties;
    private final FileJobStore store;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService maintenance;
    private final Semaphore admission;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    // Bounded by admission capacity; only small terminal snapshots, never Dataset/graphs.
    private final ConcurrentMap<String, Completion> completions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean stopping;

    public JobService(InputParser parser, ObjectMapper mapper, JobProperties properties,
                      CalculationCoordinator coordinator, ResultExporter exporter) throws IOException {
        this.parser = parser;
        this.coordinator = coordinator;
        this.exporter = exporter;
        this.properties = properties;
        if (properties.getRetention().isNegative() || properties.getRetention().isZero()) {
            throw new IllegalArgumentException("Job retention must be positive");
        }
        store = new FileJobStore(properties.getStorageDirectory(), mapper);
        try { store.recover(); } catch (IOException | RuntimeException exception) {
            store.close();
            throw exception;
        }
        int capacity = Math.addExact(properties.getWorkers(), properties.getQueueCapacity());
        admission = new Semaphore(capacity);
        // Admission also counts uploads. The physical queue accommodates accepted work while a
        // worker is finishing its finally block; configured capacity is enforced by the semaphore.
        workers = new ThreadPoolExecutor(properties.getWorkers(), properties.getWorkers(), 0,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), threads("heat-job-"));
        maintenance = Executors.newSingleThreadScheduledExecutor(threads("heat-job-cleanup-"));
        maintenance.scheduleWithFixedDelay(this::retryCompletions, 5, 5, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::expire, 60, 60, TimeUnit.SECONDS);
        expire();
    }

    public JobView submit(MultipartFile file, String mode) throws IOException {
        if (!"2d".equals(mode) && !"depth".equals(mode)) {
            throw new ApiException(400, "INVALID_INPUT", "mode должен быть 2d или depth.");
        }
        if (file == null || file.isEmpty()) throw new ApiException(400, "INVALID_INPUT", "Передайте непустой файл в поле file.");
        if (file.getSize() > properties.getMaxFileBytes()) throw tooLarge();
        lifecycle.readLock().lock();
        try {
            if (stopping || !admission.tryAcquire()) throw unavailable();
            String id = UUID.randomUUID().toString();
            active.add(id);
            JobView queued = new JobView(id, JobView.Status.QUEUED, JobView.Stage.QUEUED, mode, List.of());
            boolean submitted = false;
            try {
                copyUpload(file, id);
                store.save(queued);
                workers.execute(new Work(queued));
                submitted = true;
                return queued; // Stable admission response, even when a worker has already started.
            } catch (RejectedExecutionException exception) {
                throw unavailable();
            } finally {
                if (!submitted) {
                    try { store.delete(id); } finally { active.remove(id); admission.release(); }
                }
            }
        } finally { lifecycle.readLock().unlock(); }
    }

    public JobView get(String id) throws IOException {
        if (!FileJobStore.validId(id)) throw notFound();
        try { return store.get(id); } catch (NoSuchFileException exception) { throw notFound(); }
    }

    public List<VariantSummaryView> variants(String id) throws IOException { return store.variants(id); }
    FileJobStore.Download download(String id) throws IOException { return store.download(id); }

    MapArchive.Page map(String id, MapQuery query) throws IOException {
        int variant = store.mapVariant(id, query);
        MapArchive.Reader archive = store.openMap(id, query);
        try { return archive.select(query, variant); }
        catch (IOException | RuntimeException exception) {
            try { archive.close(); } catch (IOException closing) { exception.addSuppressed(closing); }
            throw exception;
        }
    }

    MapBoundsView bounds(String id, String variantId) throws IOException {
        if (variantId == null || variantId.isBlank()) throw MapQuery.bad("INVALID_VARIANT", "Укажите variantId.");
        int variant = store.mapVariant(id, variantId);
        try (MapArchive.Reader input = store.openMap(id, "input");
             MapArchive.Reader result = store.openMap(id, "result")) {
            double[] a = input.bounds(0), b = result.bounds(variant);
            if (a == null) return new MapBoundsView(b);
            if (b != null) {
                a[0] = Math.min(a[0], b[0]); a[1] = Math.min(a[1], b[1]);
                a[2] = Math.max(a[2], b[2]); a[3] = Math.max(a[3], b[3]);
            }
            return new MapBoundsView(a);
        }
    }

    private void copyUpload(MultipartFile file, String id) throws IOException {
        // The original filename never participates in path construction.
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(store.input(id), StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Upload interrupted");
                total += count;
                if (total > properties.getMaxFileBytes()) throw tooLarge();
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new ApiException(400, "INVALID_INPUT", "Файл пуст.");
        }
    }

    private final class Work implements Runnable {
        private final JobView job;
        Work(JobView job) { this.job = job; }

        @Override public void run() {
            JobView terminal;
            try {
                store.save(job.validating());
                try (Dataset dataset = parser.parse(store.input(job.jobId))) {
                    List<ApiError> diagnostics = new java.util.ArrayList<>();
                    ru.hackathon.heatnetwork.model.Model.Mode mode = "depth".equals(job.mode)
                            ? ru.hackathon.heatnetwork.model.Model.Mode.DEPTH : ru.hackathon.heatnetwork.model.Model.Mode.TWO_D;
                    List<CalculatedVariant> variants = coordinator.calculate(dataset, mode,
                            stage -> store.save(job.running(stage)), diagnostics);
                    store.save(job.running(JobView.Stage.EXPORTING));
                    store.writeResult(job.jobId, dataset, variants, exporter);
                    store.writeMaps(job.jobId);
                    CalculationCoordinator.interrupted();
                    int missing = variants.get(0).unconnectedPointIds.size();
                    if (missing != 0) diagnostics.add(new ApiError("ROUTE_NOT_FOUND",
                            "В лучшем варианте не подключено точек: " + missing + ". Поиск завершён в пределах заданного бюджета."));
                    terminal = job.succeeded(diagnostics);
                }
            } catch (CalculationCoordinator.NoValidVariantException exception) {
                terminal = job.failed(exception.diagnostics);
            } catch (OutputLimitExceededException exception) {
                terminal = job.failed(List.of(new ApiError("OUTPUT_LIMIT_EXCEEDED", exception.getMessage())));
            } catch (InvalidInputException exception) {
                terminal = job.failed(exception.getDiagnostics().stream().map(ApiError::from).collect(Collectors.toList()));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException || exception instanceof InterruptedIOException
                        || Thread.currentThread().isInterrupted()) {
                    terminal = interrupted(job);
                } else {
                    LOG.error("Job {} failed", job.jobId, exception);
                    terminal = job.failed(List.of(new ApiError("INTERNAL_ERROR",
                            "Не удалось обработать файл из-за ошибки сервера. Повторите загрузку позже.")));
                }
            }
            finish(terminal);
        }

        void cancelBeforeStart() { finish(interrupted(job)); }

        private void finish(JobView terminal) {
            Completion completion = new Completion(terminal);
            completions.put(job.jobId, completion);
            completion.persist();
        }
    }

    private final class Completion {
        private final JobView terminal;
        private boolean persisted;

        Completion(JobView terminal) { this.terminal = terminal; }

        synchronized void persist() {
            if (persisted) return;
            // Clear cancellation while persisting the final state: NIO can reject interrupted I/O.
            boolean interrupted = Thread.interrupted();
            try {
                try { store.removeInput(terminal.jobId); }
                catch (IOException exception) { LOG.error("Cannot remove input for job {}", terminal.jobId, exception); }
                if (terminal.status != JobView.Status.SUCCEEDED) {
                    try { store.removeResult(terminal.jobId); }
                    catch (IOException exception) { LOG.error("Cannot remove result for job {}", terminal.jobId, exception); }
                }
                store.save(terminal);
                persisted = true;
                completions.remove(terminal.jobId, this);
                active.remove(terminal.jobId);
                admission.release();
            } catch (IOException | RuntimeException exception) {
                LOG.error("Cannot persist terminal state for job {}; retaining for retry", terminal.jobId, exception);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    void retryCompletions() {
        for (Completion completion : completions.values()) completion.persist();
    }

    private static JobView interrupted(JobView job) {
        return job.failed(List.of(new ApiError("JOB_INTERRUPTED", "Обработка остановлена вместе с сервером. Загрузите файл повторно.")));
    }

    private void expire() {
        try { store.expire(Instant.now().minus(properties.getRetention()), active); }
        catch (IOException | RuntimeException exception) { LOG.error("Cannot clean up expired jobs", exception); }
    }

    @Override public void destroy() throws Exception {
        lifecycle.writeLock().lock();
        try {
            if (stopping) return;
            stopping = true;
            maintenance.shutdownNow();
            for (Runnable pending : workers.shutdownNow()) ((Work) pending).cancelBeforeStart();
            boolean terminated = workers.awaitTermination(30, TimeUnit.SECONDS);
            boolean maintenanceStopped = maintenance.awaitTermination(5, TimeUnit.SECONDS);
            if (terminated && maintenanceStopped) {
                retryCompletions();
                if (!completions.isEmpty()) LOG.error("{} terminal job states remain unsaved at shutdown", completions.size());
                store.close();
            }
            else LOG.error("Job workers did not stop in time; storage remains locked until process exit");
        } finally { lifecycle.writeLock().unlock(); }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return work -> {
            Thread thread = new Thread(work, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ApiException notFound() { return new ApiException(404, "JOB_NOT_FOUND", "Задача не найдена или срок её хранения истёк."); }
    private static ApiException unavailable() { return new ApiException(503, "QUEUE_FULL", "Очередь заполнена или сервер останавливается. Повторите загрузку позже."); }
    private ApiException tooLarge() { return new ApiException(413, "FILE_TOO_LARGE", "Размер файла превышает допустимый предел: " + properties.getMaxFileBytes() + " байт."); }
}
